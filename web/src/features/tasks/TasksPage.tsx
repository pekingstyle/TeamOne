import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { AlertTriangle, CalendarDays, GitPullRequest, Plus, X } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import type { WorkItem, WorkItemStatus } from '../../data/types'
import { dateStr, daysBetween, defectStatusFlow, severityTone, workItemStatusText } from '../../data/store'
import { Avatar, Btn, Card, Empty, PageHeader, Pill, PriorityBadge } from '../../components/ui'
import type { PageProps } from '../../nav'
import {
  useMergeRequests, useGoals, useProducts, useReleases, useRoadmapItems,
  useSprintWorkItems, useSprints, useWorkItem, workItemsApi,
} from '../../api/queries'
import type { RemoteSprint, RemoteSprintWorkItem } from '../../api/queries'
import { toBrief, useUserBriefs } from '../../api/users'
import { DefectFormModal } from '../defects/DefectForm'

type Tone = 'neutral' | 'brand' | 'ok' | 'warn' | 'bad' | 'info' | 'purple' | 'orange' | 'pink' | 'teal'
const COLS = [
  { id: 'todo', label: '待处理' },
  { id: 'in_progress', label: '进行中' },
  { id: 'in_review', label: '待验收' },
  { id: 'blocked', label: '受阻' },
  { id: 'done', label: '已完成' },
] as const
const DEFECT_COL: Record<string, string> = { 新建: 'todo', 修复中: 'in_progress', 已修复: 'in_review', 重新打开: 'blocked', 回归通过: 'done', 已关闭: 'done' }
const SPRINT_META = { active: { label: '进行中', tone: 'brand' }, planned: { label: '未开始', tone: 'neutral' }, done: { label: '已完成', tone: 'ok' } } as const
const BORDER: Record<WorkItem['type'], string> = { task: 'var(--color-cat-blue)', testtask: 'var(--color-cat-teal)', defect: 'var(--color-cat-orange)' }

function colOf(w: WorkItem): string {
  return w.type === 'defect' ? DEFECT_COL[w.status] ?? 'todo' : w.status
}

/** 后端 test_task 状态机 → 看板列展示口径（pending/in_progress/passed/failed；纯展示映射） */
function displayStatusOf(raw: RemoteSprintWorkItem): string {
  if (raw.type !== 'test_task') return raw.status
  if (raw.status === 'passed') return 'done'
  if (raw.status === 'failed') return 'blocked'
  if (raw.status === 'pending') return 'todo'
  return 'in_progress'
}

/** GET /work-items?sprintId= 条目 → 看板/列表渲染形（缺失展示字段安全兜底；仅页面消费） */
function remoteToWorkItem(raw: RemoteSprintWorkItem): WorkItem {
  let labels: string[] = []
  try { labels = JSON.parse(raw.labels ?? '[]') as string[] } catch { /* labels 非法时按空处理 */ }
  const type = raw.type === 'test_task' ? 'testtask' : (raw.type as WorkItem['type'])
  const base = {
    id: raw.id,
    key: raw.key,
    title: raw.title,
    description: raw.description ?? '',
    type,
    status: displayStatusOf(raw) as WorkItemStatus,
    priority: (raw.priority ?? 'P2'),
    productId: raw.productId,
    componentId: undefined,
    sprintId: raw.sprintId,
    releaseId: raw.releaseId,
    roadmapItemId: raw.roadmapItemId,
    parentWorkItemId: undefined,
    blockedByIds: [],
    assigneeId: raw.assigneeId,
    creatorId: raw.reporterId,
    estimateHours: raw.estimateHours ?? 0,
    startDate: raw.startDate,
    dueDate: raw.dueDate,
    points: raw.storyPoints ?? 0,
    labels,
    topicId: undefined,
    requirementId: raw.requirementId,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
  }
  if (type === 'testtask') {
    return { ...base, caseCount: 0, passedCount: 0, relatedDefectIds: [], verifierIds: [] } as unknown as WorkItem
  }
  if (type === 'defect') {
    return { ...base, severity: raw.severity ?? '一般', reportedById: raw.reporterId, reopenedCount: 0 } as unknown as WorkItem
  }
  return base as WorkItem
}

/** 真实迭代状态：completedAt 非空=done；今日落在 [start,due] 内=active；否则 planned */
function sprintStateOf(s: RemoteSprint, today: string): 'active' | 'planned' | 'done' {
  if (s.completedAt) return 'done'
  if (!s.startDate) return 'planned'
  const end = s.dueDate ?? '9999-12-31'
  return s.startDate <= today && today <= end ? 'active' : 'planned'
}
function statusText(w: WorkItem): string {
  return w.type === 'defect' ? w.status : workItemStatusText[w.status]
}
function statusTone(w: WorkItem): Tone {
  if (w.type === 'defect') return ({ 新建: 'neutral', 修复中: 'info', 已修复: 'warn', 回归通过: 'ok', 已关闭: 'neutral', 重新打开: 'bad' } as const)[w.status]
  return ({ todo: 'neutral', in_progress: 'info', in_review: 'warn', blocked: 'bad', done: 'ok', closed: 'neutral' } as const)[w.status]
}
function nextStatus(w: WorkItem): string | undefined {
  if (w.type === 'defect') {
    if (w.status === '重新打开') return '修复中'
    const i = defectStatusFlow.indexOf(w.status)
    return i >= 0 && i < defectStatusFlow.length - 1 ? defectStatusFlow[i + 1] : undefined
  }
  // task 状态机仅 todo→in_progress→done（TransitionTable/DB CHECK 同），不经过待验收/受阻列
  const flow: Partial<Record<WorkItemStatus, WorkItemStatus>> = { todo: 'in_progress', in_progress: 'done' }
  return flow[w.status]
}
function nextLabel(s: string, isDefect: boolean): string {
  return isDefect ? s : workItemStatusText[s as WorkItemStatus]
}
const today = dateStr(0)

export default function TasksPage({ nav, id }: PageProps) {
  const queryClient = useQueryClient()
  const [view, setView] = useState<'board' | 'list'>('board')
  const [drawerId, setDrawerId] = useState<string | undefined>(undefined)
  const [creating, setCreating] = useState<'task' | 'defect' | null>(null)
  const [dragOverCol, setDragOverCol] = useState<string | undefined>()
  const [optimisticStatus, setOptimisticStatus] = useState<Record<string, string>>({})
  // 流转/拦截结果信封提示（对齐缺陷中心样式）
  const [toast, setToast] = useState<{ ok: boolean; text: string } | undefined>()

  // dogfooding 切换：迭代与看板仅真实 API（GET /sprints + GET /work-items?sprintId=），store 迭代/工作项兜底移除
  const sprintsQ = useSprints()
  const realSprints = useMemo(() => sprintsQ.data ?? [], [sprintsQ.data])
  const [sprintId, setSprintId] = useState<string | undefined>(undefined)

  useEffect(() => {
    if (!id) return
    // 深链：id 为迭代 → 切迭代；id 为工作项 → 打开详情抽屉（跨迭代由 useWorkItem 拉详情）。
    // 依赖补 realSprints（QA 复审 NICE）：迭代缓存未就绪时不误判为工作项
    if (realSprints.some((s) => s.id === id)) setSprintId(id)
    else if (realSprints.length > 0) setDrawerId(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, realSprints])

  useEffect(() => {
    if (realSprints.length === 0) return
    if (!sprintId || !realSprints.some((s) => s.id === sprintId)) setSprintId(realSprints[0].id)
  }, [realSprints, sprintId])

  const activeReal = realSprints.find((s) => s.id === sprintId)
  const itemsQ = useSprintWorkItems(sprintId)
  const realItems = useMemo(() => (itemsQ.data ?? []).map(remoteToWorkItem), [itemsQ.data])

  // 看板拖拽目标列转换为对应类型的工作项目标状态；返回空串=该类型不可落入此列（QA 复审 MUST-FIX：
  // task 状态机仅 todo/in_progress/done，拖入待验收/受阻列会被后端 422 拒绝）
  const mapColToStatus = (w: WorkItem, targetCol: string): string => {
    if (w.type === 'defect') {
      const colMap: Record<string, string> = {
        todo: '新建',
        in_progress: '修复中',
        in_review: '已修复',
        blocked: '重新打开',
        done: '回归通过',
      }
      return colMap[targetCol] ?? '修复中'
    }
    if (w.type === 'testtask') {
      const colMap: Record<string, string> = {
        todo: 'pending',
        in_progress: 'in_progress',
        blocked: 'failed',
        done: 'passed',
      }
      return colMap[targetCol] ?? 'in_progress'
    }
    const colMap: Record<string, WorkItemStatus> = {
      todo: 'todo',
      in_progress: 'in_progress',
      done: 'done',
    }
    return colMap[targetCol] ?? ''
  }

  // 统一推进与流转处理器：乐观状态立即更新保证卡片即时移动，真实 API 流转（POST /{id}/transition）。
  // targetStatus 为空串=目标列对该类型非法（QA 复审 MUST-FIX），直接 toast 不发请求。
  const handleTransition = async (w: WorkItem, targetStatus: string) => {
    if (!targetStatus) {
      setToast({ ok: false, text: `${w.type === 'testtask' ? '测试任务' : '任务'}不支持流转到该列（任务仅 待处理/进行中/已完成 三态）` })
      return
    }
    setOptimisticStatus((prev) => ({ ...prev, [w.id]: targetStatus }))
    try {
      await workItemsApi.transition(w.id, targetStatus)
      void queryClient.invalidateQueries({ queryKey: ['work-items'] })
      setToast({ ok: true, text: `已流转：${w.title.slice(0, 18)}` })
    } catch (err: any) {
      // 流转失败：失效查询以服务端状态回滚乐观更新 + 信封 toast（对齐缺陷中心）
      void queryClient.invalidateQueries({ queryKey: ['work-items'] })
      setToast({ ok: false, text: err?.message || '状态流转失败' })
    }
  }

  const items: WorkItem[] = useMemo(() => {
    return realItems.map((w) => {
      const opt = optimisticStatus[w.id]
      if (opt) {
        return { ...w, status: opt as any }
      }
      return w
    })
  }, [realItems, optimisticStatus])
  const usedH = items.filter((w) => colOf(w) !== 'done').reduce((s, w) => s + w.estimateHours, 0)
  const over = !!activeReal?.capacityHours && usedH > activeReal.capacityHours

  // 跨迭代深链详情：看板内未命中时按 id 拉单条
  const singleQ = useWorkItem(drawerId && !realItems.some((x) => x.id === drawerId) ? drawerId : undefined)
  const drawerItem = useMemo(() => {
    if (!drawerId) return undefined
    const hit = realItems.find((x) => x.id === drawerId)
    if (hit) return hit
    return singleQ.data ? remoteToWorkItem(singleQ.data) : undefined
  }, [drawerId, realItems, singleQ.data])

  return (
    <div>
      <PageHeader
        title="迭代与任务"
        desc="任务 / 测试任务 / 缺陷同板混排 · 越级角标 = 任务截止晚于迭代截止（CF-6 可视化）"
        actions={(
          <>
            <div className="flex rounded-input border border-line p-0.5">
              {(['board', 'list'] as const).map((v) => (
                <button key={v} type="button" onClick={() => setView(v)} className={`cursor-pointer rounded-md px-3 py-1 text-xs font-medium ${view === v ? 'bg-brand-bg text-brand-deep' : 'text-txt-mid hover:text-txt-hi'}`}>
                  {v === 'board' ? '看板' : '列表'}
                </button>
              ))}
            </div>
            <Btn onClick={() => setCreating('task')} disabled={!activeReal}><Plus size={14} />新建任务</Btn>
            <Btn onClick={() => setCreating('defect')} disabled={!activeReal}><Plus size={14} />登记缺陷</Btn>
          </>
        )}
      />

      {/* 迭代 tabs（真实 API；空态显式提示） */}
      <div className="mb-3 flex flex-wrap gap-2">
        {realSprints.map((s) => ({
          id: s.id, name: s.name, state: sprintStateOf(s, today),
        })).map(({ id: sid, name, state }) => {
          const active = sid === sprintId
          const meta = SPRINT_META[state as keyof typeof SPRINT_META] ?? SPRINT_META.active
          return (
            <button
              key={sid}
              type="button"
              onClick={() => setSprintId(sid)}
              className={`flex cursor-pointer items-center gap-2 rounded-full border px-3 py-1.5 text-sm transition ${active ? 'border-brand/40 bg-brand-bg font-semibold text-brand-deep' : 'border-line bg-card text-txt-mid hover:bg-ink-700'}`}
            >
              {name}
              <Pill tone={meta.tone}>{meta.label}</Pill>
            </button>
          )
        })}
        {realSprints.length === 0 && !sprintsQ.isLoading && (
          <span className="text-xs text-txt-low">暂无迭代（在 RoadMap/版本下创建迭代后此处展示）</span>
        )}
      </div>

      {/* 概览条（真实迭代）：日期 / 状态 / 工时 / 容量 / 故事点 */}
      {activeReal && (
        <Card className="mb-4 p-4">
          <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
            <div>
              <div className="text-xs text-txt-low">迭代</div>
              <div className="mt-1 text-sm font-semibold text-txt-hi">{activeReal.name}</div>
              <div className="mt-1 flex items-center gap-1 text-xs text-txt-mid">
                <CalendarDays size={13} />
                {activeReal.startDate ? `${activeReal.startDate.slice(5)} ~ ${activeReal.dueDate?.slice(5) ?? '—'}` : '未排期'}
              </div>
            </div>
            <div>
              <div className="text-xs text-txt-low">状态</div>
              <div className="mt-1">
                <Pill tone={(SPRINT_META[sprintStateOf(activeReal, today)] ?? SPRINT_META.active).tone}>
                  {(SPRINT_META[sprintStateOf(activeReal, today)] ?? SPRINT_META.active).label}
                </Pill>
              </div>
              {(() => {
                const end = activeReal.dueDate
                if (!end) return null
                const remain = daysBetween(today, end)
                return (
                  <div className={`mt-1 text-xs ${remain < 0 ? 'text-txt-low' : remain <= 3 ? 'font-semibold text-cat-pink' : 'text-txt-mid'}`}>
                    {remain < 0 ? '已结束' : `剩余 ${remain} 天`}
                  </div>
                )
              })()}
            </div>
            <div>
              <div className="text-xs text-txt-low">工作项</div>
              <div className="mt-1 text-sm font-bold tabular-nums text-txt-hi">{realItems.length} 项</div>
              <div className="mt-1 text-xs text-txt-low">完成 {realItems.filter((w) => colOf(w) === 'done').length} 件</div>
            </div>
            <div>
              <div className="text-xs text-txt-low">工时 / 故事点</div>
              <div className="mt-1 text-sm font-bold tabular-nums text-txt-hi">
                {realItems.reduce((s2, w) => s2 + w.estimateHours, 0)}h
                <span className="ml-1 text-xs font-normal text-txt-low">/ {realItems.reduce((s2, w) => s2 + w.points, 0)} 点</span>
              </div>
              <div className={`mt-1 text-xs ${over ? 'font-semibold text-bad' : 'text-txt-low'}`}>
                进行中负载 {usedH}h{activeReal.capacityHours ? ` / 容量 ${activeReal.capacityHours}h` : ''}
              </div>
              {over && (
                <button type="button" onClick={() => nav.go('conflicts')} className="mt-1 inline-flex cursor-pointer items-center gap-1 text-xs text-bad hover:underline">
                  <AlertTriangle size={11} />容量超载 · 运行冲突检测
                </button>
              )}
            </div>
          </div>
        </Card>
      )}

      {/* 看板 / 列表 */}
      {view === 'board' ? (
        <div className="overflow-x-auto pb-1">
          <div className="flex min-w-[1000px] gap-3">
            {COLS.map((c) => {
              const list = items.filter((w) => colOf(w) === c.id)
              return (
                <div
                  key={c.id}
                  onDragOver={(e) => {
                    e.preventDefault()
                    e.dataTransfer.dropEffect = 'move'
                  }}
                  onDragEnter={() => setDragOverCol(c.id)}
                  onDragLeave={(e) => {
                    if (!e.currentTarget.contains(e.relatedTarget as Node)) {
                      setDragOverCol(undefined)
                    }
                  }}
                  onDrop={(e) => {
                    e.preventDefault()
                    setDragOverCol(undefined)
                    const wid = e.dataTransfer.getData('text/plain')
                    if (!wid) return
                    const targetW = items.find((x) => x.id === wid)
                    if (targetW) {
                      const nextSt = mapColToStatus(targetW, c.id)
                      void handleTransition(targetW, nextSt)
                    }
                  }}
                  className={`flex-1 rounded-card p-2 transition-all min-h-[440px] ${
                    dragOverCol === c.id
                      ? 'border-2 border-brand ring-2 ring-brand/30 bg-brand-bg/20'
                      : 'bg-card'
                  }`}
                >
                  <div className="flex items-center gap-1.5 px-1 pb-2">
                    <span className="text-xs font-semibold text-txt-hi">{c.label}</span>
                    {/* PM-11：task 状态机仅三态，待验收/受阻两列仅缺陷六态映射会落入，列头加提示 */}
                    {(c.id === 'in_review' || c.id === 'blocked') && <span className="text-[10px] text-txt-low">缺陷专用</span>}
                    <span className="rounded-full bg-ink-700 px-1.5 text-[10px] font-bold text-txt-mid">{list.length}</span>
                  </div>
                  <div className="space-y-2">
                    {list.map((w) => (
                      <KanCard
                        key={w.id}
                        w={w}
                        sprintEnd={activeReal?.dueDate}
                        onOpen={() => setDrawerId(w.id)}
                        onAdvance={handleTransition}
                      />
                    ))}
                    {list.length === 0 && <div className="py-12 text-center text-[11px] text-txt-low">拖放卡片到此处</div>}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      ) : (
        <Card className="overflow-x-auto">
          {items.length === 0 ? <Empty text={itemsQ.isLoading ? '工作项加载中…' : '该迭代暂无工作项'} /> : (
            <ListTable items={items} realSprints={realSprints} />
          )}
        </Card>
      )}

      {drawerId && (
        <WorkDrawer
          w={drawerItem}
          nav={nav}
          onClose={() => setDrawerId(undefined)}
          onTransition={handleTransition}
        />
      )}
      {/* R-8：登记缺陷改走共用 DefectForm（真实 API + 全字段，与缺陷中心一致）；新建任务走真实 POST /work-items */}
      {creating === 'defect' && (
        <DefectFormModal
          showSchedule
          defaults={{
            sprintId: activeReal ? sprintId : undefined,
            releaseId: activeReal?.releaseId,
          }}
          onClose={() => setCreating(null)}
          onCreated={() => {
            // 真实创建成功：失效迭代工作项/缺陷缓存，看板即时刷新
            void queryClient.invalidateQueries({ queryKey: ['work-items'] })
            void queryClient.invalidateQueries({ queryKey: ['defects'] })
          }}
        />
      )}
      {creating === 'task' && activeReal && (
        <CreateModal sprint={activeReal} onClose={() => setCreating(null)} />
      )}
      {toast && (
        <div className="fixed bottom-6 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-2 rounded-full border border-line bg-canvas px-4 py-2.5 text-sm text-txt-hi shadow-lg">
          <span className={`h-2 w-2 rounded-full ${toast.ok ? 'bg-ok' : 'bg-bad'}`}/>{toast.text}
        </div>
      )}
    </div>
  )
}

// ---------------- 看板卡片 ----------------
function KanCard({
  w,
  sprintEnd,
  onOpen,
  onAdvance,
}: {
  w: WorkItem
  sprintEnd?: string
  onOpen: () => void
  onAdvance: (w: WorkItem, nxt: string) => void
}) {
  const finished = colOf(w) === 'done'
  const nxt = nextStatus(w)
  const overflow = !!w.dueDate && !!sprintEnd && w.dueDate > sprintEnd
  return (
    <div
      draggable
      onDragStart={(e) => {
        e.dataTransfer.setData('text/plain', w.id)
        e.dataTransfer.effectAllowed = 'move'
      }}
      className="group relative cursor-grab active:cursor-grabbing rounded-lg border border-line bg-canvas p-2.5 shadow-card hover:border-line-hi transition"
      style={{ borderLeft: `3px solid ${BORDER[w.type]}` }}
      onClick={onOpen}
    >
      <div className="flex items-center gap-1.5">
        <span className="font-mono text-[11px] text-txt-low">{w.key}</span>
        {overflow && (
          <span title={`截止 ${w.dueDate} 晚于迭代截止 ${sprintEnd}（CF-6 Deadline 越级）`} className="rounded bg-bad px-1 text-[10px] font-bold leading-4 text-white">越级</span>
        )}
        <span className="flex-1" />
        <Avatar userId={w.assigneeId} size={20} />
      </div>
      <div className="mt-1 line-clamp-2 min-h-10 text-[13px] font-medium leading-5 text-txt-hi">{w.title}</div>
      <div className="mt-1 flex flex-wrap items-center gap-1">
        <PriorityBadge p={w.priority} />
        {w.type === 'testtask' && <span className="rounded bg-cat-teal/10 px-1 text-[10px] font-bold leading-4 text-cat-teal">TT {w.passedCount}/{w.caseCount}</span>}
        {w.type === 'defect' && <Pill tone={severityTone[w.severity]}>{w.severity}</Pill>}
        {w.dueDate && <span className="ml-auto text-[10px] tabular-nums text-txt-low">{w.dueDate.slice(5)}</span>}
      </div>
      {w.labels.length > 0 && <div className="mt-1 truncate text-[10px] text-txt-low">{w.labels.join(' · ')}</div>}
      {!finished && nxt && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation()
            onAdvance(w, nxt)
          }}
          title={`推进到「${nextLabel(nxt, w.type === 'defect')}」`}
          className="absolute right-1.5 bottom-1.5 inline-flex cursor-pointer items-center gap-0.5 rounded border border-brand/40 bg-brand-bg px-2 py-0.5 text-[11px] font-semibold text-brand-deep shadow-sm hover:bg-brand hover:text-white transition"
        >
          → 推进
        </button>
      )}
    </div>
  )
}

function TypeTag({ w }: { w: WorkItem }) {
  const cls = w.type === 'defect' ? 'text-cat-orange bg-cat-orange/10' : w.type === 'testtask' ? 'text-cat-teal bg-cat-teal/10' : 'text-cat-blue bg-cat-blue/10'
  const label = w.type === 'defect' ? '缺陷' : w.type === 'testtask' ? '测试任务' : '任务'
  return <span className={`rounded px-1.5 py-px text-[11px] font-semibold ${cls}`}>{label}</span>
}

// ---------------- 列表视图（真实迭代/版本名映射） ----------------
function ListTable({ items, realSprints }: { items: WorkItem[]; realSprints: RemoteSprint[] }) {
  const { data: briefRows } = useUserBriefs()
  const briefs = toBrief(briefRows)
  const { data: releaseRows } = useReleases()
  const sprintName = (sid?: string) => realSprints.find((s) => s.id === sid)?.name ?? '—'
  const releaseName = (rid?: string) => releaseRows?.find((r) => r.id === rid)?.name ?? '—'
  return (
    <table className="w-full min-w-[900px] text-left text-sm">
      <thead>
        <tr className="border-b border-line text-[11px] text-txt-low">
          {['Key', '标题', '类型', '优先级', '状态', '负责人', '迭代', '版本', '截止'].map((h) => <th key={h} className="px-3 py-2 font-medium">{h}</th>)}
        </tr>
      </thead>
      <tbody>
        {items.map((w) => {
          const overdue = !!w.dueDate && w.dueDate < today && colOf(w) !== 'done'
          return (
            <tr key={w.id} className="cursor-pointer border-b border-line/60 last:border-0 hover:bg-ink-700">
              <td className="px-3 py-2 font-mono text-xs text-txt-low">{w.key}</td>
              <td className="max-w-52 truncate px-3 py-2 text-txt-hi">{w.title}</td>
              <td className="px-3 py-2"><TypeTag w={w} /></td>
              <td className="px-3 py-2"><PriorityBadge p={w.priority} /></td>
              <td className="px-3 py-2"><Pill tone={statusTone(w)}>{statusText(w)}</Pill></td>
              <td className="px-3 py-2"><div className="flex items-center gap-1.5 text-xs text-txt-mid"><Avatar userId={w.assigneeId} size={20} />{briefs.get(w.assigneeId)?.name ?? '—'}</div></td>
              <td className="px-3 py-2 text-xs text-txt-mid">{sprintName(w.sprintId)}</td>
              <td className="px-3 py-2 text-xs text-cat-teal">{releaseName(w.releaseId)}</td>
              <td className={`px-3 py-2 text-xs tabular-nums ${overdue ? 'font-semibold text-bad' : 'text-txt-mid'}`}>{w.dueDate ?? '—'}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

// ---------------- 详情抽屉 ----------------
const linkBtn = 'inline-flex max-w-full cursor-pointer items-center gap-1 truncate rounded border border-line bg-canvas px-1.5 py-1 text-[11px] text-brand hover:bg-brand-bg'
const linkBtnRed = 'inline-flex max-w-full cursor-pointer items-center gap-1 truncate rounded border border-bad/30 bg-bad-bg px-1.5 py-1 text-[11px] text-bad-deep hover:bg-bad/15'

function WorkDrawer({
  w,
  nav,
  onClose,
  onTransition,
}: {
  w: WorkItem | undefined
  nav: PageProps['nav']
  onClose: () => void
  onTransition?: (w: WorkItem, nextSt: string) => void
}) {
  // B2 去 mock：MR 链接一律指向自研内核真实 MR（按 linkedWorkItemKey 匹配）
  const { data: mrListData } = useMergeRequests()
  const { data: briefRows } = useUserBriefs()
  const briefs = toBrief(briefRows)
  const { data: sprintRows } = useSprints()
  const { data: releaseRows } = useReleases()
  const { data: productRows } = useProducts()
  const { data: rmRows } = useRoadmapItems()
  const { data: goalRows } = useGoals()
  const reqQ = useWorkItem(w?.requirementId)
  if (!w) return null
  const nameOf = (uid: string) => briefs.get(uid)?.name ?? '—'
  const realLinkedMrs = (mrListData?.items ?? []).filter((m) => m.linkedWorkItemKey === w.key)
  const rm = w.roadmapItemId ? rmRows?.find((r) => r.id === w.roadmapItemId) : undefined
  const goal = rm?.goalId ? goalRows?.find((g) => g.id === rm.goalId) : undefined
  const rel = w.releaseId ? releaseRows?.find((r) => r.id === w.releaseId) : undefined
  const brid = w.type === 'defect' ? w.blockedReleaseId : undefined
  const blockedRelName = brid ? releaseRows?.find((r) => r.id === brid)?.name : undefined
  const product = productRows?.find((p) => p.id === w.productId)?.name ?? '—'
  const sprint = sprintRows?.find((s) => s.id === w.sprintId)?.name ?? '—'
  const field = (k: string, v: ReactNode) => (
    <div><div className="text-[11px] text-txt-low">{k}</div><div className="mt-0.5 text-xs text-txt-hi">{v}</div></div>
  )
  const nxt = nextStatus(w)
  return (
    <div className="fixed inset-0 z-50 bg-black/30" onClick={onClose}>
      <div className="absolute inset-y-0 right-0 flex w-[430px] max-w-full flex-col overflow-y-auto border-l border-line bg-canvas p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs font-bold text-txt-low">{w.key}</span>
              <TypeTag w={w} />
              <Pill tone={statusTone(w)}>{statusText(w)}</Pill>
            </div>
            <h3 className="mt-1.5 text-base font-bold leading-6 text-txt-hi">{w.title}</h3>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer text-txt-low hover:text-txt-hi"><X size={16} /></button>
        </div>

        {/* 状态推进快捷操作栏 */}
        {nxt && (
          <div className="mt-3 flex items-center justify-between rounded-lg border border-brand/30 bg-brand-bg/50 p-2.5">
            <div className="text-xs text-brand-deep">
              当前阶段：<span className="font-semibold">{statusText(w)}</span>
            </div>
            <button
              type="button"
              onClick={() => onTransition?.(w, nxt)}
              className="inline-flex cursor-pointer items-center gap-1 rounded bg-brand px-3 py-1 text-xs font-semibold text-white shadow-sm hover:bg-brand/90 transition"
            >
              推进至「{nextLabel(nxt, w.type === 'defect')}」
            </button>
          </div>
        )}

        <div className="mt-3 grid grid-cols-2 gap-3 rounded-card bg-card p-3">
          {field('负责人', <span className="flex items-center gap-1.5"><Avatar userId={w.assigneeId} size={18} />{nameOf(w.assigneeId)}</span>)}
          {field('优先级', <PriorityBadge p={w.priority} />)}
          {w.type === 'defect' && field('严重度', <Pill tone={severityTone[w.severity]}>{w.severity}</Pill>)}
          {field('产品', product)}
          {field('迭代', sprint)}
          {field('版本', rel?.name ?? '—')}
          {field('工时 / 故事点', `${w.estimateHours}h / ${w.points} 点`)}
          {field('截止', w.dueDate ?? '—')}
          {field('创建人', nameOf(w.creatorId))}
          {field('阻塞于', brid ? blockedRelName ?? brid.slice(0, 6) : '无')}
        </div>

        {w.description && <p className="mt-3 whitespace-pre-wrap text-xs leading-5 text-txt-mid">{w.description}</p>}
        {w.labels.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1">{w.labels.map((l) => <span key={l} className="rounded bg-ink-700 px-1.5 py-px text-[11px] text-txt-mid">{l}</span>)}</div>
        )}

        <div className="mt-4">
          <div className="mb-1.5 text-[11px] font-semibold text-txt-low">关联链路</div>
          <div className="flex flex-wrap gap-1.5">
            {w.requirementId && (
              <button type="button" onClick={() => nav.go('requirements', w.requirementId)} className={`${linkBtn} !border-cat-purple/40 !text-cat-purple`}>
                需求 {reqQ.data?.key ?? ''}
              </button>
            )}
            {rm && <button type="button" onClick={() => nav.go('roadmap')} className={linkBtn}>RoadMap · {rm.name}</button>}
            {goal && <button type="button" onClick={() => nav.go('goals', goal.id)} className={linkBtn}>目标 · {goal.name}</button>}
            {rel && <button type="button" onClick={() => nav.go('delivery', rel.id)} className={linkBtn}>版本 {rel.name}</button>}
            {w.type === 'task' && realLinkedMrs.map((m) => (
              <button key={m.id} type="button" onClick={() => nav.go('mr', m.id)} className={linkBtn}><GitPullRequest size={11} />MR !{m.number}</button>
            ))}
            {brid && <button type="button" onClick={() => nav.go('delivery', brid)} className={linkBtnRed}>阻塞 {blockedRelName ?? ''}</button>}
            {!w.requirementId && !rm && !rel && realLinkedMrs.length === 0 && !brid && <span className="text-xs text-txt-low">无关联对象</span>}
          </div>
        </div>

        <div className="mt-auto pt-4 text-[11px] text-txt-low">
          创建于 {w.createdAt} · 更新于 {w.updatedAt} · {w.type === 'defect' ? '缺陷中心可推进缺陷状态流转' : '看板卡片悬停可「→ 推进」'}
        </div>
      </div>
    </div>
  )
}

// ---------------- 新建任务表单（dogfooding 切换：真实 POST /work-items，type=task） ----------------
const inputCls = 'w-full rounded-input border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none focus:border-brand'

function CreateModal({ sprint, onClose }: { sprint: RemoteSprint; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [title, setTitle] = useState('')
  const [priority, setPriority] = useState<'P0' | 'P1' | 'P2' | 'P3'>('P1')
  const [assigneeId, setAssigneeId] = useState('')
  const [sid, setSid] = useState(sprint.id)
  const [rid, setRid] = useState(sprint.releaseId ?? '')
  const [due, setDue] = useState('')
  // PM-11：可选估算工时（小时），创建时透传后端 estimateHours；空串=不设置
  const [est, setEst] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const { data: briefRows } = useUserBriefs()
  const briefs = [...toBrief(briefRows).values()]
  const { data: sprintRows } = useSprints()
  const { data: releaseRows } = useReleases()
  // 迭代变更 → 版本默认跟随所选迭代（真实迭代携带 releaseId）
  const pickSprint = (id: string) => {
    setSid(id)
    setRid(sprintRows?.find((s) => s.id === id)?.releaseId ?? '')
  }
  const submit = async () => {
    if (!title.trim()) return
    setBusy(true)
    setErr('')
    try {
      await workItemsApi.create({
        type: 'task',
        title: title.trim(),
        priority,
        assigneeId: assigneeId || undefined,
        productId: sprintRows?.find((s) => s.id === sid)?.productId ?? sprint.productId,
        sprintId: sid || undefined,
        releaseId: rid || undefined,
        dueDate: due || undefined,
        estimateHours: est.trim() !== '' && Number(est) > 0 ? Number(est) : undefined,
      })
      void queryClient.invalidateQueries({ queryKey: ['work-items'] })
      onClose()
    } catch (e) {
      setErr(e instanceof Error ? e.message : '创建失败')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" onClick={onClose}>
      <div className="w-[430px] rounded-card border border-line bg-canvas p-5 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-bold text-txt-hi">新建任务</h3>
          <button type="button" onClick={onClose} className="cursor-pointer text-txt-low hover:text-txt-hi"><X size={16} /></button>
        </div>
        <div className="mt-3 space-y-2.5">
          <label className="block text-xs text-txt-mid">
            标题
            <input value={title} onChange={(e) => setTitle(e.target.value)} autoFocus placeholder="简洁描述工作内容…" className={`mt-1 ${inputCls}`} />
          </label>
          <div className="grid grid-cols-2 gap-2.5">
            <label className="block text-xs text-txt-mid">
              优先级
              <select value={priority} onChange={(e) => setPriority(e.target.value as 'P0' | 'P1' | 'P2' | 'P3')} className={`mt-1 ${inputCls}`}>
                {['P0', 'P1', 'P2', 'P3'].map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              负责人
              <select value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)} className={`mt-1 ${inputCls}`}>
                <option value="">（未指派）</option>
                {briefs.map((u) => <option key={u.id} value={u.id}>{u.name} · {u.title}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              迭代
              <select value={sid} onChange={(e) => pickSprint(e.target.value)} className={`mt-1 ${inputCls}`}>
                {(sprintRows ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              版本
              <select value={rid} onChange={(e) => setRid(e.target.value)} className={`mt-1 ${inputCls}`}>
                <option value="">（不挂版本）</option>
                {(releaseRows ?? []).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              截止日
              <input type="date" value={due} onChange={(e) => setDue(e.target.value)} className={`mt-1 ${inputCls}`} />
            </label>
            <label className="block text-xs text-txt-mid">
              估算工时(小时)
              <input
                type="number"
                min={0}
                step={0.5}
                value={est}
                onChange={(e) => setEst(e.target.value)}
                placeholder="可选"
                className={`mt-1 ${inputCls}`}
              />
            </label>
          </div>
          {err && <div className="rounded bg-bad-bg px-2.5 py-1.5 text-[11px] text-bad-deep">{err}</div>}
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <Btn variant="ghost" onClick={onClose}>取消</Btn>
          <Btn variant="primary" onClick={() => void submit()} disabled={busy || !title.trim()}>创建</Btn>
        </div>
      </div>
    </div>
  )
}
