// 工作量桑基图（B3 · R-1，裁决 D8）：Goal → RoadMap 条目 → 工作项类型三层
// 末端度量 = 工作项条数三桶（任务/需求/缺陷；test_task 并入任务桶）；工时降级为节点 tooltip
// 交互：点击 Goal 节点 → onFocusGoal（左侧列表联动高亮/过滤）；点击条目节点 → onSelectItem（下钻）
import { lazy, Suspense, useMemo } from 'react'
import type { EChartsCoreOption } from 'echarts/core'
import type { GoalOverviewItem, GoalOverviewRow } from '../../api/queries'
import { Empty, Spinner } from '../../components/ui'
import { tooltipOf, useChartTheme, type ChartTheme, type TipParam } from './chart-shared'

// ⑥h 性能批：Echart 封装（含 echarts 依赖）动态加载，落入独立 echarts chunk；
// Suspense fallback 用与桑基图同高（360px）的转圈占位，chunk 拉取期间布局不跳动
const Echart = lazy(() => import('../../components/Echart'))

/** 末端类型桶定义（顺序即桑基第三层自上而下顺序；颜色为全站语义色） */
const TYPE_BUCKETS = [
  { key: 'task', label: '任务', colorOf: (ct: ChartTheme) => ct.blue },
  { key: 'requirement', label: '需求', colorOf: (ct: ChartTheme) => ct.purple },
  { key: 'defect', label: '缺陷', colorOf: (ct: ChartTheme) => ct.orange },
] as const

/** 单条目类型桶计数（test_task 已并入 taskCount，tooltip 注明口径） */
const countOf = (it: GoalOverviewItem) => [it.taskCount || 0, it.requirementCount || 0, it.defectCount || 0] as const

/** 工时三桶文案（D8：工时降级为 tooltip，不再做带宽） */
const hoursTip = (it: GoalOverviewItem) => {
  const h = (it.doneHours || 0) + (it.inProgressHours || 0) + (it.todoHours || 0)
  return `工时 已完成 ${it.doneHours || 0}h / 进行中 ${it.inProgressHours || 0}h / 未开始 ${it.todoHours || 0}h（合计 ${Math.round(h * 10) / 10}h）`
}

export interface SankeyChartProps {
  goals: GoalOverviewRow[]
  /** 当前聚焦（高亮/过滤）的 GoalId；null = 全部 */
  focusGoalId?: string | null
  /** 当前选中（下钻）的条目 id（节点描边强调） */
  selectedItemId?: string | null
  /** 点击 Goal 节点（toggle 语义由父级处理） */
  onFocusGoal: (goalId: string) => void
  /** 点击条目节点 → 父级展开下钻面板 */
  onSelectItem: (goalId: string, item: GoalOverviewItem) => void
}

/** 工作量桑基图：色带宽度 = 工作项条数；色带颜色按左列 Goal 取 cat 色 */
export default function SankeyChart({ goals, focusGoalId, selectedItemId, onFocusGoal, onSelectItem }: SankeyChartProps) {
  const ct = useChartTheme()

  // 预计算：扁平条目（仅保留类型计数 > 0 的条目，零宽链接无信息量）、各层节点/链接
  const model = useMemo(() => {
    const list = goals.filter((g) => (g.items ?? []).some((it) => countOf(it).some((n) => n > 0)))
    const flat = list.flatMap((g, gi) =>
      g.items.filter((it) => countOf(it).some((n) => n > 0)).map((it) => ({ goalIdx: gi, goalId: g.goalId, it })),
    )
    return { list, flat }
  }, [goals])

  const option = useMemo<EChartsCoreOption | null>(() => {
    const { list, flat } = model
    if (list.length === 0) return null

    const goalKey = (i: number) => `g:${list[i].goalId}`
    const itemKey = (i: number) => `i:${flat[i].it.id}`
    const typeKey = (bi: number) => `t:${TYPE_BUCKETS[bi].label}`

    // 层级合计：Goal 行合计 / 类型列合计（item→type 链值之和）
    const goalV = list.map((_, gi) =>
      flat.filter((f) => f.goalIdx === gi).reduce((s, f) => s + countOf(f.it).reduce((a, b) => a + b, 0), 0),
    )
    const typeV = TYPE_BUCKETS.map((_, bi) => flat.reduce((s, f) => s + countOf(f.it)[bi], 0))
    if (!typeV.some((v) => v > 0)) return null
    // PM-2：只保留计数 > 0 的类型节点——零值桶无任何 link，ECharts 会把无连线节点塌进第一列，
    // 单 Goal 单条目时左列被渲染成占满画布高的实心柱、条目/类型列挤成不可读
    const activeBuckets = TYPE_BUCKETS.map((b, bi) => ({ b, bi })).filter(({ bi }) => typeV[bi] > 0)

    // 聚焦过滤：非聚焦 Goal 的节点/链接整体降透明度（D8 AC：点击 Goal 即高亮/过滤）
    const fi = focusGoalId ? list.findIndex((g) => g.goalId === focusGoalId) : -1
    const dimNode = (goalIdx: number | null) => (fi >= 0 && goalIdx !== fi ? 0.18 : 1)

    // 色带按左列 Goal 取 cat 颜色（沿用迁移前 SANKEY_COLORS 循环：蓝/青/紫/橙/绿/粉）
    const catOf = [ct.blue, ct.teal, ct.purple, ct.orange, ct.green, ct.pink]
    const neutralNode = { color: ct.ink700, borderColor: ct.lineHi, borderWidth: 1 }

    const nodes: { name: string; itemStyle: Record<string, unknown>; label?: Record<string, unknown> }[] = [
      ...list.map((_, i) => ({
        name: goalKey(i),
        itemStyle: { color: catOf[i % catOf.length], opacity: dimNode(i) },
      })),
      ...flat.map((f, i) => ({
        name: itemKey(i),
        itemStyle: selectedItemId && selectedItemId === f.it.id
          ? { color: catOf[f.goalIdx % catOf.length], borderColor: ct.txtMid, borderWidth: 2, opacity: dimNode(f.goalIdx) }
          : { ...neutralNode, opacity: dimNode(f.goalIdx) },
        label: { width: 150, overflow: 'truncate' as const },
      })),
      ...activeBuckets.map(({ b, bi }) => ({
        name: typeKey(bi),
        itemStyle: { color: b.colorOf(ct), opacity: 1 },
      })),
    ]

    // 节点展示文案（唯一键 → 展示名，避免同名合并）
    const LABEL: Record<string, string> = {}
    list.forEach((g, i) => { LABEL[goalKey(i)] = `${g.name} · ${goalV[i]} 项` })
    flat.forEach((f, i) => { LABEL[itemKey(i)] = f.it.name })
    TYPE_BUCKETS.forEach((b, bi) => { LABEL[typeKey(bi)] = `${b.label} ${typeV[bi]}` })

    // 节点 tooltip（D8：工时在此展示，不占带宽）
    const TIP: Record<string, string> = {}
    list.forEach((g, i) => {
      const done = g.items.reduce((s, it) => s + (it.doneHours || 0), 0)
      const wip = g.items.reduce((s, it) => s + (it.inProgressHours || 0), 0)
      const todo = g.items.reduce((s, it) => s + (it.todoHours || 0), 0)
      TIP[goalKey(i)] = `${g.name}\n工作项 ${g.done}/${g.total}（完成率 ${(g.completionRate ?? 0).toFixed(1)}%）\n工时 已完成 ${done}h / 进行中 ${wip}h / 未开始 ${todo}h`
    })
    flat.forEach((f, i) => {
      const c = countOf(f.it)
      TIP[itemKey(i)] = `${f.it.name}\n任务 ${f.it.taskDone || 0}/${c[0]} · 需求 ${f.it.requirementDone || 0}/${c[1]} · 缺陷 ${f.it.defectDone || 0}/${c[2]}\n${hoursTip(f.it)}\n（test_task 计入任务桶）`
    })
    TYPE_BUCKETS.forEach((b, bi) => { TIP[typeKey(bi)] = `${b.label}（全部 Goal 合计）${typeV[bi]} 项` })

    const links: { source: string; target: string; value: number; itemStyle: { color: string; opacity: number }; tip: string }[] = []
    flat.forEach((f, i) => {
      const c = countOf(f.it)
      const v = c[0] + c[1] + c[2]
      const color = catOf[f.goalIdx % catOf.length]
      const dim = fi >= 0 && f.goalIdx !== fi
      if (v > 0) {
        links.push({
          source: goalKey(f.goalIdx), target: itemKey(i), value: v,
          itemStyle: { color, opacity: dim ? 0.05 : 0.4 },
          tip: `${list[f.goalIdx].name} → ${f.it.name}：${v} 项`,
        })
      }
      c.forEach((bv, bi) => {
        if (!(bv > 0)) return
        links.push({
          source: itemKey(i), target: typeKey(bi), value: bv,
          itemStyle: { color, opacity: dim ? 0.04 : 0.3 },
          tip: `${f.it.name} → ${TYPE_BUCKETS[bi].label}：${bv} 项`,
        })
      })
    })

    return {
      tooltip: {
        trigger: 'item', ...tooltipOf(ct),
        formatter: (p: TipParam) => (p.data?.tip ?? TIP[p.name] ?? p.name).split('\n').join('<br/>'),
      },
      series: [{
        type: 'sankey',
        left: 8, top: 14, bottom: 14, right: 130,
        nodeWidth: 12, nodeGap: 10, nodeAlign: 'justify',
        layoutIterations: 0, // 保持 Goal→条目→类型 的给定顺序，不做自动重排
        draggable: false,
        emphasis: { focus: 'adjacency' },
        data: nodes,
        links,
        label: { color: ct.txtHi, fontSize: 11, formatter: (p: { name: string }) => LABEL[p.name] ?? p.name },
        lineStyle: { color: 'source', curveness: 0.5 },
      }],
    }
  }, [model, focusGoalId, selectedItemId, ct])

  // 节点点击：Goal 节点 → 聚焦；条目节点 → 下钻；类型节点不响应
  const onEvents = useMemo(() => ({
    click: (params: unknown) => {
      const p = params as { dataType?: string; name?: string }
      if (p.dataType !== 'node' || !p.name) return
      if (p.name.startsWith('g:')) onFocusGoal(p.name.slice(2))
      else if (p.name.startsWith('i:')) {
        const f = model.flat.find((x) => x.it.id === p.name!.slice(2))
        if (f) onSelectItem(f.goalId, f.it)
      }
    },
  }), [model, onFocusGoal, onSelectItem])

  if (!option) return <Empty text="暂无工作项数据（当前 Goal 下无挂接条目）" />

  return (
    <div>
      <Suspense fallback={<div className="flex items-center justify-center" style={{ height: 360 }}><Spinner size={16} /></div>}>
        <Echart option={option} height={360} className="w-full" onEvents={onEvents} />
      </Suspense>
      {/* 三列标注（Goal 色 / 条目 / 类型语义色） */}
      <div className="mt-1 flex flex-wrap gap-4 text-[11px] text-txt-low">
        <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-sm bg-cat-purple" />Goal</span>
        <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-sm border border-line-hi bg-ink-700" />RoadMap 条目</span>
        {TYPE_BUCKETS.map((b) => (
          <span key={b.key} className="flex items-center gap-1">
            <span className="h-2 w-2 rounded-sm" style={{ background: b.colorOf(ct) }} />{b.label}
          </span>
        ))}
        <span className="text-txt-low/70">带宽 = 工作项条数 · 悬停节点见工时 · 点击 Goal 高亮 / 点击条目下钻</span>
      </div>
    </div>
  )
}
