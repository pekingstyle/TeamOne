// 缺陷中心 v2：严重度/状态/产品/版本过滤器 + 统计条（严重度计数 + 阻塞版本数）
// + 四视图（全部 / 阻塞清单 / 按测试任务分组 / 按模块分组）+ 行四联跳转 + 详情抽屉（defectStatusFlow 流转）+ 登记缺陷
import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { AlertTriangle, ArrowRight, FlaskConical, GitPullRequest, Hash, ListChecks, Package, Plus, X } from 'lucide-react'
import type { Defect, DefectSeverity } from '../../data/types'
import {
  bump, components, createWorkItem, defectStatusFlow, defects, mrById, productById, products,
  releaseById, releases, setWorkItemStatus, severityTone, sprintById, sprints, tasks, testTasks,
  topicById, useStore, userById, users, workItemById,
} from '../../data/store'
import { Avatar, Btn, Card, Empty, PageHeader, Pill, PriorityBadge } from '../../components/ui'
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
const linkBtn = 'inline-flex max-w-full cursor-pointer items-center gap-1 truncate rounded border border-line bg-canvas px-1.5 py-1 text-[11px] text-brand hover:bg-brand-bg'
const linkBtnRed = 'inline-flex max-w-full cursor-pointer items-center gap-1 truncate rounded border border-bad/30 bg-bad-bg px-1.5 py-1 text-[11px] text-bad-deep hover:bg-bad/15'
const iconBtn = 'cursor-pointer rounded p-1 hover:bg-ink-700'

function statusTone(d: Defect): Tone {
  return d.status === '修复中' ? 'info' : d.status === '已修复' ? 'warn' : d.status === '回归通过' ? 'ok' : d.status === '重新打开' ? 'bad' : 'neutral'
}
/** 滞留天数 = updatedAt 距今天数（fmt 产出串兜底解析：完整日期走 Date，年内相对时间走 M-D 正则） */
function daysSince(ts: string): number {
  const now = Date.now()
  if (/^\d{4}-/.test(ts)) {
    const d = new Date(ts).getTime()
    if (!isNaN(d)) return Math.max(0, Math.floor((now - d) / 86400000))
  }
  const m = ts.match(/^(\d{1,2})-(\d{1,2})/)
  if (m) return Math.max(0, Math.floor((now - new Date(new Date().getFullYear(), +m[1] - 1, +m[2]).getTime()) / 86400000))
  return 0
}

export default function DefectsPage({ nav, id }: PageProps) {
  useStore()
  const [sevSel, setSevSel] = useState<DefectSeverity[]>([])
  const [statusSel, setStatusSel] = useState('全部')
  const [prodSel, setProdSel] = useState('全部')
  const [relSel, setRelSel] = useState('全部')
  const [view, setView] = useState<(typeof VIEWS)[number]['id']>('all')
  const [drawerId, setDrawerId] = useState<string | undefined>(() => (id && workItemById(id)?.type === 'defect' ? id : undefined))
  const [creating, setCreating] = useState(false)
  const [toast, setToast] = useState<string | undefined>()
  useEffect(() => {
    if (id && workItemById(id)?.type === 'defect') setDrawerId(id)
  }, [id])

  const filtered = defects.filter(
    (d) => (sevSel.length === 0 || sevSel.includes(d.severity))
      && (statusSel === '全部' || d.status === statusSel)
      && (prodSel === '全部' || d.productId === prodSel)
      && (relSel === '全部' || d.releaseId === relSel),
  )
  const blockedList = filtered.filter((d) => d.blockedReleaseId && d.status !== '已关闭' && d.status !== '回归通过')
  const blockedReleaseCount = releases.filter((r) => r.blocked).length

  const row = (d: Defect) => <DefectRow key={d.id} d={d} nav={nav} onOpen={() => setDrawerId(d.id)} />

  return (
    <div>
      <PageHeader title="缺陷中心" desc="严重度与优先级分离 · S1/S2 未关闭缺陷阻塞版本发布（RL-3） · 滞留 ≥ 3 天粉色预警" />

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
          {products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
        <select value={relSel} onChange={(e) => setRelSel(e.target.value)} className={selCls}>
          <option value="全部">全部版本</option>
          {releases.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
        </select>
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
        filtered.length === 0 ? <Card><Empty text="没有符合条件的缺陷" /></Card> : <Card className="divide-y divide-line">{filtered.map(row)}</Card>
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
                  <span className="text-xs text-txt-low">计划发布 {r.planDate} · 未关闭阻塞缺陷 {list.length} 个</span>
                </div>
                <Card className="divide-y divide-line">{list.map(row)}</Card>
              </div>
            )
          })
        )
      )}

      {view === 'byTest' && (
        <>
          {testTasks.map((tt) => {
            const list = filtered.filter((d) => d.foundInTestTaskId === tt.id)
            if (list.length === 0) return null
            return (
              <div key={tt.id} className="mb-4">
                <div className="mb-1.5 flex flex-wrap items-center gap-2">
                  <Pill tone="teal">{tt.key}</Pill>
                  <span className="text-sm font-semibold text-txt-hi">{tt.title}</span>
                  <span className="text-xs text-txt-low">用例通过 {tt.passedCount}/{tt.caseCount} · 产出缺陷 {list.length}</span>
                </div>
                <Card className="divide-y divide-line">{list.map(row)}</Card>
              </div>
            )
          })}
          <GroupCard title="未关联测试任务（线上 / 开发自测发现）" list={filtered.filter((d) => !d.foundInTestTaskId)} row={row} />
          {filtered.length === 0 && <Card><Empty text="没有符合条件的缺陷" /></Card>}
        </>
      )}

      {view === 'byModule' && (
        <>
          {components.map((c) => {
            const list = filtered.filter((d) => d.componentId === c.id)
            if (list.length === 0) return null
            return (
              <div key={c.id} className="mb-4">
                <div className="mb-1.5 flex flex-wrap items-center gap-2">
                  <Pill tone="brand">{c.name}</Pill>
                  <span className="text-xs text-txt-low">{productById(c.productId)?.name} · 缺陷 {list.length} 个 · 负责人 {userById(c.leadId)?.name}</span>
                </div>
                <Card className="divide-y divide-line">{list.map(row)}</Card>
              </div>
            )
          })}
          <GroupCard title="未指定模块" list={filtered.filter((d) => !d.componentId)} row={row} />
          {filtered.length === 0 && <Card><Empty text="没有符合条件的缺陷" /></Card>}
        </>
      )}

      {drawerId && <DefectDrawer wid={drawerId} nav={nav} onClose={() => setDrawerId(undefined)} />}
      {creating && (
        <CreateModal
          onClose={() => setCreating(false)}
          onCreated={(n) => {
            setToast(`已自动创建话题并拉入 ${n} 名干系人`)
            setTimeout(() => setToast(undefined), 4000)
          }}
        />
      )}
      {toast && (
        <div className="fixed bottom-6 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-2 rounded-full border border-line bg-canvas px-4 py-2.5 text-sm text-txt-hi shadow-lg">
          <span className="h-2 w-2 rounded-full bg-ok" />{toast}
        </div>
      )}
    </div>
  )
}

function GroupCard({ title, list, row }: { title: string; list: Defect[]; row: (d: Defect) => ReactNode }) {
  if (list.length === 0) return null
  return (
    <div className="mb-4">
      <div className="mb-1.5 text-sm font-semibold text-txt-hi">{title}<span className="ml-2 text-xs font-normal text-txt-low">{list.length} 个</span></div>
      <Card className="divide-y divide-line">{list.map(row)}</Card>
    </div>
  )
}

// ---------------- 缺陷行：severity 胶囊 / 状态 / 滞留 / 四联跳转 ----------------
function DefectRow({ d, nav, onOpen }: { d: Defect; nav: PageProps['nav']; onOpen: () => void }) {
  const stale = daysSince(d.updatedAt)
  const tt = d.foundInTestTaskId ? workItemById(d.foundInTestTaskId) : undefined
  const rt = d.relatedTaskId ? workItemById(d.relatedTaskId) : undefined
  const mr = d.fixedInMrId ? mrById(d.fixedInMrId) : undefined
  const br = d.blockedReleaseId ? releaseById(d.blockedReleaseId) : undefined
  return (
    <div className="flex cursor-pointer items-center gap-2.5 px-3.5 py-2.5 hover:bg-ink-700" onClick={onOpen}>
      <Pill tone={severityTone[d.severity]}>{d.severity}</Pill>
      <span className="w-14 shrink-0 font-mono text-xs text-txt-low">{d.key}</span>
      <span className="min-w-0 flex-1 truncate text-sm text-txt-hi">{d.title}</span>
      <Pill tone={statusTone(d)}>{d.status}</Pill>
      <Avatar userId={d.assigneeId} size={20} />
      <span className="hidden w-36 shrink-0 truncate text-xs text-txt-low xl:block">
        {productById(d.productId)?.key} · {sprintById(d.sprintId ?? '')?.name ?? '—'}
      </span>
      <span title={`更新于 ${d.updatedAt}`} className={`w-12 shrink-0 text-right text-xs tabular-nums ${stale >= 3 ? 'font-bold text-cat-pink' : 'text-txt-low'}`}>
        {stale}天
      </span>
      <div className="flex w-28 shrink-0 items-center justify-end gap-0.5">
        {tt && (
          <button type="button" title={`发现于 ${tt.key}`} className={`${iconBtn} text-cat-teal`} onClick={(e) => { e.stopPropagation(); nav.go('tasks', tt.id) }}><FlaskConical size={13} /></button>
        )}
        {mr && (
          <button type="button" title={`修复 MR !${mr.number}`} className={`${iconBtn} text-brand`} onClick={(e) => { e.stopPropagation(); nav.go('mr', mr.id) }}><GitPullRequest size={13} /></button>
        )}
        {br && (
          <button type="button" title={`阻塞 ${br.name}`} className={`${iconBtn} text-cat-red`} onClick={(e) => { e.stopPropagation(); nav.go('delivery', br.id) }}><Package size={13} /></button>
        )}
        {rt && (
          <button type="button" title={`关联任务 ${rt.key}`} className={`${iconBtn} text-cat-blue`} onClick={(e) => { e.stopPropagation(); nav.go('tasks', rt.id) }}><ListChecks size={13} /></button>
        )}
      </div>
    </div>
  )
}

// ---------------- 详情抽屉：全字段 + 四联跳转 + 状态流转 + 话题 ----------------
function DefectDrawer({ wid, nav, onClose }: { wid: string; nav: PageProps['nav']; onClose: () => void }) {
  const d = workItemById(wid)
  if (!d || d.type !== 'defect') return null
  const flowIdx = defectStatusFlow.indexOf(d.status)
  const tid = d.topicId
  const fid = d.foundInTestTaskId
  const vtid = d.verifyTestTaskId
  const rtid = d.relatedTaskId
  const brid = d.blockedReleaseId
  const fmid = d.fixedInMrId
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

        {/* 状态流转按钮组（defectStatusFlow 顺序） */}
        <div className="mt-3 flex flex-wrap items-center gap-1.5">
          {defectStatusFlow.map((s, i) => (
            <button
              key={s}
              type="button"
              onClick={() => setWorkItemStatus(d.id, s)}
              className={`cursor-pointer rounded-full px-2.5 py-1 text-xs font-medium transition ${
                d.status === s ? 'bg-brand text-white' : i < flowIdx ? 'bg-ok-bg text-ok-deep' : 'border border-line text-txt-mid hover:bg-ink-700'
              }`}
            >
              {s}
            </button>
          ))}
          {d.status === '已关闭' && (
            <button type="button" onClick={() => setWorkItemStatus(d.id, '重新打开')} className="cursor-pointer rounded-full border border-line px-2.5 py-1 text-xs font-medium text-txt-mid hover:bg-ink-700">
              重新打开
            </button>
          )}
          {d.status === '重新打开' && <Pill tone="bad">已重开 {d.reopenedCount} 次</Pill>}
        </div>

        <div className="mt-3 grid grid-cols-2 gap-3 rounded-card bg-card p-3">
          {field('负责人', <span className="flex items-center gap-1.5"><Avatar userId={d.assigneeId} size={18} />{userById(d.assigneeId)?.name}</span>)}
          {field('报告人', userById(d.reportedById)?.name ?? '—')}
          {field('优先级', <PriorityBadge p={d.priority} />)}
          {field('产品 / 组件', `${productById(d.productId)?.name ?? '—'} / ${d.componentId ?? '—'}`)}
          {field('迭代', sprintById(d.sprintId ?? '')?.name ?? '—')}
          {field('工时 / 故事点', `${d.estimateHours}h / ${d.points} 点`)}
          {field('创建 / 更新', `${d.createdAt} / ${d.updatedAt}`)}
          {field('滞留天数', `${daysSince(d.updatedAt)} 天`)}
        </div>

        {d.description && <p className="mt-3 whitespace-pre-wrap text-xs leading-5 text-txt-mid">{d.description}</p>}
        {d.labels.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1">{d.labels.map((l) => <span key={l} className="rounded bg-ink-700 px-1.5 py-px text-[11px] text-txt-mid">{l}</span>)}</div>
        )}

        {/* 四联跳转 */}
        <div className="mt-4">
          <div className="mb-1.5 text-[11px] font-semibold text-txt-low">关联链路（四联跳转）</div>
          <div className="flex flex-wrap gap-1.5">
            {fid && <button type="button" onClick={() => nav.go('tasks', fid)} className={linkBtn}><FlaskConical size={11} />发现于 {workItemById(fid)?.key}</button>}
            {vtid && <button type="button" onClick={() => nav.go('tasks', vtid)} className={linkBtn}>回归于 {workItemById(vtid)?.key}</button>}
            {rtid && <button type="button" onClick={() => nav.go('tasks', rtid)} className={linkBtn}><ListChecks size={11} />关联 {workItemById(rtid)?.key}</button>}
            {fmid && <button type="button" onClick={() => nav.go('mr', fmid)} className={linkBtn}><GitPullRequest size={11} />修复 !{mrById(fmid)?.number}</button>}
            {brid && <button type="button" onClick={() => nav.go('delivery', brid)} className={linkBtnRed}><Package size={11} />阻塞 {releaseById(brid)?.name}</button>}
            {!fid && !vtid && !rtid && !fmid && !brid && <span className="text-xs text-txt-low">无关联对象</span>}
          </div>
        </div>

        {/* 话题入口 */}
        {tid && (
          <button type="button" onClick={() => nav.go('topics', tid)} className="mt-4 flex cursor-pointer items-center gap-2 rounded-card bg-brand-bg/60 px-3 py-2.5 text-left hover:bg-brand-bg">
            <Hash size={14} className="shrink-0 text-brand" />
            <span className="min-w-0 flex-1 truncate text-xs text-txt-mid">关联话题：{topicById(tid)?.title ?? tid}</span>
            <ArrowRight size={12} className="shrink-0 text-brand" />
          </button>
        )}
      </div>
    </div>
  )
}

// ---------------- 登记缺陷表单 ----------------
function CreateModal({ onClose, onCreated }: { onClose: () => void; onCreated: (stakeholderCount: number) => void }) {
  const [title, setTitle] = useState('')
  const [severity, setSeverity] = useState<DefectSeverity>('一般')
  const [priority, setPriority] = useState<'P0' | 'P1' | 'P2' | 'P3'>('P1')
  const [assigneeId, setAssigneeId] = useState('u3')
  const [ttId, setTtId] = useState('')
  const [taskId, setTaskId] = useState('')
  const [blockRid, setBlockRid] = useState('')
  const [sid, setSid] = useState(sprints.find((s) => s.status === 'active')?.id ?? '')
  const submit = () => {
    if (!title.trim()) return
    const item = createWorkItem({ type: 'defect', title: title.trim(), severity, priority, assigneeId, sprintId: sid || undefined })
    if (item.type === 'defect') {
      const d = item as Defect
      d.foundInTestTaskId = ttId || undefined
      d.relatedTaskId = taskId || undefined
      d.blockedReleaseId = blockRid || undefined
      if (blockRid) setWorkItemStatus(d.id, '新建') // 触发 release.blocked 重算（RL-3）
      const n = d.topicId ? topicById(d.topicId)?.participantIds.length ?? 0 : 0
      bump()
      onCreated(n)
    }
    onClose()
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" onClick={onClose}>
      <div className="w-[430px] rounded-card border border-line bg-canvas p-5 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-bold text-txt-hi">登记缺陷</h3>
          <button type="button" onClick={onClose} className="cursor-pointer text-txt-low hover:text-txt-hi"><X size={16} /></button>
        </div>
        <div className="mt-3 space-y-2.5">
          <label className="block text-xs text-txt-mid">
            标题
            <input value={title} onChange={(e) => setTitle(e.target.value)} autoFocus placeholder="缺陷现象一句话…" className="mt-1 w-full rounded-input border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none focus:border-brand" />
          </label>
          <div className="grid grid-cols-2 gap-2.5">
            <label className="block text-xs text-txt-mid">
              严重度
              <select value={severity} onChange={(e) => setSeverity(e.target.value as DefectSeverity)} className={`mt-1 w-full ${selCls}`}>
                {SEVS.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              优先级
              <select value={priority} onChange={(e) => setPriority(e.target.value as 'P0' | 'P1' | 'P2' | 'P3')} className={`mt-1 w-full ${selCls}`}>
                {['P0', 'P1', 'P2', 'P3'].map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              负责人（修复人）
              <select value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)} className={`mt-1 w-full ${selCls}`}>
                {users.map((u) => <option key={u.id} value={u.id}>{u.name} · {u.title}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              迭代
              <select value={sid} onChange={(e) => setSid(e.target.value)} className={`mt-1 w-full ${selCls}`}>
                {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              所属测试任务
              <select value={ttId} onChange={(e) => setTtId(e.target.value)} className={`mt-1 w-full ${selCls}`}>
                <option value="">（无 / 线上发现）</option>
                {testTasks.map((t) => <option key={t.id} value={t.id}>{t.key} {t.title}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              关联任务
              <select value={taskId} onChange={(e) => setTaskId(e.target.value)} className={`mt-1 w-full ${selCls}`}>
                <option value="">（无）</option>
                {tasks.map((t) => <option key={t.id} value={t.id}>{t.key} {t.title}</option>)}
              </select>
            </label>
            <label className="col-span-2 block text-xs text-txt-mid">
              阻塞版本（致命/严重未关闭时锁定发布）
              <select value={blockRid} onChange={(e) => setBlockRid(e.target.value)} className={`mt-1 w-full ${selCls}`}>
                <option value="">（不阻塞版本）</option>
                {releases.filter((r) => r.status !== 'released').map((r) => <option key={r.id} value={r.id}>{r.name} · 计划 {r.planDate}</option>)}
              </select>
            </label>
          </div>
          {(severity === '致命' || severity === '严重') && (
            <div className="rounded-lg bg-bad-bg px-2.5 py-2 text-[11px] text-bad-deep">致命/严重缺陷为强制事件：将自动创建话题并拉入干系人（报告人 / 修复人 / 测试执行人 / 版本负责人），不可退订。</div>
          )}
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <Btn variant="ghost" onClick={onClose}>取消</Btn>
          <Btn variant="primary" onClick={submit} disabled={!title.trim()}>登记</Btn>
        </div>
      </div>
    </div>
  )
}
