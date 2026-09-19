// 产品 RoadMap v2.2（R-7 版本主视角重构 · B5 批，裁决 D3）：
//   默认「版本泳道」= 以版本为主维度的交付时间轴 —— 泳道=版本（五态状态着色），
//   泳道内条形=该版本挂载的 RoadMap 条目（特性，正向 releaseId ∪ release.roadmapItemId 反查，服务端已去重），
//   菱形里程碑=计划发布日；点击条形 → 右侧抽屉下钻 版本→迭代→工作项（两级懒加载，行可跳转）。
//   「目标」降级为顶部过滤器（下拉后仅显示关联版本/条目并高亮）；原「目标视角」保留为次要 tab。
// 时间窗（D3 自适应）：以今天为中心，缺省 过去 1 月 ~ 未来 6 月；泳道日期越界时外扩覆盖全部，
//   越界条形渲染侧裁剪；支持横向拖移（原生滚动 + 鼠标拖拽平移），不固定月份区间（随时间不失效）。
// 数据源：GET /api/v1/roadmap/timeline?view=release|goal（真实 API，05 §3.3）+ /releases + /sprints + /work-items?sprintId=
//   「发布挤压」警示带接 GET /api/v1/conflicts?kind=CF-3 真实快照（UT-31，缺省安全）。
// R-9 延续：API 空数据显示显式空态（无 mock 兜底）；released 版本条目行渲染「已交付」徽标，不再显示「执行中 x/y」。
import { useEffect, useMemo, useRef, useState } from 'react'
import type { PointerEvent as RPointerEvent, ReactNode } from 'react'
import { AlertTriangle, ArrowRight, ChevronRight, ListChecks, Lock, Target, X } from 'lucide-react'
import { dateStr, daysBetween } from '../../data/store'
import type { PageId } from '../../data/types'
import { Bar, Card, Empty, PageHeader, Pill, Spinner } from '../../components/ui'
import { useCf3Conflicts, useGoals, useReleases, useRoadmapTimeline, useSprintWorkItems, useSprints } from '../../api/queries'
import type { RemoteConflict, RemoteSprint, RoadmapItemRow, TimelineReleaseRow } from '../../api/queries'
import { useUserBriefs, toBrief } from '../../api/users'
import { GlossaryButton } from '../../components/GlossaryButton'
import type { PageProps } from '../../nav'

// ---- 通用小工具 ----
/** 后端日期统一截断为 yyyy-MM-dd（jsr310 兼容 ISO/带时间两种形态） */
const day = (v?: string) => (v ? v.slice(0, 10) : undefined)
/** 条目完成度百分比（查询侧 COUNT FILTER 投影 workItemDone/total） */
const pct = (done: number, total: number) => (total > 0 ? Math.round((done / total) * 100) : 0)
/** 排序：日期升序，缺省值沉底（泳道按 planDate / 条目与迭代按 startDate） */
const byDate = <T,>(get: (x: T) => string | undefined) => (a: T, b: T) =>
  (get(a) ?? '9999-12-31').localeCompare(get(b) ?? '9999-12-31')

// ---- 自适应时间窗（裁决 D3）：今天为中心，缺省 [-30d, +180d]，越界泳道日期外扩覆盖 ----
const PPX = 300 / 30.44 // 每天像素（沿原月宽 300px 比例尺 ≈ 9.86px/天）
interface Win {
  t0: string
  t1: string
  totalPx: number
  months: string[]
  px: (d: string) => number
}
/** 由月份 yyyy-MM 序列生成跨度（覆盖 t0~t1 的自然月，含首尾） */
function monthSpan(t0: string, t1: string): string[] {
  const out: string[] = []
  let y = Number(t0.slice(0, 4))
  let m = Number(t0.slice(5, 7))
  for (;;) {
    const key = `${y}-${String(m).padStart(2, '0')}`
    out.push(key)
    if (key >= t1.slice(0, 7)) break
    m += 1
    if (m > 12) { m = 1; y += 1 }
  }
  return out
}

// ---- 版本五态着色（主题语义色：blocked=bad / released=ok；口径与交付页一致） ----
type Tone = 'neutral' | 'brand' | 'ok' | 'warn' | 'bad' | 'info' | 'purple' | 'orange' | 'pink' | 'teal'
interface RelMeta { text: string; tone: Tone; stripe: string; bar: string; fill: string; diamond: string; tint: string }
const REL_META: Record<string, RelMeta> = {
  planned:     { text: '已规划',   tone: 'neutral', stripe: 'var(--color-cat-blue)', bar: 'bg-cat-blue/10 ring-cat-blue/30 hover:bg-cat-blue/20', fill: 'bg-cat-blue/25', diamond: 'bg-cat-blue', tint: '' },
  coding:      { text: '编码中',   tone: 'brand',   stripe: 'var(--color-brand)',    bar: 'bg-brand/10 ring-brand/40 hover:bg-brand/20',         fill: 'bg-brand/30',    diamond: 'bg-brand',    tint: '' },
  blocked:     { text: '发布阻塞', tone: 'bad',     stripe: 'var(--color-bad)',      bar: 'bg-bad/10 ring-bad/40 hover:bg-bad/20',               fill: 'bg-bad/30',      diamond: 'bg-bad',      tint: 'bg-bad-bg/30' },
  code_freeze: { text: '代码冻结', tone: 'info',    stripe: 'var(--color-info)',     bar: 'bg-info/10 ring-info/30 hover:bg-info/20',            fill: 'bg-info/30',     diamond: 'bg-info',     tint: '' },
  released:    { text: '已发布',   tone: 'ok',      stripe: 'var(--color-ok)',       bar: 'bg-ok/10 ring-ok/40 hover:bg-ok/20',                  fill: 'bg-ok/30',       diamond: 'bg-ok',       tint: 'bg-ok-bg/30' },
}
const relMeta = (status: string): RelMeta => REL_META[status] ?? REL_META.planned

// ---- 时间轴骨架：左侧 sticky 标签列 + 右侧时间网格（月分割线 + 今天红线） ----
const LABEL_W = 200
function Grid({ win, children }: { win: Win; children: ReactNode }) {
  return (
    <div className="relative h-full" style={{ width: win.totalPx }}>
      {win.months.slice(1).map((m) => (
        <span key={m} className="pointer-events-none absolute inset-y-0 w-px bg-line" style={{ left: win.px(`${m}-01`) }} />
      ))}
      <TodayLine win={win} />
      {children}
    </div>
  )
}
function TodayLine({ win }: { win: Win }) {
  const p = win.px(dateStr(0))
  if (p < 0 || p > win.totalPx) return null
  return (
    <span className="pointer-events-none absolute inset-y-0 z-10 w-px bg-bad/60">
      <span className="absolute top-0 -left-4 rounded-sm bg-bad px-1 text-[9px] font-bold text-white">今</span>
    </span>
  )
}
/** 泳道行：accent=状态色描边条（版本泳道），tint=整行淡色底（blocked/released） */
function Lane({ win, label, children, height, highlighted = false, accent, tint = '' }: {
  win: Win; label: ReactNode; children: ReactNode; height: number; highlighted?: boolean; accent?: string; tint?: string
}) {
  return (
    <div className={`flex items-stretch border-t border-line transition-colors ${highlighted ? 'bg-brand/5 ring-2 ring-brand/40 ring-inset' : ''} ${tint}`}>
      <div className={`sticky left-0 z-20 shrink-0 border-r border-line ${highlighted ? 'bg-brand-bg/60' : 'bg-card'}`} style={{ width: LABEL_W }}>
        {accent && <span className="absolute inset-y-0 left-0 w-[3px]" style={{ background: accent }} />}
        {label}
      </div>
      {/* overflow-hidden：越界条形在此裁剪（时间窗已外扩覆盖全部泳道日期，此为保险） */}
      <div className="relative overflow-hidden py-1.5" style={{ width: win.totalPx, height }}>
        <Grid win={win}>{children}</Grid>
      </div>
    </div>
  )
}

export default function RoadmapPage({ nav, id }: PageProps) {
  // R-7：默认「版本泳道」；目标视角收缩为次要 tab
  const [view, setView] = useState<'release' | 'goal'>('release')
  const [goalFilter, setGoalFilter] = useState<string | undefined>()
  // 版本泳道抽屉：{ 版本, 选中特性 }（点击条形打开）
  const [drawer, setDrawer] = useState<{ rel: TimelineReleaseRow; item: RoadmapItemRow } | undefined>()
  // 目标视角条目面板（保留原实现，收缩为次要）
  const [goalSel, setGoalSel] = useState<RoadmapItemRow | undefined>()
  const scrollRef = useRef<HTMLDivElement>(null)

  const tlR = useRoadmapTimeline('release')
  const tlG = useRoadmapTimeline('goal')
  const goalsQ = useGoals()
  // UT-31：发布挤压警示带接真实 CF-3 快照；UUID → 姓名 humanize 走 /users/briefs（普通用户可访问）
  const cf3 = useCf3Conflicts()
  const userMap = toBrief(useUserBriefs().data)
  const humanize = (text: string) => {
    let out = text
    for (const [uid, brief] of userMap) out = out.split(uid).join(brief.name)
    return out
  }

  // 切换视角时清空下钻态
  useEffect(() => {
    setDrawer(undefined)
    setGoalSel(undefined)
  }, [view])

  // ---- 数据整形：泳道按 planDate 升序（缺省沉底），条目按 startDate 升序；目标过滤器裁剪 ----
  const allRels = useMemo(() => (tlR.data && tlR.data.view === 'release' ? tlR.data.items : []), [tlR.data])
  const allGoalRows = useMemo(() => (tlG.data && tlG.data.view === 'goal' ? tlG.data.items : []), [tlG.data])

  // 外部传入 id → 目标过滤器：
  //  - 战略目标卡片下钻传 goalId → 直接过滤（AC②「从目标进入高亮关联版本/条目」，停留版本泳道）；
  //  - IM 话题跳转（nav.go('roadmap', targetId)）传的是 RoadMap 条目 id，不匹配任何 goal →
  //    在时间轴数据里解析归属目标，用其 goalId 作为过滤器（⑥h 任务③，修复跳转落空态）；
  //  - 时间轴就绪仍解析不到（条目不存在/未挂目标）→ 维持原 id 过滤，呈现该 id 的落空态提示。
  // 注：handledId 只记「已定案」的 id——goals 未就绪时的临时过滤不定案，数据到达后重跑纠偏；
  //     定案后用户手动切换过滤器不会被本 effect 重置。
  const handledId = useRef<string | undefined>(undefined)
  useEffect(() => {
    if (!id || handledId.current === id) return
    const goals = goalsQ.data
    if (!goals) {
      setGoalFilter(id)
      return
    }
    handledId.current = id
    if (goals.some((g) => g.id === id)) {
      setGoalFilter(id)
      return
    }
    // 未命中目标字典 → 视为 RoadMap 条目 id：release/goal 两个视角的时间轴都参与解析
    const goalIdOf = (rows: { roadmapItems: RoadmapItemRow[] }[]) =>
      rows.flatMap((r) => r.roadmapItems).find((i) => i.id === id)?.goalId
    const goalId = goalIdOf(allRels) ?? goalIdOf(allGoalRows)
    if (goalId) {
      setGoalFilter(goalId)
      return
    }
    if (!tlR.isLoading && !tlG.isLoading) setGoalFilter(id) // 落空态兜底
  }, [id, goalsQ.data, allRels, allGoalRows, tlR.isLoading, tlG.isLoading])

  const rels = useMemo(() => {
    const sorted = [...allRels].sort(byDate((r) => r.planDate))
    if (!goalFilter) return sorted
    // 过滤口径：仅保留关联版本（条目 goalId 命中），版本内仅显示关联条目
    return sorted
      .map((r) => ({ ...r, roadmapItems: r.roadmapItems.filter((i) => i.goalId === goalFilter).sort(byDate((i) => i.startDate)) }))
      .filter((r) => r.roadmapItems.length > 0)
  }, [allRels, goalFilter])

  const goalRows = useMemo(() => {
    // 目标视角：过滤时仅保留该目标泳道；条目统一按 startDate 升序（缺省沉底）
    const list = goalFilter ? allGoalRows.filter((g) => g.id === goalFilter) : [...allGoalRows]
    return list.map((g) => ({ ...g, roadmapItems: [...g.roadmapItems].sort(byDate((i) => i.startDate)) }))
  }, [allGoalRows, goalFilter])

  // ---- 时间窗：以今天为中心（D3），覆盖当前视角全部泳道日期后自适应外扩；窗口外条形渲染侧裁剪 ----
  const win = useMemo<Win>(() => {
    const dates: string[] = [dateStr(-30), dateStr(180)]
    const push = (v?: string) => { const d = day(v); if (d) dates.push(d) }
    if (view === 'release') {
      for (const r of rels) {
        push(r.planDate)
        push(r.codeFreezeDate)
        r.roadmapItems.forEach((i) => { push(i.startDate); push(i.dueDate) })
      }
    } else {
      for (const g of goalRows) g.roadmapItems.forEach((i) => { push(i.startDate); push(i.dueDate) })
    }
    const t0 = dates.reduce((a, b) => (b < a ? b : a))
    const t1 = dates.reduce((a, b) => (b > a ? b : a))
    const totalPx = Math.max(Math.round((daysBetween(t0, t1) + 1) * PPX), 600)
    return { t0, t1, totalPx, months: monthSpan(t0, t1), px: (d: string) => daysBetween(t0, d) * PPX }
  }, [view, rels, goalRows])

  // 打开页面/切换视角/时间窗变化 → 自动横移到「今天」附近
  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollLeft = Math.max(0, win.px(dateStr(0)) - 420)
  }, [view, win])

  // ---- 鼠标拖拽平移（原生滚动保留；触摸/滚动条不受影响） ----
  const dragRef = useRef<{ startX: number; startLeft: number } | null>(null)
  const onPanePointerDown = (e: RPointerEvent<HTMLDivElement>) => {
    if (e.pointerType !== 'mouse' || e.button !== 0) return
    const t = e.target as HTMLElement
    if (t.closest('button, a, select, input')) return // 交互元素上按下不启动拖移，避免与点击冲突
    e.currentTarget.setPointerCapture(e.pointerId)
    dragRef.current = { startX: e.clientX, startLeft: scrollRef.current?.scrollLeft ?? 0 }
  }
  const onPanePointerMove = (e: RPointerEvent<HTMLDivElement>) => {
    const d = dragRef.current
    const el = scrollRef.current
    if (!d || !el) return
    el.scrollLeft = d.startLeft - (e.clientX - d.startX)
  }
  const endPaneDrag = () => { dragRef.current = null }

  // ---- 派生展示量 ----
  const loading = view === 'release' ? tlR.isLoading : tlG.isLoading
  const failed = view === 'release' ? tlR.isError : tlG.isError
  const empty = !loading && !failed && (view === 'release' ? rels.length === 0 : goalRows.length === 0)
  const filterGoalName = goalsQ.data?.find((g) => g.id === goalFilter)?.name
  const emptyText =
    view === 'release'
      ? goalFilter
        ? `「${filterGoalName ?? '所选目标'}」下暂无关联版本或 RoadMap 条目（清除过滤器可查看全部）`
        : '暂无版本数据（当前无交付版本或其下无 RoadMap 条目）'
      : goalFilter
        ? '该目标下暂无 RoadMap 条目（清除过滤器可查看全部目标）'
        : '暂无战略目标数据（当前无目标或其下无 RoadMap 条目）'

  return (
    <div>
      <PageHeader
        title="产品 RoadMap"
        desc="版本泳道 = 交付时间轴：泳道=版本（状态着色）· 条形=挂载特性 · 菱形=计划发布日 · 点击条形下钻 版本→迭代→工作项 · 时间窗以今天为中心自适应（过去 1 月 ~ 未来 6 月，可拖移）"
        actions={
          <div className="flex items-center gap-2">
            <div className="flex items-center gap-1 rounded-full border border-line bg-card p-1">
              {([['release', '版本泳道'], ['goal', '目标视角']] as const).map(([k, label]) => (
                <button
                  key={k}
                  type="button"
                  onClick={() => setView(k)}
                  className={`cursor-pointer rounded-full px-3 py-1 text-xs font-semibold transition-colors ${
                    view === k ? 'bg-brand text-white' : 'text-txt-mid hover:text-txt-hi'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
            <GlossaryButton />
          </div>
        }
      />

      {/* 目标过滤器（R-7：目标从「视角」降级为顶部过滤器，过滤后仅显示关联版本/条目并高亮） */}
      <div className="mb-3 flex flex-wrap items-center gap-2 rounded-card border border-line bg-card px-3.5 py-2 shadow-card">
        <Target size={14} className="shrink-0 text-brand" />
        <span className="text-xs font-semibold text-txt-mid">按战略目标过滤</span>
        <select
          value={goalFilter ?? ''}
          onChange={(e) => setGoalFilter(e.target.value || undefined)}
          className="cursor-pointer rounded-md border border-line bg-canvas px-2 py-1 text-xs text-txt-hi outline-none focus:ring-2 focus:ring-brand/40"
        >
          <option value="">全部目标</option>
          {(goalsQ.data ?? []).map((g) => (
            <option key={g.id} value={g.id}>{g.name}</option>
          ))}
        </select>
        {goalFilter && (
          <button
            type="button"
            onClick={() => setGoalFilter(undefined)}
            className="inline-flex cursor-pointer items-center gap-0.5 rounded border border-brand/30 bg-brand-bg px-2 py-0.5 text-[11px] font-semibold text-brand-deep hover:bg-brand hover:text-white transition"
          >
            清除过滤 <X size={10} />
          </button>
        )}
        <span className="ml-auto text-[11px] text-txt-low">
          {goalFilter
            ? `仅显示「${filterGoalName ?? '所选目标'}」关联的版本 / 条目，并高亮泳道`
            : '切换「目标视角」可按战略目标聚合查看'}
        </span>
      </div>

      <div className="flex items-start gap-4">
        <div
          ref={scrollRef}
          onPointerDown={onPanePointerDown}
          onPointerMove={onPanePointerMove}
          onPointerUp={endPaneDrag}
          onPointerCancel={endPaneDrag}
          className="min-w-0 flex-1 cursor-grab select-none overflow-x-auto rounded-card border border-line bg-card shadow-card active:cursor-grabbing"
        >
          <div style={{ minWidth: LABEL_W + win.totalPx }}>
            {/* 月份表头（自适应窗口动态生成） */}
            <div className="flex border-b border-line-hi">
              <div className="sticky left-0 z-20 flex shrink-0 items-end bg-card py-1.5 pl-3 text-[11px] font-semibold text-txt-low" style={{ width: LABEL_W }}>
                {view === 'goal' ? '战略目标 / 月份' : '交付版本 / 月份'}
              </div>
              <div className="relative h-7" style={{ width: win.totalPx }}>
                {win.months.map((m) => (
                  <span
                    key={m}
                    className="absolute top-1.5 whitespace-nowrap text-[11px] font-semibold text-txt-mid"
                    style={{ left: win.px(`${m}-01`), paddingLeft: 12, minWidth: 56 }}
                  >
                    {m}
                  </span>
                ))}
              </div>
            </div>

            {/* 发布挤压警示带（CF-3 真实快照：逐条 detail（姓名 humanize）+ 检测时间；无快照时给出明确空态） */}
            <div className="flex border-b border-line bg-warn-bg/50">
              <div className="sticky left-0 z-20 flex shrink-0 items-center gap-1 border-r border-line bg-card py-2 pl-3 text-[11px] font-semibold text-warn-deep" style={{ width: LABEL_W }}>
                <AlertTriangle size={12} />发布挤压
              </div>
              <div className="relative min-h-9 py-1" style={{ width: win.totalPx }}>
                {cf3.isLoading ? (
                  <span className="flex h-7 items-center gap-1.5 pl-3 text-[11px] text-txt-low"><Spinner size={11} />挤压快照加载中…</span>
                ) : (cf3.data?.length ?? 0) === 0 ? (
                  <span className="flex h-7 items-center pl-3 text-[11px] text-txt-low">暂无里程碑挤压（CF-3）冲突</span>
                ) : (
                  <ul className="space-y-0.5">
                    {cf3.data!.map((c: RemoteConflict) => (
                      <li key={`${c.payload.fp}|${c.detectedAt}`} className="flex items-center gap-1.5 pl-3 text-[11px] leading-5 text-txt-mid">
                        <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${c.payload.severity === 'red' ? 'bg-bad' : 'bg-warn'}`} />
                        <span className="min-w-0 truncate">{humanize(c.payload.detail)}</span>
                        <span className="shrink-0 tabular-nums text-txt-low">检测于 {new Date(c.detectedAt).toLocaleString()}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>

            {loading && <Empty text={view === 'release' ? '版本时间轴加载中…' : '目标时间轴加载中…'} size="sm" icon={<Spinner />} />}

            {/* ==================== 版本泳道（默认主视角） ==================== */}
            {!loading && view === 'release' && rels.map((r) => {
              const meta = relMeta(r.status)
              const plan = day(r.planDate)
              const h = Math.max(r.roadmapItems.length, 1) * 34 + 10
              return (
                <Lane
                  key={r.id}
                  win={win}
                  height={h}
                  highlighted={!!goalFilter}
                  accent={meta.stripe}
                  tint={meta.tint}
                  label={
                    <div className="flex h-full flex-col justify-center gap-1 py-1 pl-3 pr-2">
                      <div className="flex items-center gap-1.5">
                        <span className="min-w-0 flex-1 truncate font-mono text-xs font-bold text-txt-hi" title={`${r.name}（${r.key}）`}>{r.name}</span>
                        <Pill tone={meta.tone}>{meta.text}</Pill>
                      </div>
                      <div className="flex items-center gap-1 text-[10px] text-txt-low">
                        <span>{plan ? `计划发布 ${plan}` : '未排期'}</span>
                        <span className="flex-1" />
                        <span>{r.roadmapItems.length} 项特性</span>
                      </div>
                    </div>
                  }
                >
                  {/* 菱形里程碑 = 计划发布日（点击前往版本与发布） */}
                  {plan && (
                    <button
                      type="button"
                      title={`${r.name} 计划发布 ${plan}${r.status === 'blocked' ? ' · 发布锁定' : ''} · 点击前往「版本与发布」`}
                      onClick={() => nav.go('delivery', r.id)}
                      className="absolute top-1/2 z-20 -translate-y-1/2 cursor-pointer"
                      style={{ left: win.px(plan) }}
                    >
                      <span className={`block h-2.5 w-2.5 rotate-45 ring-2 ring-canvas ${meta.diamond}`} />
                    </button>
                  )}
                  {r.roadmapItems.length === 0 && <span className="absolute top-2 left-3 text-[11px] text-txt-low">该版本暂未挂载 RoadMap 条目</span>}
                  {r.roadmapItems.map((rm, i) => (
                    <RelItemBar
                      key={rm.id}
                      rm={rm}
                      win={win}
                      meta={meta}
                      released={r.status === 'released'}
                      selected={drawer?.item.id === rm.id}
                      top={4 + i * 34}
                      onSelect={() => setDrawer({ rel: r, item: rm })}
                    />
                  ))}
                </Lane>
              )
            })}

            {/* ==================== 目标视角（次要 tab，实现沿用 v2.1） ==================== */}
            {!loading && view === 'goal' && goalRows.map((g) => {
              const items = g.roadmapItems
              const h = Math.max(items.length, 1) * 34 + 10
              const progress = pct(g.workItemDone, g.total)
              return (
                <Lane
                  key={g.id}
                  win={win}
                  height={h}
                  highlighted={g.id === goalFilter}
                  label={
                    <div className="flex h-full flex-col justify-center gap-1 py-1 pl-3 pr-2">
                      <div className="flex items-center gap-1.5">
                        <span className="min-w-0 flex-1 truncate text-xs font-semibold text-txt-hi" title={g.name}>{g.name}</span>
                      </div>
                      <div className="flex items-center gap-1">
                        <Bar value={progress} tone={progress >= 80 ? 'ok' : 'brand'} className="w-14" />
                        <span className="text-[10px] font-bold tabular-nums text-txt-mid">{progress}%</span>
                        <span className="flex-1" />
                        <span className="text-[10px] text-txt-low">{g.workItemDone}/{g.total} 项</span>
                        <button
                          type="button"
                          title={`查看「${g.name}」的迭代与任务`}
                          onClick={() => nav.go('tasks')}
                          className="inline-flex cursor-pointer items-center gap-0.5 rounded border border-line bg-canvas px-1 py-0.5 text-[10px] text-brand hover:bg-brand-bg"
                        >
                          <ListChecks size={10} /> 迭代与任务
                        </button>
                      </div>
                    </div>
                  }
                >
                  {items.length === 0 && <span className="absolute top-2 left-3 text-[11px] text-txt-low">暂无关联 RoadMap 条目</span>}
                  {items.map((rm, i) => (
                    <RmBar key={rm.id} rm={rm} win={win} selected={goalSel?.id === rm.id} onSelect={() => setGoalSel(rm)} top={4 + i * 34} showReleaseTag />
                  ))}
                </Lane>
              )
            })}

            {!loading && failed && <Empty text="时间轴加载失败，请点击刷新或稍后重试" size="sm" />}
            {empty && <Empty text={emptyText} size="sm" />}
          </div>
        </div>

        {/* 右侧抽屉：版本泳道 = 版本→迭代→工作项 下钻；目标视角 = 条目详情面板（保留） */}
        {view === 'release' && drawer && (
          <ReleaseDrawer
            rel={drawer.rel}
            item={drawer.item}
            nav={nav}
            onPickItem={(it) => setDrawer((cur) => (cur ? { ...cur, item: it } : cur))}
            onClose={() => setDrawer(undefined)}
          />
        )}
        {view === 'goal' && goalSel && <RmPanel rm={goalSel} nav={nav} onClose={() => setGoalSel(undefined)} />}
      </div>
    </div>
  )
}

/* ==================== 版本泳道条形：特性名 + 完成度 x/y（released 版本改「已交付」徽标） ==================== */

function RelItemBar({ rm, win, meta, released, selected, onSelect, top }: {
  rm: RoadmapItemRow
  win: Win
  meta: RelMeta
  released: boolean
  selected: boolean
  onSelect: () => void
  top: number
}) {
  const start = day(rm.startDate)
  const due = day(rm.dueDate)
  const x1 = start ? win.px(start) : 0
  const x2 = due ? win.px(due) : x1 + 56
  // 越界裁剪：时间窗已覆盖泳道日期，此处对窗口外区间做钳制（保险）
  const cx1 = Math.max(x1, 0)
  const cx2 = Math.min(x2, win.totalPx)
  const width = Math.max(cx2 - cx1, 48)
  const progress = pct(rm.workItemDone, rm.total)
  return (
    <button
      type="button"
      onClick={onSelect}
      title={`${rm.name} · ${start ?? '?'} ~ ${due ?? '?'} · ${released ? '已交付' : `工作项 ${rm.workItemDone}/${rm.total}`}${released ? '' : ` · 进度 ${progress}%`}`}
      className={`absolute z-10 flex h-7 cursor-pointer items-center overflow-hidden rounded-md transition ${
        selected ? 'ring-2 ring-brand' : `ring-1 ${meta.bar}`
      }`}
      style={{ left: cx1, width, top }}
    >
      <div className={`absolute inset-y-0 left-0 ${meta.fill}`} style={{ width: `${progress}%` }} />
      <span className="relative z-10 flex w-full items-center gap-1 px-1.5 text-[10px] leading-none">
        <span className="min-w-0 truncate font-semibold text-txt-hi">{rm.name}</span>
        <span className="min-w-1 flex-1" />
        {released ? (
          <Pill tone="ok">已交付</Pill>
        ) : (
          <span className="shrink-0 font-bold tabular-nums text-txt-mid">{rm.workItemDone}/{rm.total}</span>
        )}
      </span>
    </button>
  )
}

/* ==================== 版本下钻抽屉：版本（useReleases 门禁态补全）→ 迭代 → 工作项（两级懒加载） ==================== */

/** 工作项类型 → 跳转页（任务/测试任务→迭代与任务；缺陷→缺陷中心；需求→需求管理） */
const WI_PAGE: Record<string, PageId> = { task: 'tasks', test_task: 'tasks', defect: 'defects', requirement: 'requirements' }
/** 后端 work-item 投影携带 roadmapItemId（Views 有此字段，前端基类未声明，局部补形） */
type DrillWorkItem = { roadmapItemId?: string }

function ReleaseDrawer({ rel, item, nav, onPickItem, onClose }: {
  rel: TimelineReleaseRow
  item: RoadmapItemRow
  nav: PageProps['nav']
  onPickItem: (item: RoadmapItemRow) => void
  onClose: () => void
}) {
  const meta = relMeta(rel.status)
  const plan = day(rel.planDate)
  const start = day(item.startDate)
  const due = day(item.dueDate)
  const progress = pct(item.workItemDone, item.total)
  // useReleases：补全 timeline 泳道行没有的门禁态字段（releasedAt / 阻塞缺陷），静默合并不阻塞渲染
  const live = useReleases().data?.find((r) => r.id === rel.id)
  // useSprints by releaseId：该版本的迭代（第一级懒加载——抽屉挂载时才请求）
  const sprintsQ = useSprints()
  const sprints = useMemo(
    () => (sprintsQ.data ?? []).filter((s) => s.releaseId === rel.id).sort(byDate((s) => s.startDate)),
    [sprintsQ.data, rel.id],
  )

  return (
    <aside className="w-80 shrink-0">
      <Card className="sticky top-2 max-h-[calc(100vh-88px)] overflow-y-auto p-4">
        <div className="flex items-start gap-2">
          <h3 className="min-w-0 flex-1 text-sm font-bold leading-5 text-txt-hi">
            <span className="font-mono">{rel.name}</span> · 版本下钻
          </h3>
          <button type="button" onClick={onClose} title="收起抽屉" className="cursor-pointer text-txt-low hover:text-txt-hi"><X size={15} /></button>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <Pill tone={meta.tone}>{meta.text}</Pill>
          <span className="text-[11px] text-txt-low">{plan ? `计划发布 ${plan}` : '未排期'}</span>
          {live?.releasedAt && <span className="text-[11px] font-semibold text-ok-deep">已发布于 {day(live.releasedAt)}</span>}
        </div>
        {live && live.status === 'blocked' && (
          <div className="mt-1.5 flex items-center gap-1 text-[11px] font-semibold text-bad-deep">
            <Lock size={11} /> 发布锁定 · 阻塞缺陷 {live.blockedDefectKeys.length} 个
          </div>
        )}

        {/* 选中特性摘要（泳道条形点击带入；抽屉内可切换） */}
        <div className="mt-3 rounded-lg border border-line bg-canvas/60 p-2.5">
          <div className="text-[11px] font-semibold text-txt-low">选中特性</div>
          <div className="mt-1 flex items-center gap-2">
            <span className="min-w-0 flex-1 truncate text-xs font-semibold text-txt-hi" title={item.name}>{item.name}</span>
            <span className="shrink-0 text-[11px] font-bold tabular-nums text-txt-mid">{item.workItemDone}/{item.total}</span>
          </div>
          <div className="mt-1.5 flex items-center gap-2">
            <Bar value={progress} tone={progress >= 80 ? 'ok' : 'brand'} className="flex-1" />
            <span className="shrink-0 text-[10px] tabular-nums text-txt-low">{progress}%</span>
          </div>
          <div className="mt-1 text-[10px] text-txt-low">周期 {start ?? '—'} ~ {due ?? '—'}</div>
        </div>

        {/* 同版本其余特性快捷切换（>1 时出现） */}
        {rel.roadmapItems.length > 1 && (
          <div className="mt-2 flex flex-wrap gap-1">
            {rel.roadmapItems.map((it) => (
              <button
                key={it.id}
                type="button"
                onClick={() => onPickItem(it)}
                className={`max-w-full cursor-pointer truncate rounded border px-1.5 py-0.5 text-[10px] transition ${
                  it.id === item.id ? 'border-brand bg-brand-bg text-brand-deep' : 'border-line bg-canvas text-txt-mid hover:border-brand/40'
                }`}
              >
                {it.name}
              </button>
            ))}
          </div>
        )}

        {/* 版本 → 迭代 → 工作项（迭代展开才拉工作项，第二级懒加载） */}
        <div className="mt-3 border-t border-line pt-3">
          <div className="text-[11px] font-semibold text-txt-low">版本 → 迭代 → 工作项（点击迭代展开）</div>
          <div className="mt-1.5 space-y-1">
            {sprintsQ.isLoading && (
              <div className="flex items-center gap-1.5 text-[11px] text-txt-low"><Spinner size={11} />迭代加载中…</div>
            )}
            {!sprintsQ.isLoading && sprints.length === 0 && (
              <div className="text-[11px] text-txt-low">该版本暂未挂载迭代（可在「版本与发布」或迭代设置中关联 releaseId）</div>
            )}
            {sprints.map((s) => (
              <DrawerSprint key={s.id} sprint={s} focusItemId={item.id} nav={nav} />
            ))}
          </div>
        </div>

        <div className="mt-3 space-y-1.5 border-t border-line pt-3">
          <LinkBtn onClick={() => nav.go('delivery', rel.id)}>前往「版本与发布」查看流水线与部署</LinkBtn>
          <LinkBtn onClick={() => (sprints[0] ? nav.go('tasks', sprints[0].id) : nav.go('tasks'))}>打开「迭代与任务」看板</LinkBtn>
        </div>
      </Card>
    </aside>
  )
}

/** 迭代分支：展开时才拉取该迭代工作项（GET /work-items?sprintId=，懒加载）；行可跳转对应中心 */
function DrawerSprint({ sprint, focusItemId, nav }: { sprint: RemoteSprint; focusItemId: string; nav: PageProps['nav'] }) {
  const [open, setOpen] = useState(false)
  const query = useSprintWorkItems(open ? sprint.id : undefined)
  const items = query.data ?? []
  const spMeta = sprintMeta(sprint)
  return (
    <div className="rounded-md border border-line bg-canvas/60">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full cursor-pointer items-center gap-1.5 rounded-md px-2 py-1.5 text-left hover:bg-brand-bg/40"
      >
        <ChevronRight size={12} className={`shrink-0 text-txt-low transition-transform ${open ? 'rotate-90' : ''}`} />
        <span className="min-w-0 flex-1 truncate text-xs font-semibold text-txt-hi" title={sprint.name}>{sprint.name}</span>
        <Pill tone={spMeta.tone}>{spMeta.text}</Pill>
        <span className="shrink-0 text-[10px] tabular-nums text-txt-low">{sprint.startDate?.slice(5) ?? '—'}~{sprint.dueDate?.slice(5) ?? '—'}</span>
        <span className="w-8 shrink-0 text-right text-[10px] tabular-nums text-txt-mid">{open ? (query.isLoading ? '…' : `${items.length} 项`) : ''}</span>
      </button>
      {open && (
        <div className="space-y-0.5 border-t border-line px-1.5 py-1.5">
          {query.isLoading && (
            <div className="flex items-center gap-1.5 px-1 py-1 text-[11px] text-txt-low"><Spinner size={11} />工作项加载中…</div>
          )}
          {!query.isLoading && items.length === 0 && (
            <div className="px-1 py-1 text-[11px] text-txt-low">该迭代暂无工作项</div>
          )}
          {items.map((w) => (
            <WiRow key={w.id} w={w} focus={(w as DrillWorkItem).roadmapItemId === focusItemId} nav={nav} />
          ))}
        </div>
      )}
    </div>
  )
}

/** 迭代状态（RemoteSprint 无 status 列，按 completedAt/日期推导展示口径） */
function sprintMeta(s: RemoteSprint): { text: string; tone: Tone } {
  if (s.completedAt) return { text: '已完成', tone: 'ok' }
  const t = dateStr(0)
  if (s.startDate && s.startDate <= t && (!s.dueDate || s.dueDate >= t)) return { text: '进行中', tone: 'brand' }
  return { text: '未开始', tone: 'neutral' }
}

/** 工作项行：类型徽标 + key + 标题 + 状态；点击跳转（任务/测试任务→迭代与任务 · 缺陷→缺陷中心 · 需求→需求管理） */
function WiRow({ w, focus, nav }: { w: { id: string; key: string; type: string; title: string; status: string }; focus: boolean; nav: PageProps['nav'] }) {
  const t = w.type === 'defect'
    ? { label: '缺陷', cls: 'text-cat-orange bg-cat-orange/10' }
    : w.type === 'test_task' ? { label: '测试', cls: 'text-cat-teal bg-cat-teal/10' }
      : w.type === 'requirement' ? { label: '需求', cls: 'text-cat-purple bg-cat-purple/10' }
        : { label: '任务', cls: 'text-cat-blue bg-cat-blue/10' }
  const doneSet = ['done', 'closed', 'passed', '回归通过', '已关闭', '已修复']
  return (
    <button
      type="button"
      onClick={() => nav.go(WI_PAGE[w.type] ?? 'tasks', w.id)}
      title={`${w.key} ${w.title} · 点击跳转${w.type === 'defect' ? '缺陷中心' : w.type === 'requirement' ? '需求管理' : '迭代与任务'}`}
      className="flex w-full cursor-pointer items-center gap-1.5 rounded px-1 py-1 text-left hover:bg-ink-700"
    >
      <span className={`shrink-0 rounded px-1 text-[10px] font-semibold ${t.cls}`}>{t.label}</span>
      <span className="shrink-0 font-mono text-[10px] text-txt-low">{w.key}</span>
      <span className="min-w-0 flex-1 truncate text-xs text-txt-hi">{w.title}</span>
      {focus && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-brand" title="挂接当前选中特性" />}
      <Pill tone={doneSet.includes(w.status) ? 'ok' : 'neutral'}>{WI_STATUS_TEXT[w.status] ?? w.status}</Pill>
    </button>
  )
}
const WI_STATUS_TEXT: Record<string, string> = {
  todo: '待处理', in_progress: '进行中', in_review: '待验收', blocked: '受阻', done: '已完成',
  pending: '待执行', passed: '已通过', failed: '未通过', closed: '已关闭',
}

/* ==================== 目标视角（次要 tab，实现沿用 v2.1：条形/详情面板） ==================== */

/** 目标视角条形：进度填充 + 版本标签（目标→版本挂载）；released 版本条目改「已交付」徽标（R-9 延续） */
function RmBar({ rm, win, selected, onSelect, top, showReleaseTag = false }: {
  rm: RoadmapItemRow; win: Win; selected: boolean; onSelect: () => void; top: number; showReleaseTag?: boolean
}) {
  const start = day(rm.startDate)
  const due = day(rm.dueDate)
  const cx1 = Math.max(start ? win.px(start) : 0, 0)
  const cx2 = Math.min(due ? win.px(due) : (start ? win.px(start) : 0) + 56, win.totalPx)
  const progress = pct(rm.workItemDone, rm.total)
  const delivered = rm.release?.status === 'released'
  return (
    <button
      type="button"
      onClick={onSelect}
      title={`${rm.name} · ${start ?? '?'} ~ ${due ?? '?'} · ${delivered ? '已交付' : `工作项 ${rm.workItemDone}/${rm.total}`}${rm.release ? ` · 交付版本 ${rm.release.name}` : ''}`}
      className={`absolute z-10 flex h-7 cursor-pointer items-center overflow-hidden rounded-md transition ${
        selected ? 'bg-cat-teal/25 ring-2 ring-brand' : 'bg-cat-teal/15 ring-1 ring-cat-teal/40 hover:bg-cat-teal/25'
      }`}
      style={{ left: cx1, width: Math.max(cx2 - cx1, 56), top }}
    >
      <div className="absolute inset-y-0 left-0 bg-cat-teal/30" style={{ width: `${progress}%` }} />
      <span className="relative z-10 flex w-full items-center gap-1 px-1.5 text-[10px] leading-none">
        <span className="min-w-0 truncate font-semibold text-txt-hi">{rm.name}</span>
        {showReleaseTag && rm.release && (
          <span className="shrink-0 rounded bg-cat-teal px-1 py-px font-mono font-bold text-white" title={`交付版本 ${rm.release.name}`}>{rm.release.name}</span>
        )}
        <span className="min-w-1 flex-1" />
        {delivered ? (
          <Pill tone="ok">已交付</Pill>
        ) : (
          <span className="shrink-0 font-bold tabular-nums text-txt-mid">{rm.workItemDone}/{rm.total}</span>
        )}
      </span>
    </button>
  )
}

/** 目标视角条目详情（真实 API 字段）：名称 / 周期 / 工作项进度 / 关联版本 / 下钻入口 */
function RmPanel({ rm, nav, onClose }: { rm: RoadmapItemRow; nav: PageProps['nav']; onClose: () => void }) {
  const start = day(rm.startDate)
  const due = day(rm.dueDate)
  const progress = pct(rm.workItemDone, rm.total)
  return (
    <aside className="w-72 shrink-0">
      <Card className="p-4">
        <div className="flex items-start gap-2">
          <h3 className="min-w-0 flex-1 text-sm font-bold leading-5 text-txt-hi">{rm.name}</h3>
          <button type="button" onClick={onClose} className="cursor-pointer text-txt-low hover:text-txt-hi"><X size={15} /></button>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <Pill tone="teal">RoadMap 条目</Pill>
          {rm.release && <Pill tone="brand">{rm.release.name}</Pill>}
        </div>
        <div className="mt-3 space-y-2 text-xs text-txt-mid">
          <div>周期：{start ?? '—'} ~ {due ?? '—'}</div>
          <div className="flex items-center gap-2">
            工作项 <Bar value={progress} tone={progress >= 80 ? 'ok' : 'brand'} className="flex-1" />
            <span className="font-bold tabular-nums text-txt-hi">{rm.workItemDone}/{rm.total}</span>
          </div>
        </div>
        <div className="mt-3 space-y-1.5 border-t border-line pt-3">
          <div className="text-[11px] font-semibold text-txt-low">关联链路（版本 → 迭代）</div>
          {rm.release ? (
            <LinkBtn onClick={() => nav.go('delivery', rm.release!.id)}>交付版本 {rm.release.name} · 计划 {day(rm.release.planDate)?.slice(5) ?? '—'}</LinkBtn>
          ) : (
            <div className="text-xs text-txt-low">未关联交付版本</div>
          )}
          <LinkBtn onClick={() => nav.go('tasks')}>迭代与任务</LinkBtn>
        </div>
      </Card>
    </aside>
  )
}

function LinkBtn({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full cursor-pointer items-center gap-1 truncate rounded border border-line bg-canvas px-1.5 py-1 text-[11px] text-brand hover:bg-brand-bg"
    >
      <span className="min-w-0 flex-1 truncate text-left">{children}</span>
      <ArrowRight size={10} className="shrink-0" />
    </button>
  )
}
