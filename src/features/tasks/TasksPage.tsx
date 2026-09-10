// 迭代与任务 v2 核心：迭代 tabs + 概览（目标/剩余天数/故事点/燃尽 SVG/容量条 CF 提示）
// + 看板（三型工作项混排、CF-6 越级角标、severity 胶囊、悬停推进）+ 列表视图 + 详情抽屉（关联链路跳转）+ 新建表单
import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { AlertTriangle, ArrowRight, CalendarDays, GitPullRequest, Hash, Plus, X } from 'lucide-react'
import type { DefectSeverity, PageId, WorkItem, WorkItemStatus } from '../../data/types'
import {
  createWorkItem, dateStr, daysBetween, defectStatusFlow, goalById, mrById, productById,
  releaseById, releases, requirementById, roadmapItems, setWorkItemStatus, severityTone,
  sprintById, sprints, topicById, useStore, userById, users, workItemById, workItemStatusText,
  workItems,
} from '../../data/store'
import { Avatar, Bar, Btn, Card, Empty, PageHeader, Pill, PriorityBadge } from '../../components/ui'
import type { PageProps } from '../../nav'

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
  const flow: Partial<Record<WorkItemStatus, WorkItemStatus>> = { todo: 'in_progress', in_progress: 'in_review', in_review: 'done', blocked: 'in_progress' }
  return flow[w.status]
}
function nextLabel(s: string, isDefect: boolean): string {
  return isDefect ? s : workItemStatusText[s as WorkItemStatus]
}
const today = dateStr(0)

export default function TasksPage({ nav, id }: PageProps) {
  useStore()
  const [sprintId, setSprintId] = useState<string>(() => {
    if (id && sprints.some((s) => s.id === id)) return id
    if (id && workItemById(id)?.sprintId) return workItemById(id)!.sprintId!
    return sprints.find((s) => s.status === 'active')?.id ?? sprints[0].id
  })
  const [view, setView] = useState<'board' | 'list'>('board')
  const [drawerId, setDrawerId] = useState<string | undefined>(() => (id && workItemById(id) ? id : undefined))
  const [creating, setCreating] = useState<'task' | 'defect' | null>(null)
  useEffect(() => {
    if (!id) return
    if (sprints.some((s) => s.id === id)) setSprintId(id)
    else {
      const w = workItemById(id)
      if (w) { setDrawerId(id); if (w.sprintId) setSprintId(w.sprintId) }
    }
  }, [id])

  const sp = sprintById(sprintId)
  const items = workItems.filter((w) => w.sprintId === sprintId)
  const openItems = items.filter((w) => colOf(w) !== 'done')
  const usedH = openItems.reduce((s, w) => s + w.estimateHours, 0)
  const over = !!sp && usedH > sp.capacityHours
  const donePts = items.filter((w) => colOf(w) === 'done').reduce((s, w) => s + w.points, 0)
  const remain = sp ? daysBetween(today, sp.end) : 0

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
            <Btn onClick={() => setCreating('task')}><Plus size={14} />新建任务</Btn>
            <Btn onClick={() => setCreating('defect')}><Plus size={14} />登记缺陷</Btn>
          </>
        )}
      />

      {/* 迭代 tabs */}
      <div className="mb-3 flex flex-wrap gap-2">
        {sprints.map((s) => {
          const active = s.id === sprintId
          return (
            <button
              key={s.id}
              type="button"
              onClick={() => setSprintId(s.id)}
              className={`flex cursor-pointer items-center gap-2 rounded-full border px-3 py-1.5 text-sm transition ${active ? 'border-brand/40 bg-brand-bg font-semibold text-brand-deep' : 'border-line bg-card text-txt-mid hover:bg-ink-700'}`}
            >
              {s.name}
              <Pill tone={SPRINT_META[s.status].tone}>{SPRINT_META[s.status].label}</Pill>
            </button>
          )
        })}
      </div>

      {/* 概览条：目标 / 日期 / 故事点 / 燃尽 / 容量 */}
      {sp && (
        <Card className="mb-4 p-4">
          <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-5">
            <div className="col-span-2 xl:col-span-1">
              <div className="text-xs text-txt-low">迭代目标</div>
              <div className="mt-1 line-clamp-2 text-sm font-medium leading-5 text-txt-hi">{sp.goal}</div>
              <div className="mt-1 text-xs text-txt-low">{productById(sp.productId)?.name}</div>
            </div>
            <div>
              <div className="text-xs text-txt-low">迭代日期</div>
              <div className="mt-1 flex items-center gap-1 text-sm text-txt-hi"><CalendarDays size={13} className="text-txt-mid" />{sp.start.slice(5)} ~ {sp.end.slice(5)}</div>
              <div className={`mt-1 text-xs ${remain < 0 ? 'text-txt-low' : remain <= 3 ? 'font-semibold text-cat-pink' : 'text-txt-mid'}`}>
                {remain < 0 ? '已结束' : `剩余 ${remain} 天`}
              </div>
            </div>
            <div>
              <div className="text-xs text-txt-low">故事点进度</div>
              <div className="mt-1 text-sm font-bold tabular-nums text-txt-hi">{donePts} <span className="text-xs font-normal text-txt-low">/ {sp.totalPoints} 点</span></div>
              <Bar value={(donePts / sp.totalPoints) * 100} tone={donePts >= sp.totalPoints ? 'ok' : 'brand'} className="mt-2" />
            </div>
            <div className="flex items-center gap-3">
              <BurnSvg data={sp.burndown} />
              <div>
                <div className="text-xs text-txt-low">燃尽图</div>
                <div className="mt-1 text-xs text-txt-mid">剩余 {sp.burndown[sp.burndown.length - 1] ?? sp.totalPoints} 点</div>
              </div>
            </div>
            <div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-txt-low">迭代容量</span>
                <span className={`font-bold tabular-nums ${over ? 'text-bad' : 'text-txt-hi'}`}>{usedH}h / {sp.capacityHours}h</span>
              </div>
              <Bar value={(usedH / sp.capacityHours) * 100} tone={over ? 'bad' : 'brand'} className="mt-2" />
              {over && (
                <button type="button" onClick={() => nav.go('conflicts')} className="mt-1.5 inline-flex cursor-pointer items-center gap-1 text-xs text-bad hover:underline">
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
                <div key={c.id} className="flex-1 rounded-card bg-card p-2">
                  <div className="flex items-center gap-1.5 px-1 pb-2">
                    <span className="text-xs font-semibold text-txt-hi">{c.label}</span>
                    <span className="rounded-full bg-ink-700 px-1.5 text-[10px] font-bold text-txt-mid">{list.length}</span>
                  </div>
                  <div className="space-y-2">
                    {list.map((w) => <KanCard key={w.id} w={w} sprintEnd={sp?.end} onOpen={() => setDrawerId(w.id)} />)}
                    {list.length === 0 && <div className="py-6 text-center text-[11px] text-txt-low">空</div>}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      ) : (
        <Card className="overflow-x-auto">
          {items.length === 0 ? <Empty text="该迭代暂无工作项" /> : (
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
                    <tr key={w.id} onClick={() => setDrawerId(w.id)} className="cursor-pointer border-b border-line/60 last:border-0 hover:bg-ink-700">
                      <td className="px-3 py-2 font-mono text-xs text-txt-low">{w.key}</td>
                      <td className="max-w-52 truncate px-3 py-2 text-txt-hi">{w.title}</td>
                      <td className="px-3 py-2"><TypeTag w={w} /></td>
                      <td className="px-3 py-2"><PriorityBadge p={w.priority} /></td>
                      <td className="px-3 py-2"><Pill tone={statusTone(w)}>{statusText(w)}</Pill></td>
                      <td className="px-3 py-2"><div className="flex items-center gap-1.5 text-xs text-txt-mid"><Avatar userId={w.assigneeId} size={20} />{userById(w.assigneeId)?.name}</div></td>
                      <td className="px-3 py-2 text-xs text-txt-mid">{sprintById(w.sprintId ?? '')?.name ?? '—'}</td>
                      <td className="px-3 py-2 text-xs text-cat-teal">{releaseById(w.releaseId ?? '')?.name ?? '—'}</td>
                      <td className={`px-3 py-2 text-xs tabular-nums ${overdue ? 'font-semibold text-bad' : 'text-txt-mid'}`}>{w.dueDate ?? '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {drawerId && <WorkDrawer wid={drawerId} nav={nav} onClose={() => setDrawerId(undefined)} />}
      {creating && <CreateModal kind={creating} sprintId={sprintId} onClose={() => setCreating(null)} />}
    </div>
  )
}

// ---------------- 看板卡片 ----------------
function KanCard({ w, sprintEnd, onOpen }: { w: WorkItem; sprintEnd?: string; onOpen: () => void }) {
  const finished = colOf(w) === 'done'
  const nxt = nextStatus(w)
  const overflow = !!w.dueDate && !!sprintEnd && w.dueDate > sprintEnd
  return (
    <div
      className="group relative cursor-pointer rounded-lg border border-line bg-canvas p-2.5 shadow-card hover:border-line-hi"
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
          onClick={(e) => { e.stopPropagation(); setWorkItemStatus(w.id, nxt) }}
          title={`推进到「${nextLabel(nxt, w.type === 'defect')}」`}
          className="absolute right-1.5 bottom-1.5 hidden cursor-pointer items-center gap-0.5 rounded border border-line bg-canvas px-1.5 py-0.5 text-[11px] font-medium text-brand shadow-sm group-hover:inline-flex"
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

/** 本地燃尽 SVG：实际折线 + 理想虚线 */
function BurnSvg({ data }: { data: number[] }) {
  const W = 132
  const H = 44
  const P = 4
  const max = Math.max(...data, 1)
  const x = (i: number) => P + (i * (W - 2 * P)) / Math.max(1, data.length - 1)
  const y = (v: number) => H - P - (v / max) * (H - 2 * P)
  return (
    <svg width={W} height={H} className="shrink-0">
      <line x1={P} y1={y(max)} x2={W - P} y2={y(0)} strokeDasharray="3 3" className="stroke-txt-low/60" strokeWidth="1" />
      <polyline points={data.map((v, i) => `${x(i)},${y(v)}`).join(' ')} fill="none" className="stroke-brand" strokeWidth="1.8" strokeLinejoin="round" strokeLinecap="round" />
      {data.map((v, i) => <circle key={i} cx={x(i)} cy={y(v)} r="1.8" className="fill-brand" />)}
    </svg>
  )
}

// ---------------- 详情抽屉 ----------------
const linkBtn = 'inline-flex max-w-full cursor-pointer items-center gap-1 truncate rounded border border-line bg-canvas px-1.5 py-1 text-[11px] text-brand hover:bg-brand-bg'
const linkBtnRed = 'inline-flex max-w-full cursor-pointer items-center gap-1 truncate rounded border border-bad/30 bg-bad-bg px-1.5 py-1 text-[11px] text-bad-deep hover:bg-bad/15'

function WorkDrawer({ wid, nav, onClose }: { wid: string; nav: PageProps['nav']; onClose: () => void }) {
  const w = workItemById(wid)
  if (!w) return null
  const rm = w.roadmapItemId ? roadmapItems.find((r) => r.id === w.roadmapItemId) : undefined
  const rel = w.releaseId ? releaseById(w.releaseId) : undefined
  const tid = w.topicId
  const fid = w.type === 'defect' ? w.foundInTestTaskId : undefined
  const rtid = w.type === 'defect' ? w.relatedTaskId : undefined
  const brid = w.type === 'defect' ? w.blockedReleaseId : undefined
  const fmid = w.type === 'defect' ? w.fixedInMrId : undefined
  const field = (k: string, v: ReactNode) => (
    <div><div className="text-[11px] text-txt-low">{k}</div><div className="mt-0.5 text-xs text-txt-hi">{v}</div></div>
  )
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

        <div className="mt-3 grid grid-cols-2 gap-3 rounded-card bg-card p-3">
          {field('负责人', <span className="flex items-center gap-1.5"><Avatar userId={w.assigneeId} size={18} />{userById(w.assigneeId)?.name}</span>)}
          {field('优先级', <PriorityBadge p={w.priority} />)}
          {w.type === 'defect' && field('严重度', <Pill tone={severityTone[w.severity]}>{w.severity}</Pill>)}
          {field('产品 / 组件', `${productById(w.productId)?.name ?? '—'} / ${w.componentId ?? '—'}`)}
          {field('迭代', sprintById(w.sprintId ?? '')?.name ?? '—')}
          {field('版本', rel?.name ?? '—')}
          {field('工时 / 故事点', `${w.estimateHours}h / ${w.points} 点`)}
          {field('截止', w.dueDate ?? '—')}
          {field('创建人', userById(w.creatorId)?.name ?? '—')}
          {field('阻塞于', w.blockedByIds.map((b) => workItemById(b)?.key).join('、') || '无')}
        </div>

        {w.description && <p className="mt-3 whitespace-pre-wrap text-xs leading-5 text-txt-mid">{w.description}</p>}
        {w.labels.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1">{w.labels.map((l) => <span key={l} className="rounded bg-ink-700 px-1.5 py-px text-[11px] text-txt-mid">{l}</span>)}</div>
        )}

        <div className="mt-4">
          <div className="mb-1.5 text-[11px] font-semibold text-txt-low">关联链路</div>
          <div className="flex flex-wrap gap-1.5">
            {w.requirementId && (() => {
              const rq = requirementById(w.requirementId)
              return rq ? <button type="button" onClick={() => nav.go('requirements', rq.id)} className={`${linkBtn} !border-cat-purple/40 !text-cat-purple`}>需求 {rq.key}</button> : null
            })()}
            {rm && <button type="button" onClick={() => nav.go('roadmap')} className={linkBtn}>RoadMap · {rm.name}</button>}
            {rm?.goalId && <button type="button" onClick={() => nav.go('goals', rm.goalId)} className={linkBtn}>目标 {goalById(rm.goalId)?.key}</button>}
            {rel && <button type="button" onClick={() => nav.go('delivery', rel.id)} className={linkBtn}>版本 {rel.name}</button>}
            {w.type === 'task' && w.linkedMrIds?.map((mid) => (
              <button key={mid} type="button" onClick={() => nav.go('mr', mid)} className={linkBtn}><GitPullRequest size={11} />MR !{mrById(mid)?.number}</button>
            ))}
            {fid && <button type="button" onClick={() => nav.go('tasks', fid)} className={linkBtn}>发现于 {workItemById(fid)?.key}</button>}
            {rtid && <button type="button" onClick={() => nav.go('tasks', rtid)} className={linkBtn}>关联 {workItemById(rtid)?.key}</button>}
            {brid && <button type="button" onClick={() => nav.go('delivery', brid)} className={linkBtnRed}>阻塞 {releaseById(brid)?.name}</button>}
            {fmid && <button type="button" onClick={() => nav.go('mr', fmid)} className={linkBtn}>修复 !{mrById(fmid)?.number}</button>}
            {!rm && !rel && !fid && !rtid && !brid && !fmid && <span className="text-xs text-txt-low">无关联对象</span>}
          </div>
        </div>

        {tid && (
          <button type="button" onClick={() => nav.go('topics', tid)} className="mt-4 flex cursor-pointer items-center gap-2 rounded-card bg-brand-bg/60 px-3 py-2.5 text-left hover:bg-brand-bg">
            <Hash size={14} className="shrink-0 text-brand" />
            <span className="min-w-0 flex-1 truncate text-xs text-txt-mid">关联话题：{topicById(tid)?.title ?? tid}</span>
            <ArrowRight size={12} className="shrink-0 text-brand" />
          </button>
        )}

        <div className="mt-auto pt-4 text-[11px] text-txt-low">
          创建于 {w.createdAt} · 更新于 {w.updatedAt} · {pageOf(w) === 'defects' ? '缺陷中心可推进缺陷状态流转' : '看板卡片悬停可「→ 推进」'}
        </div>
      </div>
    </div>
  )
}
function pageOf(w: WorkItem): PageId {
  return w.type === 'defect' ? 'defects' : 'tasks'
}

// ---------------- 新建表单 ----------------
const inputCls = 'w-full rounded-input border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none focus:border-brand'

function CreateModal({ kind, sprintId, onClose }: { kind: 'task' | 'defect'; sprintId: string; onClose: () => void }) {
  const [title, setTitle] = useState('')
  const [priority, setPriority] = useState<'P0' | 'P1' | 'P2' | 'P3'>('P1')
  const [assigneeId, setAssigneeId] = useState('u3')
  const [severity, setSeverity] = useState<DefectSeverity>('一般')
  const [sid, setSid] = useState(sprintId)
  const [rid, setRid] = useState(sprintById(sprintId)?.releaseId ?? '')
  const [due, setDue] = useState('')
  const submit = () => {
    if (!title.trim()) return
    createWorkItem({
      type: kind, title: title.trim(), priority, assigneeId,
      severity: kind === 'defect' ? severity : undefined,
      sprintId: sid || undefined, releaseId: rid || undefined, dueDate: due || undefined,
    })
    onClose()
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" onClick={onClose}>
      <div className="w-[430px] rounded-card border border-line bg-canvas p-5 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-bold text-txt-hi">{kind === 'defect' ? '登记缺陷' : '新建任务'}</h3>
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
                {users.map((u) => <option key={u.id} value={u.id}>{u.name} · {u.title}</option>)}
              </select>
            </label>
            {kind === 'defect' && (
              <label className="block text-xs text-txt-mid">
                严重度
                <select value={severity} onChange={(e) => setSeverity(e.target.value as DefectSeverity)} className={`mt-1 ${inputCls}`}>
                  {(['致命', '严重', '一般', '轻微'] as const).map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </label>
            )}
            <label className="block text-xs text-txt-mid">
              迭代
              <select value={sid} onChange={(e) => { setSid(e.target.value); setRid(sprintById(e.target.value)?.releaseId ?? '') }} className={`mt-1 ${inputCls}`}>
                {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              版本
              <select value={rid} onChange={(e) => setRid(e.target.value)} className={`mt-1 ${inputCls}`}>
                <option value="">（不挂版本）</option>
                {releases.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
              </select>
            </label>
            <label className="block text-xs text-txt-mid">
              截止日
              <input type="date" value={due} onChange={(e) => setDue(e.target.value)} className={`mt-1 ${inputCls}`} />
            </label>
          </div>
          {kind === 'defect' && (severity === '致命' || severity === '严重') && (
            <div className="rounded-lg bg-bad-bg px-2.5 py-2 text-[11px] text-bad-deep">致命/严重缺陷将强制自动创建话题并拉入干系人；未关闭时阻塞所属版本发布。</div>
          )}
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <Btn variant="ghost" onClick={onClose}>取消</Btn>
          <Btn variant="primary" onClick={submit} disabled={!title.trim()}>创建</Btn>
        </div>
      </div>
    </div>
  )
}
