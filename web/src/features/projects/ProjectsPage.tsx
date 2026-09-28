// 项目交付（⑥o 双轨 · docs/v2/15 §5）：项目卡网格（客制化率/回流率两张度量数字）+
// 新建项目弹窗 + 详情侧板（客制化需求列表、未回流项行内「回流产品」、顶部汇总条）。
// 口径：客制化率 = 客制化需求 / 产品全部工作项（客制占产品账本比例）；回流率 = 已回流 / 客制化（rate 均为 0~1 小数）。
import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Building2, CalendarRange, Package, Plus, RefreshCcw, X } from 'lucide-react'
import type { PageProps } from '../../nav'
import {
  projectsApi, useProducts, useProject, useProjects, useUpgradeWarnings, workItemsApi,
  type RemoteProject, type RemoteProjectItem,
} from '../../api/queries'
import { toBrief, useUserBriefs } from '../../api/users'
import { Avatar, Btn, Card, Empty, PageHeader, Pill, Spinner } from '../../components/ui'

const inputCls = 'w-full rounded-input border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none placeholder:text-txt-low/70 focus:border-brand'

/** 项目状态 → Pill 语义（delivering=交付中 warn / accepted=已验收 ok / closed=已关闭 neutral） */
const PROJ_STATUS: Record<RemoteProject['status'], { label: string; tone: 'warn' | 'ok' | 'neutral' }> = {
  delivering: { label: '交付中', tone: 'warn' },
  accepted: { label: '已验收', tone: 'ok' },
  closed: { label: '已关闭', tone: 'neutral' },
}

/** 条目（客制化需求）状态 → Pill 语义（与需求管理页同口径） */
const ITEM_STATUS: Record<string, { label: string; tone: 'neutral' | 'warn' | 'brand' | 'info' | 'ok' | 'bad' }> = {
  draft: { label: '草稿', tone: 'neutral' },
  pending_review: { label: '待评审', tone: 'warn' },
  accepted: { label: '已受理', tone: 'brand' },
  in_dev: { label: '开发中', tone: 'info' },
  delivered: { label: '已交付', tone: 'ok' },
  closed: { label: '已验收', tone: 'ok' },
  rejected: { label: '已拒绝', tone: 'bad' },
}
const itemStatus = (s: string) => ITEM_STATUS[s] ?? { label: s, tone: 'neutral' as const }

/** 0~1 小数 → 百分比整数文案（非法/缺省按 0 展示） */
const pct = (v: number | undefined) => (v != null && isFinite(v) && v > 0 ? `${Math.round(v * 100)}%` : '0%')

/** ISO 日期 → YYYY-MM-DD（空/非法 → '—'） */
const fmtDay = (d?: string) => (d && d.length >= 10 ? d.slice(0, 10) : '—')

/** 产品全部工作项数（productTotal 直读；旧响应无此字段时由 rate 反推兜底） */
const totalOf = (p: RemoteProject) => (p.productTotal ?? (p.customRate > 0 ? Math.round(p.customTotal / p.customRate) : p.customTotal))

export default function ProjectsPage({ nav, id }: PageProps) {
  const projectsQ = useProjects()
  const [showNew, setShowNew] = useState(false)
  const [openId, setOpenId] = useState<string | undefined>(id)
  const [toast, setToast] = useState<{ ok: boolean; text: string } | undefined>()
  // QA 顺手项：toast 4.2s 自动消失（家规见 DefectsPage）
  useEffect(() => {
    if (!toast) return
    const t = window.setTimeout(() => setToast(undefined), 4200)
    return () => window.clearTimeout(t)
  }, [toast])

  const projects = projectsQ.data ?? []

  return (
    <div>
      <PageHeader
        title="项目交付"
        desc="客制化是产品探针——交付中沉淀，回流处产品化 · 客制化需求挂产品账本并记来源项目，验收后经「回流产品」进入产品 backlog 评审"
        actions={<Btn variant="primary" onClick={() => setShowNew(true)}><Plus size={14} /> 新建项目</Btn>}
      />

      {/* 项目卡网格 */}
      {projectsQ.isLoading && <Card><Empty text="项目加载中…" size="sm" icon={<Spinner />} /></Card>}
      {projectsQ.isError && (
        <Card><Empty text="项目加载失败（/api/v1/projects 暂不可用，后端部署后刷新重试）" size="sm" /></Card>
      )}
      {!projectsQ.isLoading && !projectsQ.isError && projects.length === 0 && (
        <Card>
          <Empty text="暂无项目——新建第一个交付项目" />
          <div className="flex justify-center pb-8">
            <Btn variant="primary" onClick={() => setShowNew(true)}><Plus size={14} /> 新建项目</Btn>
          </div>
        </Card>
      )}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        {projects.map((p) => {
          const st = PROJ_STATUS[p.status] ?? PROJ_STATUS.delivering
          return (
            // 卡片整体点击展开详情侧板
            <div key={p.id} onClick={() => setOpenId(p.id)}>
              <Card className="h-full cursor-pointer p-4 transition-colors hover:border-brand">
                <div className="flex items-start justify-between gap-2">
                  <span className="min-w-0 truncate text-sm font-bold text-txt-hi" title={p.name}>{p.name}</span>
                  <Pill tone={st.tone}>{st.label}</Pill>
                </div>
                <div className="mt-1.5 flex items-center gap-1.5 text-xs text-txt-mid">
                  <Building2 size={13} className="shrink-0 text-txt-low" />
                  <span className="truncate">{p.customerName || '未填写客户'}</span>
                  <span className="text-txt-low">·</span>
                  <Package size={13} className="shrink-0 text-txt-low" />
                  <span className="truncate" title={p.productName ?? p.productId}>{p.productName ?? `产品 ${(p.productId ?? '').slice(0, 4)}`}</span>
                </div>
                <div className="mt-1 flex items-center gap-1.5 text-xs text-txt-low">
                  <CalendarRange size={13} className="shrink-0" />
                  <span className="tabular-nums">{fmtDay(p.startDate)} → {fmtDay(p.planAcceptDate)}</span>
                </div>
                {/* 双轮度量：客制化率 / 回流率（title 给分子分母口径） */}
                <div className="mt-3 grid grid-cols-2 gap-2">
                  <div className="rounded-lg bg-canvas px-3 py-2" title={`客制 ${p.customTotal}/${totalOf(p)} 项`}>
                    <div className="text-lg font-bold tabular-nums text-cat-teal">{pct(p.customRate)}</div>
                    <div className="text-[11px] text-txt-low">客制化率 · 客制 {p.customTotal}/{totalOf(p)} 项</div>
                  </div>
                  <div className="rounded-lg bg-canvas px-3 py-2" title={`已回流 ${p.promotedTotal}/${p.customTotal} 项`}>
                    <div className="text-lg font-bold tabular-nums text-brand">{pct(p.promoteRate)}</div>
                    <div className="text-[11px] text-txt-low">回流率 · 已回流 {p.promotedTotal}/{p.customTotal} 项</div>
                  </div>
                </div>
              </Card>
            </div>
          )
        })}
      </div>

      {openId && <ProjectDrawer id={openId} nav={nav} onClose={() => setOpenId(undefined)} onToast={setToast} />}
      {showNew && <NewProjectModal onClose={() => setShowNew(false)} onDone={() => { setShowNew(false); setToast({ ok: true, text: '项目已创建' }) }} />}
      {toast && (
        <div className="fixed bottom-6 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-2 rounded-full border border-line bg-canvas px-4 py-2.5 text-sm text-txt-hi shadow-lg">
          <span className={`h-2 w-2 rounded-full ${toast.ok ? 'bg-ok' : 'bg-bad'}`} />{toast.text}
        </div>
      )}
    </div>
  )
}

// ==================== 详情侧板：汇总条 + 客制化需求列表 + 回流产品 ====================

/** 项目详情侧板（风格随需求抽屉）：顶部度量汇总条 + 客制化需求列表（未回流项行内回流按钮） */
function ProjectDrawer({ id, nav, onClose, onToast }: {
  id: string
  nav: PageProps['nav']
  onClose: () => void
  onToast: (t: { ok: boolean; text: string }) => void
}) {
  const queryClient = useQueryClient()
  const projQ = useProject(id)
  const warnQ = useUpgradeWarnings(id)
  const { data: briefRows } = useUserBriefs()
  const briefs = toBrief(briefRows)
  const [busyKey, setBusyKey] = useState<string | undefined>()
  const [err, setErr] = useState('')

  const p = projQ.data
  // 详情 items 契约为该项目的客制化需求视图；此处再按 origin 防御过滤
  const items = (p?.customItems ?? []).filter((it) => it.origin !== 'product')

  /** 回流产品：POST /work-items/{idOrKey}/promote-to-product → 复制为产品需求 + 回写 + 建链；
   *  成功失效 projects/requirements/work-items；422（如重复回流 T1-PRD-4256）展示后端 message */
  const promote = async (item: RemoteProjectItem) => {
    setBusyKey(item.id)
    setErr('')
    try {
      await workItemsApi.promoteToProduct(item.key)
      await queryClient.invalidateQueries({ queryKey: ['projects'] })
      await queryClient.invalidateQueries({ queryKey: ['requirements'] })
      await queryClient.invalidateQueries({ queryKey: ['work-items'] })
      onToast({ ok: true, text: `已回流为产品需求（${item.key}）` })
    } catch (e) {
      const msg = e instanceof Error ? e.message : '回流失败'
      setErr(msg)
      onToast({ ok: false, text: msg })
    } finally {
      setBusyKey(undefined)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-txt-hi/25" onClick={onClose} />
      <aside className="relative flex h-full w-[520px] max-w-full flex-col overflow-y-auto border-l border-line bg-card p-5">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <Pill tone={(p && PROJ_STATUS[p.status]?.tone) ?? 'neutral'}>{(p && PROJ_STATUS[p.status]?.label) ?? '—'}</Pill>
              {p?.productName && <span className="text-xs text-txt-mid">{p.productName}</span>}
            </div>
            <h2 className="mt-1.5 truncate text-base font-bold text-txt-hi" title={p?.name}>{p?.name ?? (projQ.isLoading ? '加载中…' : '项目')}</h2>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"><X size={16} /></button>
        </div>

        {projQ.isLoading && <Empty text="项目详情加载中…" size="sm" icon={<Spinner />} />}
        {projQ.isError && <Empty text="项目详情加载失败（后端 /api/v1/projects 不可用）" size="sm" />}

        {p && (
          <>
            {/* 元信息 */}
            <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-txt-mid">
              <div className="flex items-center gap-1.5"><Building2 size={13} className="text-txt-low" />客户 {p.customerName || '—'}</div>
              <div className="flex items-center gap-1.5">
                项目经理
                {p.managerId
                  ? <><Avatar userId={p.managerId} size={18} />{briefs.get(p.managerId)?.name ?? '—'}</>
                  : '—'}
              </div>
              <div>启动 {fmtDay(p.startDate)}</div>
              <div>计划验收 {fmtDay(p.planAcceptDate)}</div>
            </div>

            {/* 顶部汇总条（双轮度量口径与卡片一致） */}
            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border border-line bg-canvas px-3 py-2 text-xs text-txt-mid">
              <span className="font-semibold text-txt-hi">双轮汇总</span>
              <span title={`客制 ${p.customTotal}/${totalOf(p)} 项`}>客制化 <strong className="tabular-nums text-cat-teal">{pct(p.customRate)}</strong>（客制 {p.customTotal}/{totalOf(p)} 项）</span>
              <span title={`已回流 ${p.promotedTotal}/${p.customTotal} 项`}>回流 <strong className="tabular-nums text-brand">{pct(p.promoteRate)}</strong>（已回流 {p.promotedTotal}/{p.customTotal} 项）</span>
            </div>

            {/* 升级冲突预警（⑥p · docs/v2/15 §9）：客制组件集 ∩ 产品演进组件集 */}
            <div className="mt-4">
              <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-semibold tracking-wide text-txt-low"
                title={`口径：本项目的客制需求所挂组件 ∩ 产品线在基线（${fmtDay(warnQ.data?.baselineDate)}）后动过的组件；红=产品 in_dev 正在改同一模块（升级前须对齐），黄=产品已动过（升级需回归）。客制需求未挂组件时不参与计算。`}>
                <AlertTriangle size={11} /> 升级冲突预警（{warnQ.data ? warnQ.data.redCount + warnQ.data.yellowCount : '…'}）
              </div>
              <div className="space-y-1.5">
                {(warnQ.data?.items ?? []).map((w) => (
                  <div key={w.componentId} className={`rounded-lg border px-2.5 py-2 text-xs ${
                    w.severity === 'red' ? 'border-bad/40 bg-bad/5' : 'border-warn/40 bg-warn/5'
                  }`}>
                    <div className="flex items-center gap-2">
                      <Pill tone={w.severity === 'red' ? 'bad' : 'warn'}>
                        {w.severity === 'red' ? '撞线' : '需回归'}
                      </Pill>
                      <span className="font-semibold text-txt-hi">{w.componentName}</span>
                      <span className="text-txt-low">客制 {w.customReqKeys.join('、')} × 产品 {w.productReqKeys.join('、')}</span>
                    </div>
                    <div className="mt-1 text-[11px] text-txt-low">
                      {w.severity === 'red'
                        ? `产品线正在改该模块（${w.productReqKeys.join('、')} 开发中）——升级版本前须先对齐合入`
                        : `产品线基线后动过该模块（${w.productReqKeys.join('、')}）——升级时需回归客制`}
                    </div>
                  </div>
                ))}
                {warnQ.data && warnQ.data.items.length === 0 && (
                  <div className="rounded-lg border border-dashed border-line bg-canvas/60 p-3 text-xs text-txt-low">
                    暂无升级冲突——客制模块与产品演进无重叠；客制化需求挂上「所属组件」后可计算影响面。
                  </div>
                )}
              </div>
            </div>

            {/* 客制化需求列表：key/标题/状态/回流徽标 + 未回流项行内回流按钮 */}
            <div className="mt-4">
              <div className="mb-1.5 text-[11px] font-semibold tracking-wide text-txt-low">客制化需求（{items.length}）</div>
              <div className="space-y-1.5">
                {items.map((it) => {
                  const st = itemStatus(it.status)
                  const promoted = !!it.promotedToId
                  return (
                    <div key={it.id} className="flex items-center gap-2 rounded-lg border border-line bg-canvas px-2.5 py-2 text-xs">
                      <button type="button" onClick={() => nav.go('requirements', it.id)} title="在需求管理中查看"
                        className="cursor-pointer font-mono font-bold text-cat-purple hover:underline">{it.key}</button>
                      <span className="min-w-0 flex-1 truncate text-txt-hi" title={it.title}>{it.title}</span>
                      <Pill tone={st.tone}>{st.label}</Pill>
                      {promoted
                        ? <Pill tone="ok">已回流</Pill>
                        : (
                          <span title="复制为产品需求进入 backlog 评审">
                            <Btn variant="default" disabled={busyKey === it.id} onClick={() => void promote(it)}>
                              <RefreshCcw size={12} />{busyKey === it.id ? '回流中…' : '回流产品'}
                            </Btn>
                          </span>
                        )}
                    </div>
                  )
                })}
                {items.length === 0 && (
                  <div className="rounded-lg border border-dashed border-line bg-canvas/60 p-3 text-xs text-txt-low">
                    暂无客制化需求。在「需求管理」新建时选择来源为客制化并指定本项目，即可在此沉淀交付中的客制信号。
                  </div>
                )}
              </div>
              {err && <div className="mt-2 rounded bg-bad-bg px-2.5 py-1.5 text-[11px] text-bad-deep">{err}</div>}
            </div>
          </>
        )}
      </aside>
    </div>
  )
}

// ==================== 新建项目弹窗 ====================

/** 新建项目（POST /api/v1/projects；名称*、产品*必填，状态默认交付中；重名 422 T1-PRD-4257 展示后端 message） */
function NewProjectModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const queryClient = useQueryClient()
  const { data: productRows } = useProducts()
  const [name, setName] = useState('')
  const [customerName, setCustomerName] = useState('')
  const [productId, setProductId] = useState('')
  const [status, setStatus] = useState<'delivering' | 'accepted' | 'closed'>('delivering')
  const [planAcceptDate, setPlanAcceptDate] = useState('')
  const [busy, setBusy] = useState(false)
  const [fieldErr, setFieldErr] = useState<Record<string, string>>({})
  const [err, setErr] = useState('')

  const submit = async () => {
    setFieldErr({})
    setErr('')
    if (!name.trim()) { setFieldErr({ name: '名称必填' }); return }
    if (!productId) { setFieldErr({ productId: '请选择交付产品' }); return }
    setBusy(true)
    try {
      await projectsApi.create({
        name: name.trim(),
        customerName: customerName.trim() || undefined,
        productId,
        status,
        planAcceptDate: planAcceptDate || undefined,
      })
      await queryClient.invalidateQueries({ queryKey: ['projects'] })
      onDone()
    } catch (e) {
      setErr(e instanceof Error ? e.message : '创建失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-txt-hi/25" onClick={onClose} />
      <div className="relative w-[460px] max-w-full rounded-card border border-line bg-canvas p-5 shadow-2xl">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-base font-bold text-txt-hi">新建交付项目</h3>
          <button type="button" onClick={onClose} className="cursor-pointer rounded-md p-1 text-txt-mid hover:bg-ink-700 hover:text-txt-hi"><X size={16} /></button>
        </div>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-txt-mid">项目名称（必填）</label>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="如：A 集团协同平台实施" className={`${inputCls} ${fieldErr.name ? 'border-bad' : ''}`} />
            {fieldErr.name && <div className="mt-1 text-xs text-bad">{fieldErr.name}</div>}
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-txt-mid">客户</label>
            <input value={customerName} onChange={(e) => setCustomerName(e.target.value)} placeholder="客户名（可选）" className={inputCls} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1 block text-xs font-medium text-txt-mid">交付产品（必填）</label>
              <select value={productId} onChange={(e) => setProductId(e.target.value)} className={`${inputCls} ${fieldErr.productId ? 'border-bad' : ''}`}>
                <option value="">选择产品…</option>
                {(productRows ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
              {fieldErr.productId && <div className="mt-1 text-xs text-bad">{fieldErr.productId}</div>}
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-txt-mid">状态</label>
              <select value={status} onChange={(e) => setStatus(e.target.value as 'delivering' | 'accepted' | 'closed')} className={inputCls}>
                <option value="delivering">交付中</option>
                <option value="accepted">已验收</option>
                <option value="closed">已关闭</option>
              </select>
            </div>
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-txt-mid">计划验收日</label>
            <input type="date" value={planAcceptDate} onChange={(e) => setPlanAcceptDate(e.target.value)} className={inputCls} />
          </div>
        </div>
        {err && <div className="mt-2 text-xs text-bad-deep">{err}</div>}
        <div className="mt-4 flex items-center justify-between border-t border-line pt-3">
          <p className="text-[11px] text-txt-low">建项目后，客制化需求在「需求管理」按来源沉淀到本项目。</p>
          <div className="flex gap-2">
            <Btn variant="ghost" onClick={onClose}>取消</Btn>
            <Btn variant="primary" disabled={busy} onClick={() => void submit()}><Plus size={13} /> {busy ? '创建中…' : '创建项目'}</Btn>
          </div>
        </div>
      </div>
    </div>
  )
}
