// 战略目标 v3（M2-W4 P1-1 双数据源收敛 + P2-3 下钻入口 + P2-2 字段级错误）：
// 全部数据来自真实 API——GET /api/v1/goals + GET /goals/{id}/rollup + POST /api/v1/goals，
// 进度卡片条 = GET /roadmap/timeline?view=goal（查询侧聚合），负责人头像 = GET /users。
// 红线：本页不再渲染 store 业务数据；五层下钻 Goal→条目→版本→迭代→工作项 全走真实接口。
import { useMemo, useState } from 'react'
import {
  ArrowLeft, ArrowRight, CalendarDays, ChevronDown, ChevronRight, Plus, Target, X,
} from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  goalsApi, parseFieldError, useGoalRollup, useGoals, useReleases, useRoadmapTimeline,
  useSprintWorkItems, useSprints,
} from '../../api/queries'
import type { GoalRollupRow, RemoteGoal, RemoteRelease, RemoteSprint, RemoteSprintWorkItem } from '../../api/queries'
import { RemoteAvatar, remoteName } from '../../api/RemoteAvatar'
import { toBrief, useRemoteUsers } from '../../api/users'
import { Bar, Btn, Card, CardHeader, Empty, PageHeader, Pill, ProgressRing, Spinner } from '../../components/ui'
import type { Nav, PageProps } from '../../nav'

// PM-1：strategic_goal 无 key 列，原 goalKey() 以 id 前 8 位兜底当编号（紫色位 ba4cd198）已删除；
// 目标卡/详情头不再渲染编号徽标（或用「目标」字样徽标），名称完整展示
const pct = (done: number, total: number) => (total > 0 ? Math.round((done / total) * 100) : 0)

export default function GoalsPage({ nav, id }: PageProps) {
  const [localSel, setLocalSel] = useState<string | undefined>()
  const selId = id ?? localSel
  const { data: goals = [], isLoading } = useGoals()
  const goal = selId ? goals.find((g) => g.id === selId) : undefined

  return (
    <>
      {goal ? (
        <GoalDetail goal={goal} nav={nav} onBack={() => setLocalSel(undefined)} />
      ) : (
        <GoalList nav={nav} goals={goals} isLoading={isLoading} onOpen={setLocalSel} />
      )}
    </>
  )
}

// ---------------- 列表：目标卡片栅格（真实 API） ----------------
function GoalList({ nav, goals, isLoading, onOpen }: {
  nav: Nav
  goals: RemoteGoal[]
  isLoading: boolean
  onOpen: (id: string) => void
}) {
  const [creating, setCreating] = useState(false)
  // 进度卡：复用 roadmap timeline goal 视角（查询侧聚合 workItemDone/total），避免逐目标 rollup
  const { data: timeline } = useRoadmapTimeline('goal')
  const { data: userRows } = useRemoteUsers()
  const users = toBrief(userRows)
  const progressOf = useMemo(() => {
    const m = new Map<string, { done: number; total: number }>()
    if (timeline?.view === 'goal') {
      for (const row of timeline.items) m.set(row.id, { done: row.workItemDone, total: row.total })
    }
    return m
  }, [timeline])

  return (
    <div>
      <PageHeader
        title="战略目标"
        desc="L1 目标 · 向下钻取 RoadMap / 版本 / 迭代 / 工作项，上层进度只读聚合（真实 API）"
        actions={
          <div className="flex items-center gap-2">
            <Pill tone="purple"><Target size={12} />{goals.length} 个目标</Pill>
            <Btn variant="primary" onClick={() => setCreating(true)}><Plus size={14} />新建战略目标</Btn>
          </div>
        }
      />
      {isLoading && goals.length === 0 && <Card><Empty text="目标加载中…" icon={<Spinner />} /></Card>}
      {!isLoading && goals.length === 0 && <Card><Empty text="暂无战略目标，点击右上角「新建战略目标」创建" /></Card>}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        {goals.map((g) => {
          const agg = progressOf.get(g.id)
          const progress = pct(agg?.done ?? 0, agg?.total ?? 0)
          return (
            <div key={g.id} className="group relative">
              {/* P2-3：hover 出「查看下钻」直达 RoadMap */}
              <button
                type="button"
                title={`下钻查看「${g.name}」的 RoadMap 时间轴`}
                onClick={() => nav.go('roadmap', g.id)}
                className="absolute top-2.5 right-2.5 z-10 hidden cursor-pointer items-center gap-1 rounded border border-brand/30 bg-canvas px-2 py-0.5 text-[11px] font-semibold text-brand shadow-sm group-hover:inline-flex hover:bg-brand hover:text-white"
              >
                查看下钻 <ArrowRight size={11} />
              </button>
              <button
                type="button"
                onClick={() => onOpen(g.id)}
                className="cursor-pointer rounded-card border border-line bg-card p-4 pt-6 text-left shadow-card transition hover:border-line-hi"
              >
                <div className="flex items-start gap-2">
                  {/* PM-1：编号位不再显示 id 前 8 位，改为「目标」字样徽标；名称完整展示（两行截断） */}
                  <span className="mt-0.5 shrink-0 rounded bg-cat-purple/10 px-1.5 py-0.5 text-[10px] font-semibold text-cat-purple">目标</span>
                  <span className="line-clamp-2 flex-1 text-sm font-semibold leading-5 text-txt-hi" title={g.name}>{g.name}</span>
                </div>
                <div className="mt-2.5 flex items-center gap-2">
                  <Pill tone="info">{agg?.total ?? 0} 项工作项</Pill>
                  <span className="flex-1" />
                  <ProgressRing value={progress} size={44} stroke={5} />
                </div>
                <div className="mt-2 truncate text-xs text-txt-mid" title={g.description ?? ''}>
                  {g.description || '—'}
                </div>
                <div className="mt-3 flex items-center gap-2 border-t border-line pt-2.5 text-xs text-txt-low">
                  <CalendarDays size={12} />{new Date(g.createdAt).toLocaleDateString('zh-CN')} 创建
                  <span className="flex-1" />
                  <RemoteAvatar userId={g.ownerId ?? ''} users={users} size={20} />
                </div>
              </button>
            </div>
          )
        })}
      </div>
      {creating && <GoalModal onClose={() => setCreating(false)} onCreated={(gid) => { setCreating(false); onOpen(gid) }} />}
    </div>
  )
}

// ---------------- 详情：顶部 + 下钻树（rollup → 版本 → 迭代 → 工作项，全真实 API） ----------------
function GoalDetail({ goal, nav, onBack }: { goal: RemoteGoal; nav: Nav; onBack: () => void }) {
  const [openRm, setOpenRm] = useState<Set<string>>(new Set())
  const [openRel, setOpenRel] = useState<Set<string>>(new Set())
  // R-5/D1：树顶「按产品」过滤（纯前端；产品选项来自 rollup 行 productName 投影）
  const [productFilter, setProductFilter] = useState<'all' | string>('all')
  const { data: rollup, isLoading } = useGoalRollup(goal.id)
  const { data: userRows } = useRemoteUsers()
  const users = toBrief(userRows)
  const { data: releases = [] } = useReleases()
  const allItems = rollup?.roadmapItems ?? []
  // 产品下拉选项：rollup 行涉及的 productId/productName 去重保序（未挂产品归「未挂产品」）
  const productOptions = useMemo(() => {
    const m = new Map<string, string>()
    for (const it of allItems) {
      const k = it.productId ?? '_none'
      if (!m.has(k)) m.set(k, it.productName ?? '未挂产品')
    }
    return [...m.entries()]
  }, [allItems])
  const items = productFilter === 'all' ? allItems : allItems.filter((it) => (it.productId ?? '_none') === productFilter)
  // R-5 AC③：可下钻条目数不含哨兵行（直挂工作项无条目归属）
  const drillableCount = items.filter((r) => r.id).length
  const total = allItems.reduce((s, r) => s + r.total, 0)
  const done = allItems.reduce((s, r) => s + r.workItemDone, 0)
  const progress = pct(done, total)
  const toggle = (set: Set<string>, k: string): Set<string> => {
    const n = new Set(set)
    if (n.has(k)) n.delete(k)
    else n.add(k)
    return n
  }

  return (
    <div>
      <button type="button" onClick={onBack} className="mb-3 inline-flex cursor-pointer items-center gap-1 text-sm text-txt-mid hover:text-brand">
        <ArrowLeft size={14} />返回目标列表
      </button>

      {/* 顶部：名称 + 进度环 + owner */}
      <Card className="mb-4 p-5">
        <div className="flex flex-wrap items-center gap-5">
          <ProgressRing value={progress} size={64} stroke={7} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              {/* PM-1：详情头编号位同样不再显示 id 前 8 位，改「目标」徽标 + 完整目标名 */}
              <span className="shrink-0 rounded bg-cat-purple/10 px-1.5 py-0.5 text-[11px] font-semibold text-cat-purple">目标</span>
              <h2 className="text-lg font-bold text-txt-hi">{goal.name}</h2>
              <Pill tone="brand">进行中</Pill>
            </div>
            <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-txt-mid">
              <span className="flex items-center gap-1.5">
                <RemoteAvatar userId={goal.ownerId ?? ''} users={users} size={18} />
                {goal.ownerId ? remoteName(users, goal.ownerId) : '—'}
              </span>
              <span>下钻条目 {drillableCount} 项 · 工作项 {done}/{total}</span>
            </div>
            {goal.description && <p className="mt-2 max-w-xl text-xs leading-5 text-txt-low">{goal.description}</p>}
          </div>
          <div className="flex items-center gap-2">
            <Btn onClick={() => nav.go('roadmap', goal.id)}>下钻到 RoadMap <ArrowRight size={13} /></Btn>
          </div>
        </div>
      </Card>

      {/* 下钻树：rollup 条目 → 版本 → 迭代 → 工作项 */}
      <Card>
        <CardHeader
          title="五层下钻 · Goal → 条目 → 版本 → 迭代 → 工作项"
          extra={(
            /* R-5/D1：树顶「按产品」过滤（全部/各产品，纯前端） */
            <div className="flex items-center gap-2">
              {productOptions.length > 0 && (
                <select
                  value={productFilter}
                  onChange={(e) => setProductFilter(e.target.value)}
                  className="cursor-pointer rounded-input border border-line bg-card px-2 py-1 text-xs text-txt-mid outline-none focus:border-brand"
                >
                  <option value="all">按产品：全部</option>
                  {productOptions.map(([pid, name]) => (
                    <option key={pid} value={pid}>{name}</option>
                  ))}
                </select>
              )}
              <span className="text-xs text-txt-low">点击逐级展开 / 收起（真实 API）</span>
            </div>
          )}
        />
        <div className="px-2 py-1">
          {isLoading && <Empty text="rollup 聚合加载中…" size="sm" icon={<Spinner />} />}
          {!isLoading && allItems.length === 0 && <Empty text="该目标暂未关联 RoadMap 条目" size="sm" />}
          {!isLoading && allItems.length > 0 && items.length === 0 && (
            <Empty text="该产品下暂无条目" size="sm" />
          )}
          {/* R-5 AC③ 直连口径：id 为 null 的哨兵行渲染为「直挂目标」分组（无下钻），
              有 id 的条目行走 RmBranch 五层下钻 */}
          {items.map((rm) => {
            if (!rm.id) return <DirectGoalGroup key="direct-goal-items" rm={rm} />
            return (
              <RmBranch
                key={rm.id}
                rm={rm}
                releases={releases}
                open={openRm.has(rm.id)}
                onToggle={() => setOpenRm((p) => toggle(p, rm.id!))}
                openRels={openRel}
                onToggleRel={(rid) => setOpenRel((p) => toggle(p, rid))}
                nav={nav}
              />
            )
          })}
        </div>
      </Card>
    </div>
  )
}

/**
 * 直挂目标分组行（R-5 AC③ 直连口径哨兵行，id=null）：goal_id 直挂的工作项（需求类直挂、
 * 无 RoadMap 条目归属）汇总——仅展示计数与进度，不提供下钻抽屉（直挂需求无条目归属，
 * 版本/迭代链感知不到它），点击不展开。仅在后端计数 > 0 时出现。
 */
function DirectGoalGroup({ rm }: { rm: GoalRollupRow }) {
  const progress = pct(rm.workItemDone, rm.total)
  return (
    <div className="border-b border-line/60 last:border-0">
      <div
        className="flex w-full items-center gap-2 px-2 py-2.5 text-left"
        title="直挂目标的工作项：goal_id 直挂、无 RoadMap 条目归属，不支持逐级下钻"
      >
        {/* 占位与条目行 chevron 对齐 */}
        <span className="w-[13px] shrink-0" />
        <span className="shrink-0 rounded bg-cat-teal/10 px-1.5 py-0.5 text-[10px] font-semibold text-cat-teal">直挂目标</span>
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-txt-mid">{rm.name}（{rm.total}）</span>
        <span className="w-20 shrink-0"><Bar value={progress} tone={progress >= 80 ? 'ok' : 'brand'} /></span>
        <span className="w-16 shrink-0 text-right text-xs font-bold tabular-nums text-txt-mid">{rm.workItemDone}/{rm.total} 项</span>
      </div>
    </div>
  )
}

/** 条目分支：rollup 行 + 关联版本展开（R-5/D1：行首渲染所属产品标签） */
function RmBranch({ rm, releases, open, onToggle, openRels, onToggleRel, nav }: {
  rm: GoalRollupRow
  releases: RemoteRelease[]
  open: boolean
  onToggle: () => void
  openRels: Set<string>
  onToggleRel: (rid: string) => void
  nav: Nav
}) {
  const rels = releases.filter((r) => rm.releaseIds.includes(r.id))
  const progress = pct(rm.workItemDone, rm.total)
  const chev = (on: boolean) => (on ? <ChevronDown size={13} className="shrink-0 text-txt-low" /> : <ChevronRight size={13} className="shrink-0 text-txt-low" />)
  return (
    <div className="border-b border-line/60 last:border-0">
      <button type="button" onClick={onToggle} className="flex w-full cursor-pointer items-center gap-2 px-2 py-2.5 text-left hover:bg-ink-700">
        {chev(open)}
        {/* R-5/D1 产品标签：条目必挂产品（productName 由 rollup 投影；缺产品显示灰色占位） */}
        {rm.productName
          ? <span className="shrink-0 rounded bg-cat-purple/10 px-1.5 py-0.5 text-[10px] font-semibold text-cat-purple">{rm.productName}</span>
          : <span className="shrink-0 rounded bg-ink-700 px-1.5 py-0.5 text-[10px] text-txt-low">未挂产品</span>}
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-txt-hi">{rm.name}</span>
        {rels.slice(0, 1).map((r) => <span key={r.id} className="shrink-0 rounded bg-info-bg px-1.5 text-[11px] font-semibold text-cat-teal">{r.name}</span>)}
        <span className="w-20 shrink-0"><Bar value={progress} tone={progress >= 80 ? 'ok' : 'brand'} /></span>
        <span className="w-16 shrink-0 text-right text-xs font-bold tabular-nums text-txt-mid">{rm.workItemDone}/{rm.total} 项</span>
      </button>
      {open && (
        <div className="pb-2 pl-6 pr-2">
          {rels.length === 0 && <div className="py-1 text-xs text-txt-low">该条目未关联交付版本</div>}
          {rels.map((rel) => (
            <ReleaseBranch key={rel.id} release={rel} open={openRels.has(rel.id)} onToggle={() => onToggleRel(rel.id)} nav={nav} />
          ))}
        </div>
      )}
    </div>
  )
}

/** 版本分支：版本信息 + 该版本迭代展开（GET /sprints 过滤 releaseId） */
function ReleaseBranch({ release, open, onToggle, nav }: {
  release: RemoteRelease
  open: boolean
  onToggle: () => void
  nav: Nav
}) {
  const { data: sprints = [] } = useSprints()
  const relSprints = sprints.filter((s) => s.releaseId === release.id)
  const chev = (on: boolean) => (on ? <ChevronDown size={13} className="shrink-0 text-txt-low" /> : <ChevronRight size={13} className="shrink-0 text-txt-low" />)
  const relTone = release.status === 'released' ? 'ok' : release.status === 'coding' ? 'brand' : release.status === 'blocked' ? 'bad' : 'neutral'
  const relText = ({ released: '已发布', testing: '测试中', coding: '编码中', blocked: '已阻塞', planned: '已规划', code_freeze: '代码冻结' } as Record<string, string>)[release.status] ?? release.status
  return (
    <div>
      <button type="button" onClick={onToggle} className="flex w-full cursor-pointer items-center gap-2 rounded px-1.5 py-1.5 text-left hover:bg-ink-700">
        {chev(open)}
        <span className="font-mono text-xs font-bold text-cat-teal">{release.name}</span>
        <Pill tone={relTone}>{relText}</Pill>
        <span className="text-[11px] text-txt-low">计划 {release.planDate?.slice(0, 10) ?? '—'}</span>
        <span className="flex-1" />
        <span className="text-[11px] tabular-nums text-txt-mid">{relSprints.length} 个迭代</span>
      </button>
      {open && (
        <div className="pl-5">
          {relSprints.length === 0 && <div className="py-1 text-xs text-txt-low">该版本暂无迭代</div>}
          {relSprints.map((sp) => <SprintBranch key={sp.id} sprint={sp} nav={nav} />)}
        </div>
      )}
    </div>
  )
}

/** 迭代分支：展开时拉取该迭代工作项（GET /work-items?sprintId=） */
function SprintBranch({ sprint, nav }: { sprint: RemoteSprint; nav: Nav }) {
  const [open, setOpen] = useState(false)
  const query = useSprintWorkItems(open ? sprint.id : undefined)
  const items = query.data ?? []
  const totalPoints = items.reduce((s, w) => s + (w.storyPoints ?? 0), 0)
  const donePoints = items
    // UT-29 口径：'passed' 为 test_task 完成态，计入完成点数
    .filter((w) => ['done', 'closed', '回归通过', '已关闭', 'passed'].includes(w.status))
    .reduce((s, w) => s + (w.storyPoints ?? 0), 0)
  const progress = pct(donePoints, totalPoints)
  const chev = (on: boolean) => (on ? <ChevronDown size={13} className="shrink-0 text-txt-low" /> : <ChevronRight size={13} className="shrink-0 text-txt-low" />)
  return (
    <div>
      <button type="button" onClick={() => setOpen((o) => !o)} className="flex w-full cursor-pointer items-center gap-2 rounded px-1.5 py-1.5 text-left hover:bg-ink-700">
        {chev(open)}
        <span className="min-w-0 flex-1 truncate text-xs font-medium text-txt-hi">{sprint.name}</span>
        <span className="shrink-0 text-[11px] tabular-nums text-txt-low">{sprint.startDate?.slice(5) ?? '—'} ~ {sprint.dueDate?.slice(5) ?? '—'}</span>
        <span className="w-14 shrink-0"><Bar value={progress} tone={progress >= 100 ? 'ok' : 'brand'} /></span>
        <span className="w-14 shrink-0 text-right text-[11px] tabular-nums text-txt-mid">{donePoints}/{totalPoints} 点</span>
      </button>
      {open && (
        <div className="ml-4 space-y-0.5 border-l border-line py-1 pl-3">
          {query.isLoading && <div className="flex items-center gap-2 py-1 text-xs text-txt-low"><Spinner size={12} />工作项加载中…</div>}
          {!query.isLoading && items.length === 0 && <div className="py-1 text-xs text-txt-low">该迭代暂无工作项</div>}
          {items.map((w) => <WorkItemLeaf key={w.id} w={w} nav={nav} />)}
        </div>
      )}
    </div>
  )
}

function WorkItemLeaf({ w, nav }: { w: RemoteSprintWorkItem; nav: Nav }) {
  const typeLabel = w.type === 'defect' ? '缺陷' : w.type === 'test_task' ? '测试' : '任务'
  const typeCls = w.type === 'defect'
    ? 'text-cat-orange bg-cat-orange/10'
    : w.type === 'test_task' ? 'text-cat-teal bg-cat-teal/10' : 'text-cat-blue bg-cat-blue/10'
  return (
    <button
      type="button"
      onClick={() => nav.go(w.type === 'defect' ? 'defects' : 'tasks', w.id)}
      className="flex w-full cursor-pointer items-center gap-2 rounded px-1.5 py-1 text-left hover:bg-ink-700"
    >
      <span className={`shrink-0 rounded px-1 text-[10px] font-semibold ${typeCls}`}>{typeLabel}</span>
      <span className="shrink-0 font-mono text-[11px] text-txt-low">{w.key}</span>
      <span className="min-w-0 flex-1 truncate text-xs text-txt-hi">{w.title}</span>
      <Pill tone="neutral">{w.status}</Pill>
    </button>
  )
}

// ---------------- 新建战略目标弹窗（POST /goals，P2-2 字段级错误） ----------------
const inputCls = 'w-full rounded-input border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none placeholder:text-txt-low/70 focus:border-brand'

function GoalModal({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [ownerId, setOwnerId] = useState('')
  const [fieldErr, setFieldErr] = useState<Record<string, string>>({})
  const [topErr, setTopErr] = useState('')
  const { data: userRows } = useRemoteUsers()
  const users = toBrief(userRows)

  const createMut = useMutation({
    mutationFn: () => goalsApi.create({
      name: name.trim(),
      description: description.trim() || undefined,
      ownerId: ownerId || undefined,
    }),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ['goals'] })
      await queryClient.invalidateQueries({ queryKey: ['roadmap'] })
      onCreated(created.id)
    },
    onError: (err) => {
      // P2-2：PLT_4000 message 带字段前缀时落到对应字段红字，否则表单顶部统一展示
      const parsed = parseFieldError(err)
      setFieldErr(parsed.field ? { [parsed.field]: parsed.message } : {})
      setTopErr(parsed.field ? '' : parsed.message)
    },
  })

  const submit = () => {
    setFieldErr({})
    setTopErr('')
    if (!name.trim()) {
      setFieldErr({ name: '目标名称不能为空' })
      return
    }
    createMut.mutate()
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div className="w-full max-w-lg rounded-card border border-line bg-canvas p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between border-b border-line pb-3">
          <div className="flex items-center gap-2">
            <Target size={16} className="text-brand" />
            <h3 className="text-sm font-bold text-txt-hi">新建战略目标</h3>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer text-txt-low hover:text-txt-hi"><X size={16} /></button>
        </div>

        <div className="mt-3 space-y-3">
          <div>
            <label className="mb-1 block text-xs font-semibold text-txt-mid">目标名称（必填）</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：双周一版稳定交付 / 核心链路可用性 99.9%…"
              className={`${inputCls} ${fieldErr.name ? 'border-bad' : ''}`}
              autoFocus
            />
            {fieldErr.name && <div className="mt-1 text-xs text-bad">{fieldErr.name}</div>}
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold text-txt-mid">目标描述</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="衡量口径、关键结果、验收标准…"
              rows={3}
              className={`${inputCls} resize-none ${fieldErr.description ? 'border-bad' : ''}`}
            />
            {fieldErr.description && <div className="mt-1 text-xs text-bad">{fieldErr.description}</div>}
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold text-txt-mid">负责人</label>
            <select value={ownerId} onChange={(e) => setOwnerId(e.target.value)} className={`${inputCls} ${fieldErr.ownerId ? 'border-bad' : ''}`}>
              <option value="">（默认自己）</option>
              {[...users.values()].map((u) => (
                <option key={u.id} value={u.id}>{u.name} · {u.title}</option>
              ))}
            </select>
            {fieldErr.ownerId && <div className="mt-1 text-xs text-bad">{fieldErr.ownerId}</div>}
          </div>

          {topErr && <div className="rounded bg-bad-bg px-2.5 py-1.5 text-xs text-bad-deep">{topErr}</div>}
        </div>

        <div className="mt-4 flex justify-end gap-2 border-t border-line pt-3">
          <Btn variant="ghost" onClick={onClose}>取消</Btn>
          <Btn variant="primary" onClick={submit} disabled={createMut.isPending}>{createMut.isPending ? '创建中…' : '创建战略目标'}</Btn>
        </div>
      </div>
    </div>
  )
}
