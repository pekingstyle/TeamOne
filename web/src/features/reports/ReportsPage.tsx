// 统计报表中心（R10 → B3 报表重构 R-1~R-4）：维度过滤 + 效能仪表盘
// ① 目标交付总览（Goal 完成率 + Goal→条目→工作项类型桑基 + 迭代/工作项下钻，见 GoalOverviewCard.tsx）
// ② 燃尽 ③ 累积流 ④ 控制图（ECharts）｜⑤ 速率（点/迭代 + 近3迭代均值线）⑥ 缺陷分布（组件×严重度堆叠，见 DefectDistribution.tsx）⑦ 资源投入 ｜ 绩效视图
// dogfooding 切换（原「双轨」store 兜底移除）：图表数据仅真实 API——
//   效能速览/速率 = GET /reports/efficiency；燃尽 = /reports/burndown；CFD = /reports/cfd；
//   控制图/资源投入/绩效表 = GET /work-items 全量 + GET /users；无数据显示显式空态。
import { lazy, Suspense, useMemo, useState, type ReactNode } from 'react'
import type { EChartsCoreOption } from 'echarts/core'
import { Download } from 'lucide-react'
import { Avatar, Btn, Card, CardHeader, Empty, PageHeader, Spinner } from '../../components/ui'
import {
  useDefects, useEfficiencyReport, useGoalOverview, useMergeRequests, useProducts,
  useRepos, useSprintBurndown, useCfdReport, useSprints, useWorkItems,
  type RemoteBurndownReport, type RemoteCfdReport, type RemoteEfficiencyReport,
  type RemoteProduct, type RemoteSprint,
} from '../../api/queries'
import { useUserBriefs, type UserBriefRow } from '../../api/users'
import type { PageProps } from '../../nav'
import GoalOverviewCard from './GoalOverviewCard'
import DefectDistribution from './DefectDistribution'
import { CHART_H, axisLbl, tooltipOf, ts, useChartTheme, type ChartTheme, type TipParam } from './chart-shared'

// ⑥h 性能批：ECharts 代码分割——Echart 封装（含 echarts 依赖）整体动态加载，首屏不打包图表库
const Echart = lazy(() => import('../../components/Echart'))

const DAY = 86400000
const SEG_COLORS = ['var(--color-cat-blue)', 'var(--color-cat-teal)', 'var(--color-cat-purple)', 'var(--color-cat-orange)']
const selectCls = 'cursor-pointer rounded-input border border-line bg-card px-2 py-1 text-xs text-txt-mid'

/** 图表用工作项投影（GET /work-items 全量行 → 控制图/资源投入/绩效表消费） */
interface ChartItem {
  id: string
  key: string
  type: string
  status: string
  sprintId?: string
  productId?: string
  assigneeId?: string
  points: number
  estimateHours: number
  dueDate?: string
  createdAt: string
  updatedAt: string
}

function toChartItem(raw: import('../../api/queries').RemoteSprintWorkItem): ChartItem {
  return {
    id: raw.id,
    key: raw.key,
    type: raw.type,
    status: raw.status,
    sprintId: raw.sprintId,
    productId: raw.productId,
    assigneeId: raw.assigneeId,
    points: raw.storyPoints ?? 0,
    estimateHours: raw.estimateHours ?? 0,
    dueDate: raw.dueDate,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
  }
}
const isDoneItem = (w: ChartItem) => w.status === 'done' || w.status === 'closed'

/** 图表懒加载占位：与目标图表同高的转圈占位（chunk 拉取仅数十字 KB，通常一闪而过） */
function ChartSuspense({ height, children }: { height: number; children: ReactNode }) {
  return (
    <Suspense
      fallback={
        <div className="flex items-center justify-center" style={{ height }}>
          <Spinner size={16} />
        </div>
      }
    >
      {children}
    </Suspense>
  )
}

/* ==================== 保留在本文件的图组件（②③④⑦ + ⑤速率 ECharts 化） ==================== */

/** 带编号图表卡（编号 = 视觉顺序，R-1 AC①） */
function ChartCard({ no, title, note, extra, className = '', children }: { no: string; title: string; note: string; extra?: ReactNode; className?: string; children: ReactNode }) {
  return (
    <Card className={className}>
      <CardHeader title={<span>{no} {title}</span>} extra={extra} />
      <div className="px-4 pb-3 pt-2">
        <div className="mb-1.5 text-[11px] text-txt-low">口径：{note}</div>
        {children}
      </div>
    </Card>
  )
}

export default function ReportsPage({ nav }: PageProps) {
  const [productId, setProductId] = useState('')
  const [sprintSel, setSprintSel] = useState('')
  // 图表主题色：初值 + 明/暗主题切换时自动重取 CSS 变量色值（传给本文件内的图组件）
  const ct = useChartTheme()

  // dogfooding 切换：全部数据源 = 真实 API（store products/sprints/users/workItems/mergeRequests 兜底移除）
  const { data: productRows } = useProducts()
  const { data: sprintRows } = useSprints()
  const { data: userRows } = useUserBriefs() // dogfooding：仅登录态即可见成员（原 /users 需管理权限，普通成员绩效表恒空）
  const workItemsQ = useWorkItems()
  const defectsQ = useDefects()
  const { data: mrListData } = useMergeRequests()
  const { data: repoRows } = useRepos()

  const allItems = useMemo(() => (workItemsQ.data ?? []).map(toChartItem), [workItemsQ.data])
  const fItems = useMemo(
    () => allItems.filter((w) => !productId || w.productId === productId),
    [allItems, productId],
  )
  const fSprints = useMemo(
    () => (sprintRows ?? []).filter((s) => !productId || s.productId === productId),
    [sprintRows, productId],
  )
  // 收口批：燃尽②/CFD③ 共用同一迭代选择——sprintSel 未选时统一回退首个迭代，两卡口径恒一致
  // （此前 CFD 直用 sprintSel 原值，未选时是全产品 30 天视图，与燃尽的 fSprints[0] 缺省错位）
  const activeSprint: RemoteSprint | undefined = fSprints.find((s) => s.id === sprintSel) ?? fSprints[0]
  const fDefs = useMemo(
    () => (defectsQ.data ?? []).filter((d) => !productId || d.productId === productId),
    [defectsQ.data, productId],
  )
  const fMembers = useMemo(() => (userRows ?? []), [userRows])

  // V-19：研发效能真实 API 查询（按产品与迭代联动）
  const { data: remoteEff, isLoading: effLoading } = useEfficiencyReport(productId || undefined, sprintSel || undefined)
  const { data: remoteBurndown, isLoading: burndownLoading } = useSprintBurndown(activeSprint?.id)
  const { data: remoteCfd, isLoading: cfdLoading } = useCfdReport(productId || undefined, activeSprint?.id, 30)
  const reportsLoading = effLoading || burndownLoading || cfdLoading
  // B2 · UT-30：Goal 宏观视角（失败显示 Empty 不报错）
  const { data: goalOverview, isLoading: goalLoading } = useGoalOverview()

  // 绩效视图：成员 × 真实工作项（完成数/点/按期率/负载）+ 真实 MR 数（repo → product 过滤）
  const repoProduct = useMemo(() => {
    const m = new Map<string, string | undefined>()
    for (const r of repoRows?.items ?? []) m.set(r.id, r.productId)
    return m
  }, [repoRows])
  interface PerfRow { id: string; name: string; title: string; doneCount: number; donePoints: number; mrCount: number; onTime: number | null; loadHours: number }
  const perfRows: PerfRow[] = useMemo(() => (fMembers).map((u) => {
    const mine = fItems.filter((w) => w.assigneeId === u.id)
    const done = mine.filter(isDoneItem)
    const withDue = done.filter((w) => w.dueDate)
    const onTime = withDue.length === 0 ? null : withDue.filter((w) => ts(w.updatedAt) <= ts(w.dueDate!)).length / withDue.length
    const mrCount = (mrListData?.items ?? []).filter((m) => {
      if (m.authorId !== u.id) return false
      const pid = repoProduct.get(m.repoId)
      if (productId) return pid === productId
      return true
    }).length
    return {
      id: u.id,
      name: u.displayName || u.username,
      title: u.title ?? u.username,
      doneCount: done.length,
      donePoints: done.reduce((s, w) => s + w.points, 0),
      mrCount,
      onTime,
      loadHours: mine.filter((w) => !isDoneItem(w)).reduce((s, w) => s + w.estimateHours, 0),
    }
  }), [fMembers, fItems, mrListData, repoProduct, productId])
  const [sort, setSort] = useState<{ key: SortKey; asc: boolean }>({ key: 'donePoints', asc: false })
  const val = (r: PerfRow): string | number => (sort.key === 'name' ? r.name : sort.key === 'onTime' ? (r.onTime ?? -1) : r[sort.key])
  const sortedRows = [...perfRows].sort((a, b) => {
    const va = val(a), vb = val(b)
    const c = typeof va === 'string' ? va.localeCompare(String(vb)) : (va as number) - (vb as number)
    return sort.asc ? c : -c
  })
  const toggleSort = (key: SortKey) => setSort((s) => (s.key === key ? { key, asc: !s.asc } : { key, asc: key === 'name' }))

  const exportCsv = () => {
    const lines = [
      PERF_COLS.map((c) => c.label).join(','),
      ...sortedRows.map((r) => [r.name, r.doneCount, r.donePoints, r.mrCount, r.onTime === null ? '—' : `${Math.round(r.onTime * 100)}%`, r.loadHours].join(',')),
    ]
    const url = URL.createObjectURL(new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'report.csv'
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div>
      {/* 页头吸顶（R-4：删「冲突中心」重复入口与「订阅」死按钮；维度过滤与导出 CSV 并入页头，消灭冗余小 bar） */}
      <div className="sticky top-0 z-20 -mx-6 bg-page/95 px-6 pb-3 pt-4 backdrop-blur-sm">
        <PageHeader
          title="统计报表"
          desc="效能仪表盘（ECharts） · 目标交付总览 + 迭代交付 / 流动 / 质量视图，全部图表与绩效表共用同一张工作项数据，交叉核对偏差为零"
        />
        {/* 筛选行：维度过滤 + 口径计数 + 导出 CSV（D10：导出移入筛选行右侧） */}
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <span className="text-xs font-semibold text-txt-low">维度过滤</span>
          <label className="flex items-center gap-1.5 text-xs text-txt-mid">
            产品
            <select value={productId} onChange={(e) => setProductId(e.target.value)} className={selectCls}>
              <option value="">全部</option>
              {(productRows ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </label>
          <span className="ml-auto text-[11px] text-txt-low">工作项 {fItems.length} · 缺陷 {fDefs.length} · 成员 {fMembers.length}</span>
          <Btn variant="primary" onClick={exportCsv}><Download size={14} /> 导出 CSV</Btn>
        </div>
      </div>

      {/* 效能大盘指标速览（V-19 真实统计指标） */}
      {reportsLoading && (
        <Card className="mb-4"><Empty text="效能报表数据加载中…" size="sm" icon={<Spinner />} /></Card>
      )}
      {remoteEff && (
        <div className="mb-4 flex flex-wrap items-center gap-4 rounded-card border border-line bg-ink-800/60 px-4 py-2 text-xs text-txt-mid">
          <span className="font-semibold text-txt-hi">效能指标速览：</span>
          <span>交付工作项：<strong className="text-brand">{remoteEff.completedItems}</strong> / {remoteEff.totalItems} ({Math.round(remoteEff.completionRate * 100)}%)</span>
          {/* PM-3：无已完成工作项（分母 0）时 avgCycleTimeDays=0 显示「0 天」误导，改显「—」；已交付故事点 0/13 为真实值保留 */}
          <span>平均交付周期：<strong className="text-cat-teal">{remoteEff.avgCycleTimeDays > 0 ? `${remoteEff.avgCycleTimeDays} 天` : '—'}</strong></span>
          <span>缺陷解决率：<strong className="text-ok">{Math.round(remoteEff.defectResolutionRate * 100)}%</strong> ({remoteEff.defectCount} 缺陷)</span>
          <span>已交付故事点：<strong className="text-cat-purple">{remoteEff.completedStoryPoints}</strong> / {remoteEff.totalStoryPoints}</span>
        </div>
      )}

      {/* ① 目标交付总览（R-1 宏观区合并：原⑦ Goal 完成率 + ⑧ 工作量桑基图 → 一张全宽大卡） */}
      <Card className="mb-4">
        <CardHeader
          title={<span>① 目标交付总览</span>}
          extra={goalLoading ? <Spinner size={13} /> : undefined}
        />
        <div className="px-4 pb-3 pt-2">
          <div className="mb-1.5 text-[11px] text-txt-low">
            口径：Goal → RoadMap 条目 → 工作项类型（任务/需求/缺陷）三层桑基，带宽 = 工作项条数（test_task 计入任务桶）· 完成率 = GET /goals/overview（task/test_task/defect）· 工时见节点 tooltip · 点击 Goal 高亮/过滤，点击条目下钻迭代与工作项
          </div>
          <GoalOverviewCard goals={goalOverview?.goals} nav={nav} />
        </div>
      </Card>

      {/* 主仪表盘：②③④ ECharts + ⑤⑥⑦（编号 = 视觉顺序），3 列对齐 */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <ChartCard no="②" title="迭代燃尽" note="迭代内剩余故事点（实线实际 / 虚线理想线性）"
          extra={fSprints.length > 0 && (
            /* 收口批：该选择为 ②③ 共享（CFD 卡头同步展示同一状态），切换同时驱动两卡 */
            <select value={activeSprint?.id ?? ''} onChange={(e) => setSprintSel(e.target.value)} className={selectCls} title="迭代选择（②燃尽 / ③CFD 共用）">
              {fSprints.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          )}>
          <BurndownChart remoteBurndown={remoteBurndown} loading={burndownLoading} ct={ct} />
        </ChartCard>
        <ChartCard no="③" title="累积流图 CFD" note={`GET /reports/cfd：todo/in_progress/done 每日累计（服务端时序）· 与②共用迭代「${activeSprint?.name ?? '—'}」`}>
          <CfdChart remoteCfd={remoteCfd} loading={cfdLoading} ct={ct} />
        </ChartCard>
        <ChartCard no="④" title="控制图" note="已完成工作项 创建→完成 周期天数散点，橙点 = 高于均值">
          <ControlChart items={fItems} avgDays={remoteEff?.avgCycleTimeDays} ct={ct} />
        </ChartCard>
        <ChartCard no="⑤" title="迭代速率" note="每迭代完成故事点（点/迭代）· 橙色虚线 = 近 3 迭代均值参考线（裁决 D9）">
          <VelocityChart remoteVelocities={remoteEff?.sprintVelocities} ct={ct} />
        </ChartCard>
        <ChartCard no="⑥" title="缺陷分布" note={`当前过滤缺陷全集 ${fDefs.length} 项按组件堆叠：长度 = 数量、颜色 = 严重度四档语义色 · 图内合计 = 速览缺陷数 · 无组件归「未指定」（R-2）`}>
          <DefectDistribution defs={fDefs} />
        </ChartCard>
        <ChartCard no="⑦" title="资源投入" note="成员各产品未完成工作项 estimateHours 合计（堆叠）">
          <ResourceChart members={fMembers} prods={productRows ?? []} items={fItems} />
        </ChartCard>
      </div>

      {/* 绩效视图 */}
      <Card className="mt-4">
        <CardHeader
          title="绩效视图"
          extra={<span className="text-xs text-txt-low">口径：done=done/closed · 按期率仅统计有 dueDate 的完成项 · 点击列头排序</span>}
        />
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-txt-low">
                {PERF_COLS.map((c) => (
                  <th key={c.key} className="px-4 py-2 font-medium">
                    <button type="button" onClick={() => toggleSort(c.key)} className="cursor-pointer hover:text-txt-hi">
                      {c.label}{sort.key === c.key ? (sort.asc ? ' ↑' : ' ↓') : ''}
                    </button>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {sortedRows.map((r) => (
                <tr key={r.id} className="hover:bg-ink-700">
                  <td className="px-4 py-2">
                    <span className="flex items-center gap-2"><Avatar userId={r.id} size={22} />
                      <span className="font-medium text-txt-hi">{r.name}</span>
                      <span className="text-xs text-txt-low">{r.title}</span>
                    </span>
                  </td>
                  <td className="px-4 py-2 font-bold tabular-nums text-txt-hi">{r.doneCount}</td>
                  <td className="px-4 py-2 font-bold tabular-nums text-txt-hi">{r.donePoints}</td>
                  <td className="px-4 py-2 tabular-nums text-txt-mid">{r.mrCount}</td>
                  <td className={`px-4 py-2 font-bold tabular-nums ${r.onTime !== null && r.onTime < 0.5 ? 'text-cat-pink' : 'text-txt-hi'}`}>
                    {r.onTime === null ? '—' : `${Math.round(r.onTime * 100)}%`}
                  </td>
                  <td className={`px-4 py-2 tabular-nums ${r.loadHours > 30 ? 'text-bad' : 'text-txt-mid'}`}>{r.loadHours}</td>
                </tr>
              ))}
              {sortedRows.length === 0 && (
                <tr><td colSpan={PERF_COLS.length} className="px-4 py-8 text-center text-xs text-txt-low">暂无成员数据</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}

/* ==================== ② 迭代燃尽（ECharts 折线）：GET /reports/burndown 真实时序（remote only） ==================== */
function BurndownChart({ remoteBurndown, loading, ct }: { remoteBurndown?: RemoteBurndownReport; loading: boolean; ct: ChartTheme }) {
  const timeline = remoteBurndown?.timeline && remoteBurndown.timeline.length > 1 ? remoteBurndown.timeline : null
  if (!timeline) return <Empty text={loading ? '燃尽数据加载中…' : '当前迭代暂无燃尽时序数据'} />
  const rows = timeline.map((p, i) => ({ day: `第${i + 1}天`, ideal: p.idealPoints, actual: p.actualPoints }))

  const option: EChartsCoreOption = {
    tooltip: {
      trigger: 'axis', ...tooltipOf(ct),
      valueFormatter: (v: unknown) => (typeof v === 'number' ? `${v} 点` : '—'),
    },
    legend: { data: ['实际', '理想'], right: 0, top: 0, itemWidth: 14, itemHeight: 8, itemGap: 12, textStyle: { color: ct.txtMid, fontSize: 10 } },
    grid: { left: 34, right: 12, top: 26, bottom: 24 },
    xAxis: {
      type: 'category', boundaryGap: false, data: rows.map((r) => r.day),
      axisLine: { lineStyle: { color: ct.line } }, axisTick: { show: false }, axisLabel: axisLbl(ct),
    },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: ct.line } }, axisLabel: axisLbl(ct) },
    series: [
      { // 理想基线：线性口径不动，保持直线虚线（不做平滑以免歪曲基线）
        name: '理想', type: 'line', data: rows.map((r) => r.ideal), smooth: false, symbol: 'none',
        lineStyle: { color: ct.txtLow, width: 1.2, type: 'dashed' },
      },
      { // 实际剩余：平滑 + 淡面积
        name: '实际', type: 'line', data: rows.map((r) => r.actual), smooth: true, symbol: 'circle', symbolSize: 5,
        lineStyle: { color: ct.blue, width: 2 }, itemStyle: { color: ct.blue },
        areaStyle: { color: ct.blue, opacity: 0.1 },
      },
    ],
  }
  return <ChartSuspense height={CHART_H}><Echart option={option} height={CHART_H} className="w-full" /></ChartSuspense>
}

/* ==================== ③ 累积流图 CFD（ECharts 堆叠面积）：GET /reports/cfd 服务端时序（remote only） ==================== */
function CfdChart({ remoteCfd, loading, ct }: { remoteCfd?: RemoteCfdReport; loading: boolean; ct: ChartTheme }) {
  if (!remoteCfd?.series || remoteCfd.series.length === 0) {
    return <Empty text={loading ? 'CFD 数据加载中…' : '暂无累积流时序数据'} />
  }
  const dates = remoteCfd.series.map((s) => s.date.slice(5))
  const done = remoteCfd.series.map((s) => s.done)
  const wip = remoteCfd.series.map((s) => s.inProgress)
  const todo = remoteCfd.series.map((s) => s.todo)
  // 堆叠面积（stack 求和）：done(绿) / in_progress(黄) / todo(蓝)
  const series = ([
    { name: 'done', data: done, color: ct.green, opacity: 0.55 },
    { name: 'in_progress', data: wip, color: ct.yellow, opacity: 0.55 },
    { name: 'todo', data: todo, color: ct.blue, opacity: 0.4 },
  ] as const).map((s) => ({
    name: s.name, type: 'line', stack: 'cfd', smooth: true, symbol: 'none', data: s.data,
    lineStyle: { color: s.color, width: 1 }, itemStyle: { color: s.color },
    areaStyle: { color: s.color, opacity: s.opacity },
  }))
  const option: EChartsCoreOption = {
    tooltip: { trigger: 'axis', ...tooltipOf(ct) },
    legend: { data: ['done', 'in_progress', 'todo'], right: 0, top: 0, itemWidth: 12, itemHeight: 8, itemGap: 12, textStyle: { color: ct.txtMid, fontSize: 10 } },
    grid: { left: 30, right: 12, top: 26, bottom: 24 },
    xAxis: {
      type: 'category', boundaryGap: false, data: dates,
      axisLine: { lineStyle: { color: ct.line } }, axisTick: { show: false }, axisLabel: axisLbl(ct),
    },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: ct.line } }, axisLabel: axisLbl(ct) },
    series,
  }
  return <ChartSuspense height={CHART_H}><Echart option={option} height={CHART_H} className="w-full" /></ChartSuspense>
}

/* ==================== ④ 控制图（ECharts 散点 + 均值标线）：蓝点 ≤ 均值 / 橙点 > 均值（口径不变） ==================== */
function ControlChart({ items, avgDays, ct }: { items: ChartItem[]; avgDays?: number; ct: ChartTheme }) {
  const doneItems = items.filter(isDoneItem)
  if (doneItems.length === 0) return <Empty text="当前过滤无已完成工作项" />
  const cycles = doneItems.map((w) => Math.max(0, Math.round((ts(w.updatedAt) - ts(w.createdAt)) / DAY)))
  const mean = avgDays != null && avgDays > 0 ? avgDays : cycles.reduce((s, v) => s + v, 0) / cycles.length
  // 按均值分两组散点：图例天然区分 蓝(≤均值) / 橙(>均值)，tooltip 展示 key 与周期天数
  const pts = doneItems.map((w, i) => ({ value: [i + 1, cycles[i]], tip: `${w.key} 周期 ${cycles[i]} 天` }))
  const option: EChartsCoreOption = {
    tooltip: { trigger: 'item', ...tooltipOf(ct), formatter: (p: TipParam) => p.data?.tip ?? '' },
    legend: { data: ['≤均值', '>均值'], right: 0, top: 0, itemWidth: 12, itemHeight: 8, itemGap: 12, textStyle: { color: ct.txtMid, fontSize: 10 } },
    grid: { left: 34, right: 12, top: 26, bottom: 24 },
    xAxis: {
      type: 'value', min: 1, max: Math.max(cycles.length, 2), name: '工作项', nameTextStyle: { color: ct.txtLow, fontSize: 10 },
      splitLine: { show: false }, axisLine: { lineStyle: { color: ct.line } }, axisTick: { show: false },
      axisLabel: { ...axisLbl(ct), formatter: '#{value}' },
    },
    yAxis: { type: 'value', name: '天', nameTextStyle: { color: ct.txtLow, fontSize: 10 }, splitLine: { lineStyle: { color: ct.line } }, axisLabel: axisLbl(ct) },
    series: [
      {
        name: '≤均值', type: 'scatter', symbolSize: 9, itemStyle: { color: ct.blue, opacity: 0.85 },
        data: pts.filter((p) => p.value[1] <= mean),
        markLine: { // 均值虚线（remote avgCycleTimeDays 优先，本地均值兜底）
          silent: true, symbol: 'none',
          lineStyle: { color: ct.orange, type: 'dashed', width: 1 },
          label: { formatter: `均值 ${mean.toFixed(1)} 天`, color: ct.orange, fontSize: 10, position: 'insideEndTop' },
          data: [{ yAxis: mean }],
        },
      },
      { name: '>均值', type: 'scatter', symbolSize: 9, itemStyle: { color: ct.orange, opacity: 0.85 }, data: pts.filter((p) => p.value[1] > mean) },
    ],
  }
  return <ChartSuspense height={CHART_H}><Echart option={option} height={CHART_H} className="w-full" /></ChartSuspense>
}

/* ==================== ⑤ 迭代速率（R-3 / 裁决 D9）：ECharts 柱状，口径「点/迭代」+ 近 3 迭代均值虚线（remote only） ==================== */
function VelocityChart({ remoteVelocities, ct }: { remoteVelocities?: RemoteEfficiencyReport['sprintVelocities']; ct: ChartTheme }) {
  const rows = (remoteVelocities ?? []).map((v) => ({ name: v.sprintName, pts: v.completedPoints }))
  if (rows.length === 0) return <Empty text="暂无迭代速率数据" />

  // D9：近 3 迭代均值参考线（不足 3 个迭代时取现有全部）
  const last3 = rows.slice(-3)
  const avg = last3.reduce((s, r) => s + r.pts, 0) / last3.length

  const option: EChartsCoreOption = {
    tooltip: { trigger: 'axis', ...tooltipOf(ct), valueFormatter: (v: unknown) => (typeof v === 'number' ? `${v} 点` : '—') },
    grid: { left: 34, right: 12, top: 26, bottom: 24 },
    xAxis: {
      type: 'category', data: rows.map((r) => r.name),
      axisLine: { lineStyle: { color: ct.line } }, axisTick: { show: false },
      axisLabel: { ...axisLbl(ct), interval: 0, width: 64, overflow: 'truncate' },
    },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: ct.line } }, axisLabel: axisLbl(ct) },
    series: [{
      name: '完成故事点', type: 'bar', barMaxWidth: 26,
      data: rows.map((r) => r.pts),
      itemStyle: { color: ct.blue },
      label: { show: true, position: 'top', fontSize: 10, color: ct.txtMid },
      markLine: { // 近 3 迭代均值（虚线，单位「点/迭代」）
        silent: true, symbol: 'none',
        lineStyle: { color: ct.orange, type: 'dashed', width: 1 },
        label: { formatter: `近3迭代均值 ${avg.toFixed(1)} 点/迭代`, color: ct.orange, fontSize: 10, position: 'insideEndTop' },
        data: [{ yAxis: avg }],
      },
    }],
  }
  return <ChartSuspense height={CHART_H}><Echart option={option} height={CHART_H} className="w-full" /></ChartSuspense>
}

/* ==================== ⑦ 资源投入：成员 × 产品 未完成工时堆叠水平条 ==================== */
function ResourceChart({ members, prods, items }: {
  members: UserBriefRow[]
  prods: RemoteProduct[]
  items: ChartItem[]
}) {
  if (members.length === 0 || prods.length === 0) return <Empty text="当前过滤无成员/产品" />
  const rows = members.map((u) => ({
    u,
    segs: prods.map((p) => items.filter((w) => w.assigneeId === u.id && w.productId === p.id && !isDoneItem(w)).reduce((s, w) => s + w.estimateHours, 0)),
  }))
  const max = Math.max(...rows.map((r) => r.segs.reduce((s, v) => s + v, 0)), 1)
  return (
    <div className="space-y-2 pt-1">
      <div className="flex gap-3 text-[11px] text-txt-mid">
        {prods.map((p, i) => (
          <span key={p.id} className="flex items-center gap-1"><span className="h-2 w-2 rounded-sm" style={{ background: SEG_COLORS[i % SEG_COLORS.length] }} />{p.name}</span>
        ))}
      </div>
      {rows.map(({ u, segs }) => {
        const total = segs.reduce((s, v) => s + v, 0)
        return (
          <div key={u.id} className="flex items-center gap-2">
            <Avatar userId={u.id} size={18} />
            <span className="w-12 shrink-0 truncate text-xs text-txt-mid">{u.displayName || u.username}</span>
            <div className="flex h-3.5 flex-1 overflow-hidden rounded-sm bg-ink-700">
              {segs.map((h, i) => h > 0 && (
                <div key={i} style={{ width: `${(h / max) * 100}%`, background: SEG_COLORS[i % SEG_COLORS.length] }} title={`${prods[i].name} ${h}h`} />
              ))}
            </div>
            <span className="w-12 shrink-0 text-right text-xs font-bold tabular-nums text-txt-hi">{total}h</span>
          </div>
        )
      })}
    </div>
  )
}

/* ==================== 绩效视图 ==================== */
type SortKey = 'name' | 'doneCount' | 'donePoints' | 'mrCount' | 'onTime' | 'loadHours'
const PERF_COLS: { key: SortKey; label: string }[] = [
  { key: 'name', label: '成员' },
  { key: 'doneCount', label: '完成工作项' },
  { key: 'donePoints', label: '完成故事点' },
  { key: 'mrCount', label: '交付 MR' },
  { key: 'onTime', label: '按期完成率' },
  { key: 'loadHours', label: '进行中负载(h)' },
]
