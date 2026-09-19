// 缺陷中心 v2（W4 API 化）：页头/列表/统计条数据来源 = 真实 API（M1）GET /api/v1/work-items?type=defect
// + 新建缺陷 → POST /work-items（致命/严重 + 挂版本 blockedReleaseId）
// + 流转按钮 → POST /{id}/transition（乐观更新失败回滚 + 错误信封 toast）
// + useGateChannel 订阅 defect.blocked_changed → invalidateQueries 实时刷新阻塞标记
// + M2-INC-1 W1（M2-C/V-6 前端接线）：详情抽屉「关联提交」块 → GET /commits?workItemKey=
// 纯展示函数（severityTone / defectStatusFlow）仍复用 store.ts 导出；
// dogfooding 切换：store 演示缺陷/演示版本兜底已移除（remote only + 显式空态）。
import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { AlertTriangle, ArrowRight, FlaskConical, GitCommitHorizontal, GitPullRequest, Hash, ListChecks, Package, Plus, X } from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { Defect, DefectSeverity } from '../../data/types'
import { defectStatusFlow, severityTone } from '../../data/store'
import { defectsApi, useCommits, useComponents, useDefects, useGateChannel, useProducts, useReleases, useWorkItems } from '../../api/queries'
import type { DefectRow, RemoteRelease } from '../../api/queries'
import { DefectFormModal } from './DefectForm'
import { ApiError } from '../../api/client'
import { useRemoteUsers, toBrief } from '../../api/users'
import { RemoteAvatar, remoteName } from '../../api/RemoteAvatar'
import { Btn, Card, Empty, PageHeader, Pill, PriorityBadge } from '../../components/ui'
import type { PageProps } from '../../nav'

type Tone = 'neutral' | 'brand' | 'ok' | 'warn' | 'bad' | 'info' | 'purple' | 'orange' | 'pink' | 'teal'
const SEVS: DefectSeverity[] = ['致命', '严重', '一般', '轻微']
const SEV_DOT: Record<DefectSeverity, string> = { 致命: 'bg-bad', 严重: 'bg-cat-orange', 一般: 'bg-info', 轻微: 'bg-txt-low' }
const SEV_HEX: Record<DefectSeverity, string> = { 致命: '#f94646', 严重: '#ff9500', 一般: '#0091ff', 轻微: '#9ca0a8' }
const VIEWS = [
  { id: 'all', label: '全部' },
  { id: 'blocked', label: '阻塞清单' },
  { id: 'byTest', label: '按测试任务分组' },
  { id: 'byModule', label: '按模块分组' },
] as const
const selCls = 'cursor-pointer rounded-input border border-line bg-canvas px-2 py-1.5 text-xs text-txt-hi outline-none focus:border-brand'
const iconBtn = 'cursor-pointer rounded p-1 hover:bg-ink-700'

function statusTone(d: Defect): Tone {
  return d.status === '修复中' ? 'info' : d.status === '已修复' ? 'warn' : d.status === '回归通过' ? 'ok' : d.status === '重新打开' ? 'bad' : 'neutral'
}
/** 滞留天数 = updatedAt 距今天数（完整日期走 Date 解析） */
function daysSince(ts: string): number {
  const now = Date.now()
  if (/^\d{4}-/.test(ts)) {
    const d = new Date(ts).getTime()
    if (!isNaN(d)) return Math.max(0, Math.floor((now - d) / 86400000))
  }
  return 0
}
/** 产品短标签：uuid → 真实产品名（GET /products），防御 undefined/null 引起的 slice 异常 */
function useShortProduct(): (id?: string | null) => string {
  const { data: productRows } = useProducts()
  return (id?: string | null): string => {
    if (!id) return '未归属产品'
    return productRows?.find((p) => p.id === id)?.name ?? `产品 ${id.slice(0, 4)}`
  }
}

/** 组件短标签（收口批真实化）：uuid → 真实组件名（GET /components）；
 *  真实 uuid 查不到（组件已删等）显示「未命名组件」而非 uuid 编码，缺失归「未指定」。 */
function useComponentName(): (id?: string | null) => string {
  const { data: componentRows } = useComponents()
  return (id?: string | null): string => {
    if (!id) return '未指定'
    return componentRows?.find((c) => c.id === id)?.name ?? '未命名组件'
  }
}

export default function DefectsPage({ nav, id }: PageProps) {
  const queryClient = useQueryClient()
  // dogfooding 切换：数据来源仅真实 API，不再回退 store 演示缺陷/演示版本
  const { data: remoteDefects, isLoading } = useDefects()
  const { data: remoteReleases } = useReleases()
  const { data: userRows } = useRemoteUsers()
  const users = toBrief(userRows)

  const defects: DefectRow[] = useMemo(() => remoteDefects ?? [], [remoteDefects])
  const releases = useMemo(() => remoteReleases ?? [], [remoteReleases])

  const [sevSel, setSevSel] = useState<DefectSeverity[]>([])
  const [statusSel, setStatusSel] = useState('全部')
  const [prodSel, setProdSel] = useState('全部')
  const [relSel, setRelSel] = useState('全部')
  const [view, setView] = useState<(typeof VIEWS)[number]['id']>('all')
  const [drawerId, setDrawerId] = useState<string | undefined>(() => (id && defects.some((d) => d.id === id) ? id : undefined))
  const [creating, setCreating] = useState(false)
  const [toast, setToast] = useState<{ ok: boolean; text: string } | undefined>()
  useEffect(() => {
    if (id && defects.some((d) => d.id === id)) setDrawerId(id)
  }, [id, defects])

  // 门禁频道：全部版本 gate:{releaseId} → defect.blocked_changed → 失效查询实时刷新
  useGateChannel(releases.map((r) => r.id))

  const showToast = (ok: boolean, text: string) => {
    setToast({ ok, text })
    setTimeout(() => setToast(undefined), 4200)
  }

  // 流转 mutation：乐观更新 → 失败回滚 + 信封 toast（沿用现有提示样式）
  const transitionMut = useMutation({
    mutationFn: ({ wid, to }: { wid: string; to: string }) => defectsApi.transition(wid, to),
    onMutate: async ({ wid, to }) => {
      await queryClient.cancelQueries({ queryKey: ['defects'] })
      const prev = queryClient.getQueryData<DefectRow[]>(['defects'])
      queryClient.setQueryData<DefectRow[]>(['defects'], (old) =>
        old?.map((d) => (d.id === wid ? { ...d, status: to as Defect['status'] } : d)))
      return { prev }
    },
    onError: (err, { to }, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(['defects'], ctx.prev)
      const text = err instanceof ApiError
        ? `流转「${to}」被拒绝：${err.message}`
        : `流转「${to}」失败：网络异常`
      showToast(false, text)
    },
    onSuccess: (raw) => {
      void queryClient.invalidateQueries({ queryKey: ['defects'] })
      showToast(true, `${raw.key} → ${raw.status}`)
    },
  })

  const shortProduct = useShortProduct()
  const componentName = useComponentName()
  // 收口批（D-91 后字段映射真实化）：foundInTestTaskId/relatedTaskId/componentId 来自真实投影，
  // 按测试任务 / 按组件两个分组视图由此解锁（组头标签查真实工作项/组件表）
  const { data: workItemRows } = useWorkItems()
  const workItemLabel = (wid: string): string => {
    const w = workItemRows?.find((x) => x.id === wid)
    return w ? `${w.key} ${w.title}` : `工作项 ${wid.slice(0, 4)}`
  }

  const filtered = defects.filter(
    (d) => (sevSel.length === 0 || sevSel.includes(d.severity))
      && (statusSel === '全部' || d.status === statusSel)
      && (prodSel === '全部' || d.productId === prodSel)
      && (relSel === '全部' || d.blockedReleaseId === relSel),
  )
  const blockedList = filtered.filter((d) => d.blockedReleaseId && d.status !== '已关闭' && d.status !== '回归通过')
  const blockedReleaseCount = releases.filter((r) => r.blocked).length
  // 产品/版本过滤选项：从真实数据反推（后端无产品 REST），防御 null/undefined
  const productIds = [...new Set([...defects.map((d) => d.productId), ...releases.map((r) => r.productId)].filter(Boolean))] as string[]
  const releaseOptions = releases.filter((r) => r.status !== 'released')

  const row = (d: DefectRow) => <DefectRow key={d.id} d={d} nav={nav} onOpen={() => setDrawerId(d.id)} users={users} releases={releases} />

  return (
    <div>
      <PageHeader title="缺陷中心" desc="严重度与优先级分离 · S1/S2 未关闭缺陷阻塞版本发布（RL-3） · 滞留 ≥ 3 天粉色预警 · 数据来源：真实 API（M1）" />

      {/* 统计条 */}
      <div className="mb-3 flex flex-wrap items-center gap-4 rounded-card border border-line bg-card px-4 py-3 text-sm">
        {SEVS.map((s) => (
          <span key={s} className="flex items-center gap-1.5 text-txt-mid">
            <span className={`h-2 w-2 rounded-full ${SEV_DOT[s]}`} />
            <span className="font-bold tabular-nums text-txt-hi">{defects.filter((d) => d.severity === s).length}</span>{s}
          </span>
        ))}
        <span className="mx-1 h-4 w-px bg-line" />
        <span className="flex items-center gap-1.5 text-txt-mid">
          <AlertTriangle size={13} className="text-cat-red" />
          <span className="font-bold tabular-nums text-txt-hi">{blockedReleaseCount}</span>阻塞版本
        </span>
        <span className="flex-1" />
        <Btn variant="primary" onClick={() => setCreating(true)}><Plus size={14} />登记缺陷</Btn>
      </div>

      {/* 过滤器 + 视图 Tab */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        {SEVS.map((s) => {
          const on = sevSel.includes(s)
          return (
            <button
              key={s}
              type="button"
              onClick={() => setSevSel((p) => (p.includes(s) ? p.filter((x) => x !== s) : [...p, s]))}
              className={`flex cursor-pointer items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition ${on ? 'border-transparent text-white' : 'border-line bg-card text-txt-mid hover:bg-ink-700'}`}
              style={on ? { background: SEV_HEX[s] } : undefined}
            >
              <span className={`h-1.5 w-1.5 rounded-full ${on ? 'bg-white/80' : SEV_DOT[s]}`} />{s}
            </button>
          )
        })}
        <span className="mx-1 h-4 w-px bg-line" />
        <select value={statusSel} onChange={(e) => setStatusSel(e.target.value)} className={selCls}>
          {['全部', ...defectStatusFlow, '重新打开'].map((s) => <option key={s} value={s}>{s === '全部' ? '全部状态' : s}</option>)}
        </select>
        <select value={prodSel} onChange={(e) => setProdSel(e.target.value)} className={selCls}>
          <option value="全部">全部产品</option>
          {productIds.map((pid) => <option key={pid} value={pid}>{shortProduct(pid)}</option>)}
        </select>
        <select value={relSel} onChange={(e) => setRelSel(e.target.value)} className={selCls}>
          <option value="全部">全部版本</option>
          {releaseOptions.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
        </select>
        {isLoading && <span className="text-xs text-txt-low">加载中…</span>}
        <span className="flex-1" />
        <div className="flex rounded-input border border-line p-0.5">
          {VIEWS.map((v) => (
            <button key={v.id} type="button" onClick={() => setView(v.id)} className={`cursor-pointer rounded-md px-2.5 py-1 text-xs font-medium ${view === v.id ? 'bg-brand-bg text-brand-deep' : 'text-txt-mid hover:text-txt-hi'}`}>
              {v.label}
            </button>
          ))}
        </div>
      </div>

      {/* 视图主体 */}
      {view === 'all' && (
        filtered.length === 0
          ? <Card><Empty text={isLoading ? '缺陷加载中…' : defects.length === 0 ? '暂无缺陷（右上角「登记缺陷」录入真实缺陷）' : '没有符合条件的缺陷'} /></Card>
          : <Card className="divide-y divide-line">{filtered.map(row)}</Card>
      )}

      {view === 'blocked' && (
        blockedList.length === 0 ? <Card><Empty text="当前无阻塞缺陷，发布通道畅通" /></Card> : (
          releases.filter((r) => blockedList.some((d) => d.blockedReleaseId === r.id)).map((r) => {
            const list = blockedList.filter((d) => d.blockedReleaseId === r.id)
            return (
              <div key={r.id} className="mb-4">
                <div className="mb-1.5 flex flex-wrap items-center gap-2">
                  <Package size={14} className="text-cat-red" />
                  <span className="font-mono text-sm font-bold text-txt-hi">{r.name}</span>
                  <Pill tone="bad">发布阻塞</Pill>
                  <span className="text-xs text-txt-low">{r.planDate ? `计划发布 ${r.planDate} · ` : ''}未关闭阻塞缺陷 {list.length} 个</span>
                </div>
                <Card className="divide-y divide-line">{list.map(row)}</Card>
              </div>
            )
          })
        )
      )}

      {view === 'byTest' && (
        /* 收口批：foundInTestTaskId 已有真实投影——按发现测试任务分组（未关联置底） */
        <GroupedDefects
          defs={filtered}
          groupKeyOf={(d) => d.foundInTestTaskId ?? ''}
          groupLabelOf={(g) => workItemLabel(g)}
          ungroupedLabel="未关联测试任务"
          row={row}
        />
      )}

      {view === 'byModule' && (
        /* 收口批：componentId 已有真实投影——按所属组件分组（真实组件名，未指定置底） */
        <GroupedDefects
          defs={filtered}
          groupKeyOf={(d) => d.componentId ?? ''}
          groupLabelOf={(g) => componentName(g)}
          ungroupedLabel="未指定"
          row={row}
        />
      )}

      {drawerId && <DefectDrawer wid={drawerId} nav={nav} onClose={() => setDrawerId(undefined)} users={users} onTransition={(wid, to) => transitionMut.mutate({ wid, to })} />}

      {creating && (
        /* R-8：缺陷中心与任务页「登记缺陷」共用 DefectForm（字段一致；原 产品/阻塞版本/处理人 全保留） */
        <DefectFormModal
          onClose={() => setCreating(false)}
          onCreated={(key) => {
            void queryClient.invalidateQueries({ queryKey: ['defects'] })
            void queryClient.invalidateQueries({ queryKey: ['releases'] })
            showToast(true, `已登记缺陷 ${key}；致命/严重级自动创建话题并拉入干系人（后端联动）`)
          }}
          onError={(message) => showToast(false, `登记被拒绝：${message}`)}
        />
      )}

      {toast && (
        <div className="fixed bottom-6 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-2 rounded-full border border-line bg-canvas px-4 py-2.5 text-sm text-txt-hi shadow-lg">
          <span className={`h-2 w-2 rounded-full ${toast.ok ? 'bg-ok' : 'bg-bad'}`}/>{toast.text}
        </div>
      )}
    </div>
  )
}

// ---------------- 分组视图（收口批真实化）：byTest / byModule 共用——组头标签 + 组内复用缺陷行 ----------------
function GroupedDefects({ defs, groupKeyOf, groupLabelOf, ungroupedLabel, row }: {
  defs: DefectRow[]
  groupKeyOf: (d: DefectRow) => string
  groupLabelOf: (groupKey: string) => string
  ungroupedLabel: string
  row: (d: DefectRow) => ReactNode
}) {
  const groups = new Map<string, DefectRow[]>()
  for (const d of defs) {
    const g = groupKeyOf(d)
    const arr = groups.get(g)
    if (arr) arr.push(d)
    else groups.set(g, [d])
  }
  // 组内条数降序；未分组（key 为空）始终置底
  const entries = [...groups.entries()].sort((a, b) => {
    const aEmpty = a[0] === ''
    const bEmpty = b[0] === ''
    if (aEmpty !== bEmpty) return aEmpty ? 1 : -1
    return b[1].length - a[1].length
  })
  if (defs.length === 0) return <Card><Empty text="没有符合条件的缺陷" /></Card>
  return (
    <div>
      {entries.map(([g, list]) => (
        <div key={g || '_ungrouped'} className="mb-4">
          <div className="mb-1.5 flex flex-wrap items-center gap-2">
            {g
              ? <span className="min-w-0 truncate text-sm font-bold text-txt-hi">{groupLabelOf(g)}</span>
              : <span className="text-sm text-txt-low">{ungroupedLabel}</span>}
            <Pill tone="neutral">{list.length}</Pill>
          </div>
          <Card className="divide-y divide-line">{list.map(row)}</Card>
        </div>
      ))}
    </div>
  )
}

// ---------------- 缺陷行：severity 胶囊 / 状态 / 滞留 / 关联跳转 ----------------
function DefectRow({ d, nav, onOpen, users, releases }: {
  d: DefectRow
  nav: PageProps['nav']
  onOpen: () => void
  users: Map<string, import('../../api/users').RemoteUserBrief>
  releases: RemoteRelease[]
}) {
  const shortProduct = useShortProduct()
  const stale = daysSince(d.updatedAt)
  const br = d.blockedReleaseId
  const rel = br // 远端版本 id（uuid），DeliveryPage 按版本 id 定位卡片
  // 挂账收口（缺陷所属版本）：行版本列优先所属/交付版本（work_item.release_id → 名称），
  // 无所属版本回退阻塞版本（原 PM-9 逻辑）；两者都有时显示所属版本名，
  // 「阻塞」语义仍由行尾 Package 徽标按钮（下方，不动）表达
  const ownRelName = d.releaseId ? releases.find((r) => r.id === d.releaseId)?.name : undefined
  const relName = ownRelName ?? (br ? releases.find((r) => r.id === br)?.name : undefined)
  return (
    <div className="flex cursor-pointer items-center gap-2.5 px-3.5 py-2.5 hover:bg-ink-700" onClick={onOpen}>
      <Pill tone={severityTone[d.severity]}>{d.severity}</Pill>
      <span className="w-14 shrink-0 font-mono text-xs text-txt-low">{d.key}</span>
      <span className="min-w-0 flex-1 truncate text-sm text-txt-hi">{d.title}</span>
      <Pill tone={statusTone(d)}>{d.status}</Pill>
      <RemoteAvatar userId={d.assigneeId} users={users} size={20} />
      <span
        className="hidden w-36 shrink-0 truncate text-xs text-txt-low xl:block"
        title={relName ? `${shortProduct(d.productId)} · ${relName}` : shortProduct(d.productId)}
      >
        {shortProduct(d.productId)}{relName ? ` · ${relName}` : ''}
      </span>
      <span title={`更新于 ${d.updatedAt}`} className={`w-12 shrink-0 text-right text-xs tabular-nums ${stale >= 3 ? 'font-bold text-cat-pink' : 'text-txt-low'}`}>
        {stale}天
      </span>
      <div className="flex w-28 shrink-0 items-center justify-end gap-0.5">
        {d.foundInTestTaskId && (
          <button type="button" title="发现于测试任务（M2）" className={`${iconBtn} text-cat-teal`} onClick={(e) => { e.stopPropagation(); nav.go('tasks') }}><FlaskConical size={13} /></button>
        )}
        {d.fixedInMrId && (
          <button type="button" title="修复 MR（M2）" className={`${iconBtn} text-brand`} onClick={(e) => { e.stopPropagation(); nav.go('review') }}><GitPullRequest size={13} /></button>
        )}
        {rel && (
          <button type="button" title="阻塞版本" className={`${iconBtn} text-cat-red`} onClick={(e) => { e.stopPropagation(); nav.go('delivery', rel) }}><Package size={13} /></button>
        )}
        {d.relatedTaskId && (
          <button type="button" title="关联任务" className={`${iconBtn} text-cat-blue`} onClick={(e) => { e.stopPropagation(); nav.go('tasks', d.relatedTaskId) }}><ListChecks size={13} /></button>
        )}
      </div>
    </div>
  )
}

// ---------------- 详情抽屉：全字段 + 状态流转（真实 transition） ----------------
function DefectDrawer({ wid, nav, onClose, users, onTransition }: {
  wid: string
  nav: PageProps['nav']
  onClose: () => void
  users: Map<string, import('../../api/users').RemoteUserBrief>
  onTransition: (wid: string, to: string) => void
}) {
  const shortProduct = useShortProduct()
  const componentName = useComponentName()
  const { data: remoteDefects = [] } = useDefects()
  const { data: remoteReleases = [] } = useReleases()
  const { data: workItemRows } = useWorkItems()
  const d = remoteDefects.find((x) => x.id === wid)
  if (!d) return null
  const flowIdx = defectStatusFlow.indexOf(d.status)
  const brid = d.blockedReleaseId
  const blockedRel = brid ? remoteReleases.find((r) => r.id === brid) : undefined
  // 收口批：发现于测试任务 / 关联任务真实链路（后端 foundInId/relatedId → 前端映射字段）
  const foundIn = d.foundInTestTaskId ? workItemRows?.find((x) => x.id === d.foundInTestTaskId) : undefined
  const related = d.relatedTaskId ? workItemRows?.find((x) => x.id === d.relatedTaskId) : undefined
  const linkChip = 'inline-flex max-w-full cursor-pointer items-center gap-1 truncate rounded border border-line bg-canvas px-1.5 py-1 text-[11px] text-txt-mid hover:bg-ink-700'
  const field = (k: string, v: ReactNode) => (
    <div><div className="text-[11px] text-txt-low">{k}</div><div className="mt-0.5 text-xs text-txt-hi">{v}</div></div>
  )
  return (
    <div className="fixed inset-0 z-50 bg-black/30" onClick={onClose}>
      <div className="absolute inset-y-0 right-0 flex w-[440px] max-w-full flex-col overflow-y-auto border-l border-line bg-canvas p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs font-bold text-txt-low">{d.key}</span>
              <Pill tone={severityTone[d.severity]}>{d.severity}</Pill>
              <Pill tone={statusTone(d)}>{d.status}</Pill>
            </div>
            <h3 className="mt-1.5 text-base font-bold leading-6 text-txt-hi">{d.title}</h3>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer text-txt-low hover:text-txt-hi"><X size={16} /></button>
        </div>

        {/* 状态流转按钮组（defectStatusFlow 顺序）→ POST transition */}
        <div className="mt-3 flex flex-wrap items-center gap-1.5">
          {defectStatusFlow.map((s, i) => (
            <button
              key={s}
              type="button"
              onClick={() => onTransition(d.id, s)}
              className={`cursor-pointer rounded-full px-2.5 py-1 text-xs font-medium transition ${
                d.status === s ? 'bg-brand text-white' : i < flowIdx ? 'bg-ok-bg text-ok-deep' : 'border border-line text-txt-mid hover:bg-ink-700'
              }`}
            >
              {s}
            </button>
          ))}
          {d.status === '已关闭' && (
            <button type="button" onClick={() => onTransition(d.id, '重新打开')} className="cursor-pointer rounded-full border border-line px-2.5 py-1 text-xs font-medium text-txt-mid hover:bg-ink-700">
              重新打开
            </button>
          )}
          {d.status === '重新打开' && d.reopenedCount > 0 && <Pill tone="bad">已重开 {d.reopenedCount} 次</Pill>}
        </div>

        <div className="mt-3 grid grid-cols-2 gap-3 rounded-card bg-card p-3">
          {field('负责人', <span className="flex items-center gap-1.5"><RemoteAvatar userId={d.assigneeId} users={users} size={18} />{remoteName(users, d.assigneeId)}</span>)}
          {field('报告人', remoteName(users, d.reportedById))}
          {field('优先级', <PriorityBadge p={d.priority} />)}
          {field('产品', shortProduct(d.productId))}
          {field('所属组件', componentName(d.componentId))}
          {field('迭代 / 工时', '—（M2）')}
          {field('乐观锁版本', `v${d.version}（PUT 需 If-Match）`)}
          {field('创建 / 更新', `${d.createdAt} / ${d.updatedAt}`)}
          {field('滞留天数', `${daysSince(d.updatedAt)} 天`)}
        </div>

        {d.description && <p className="mt-3 whitespace-pre-wrap text-xs leading-5 text-txt-mid">{d.description}</p>}
        {d.labels.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1">{d.labels.map((l) => <span key={l} className="rounded bg-ink-700 px-1.5 py-px text-[11px] text-txt-mid">{l}</span>)}</div>
        )}

        {/* 关联链路（收口批四联跳转真实化：阻塞版本 / 发现于测试任务 / 关联任务） */}
        <div className="mt-4">
          <div className="mb-1.5 text-[11px] font-semibold text-txt-low">关联链路（四联跳转）</div>
          <div className="flex flex-wrap gap-1.5">
            {brid && (
              <button type="button" onClick={() => nav.go('delivery', brid)} className="inline-flex max-w-full cursor-pointer items-center gap-1 truncate rounded border border-bad/30 bg-bad-bg px-1.5 py-1 text-[11px] text-bad-deep hover:bg-bad/15">
                <Package size={11} />阻塞 {blockedRel?.name ?? shortProduct(brid)}
              </button>
            )}
            {d.foundInTestTaskId && (
              <button type="button" onClick={() => nav.go('tasks', d.foundInTestTaskId)} className={linkChip}>
                <FlaskConical size={11} className="shrink-0 text-cat-teal" />发现于 {foundIn ? `${foundIn.key} ${foundIn.title}` : '测试任务'}
              </button>
            )}
            {d.relatedTaskId && (
              <button type="button" onClick={() => nav.go('tasks', d.relatedTaskId)} className={linkChip}>
                <ListChecks size={11} className="shrink-0 text-cat-blue" />关联 {related ? `${related.key} ${related.title}` : '任务'}
              </button>
            )}
            {!brid && !d.foundInTestTaskId && !d.relatedTaskId && <span className="text-xs text-txt-low">无关联对象</span>}
          </div>
        </div>

        {/* 关联提交（M2-C/V-6 前端接线）：push hook 解析 refs #KEY 的留痕清单 */}
        <CommitsBlock workItemKey={d.key} />

        {/* 话题入口（致命/严重后端自动建题；会话 REST M2，跳即时沟通页） */}
        <button type="button" onClick={() => nav.go('im')} className="mt-4 flex cursor-pointer items-center gap-2 rounded-card bg-brand-bg/60 px-3 py-2.5 text-left hover:bg-brand-bg">
          <Hash size={14} className="shrink-0 text-brand" />
          <span className="min-w-0 flex-1 truncate text-xs text-txt-mid">关联话题：自动建题（会话列表 M2，见即时沟通）</span>
          <ArrowRight size={12} className="shrink-0 text-brand" />
        </button>
      </div>
    </div>
  )
}

// ---------------- 登记缺陷表单：R-8 起共用 features/defects/DefectForm.tsx（原内联 CreateModal 移除） ----------------
// 保留区：关联提交块（CommitsBlock）见下。
// ---------------- 关联提交（M2-C/V-6 前端接线）：GET /commits?workItemKey= ----------------
function CommitsBlock({ workItemKey }: { workItemKey: string }) {
  const { data } = useCommits(workItemKey)
  const items = data?.items ?? []
  return (
    <div className="mt-4">
      <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-semibold text-txt-low">
        <GitCommitHorizontal size={12} />关联提交（{items.length}）
      </div>
      {items.length === 0 && (
        <div className="text-xs text-txt-low">暂无关联提交（push 时 commit message 带 refs #{workItemKey} 自动关联）</div>
      )}
      <div className="space-y-1">
        {items.map((c) => (
          <div key={c.repo + c.sha} className="flex items-center gap-1.5 rounded border border-line bg-canvas px-1.5 py-1 text-[11px]">
            <span className="shrink-0 font-mono font-bold text-brand">{c.sha.slice(0, 7)}</span>
            <span className="min-w-0 flex-1 truncate text-txt-mid">{c.subject ?? '(无说明)'}</span>
            <span className="shrink-0 text-txt-low">{c.authorName ?? '—'}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
