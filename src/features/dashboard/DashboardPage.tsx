// 工作台 v2：目标进度 / 我的冲突 / 我的话题 / 待办 / 研发动态
import {
  ArrowRight, CircleDot, GitPullRequest, Hash, Rocket, Target, Workflow,
} from 'lucide-react'
import type { ActivityEvent } from '../../data/types'
import {
  activities, computeConflicts, CURRENT_USER_ID, goals, mergeRequests, releases,
  sprints, topics, useStore, userById, workItems,
} from '../../data/store'
import { Avatar, Badge, Bar, Card, CardHeader, PageHeader, Pill } from '../../components/ui'
import type { Nav } from '../../nav'

const activityIcon: Record<ActivityEvent['type'], typeof Target> = {
  commit: Workflow, mr: GitPullRequest, workitem: CircleDot, pipeline: Workflow,
  release: Rocket, deploy: Rocket, topic: Hash, conflict: Target,
}

function greeting(): string {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
}

export default function DashboardPage({ nav }: { nav: Nav }) {
  useStore()
  const me = userById(CURRENT_USER_ID)!
  const activeSprint = sprints.find((s) => s.status === 'active')
  const conflicts = computeConflicts()
  const myConflicts = conflicts.filter((c) => c.severity === 'red' && (c.subjectId === CURRENT_USER_ID || c.relatedTaskIds.some((id) => workItems.find((w) => w.id === id)?.assigneeId === CURRENT_USER_ID)))
  const myWorkItems = workItems.filter((w) => w.assigneeId === CURRENT_USER_ID && w.status !== 'done' && w.status !== 'closed')
  const myReviews = mergeRequests.filter((m) => m.status === 'open' && m.reviewers.some((r) => r.userId === CURRENT_USER_ID && r.state === 'pending'))
  const myTopics = topics.filter((t) => t.status === 'active' && t.participantIds.includes(CURRENT_USER_ID))
  const blockedRelease = releases.find((r) => r.blocked)
  const sprintCapacityUsed = activeSprint
    ? workItems.filter((w) => w.sprintId === activeSprint.id && w.status !== 'done' && w.status !== 'closed').reduce((s, w) => s + w.estimateHours, 0)
    : 0

  return (
    <div>
      <PageHeader
        title={`${greeting()}，${me.name}`}
        desc={`${new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })} · ${activeSprint ? `当前迭代「${activeSprint.name}」· 剩余 ${Math.max(0, Math.ceil((new Date(activeSprint.end).getTime() - Date.now()) / 86400000))} 天` : '当前无活跃迭代'}`}
      />

      {/* 指标卡 */}
      <div className="mb-6 grid grid-cols-2 gap-4 xl:grid-cols-4">
        <Card className="p-4">
          <div className="flex items-center justify-between text-txt-mid"><span className="text-sm">战略目标进度</span><Target size={15} /></div>
          <div className="mt-3 space-y-2.5">
            {goals.map((g) => (
              <button key={g.id} type="button" onClick={() => nav.go('goals', g.id)} className="flex w-full cursor-pointer items-center gap-2 text-left">
                <span className="min-w-0 flex-1 truncate text-xs text-txt-mid hover:text-brand">{g.key} {g.name}</span>
                <Bar value={g.progress} tone={g.status === 'at_risk' ? 'warn' : 'brand'} className="w-16" />
                <span className="w-8 text-right text-xs font-bold tabular-nums text-txt-hi">{g.progress}%</span>
              </button>
            ))}
          </div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between text-txt-mid"><span className="text-sm">我的红色冲突</span><AlertIcon /></div>
          <div className="mt-2 text-2xl font-bold tabular-nums text-bad">{myConflicts.length}</div>
          <div className="mt-1 truncate text-xs text-txt-low">{myConflicts[0]?.detail ?? '暂无红色冲突'}</div>
          <button type="button" onClick={() => nav.go('conflicts')} className="mt-2 flex cursor-pointer items-center gap-1 text-xs text-brand hover:underline">冲突中心 <ArrowRight size={11} /></button>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between text-txt-mid"><span className="text-sm">迭代容量 · {activeSprint?.name ?? '—'}</span><Workflow size={15} /></div>
          <div className="mt-2 text-2xl font-bold tabular-nums text-txt-hi">{activeSprint ? Math.round((sprintCapacityUsed / activeSprint.capacityHours) * 100) : 0}%</div>
          <Bar value={activeSprint ? (sprintCapacityUsed / activeSprint.capacityHours) * 100 : 0} tone={sprintCapacityUsed > (activeSprint?.capacityHours ?? 0) ? 'bad' : 'brand'} className="mt-3" />
          <div className="mt-2 text-xs text-txt-low">已分配 {sprintCapacityUsed}h / 容量 {activeSprint?.capacityHours ?? 0}h</div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between text-txt-mid"><span className="text-sm">发布门禁</span><Rocket size={15} /></div>
          {blockedRelease ? (
            <>
              <div className="mt-2 flex items-center gap-2"><Pill tone="bad">已阻塞</Pill><span className="font-mono text-sm font-bold text-txt-hi">{blockedRelease.name}</span></div>
              <div className="mt-1.5 text-xs text-txt-low">未关闭阻塞缺陷 {blockedRelease.blockedDefectIds.length} 个 · 计划 {blockedRelease.planDate.slice(5)}</div>
            </>
          ) : (
            <div className="mt-2"><Pill tone="ok">畅通</Pill></div>
          )}
          <button type="button" onClick={() => nav.go('delivery')} className="mt-2 flex cursor-pointer items-center gap-1 text-xs text-brand hover:underline">版本与发布 <ArrowRight size={11} /></button>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title="我的待办" extra={<Badge tone="brand">{myWorkItems.length + myReviews.length} 项</Badge>} />
          <ul className="divide-y divide-line">
            {myWorkItems.map((w) => (
              <li key={w.id}>
                <button type="button" onClick={() => nav.go(w.type === 'defect' ? 'defects' : 'tasks', w.id)} className="flex w-full cursor-pointer items-center gap-3 px-4 py-2.5 text-left hover:bg-ink-700">
                  {w.type === 'defect'
                    ? <CircleDot size={15} className="shrink-0 text-cat-orange" />
                    : w.type === 'testtask'
                      ? <Workflow size={15} className="shrink-0 text-cat-teal" />
                      : <CircleDot size={15} className="shrink-0 text-cat-blue" />}
                  <span className="min-w-0 flex-1 truncate text-sm text-txt-hi"><span className="mr-1.5 font-mono text-txt-mid">{w.key}</span>{w.title}</span>
                  {w.dueDate && <span className="shrink-0 text-xs tabular-nums text-txt-low">截止 {w.dueDate.slice(5)}</span>}
                  <Pill tone={w.priority === 'P0' ? 'bad' : w.priority === 'P1' ? 'warn' : 'neutral'}>{w.priority}</Pill>
                </button>
              </li>
            ))}
            {myReviews.map((m) => (
              <li key={m.id}>
                <button type="button" onClick={() => nav.go('mr', m.id)} className="flex w-full cursor-pointer items-center gap-3 px-4 py-2.5 text-left hover:bg-ink-700">
                  <GitPullRequest size={15} className="shrink-0 text-brand" />
                  <span className="min-w-0 flex-1 truncate text-sm text-txt-hi"><span className="mr-1.5 font-mono text-txt-mid">!{m.number}</span>{m.title}</span>
                  <Pill tone={m.unitTestCheck.gatePassed ? 'ok' : 'bad'}>单测 {m.unitTestCheck.gatePassed ? '通过' : '未过'}</Pill>
                  <Pill tone="brand">评审</Pill>
                </button>
              </li>
            ))}
            {myWorkItems.length + myReviews.length === 0 && <li className="px-4 py-8 text-center text-sm text-txt-low">全部处理完毕 🎉</li>}
          </ul>
        </Card>

        <Card>
          <CardHeader title="我参与的话题" extra={<Pill tone="purple">{myTopics.length} 个进行中</Pill>} />
          <ul className="divide-y divide-line">
            {myTopics.slice(0, 6).map((t) => {
              const last = t.messages[t.messages.length - 1]
              return (
                <li key={t.id}>
                  <button type="button" onClick={() => nav.go('topics', t.id)} className="w-full cursor-pointer px-4 py-2.5 text-left hover:bg-ink-700">
                    <div className="flex items-center gap-2">
                      <span className="min-w-0 flex-1 truncate text-sm font-medium text-txt-hi">{t.title}</span>
                      {t.autoCreated && <Pill tone="purple">自动</Pill>}
                    </div>
                    {last && <div className="mt-1 flex items-center gap-1.5 text-xs text-txt-low"><Avatar userId={last.authorId} size={16} /><span className="truncate">{last.text}</span></div>}
                  </button>
                </li>
              )
            })}
            {myTopics.length === 0 && <li className="px-4 py-8 text-center text-sm text-txt-low">暂无进行中的话题</li>}
          </ul>
        </Card>
      </div>

      <Card className="mt-4">
        <CardHeader title="研发动态" extra={<span className="text-xs text-txt-low">点击条目可跳转</span>} />
        <ul className="divide-y divide-line">
          {activities.slice(0, 8).map((a) => {
            const Icon = activityIcon[a.type]
            return (
              <li key={a.id}>
                <button
                  type="button"
                  onClick={() => a.target && nav.go(a.target.page as never, a.target.id)}
                  className="flex w-full cursor-pointer items-center gap-3 px-4 py-2.5 text-left hover:bg-ink-700"
                >
                  <Icon size={15} className={`shrink-0 ${a.type === 'conflict' ? 'text-bad' : a.type === 'workitem' ? 'text-cat-blue' : 'text-txt-mid'}`} />
                  <span className="min-w-0 flex-1 truncate text-sm text-txt-mid">{a.text}</span>
                  {a.actorId !== 'system' && <Avatar userId={a.actorId} size={20} />}
                  <span className="w-16 shrink-0 text-right text-xs tabular-nums text-txt-low">{a.at}</span>
                </button>
              </li>
            )
          })}
        </ul>
      </Card>
    </div>
  )
}

function AlertIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="text-bad"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 20h16a2 2 0 0 0 1.73-2" /><path d="M12 9v4" /><path d="M12 17h.01" /></svg>
}
