// ① 目标交付总览（B3 · R-1 宏观区合并，裁决 D8）：原⑦ Goal 完成率 + ⑧ 工作量桑基图合为一张大卡
// 左列 = Goal 完成率列表（点击 Goal → 桑基高亮/过滤；聚焦后展开条目行，点击条目 → 下钻）
// 右侧 = 三层桑基（Goal → RoadMap 条目 → 工作项类型计数，工时在 tooltip）
// 下钻面板（卡内下方）= 选中条目的 迭代 → 工作项 两级懒加载（复用 useReleases/useSprints/useSprintWorkItems 既有链）
import { useState } from 'react'
import { X } from 'lucide-react'
import type { GoalOverviewItem, GoalOverviewRow, RemoteRelease, RemoteSprint, RemoteSprintWorkItem } from '../../api/queries'
import { useReleases, useSprintWorkItems, useSprints } from '../../api/queries'
import type { Nav } from '../../nav'
import { Bar, Empty, Pill, Spinner } from '../../components/ui'
import SankeyChart from './SankeyChart'

/** 工作项类型 → 跳转页（任务/测试任务→迭代与任务；缺陷→缺陷中心；需求→需求管理） */
const TYPE_PAGE: Record<string, 'tasks' | 'defects' | 'requirements'> = {
  task: 'tasks',
  test_task: 'tasks',
  defect: 'defects',
  requirement: 'requirements',
}

/** 下钻工作项带后端实际返回的 roadmapItemId（Views 投影有此字段，前端类型未声明，此处局部补形） */
type DrillWorkItem = RemoteSprintWorkItem & { roadmapItemId?: string }

export interface GoalOverviewCardProps {
  goals?: GoalOverviewRow[]
  nav: Nav
}

/** 宏观总览卡内容（外层 Card 由 ReportsPage 编排） */
export default function GoalOverviewCard({ goals, nav }: GoalOverviewCardProps) {
  const [focusGoalId, setFocusGoalId] = useState<string | null>(null)
  const [selected, setSelected] = useState<{ goalId: string; item: GoalOverviewItem } | null>(null)
  const list = goals ?? []

  if (list.length === 0) return <Empty text="暂无 Goal 数据（接口未就绪或无目标）" />

  // Goal 行点击 = toggle 聚焦（桑基高亮/过滤联动）；条目点击 = 选中下钻（并联动聚焦其 Goal）
  const toggleFocus = (goalId: string) => setFocusGoalId((cur) => (cur === goalId ? null : goalId))
  const selectItem = (goalId: string, item: GoalOverviewItem) => {
    setFocusGoalId(goalId)
    setSelected({ goalId, item })
  }

  return (
    <div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(250px,1fr)_2.4fr]">
        {/* 左列：Goal 完成率列表 */}
        <div className="max-h-[420px] space-y-1.5 overflow-y-auto pr-0.5">
          {list.map((g) => {
            const rate = g.total > 0 ? (g.completionRate ?? (g.done / g.total) * 100) : 0
            const focused = focusGoalId === g.goalId
            return (
              <div key={g.goalId} className={`rounded-md border ${focused ? 'border-brand/60 bg-brand-bg/20' : 'border-line/70'}`}>
                <button
                  type="button"
                  onClick={() => toggleFocus(g.goalId)}
                  title="点击在桑基图中高亮 / 过滤该 Goal"
                  className="flex w-full cursor-pointer items-center gap-2 px-2.5 py-2 text-left transition-colors hover:bg-ink-750/60"
                >
                  <span className="min-w-0 flex-1 truncate text-xs font-medium text-txt-hi" title={g.name}>{g.name}</span>
                  <span className="w-16 shrink-0"><Bar value={rate} tone={rate >= 80 ? 'ok' : rate >= 40 ? 'brand' : 'warn'} /></span>
                  <span className="w-9 shrink-0 text-right text-xs font-bold tabular-nums text-txt-hi">{Math.round(rate)}%</span>
                  <span className="w-12 shrink-0 text-right text-[11px] tabular-nums text-txt-low">{g.done}/{g.total}</span>
                </button>
                {/* 聚焦态展开条目行：完成进度 + 类型三桶计数（点击 → 下钻面板） */}
                {focused && (
                  <div className="space-y-1 border-t border-line/70 px-2.5 py-2">
                    {g.items.length === 0 && <div className="text-[11px] text-txt-low">该目标下暂无条目</div>}
                    {g.items.map((it) => {
                      const r = it.total > 0 ? (it.done / it.total) * 100 : 0
                      const picked = selected?.item.id === it.id
                      return (
                        <button
                          key={it.id}
                          type="button"
                          onClick={() => selectItem(g.goalId, it)}
                          className={`flex w-full cursor-pointer items-center gap-2 rounded px-1.5 py-1 text-left hover:bg-ink-700 ${picked ? 'bg-brand-bg/40' : ''}`}
                        >
                          <span className="min-w-0 flex-1 truncate text-[11px] text-txt-mid" title={it.name}>{it.name}</span>
                          <span className="shrink-0 text-[10px] tabular-nums text-txt-low" title="任务/需求/缺陷 条数">
                            <span className="text-cat-blue">{it.taskCount || 0}</span>·<span className="text-cat-purple">{it.requirementCount || 0}</span>·<span className="text-cat-orange">{it.defectCount || 0}</span>
                          </span>
                          <span className="w-12 shrink-0"><Bar value={r} tone={r >= 80 ? 'ok' : 'brand'} className="!h-1" /></span>
                          <span className="w-10 shrink-0 text-right text-[11px] tabular-nums text-txt-low">{it.done}/{it.total}</span>
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>
            )
          })}
        </div>

        {/* 右侧：三层桑基（Goal → 条目 → 类型计数；点击节点联动/下钻） */}
        <SankeyChart
          goals={list}
          focusGoalId={focusGoalId}
          selectedItemId={selected?.item.id ?? null}
          onFocusGoal={toggleFocus}
          onSelectItem={selectItem}
        />
      </div>

      {/* 卡内下方：选中条目的 迭代 → 工作项 下钻（两级懒加载） */}
      {selected && (
        <ItemDrillPanel item={selected.item} nav={nav} onClose={() => setSelected(null)} />
      )}
    </div>
  )
}

/* ==================== 下钻面板：条目 → 迭代 → 工作项（复用目标页既有懒加载链） ==================== */

/** 面板随选中条目挂载 → useReleases/useSprints 此时才发请求（第一级懒加载）；迭代展开才拉工作项（第二级） */
function ItemDrillPanel({ item, nav, onClose }: { item: GoalOverviewItem; nav: Nav; onClose: () => void }) {
  const { data: releases = [], isLoading: relLoading } = useReleases()
  const { data: sprints = [], isLoading: spLoading } = useSprints()

  // 条目关联版本 = 正向挂接（releaseId）∪ 反向指向（release.roadmap_item_id），与目标页 rollup 口径一致
  const rels = releases.filter((r: RemoteRelease) => r.id === item.releaseId || r.roadmapItemId === item.id)
  const relIds = new Set(rels.map((r) => r.id))
  const itemSprints = sprints.filter((s: RemoteSprint) => s.releaseId != null && relIds.has(s.releaseId))
  const [openSprints, setOpenSprints] = useState<Set<string>>(new Set())
  const toggleSprint = (id: string) =>
    setOpenSprints((prev) => {
      const n = new Set(prev)
      if (n.has(id)) n.delete(id)
      else n.add(id)
      return n
    })

  return (
    <div className="mt-3 rounded-lg border border-line bg-ink-750/40 px-3 py-2.5">
      <div className="flex items-center gap-2">
        <span className="text-xs font-semibold text-txt-hi">下钻 · {item.name}</span>
        <Pill tone="neutral">{item.done}/{item.total} 项</Pill>
        <span className="text-[11px] text-txt-low">
          任务 {item.taskDone || 0}/{item.taskCount || 0} · 需求 {item.requirementDone || 0}/{item.requirementCount || 0} · 缺陷 {item.defectDone || 0}/{item.defectCount || 0}
        </span>
        <button type="button" onClick={onClose} title="收起下钻" className="ml-auto cursor-pointer rounded p-0.5 text-txt-low hover:bg-ink-700 hover:text-txt-hi">
          <X size={14} />
        </button>
      </div>

      <div className="mt-1.5 space-y-0.5">
        {(relLoading || spLoading) && <div className="flex items-center gap-2 py-1 text-xs text-txt-low"><Spinner size={12} />版本 / 迭代加载中…</div>}
        {!(relLoading || spLoading) && itemSprints.length === 0 && (
          <div className="py-1 text-xs text-txt-low">该条目未关联交付版本或迭代（关联版本：{rels.length} 个）</div>
        )}
        {itemSprints.map((sp) => (
          <SprintDrillBranch
            key={sp.id}
            sprint={sp}
            itemId={item.id}
            open={openSprints.has(sp.id)}
            onToggle={() => toggleSprint(sp.id)}
            nav={nav}
          />
        ))}
      </div>
    </div>
  )
}

/** 迭代分支：展开时才拉取该迭代工作项（GET /work-items?sprintId=，第二级懒加载），按 roadmapItemId 过滤出本条目工作项 */
function SprintDrillBranch({ sprint, itemId, open, onToggle, nav }: {
  sprint: RemoteSprint
  itemId: string
  open: boolean
  onToggle: () => void
  nav: Nav
}) {
  const query = useSprintWorkItems(open ? sprint.id : undefined)
  const all = query.data ?? []
  // 后端 work-item 投影携带 roadmapItemId：仅展示挂接本条目的工作项（口径与桑基条目计数一致）
  const mine = all.filter((w) => (w as DrillWorkItem).roadmapItemId === itemId)

  return (
    <div>
      <button type="button" onClick={onToggle} className="flex w-full cursor-pointer items-center gap-2 rounded px-1.5 py-1.5 text-left hover:bg-ink-700">
        <span className={`shrink-0 text-txt-low transition-transform ${open ? '' : '-rotate-90'}`}>▸</span>
        <span className="min-w-0 flex-1 truncate text-xs font-medium text-txt-hi">{sprint.name}</span>
        <span className="shrink-0 text-[11px] tabular-nums text-txt-low">
          {sprint.startDate?.slice(5) ?? '—'} ~ {sprint.dueDate?.slice(5) ?? '—'}
        </span>
        <span className="shrink-0 text-[11px] tabular-nums text-txt-mid">{query.isLoading ? '…' : `${mine.length}/${all.length} 项`}</span>
      </button>
      {open && (
        <div className="ml-4 space-y-0.5 border-l border-line py-1 pl-3">
          {query.isLoading && <div className="flex items-center gap-2 py-1 text-xs text-txt-low"><Spinner size={12} />工作项加载中…</div>}
          {!query.isLoading && mine.length === 0 && (
            <div className="py-1 text-xs text-txt-low">
              本迭代共 {all.length} 项工作项，其中无直接挂接本条目的项
            </div>
          )}
          {mine.map((w) => <DrillWorkItemRow key={w.id} w={w} nav={nav} />)}
        </div>
      )}
    </div>
  )
}

/** 工作项行：点击跳转对应中心（任务/测试任务→迭代与任务 · 缺陷→缺陷中心 · 需求→需求管理） */
function DrillWorkItemRow({ w, nav }: { w: RemoteSprintWorkItem; nav: Nav }) {
  const typeLabel = w.type === 'defect' ? '缺陷' : w.type === 'test_task' ? '测试' : w.type === 'requirement' ? '需求' : '任务'
  const typeCls = w.type === 'defect'
    ? 'text-cat-orange bg-cat-orange/10'
    : w.type === 'test_task' ? 'text-cat-teal bg-cat-teal/10'
      : w.type === 'requirement' ? 'text-cat-purple bg-cat-purple/10' : 'text-cat-blue bg-cat-blue/10'
  const doneSet = ['done', 'closed', '回归通过', '已关闭', 'passed', '已修复', 'delivered']
  return (
    <button
      type="button"
      onClick={() => nav.go(TYPE_PAGE[w.type] ?? 'tasks', w.id)}
      className="flex w-full cursor-pointer items-center gap-2 rounded px-1.5 py-1 text-left hover:bg-ink-700"
    >
      <span className={`shrink-0 rounded px-1 text-[10px] font-semibold ${typeCls}`}>{typeLabel}</span>
      <span className="shrink-0 font-mono text-[11px] text-txt-low">{w.key}</span>
      <span className="min-w-0 flex-1 truncate text-xs text-txt-hi">{w.title}</span>
      <Pill tone={doneSet.includes(w.status) ? 'ok' : 'neutral'}>{w.status}</Pill>
    </button>
  )
}
