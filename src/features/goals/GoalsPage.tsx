// 战略目标 v2（R2 层级源头）：目标卡片栅格 + 详情「五层下钻树」Goal→RoadMap→版本→迭代→工作项 + 目标话题卡
import { useState } from 'react'
import { ArrowLeft, ArrowRight, CalendarDays, ChevronDown, ChevronRight, Hash, Target } from 'lucide-react'
import type { RoadmapItem, Sprint, StrategicGoal, WorkItem } from '../../data/types'
import {
  goals, goalById, goalStatusText, productById, releaseById, roadmapItems, sprints, sprintById,
  topicsOfTarget, useStore, userById, workItemStatusText, workItems,
} from '../../data/store'
import { Avatar, Bar, Btn, Card, CardHeader, Empty, PageHeader, Pill, ProgressRing } from '../../components/ui'
import type { Nav, PageProps } from '../../nav'

const goalTone: Record<StrategicGoal['status'], 'neutral' | 'brand' | 'pink' | 'ok'> = {
  draft: 'neutral', active: 'brand', at_risk: 'pink', achieved: 'ok', archived: 'neutral',
}
const typeLabel = { task: '任务', testtask: '测试', defect: '缺陷' } as const

function typeCls(w: WorkItem): string {
  return w.type === 'defect' ? 'text-cat-orange bg-cat-orange/10' : w.type === 'testtask' ? 'text-cat-teal bg-cat-teal/10' : 'text-cat-blue bg-cat-blue/10'
}
function statusText(w: WorkItem): string {
  return w.type === 'defect' ? w.status : workItemStatusText[w.status]
}
function statusTone(w: WorkItem): 'neutral' | 'info' | 'warn' | 'bad' | 'ok' {
  if (w.type === 'defect') {
    return w.status === '修复中' ? 'info' : w.status === '已修复' ? 'warn' : w.status === '回归通过' ? 'ok' : w.status === '重新打开' ? 'bad' : 'neutral'
  }
  return ({ todo: 'neutral', in_progress: 'info', in_review: 'warn', blocked: 'bad', done: 'ok', closed: 'neutral' } as const)[w.status]
}
/** 迭代聚合进度（按故事点加权，上层进度只读聚合） */
function sprintProgress(sprintId: string): { pct: number; done: number; total: number } {
  const items = workItems.filter((w) => w.sprintId === sprintId)
  const total = items.reduce((s, w) => s + w.points, 0)
  const done = items
    .filter((w) => w.status === 'done' || w.status === 'closed' || (w.type === 'defect' && (w.status === '回归通过' || w.status === '已关闭')))
    .reduce((s, w) => s + w.points, 0)
  return { pct: total ? Math.round((done / total) * 100) : 0, done, total }
}

export default function GoalsPage({ nav, id }: PageProps) {
  useStore()
  const [localSel, setLocalSel] = useState<string | undefined>()
  const [showList, setShowList] = useState(false)
  const selId = id ?? localSel
  const goal = !showList && selId ? goalById(selId) : undefined
  if (goal) return <GoalDetail goal={goal} nav={nav} onBack={() => { setShowList(true); setLocalSel(undefined) }} />
  return <GoalList onOpen={(gid) => { setLocalSel(gid); setShowList(false) }} />
}

// ---------------- 列表：目标卡片栅格 ----------------
function GoalList({ onOpen }: { onOpen: (id: string) => void }) {
  return (
    <div>
      <PageHeader
        title="战略目标"
        desc="L1 目标 · 向下钻取 RoadMap / 版本 / 迭代 / 工作项，上层进度只读聚合"
        actions={<Pill tone="purple"><Target size={12} />{goals.length} 个目标</Pill>}
      />
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        {goals.map((g) => (
          <button
            key={g.id}
            type="button"
            onClick={() => onOpen(g.id)}
            className="cursor-pointer rounded-card border border-line bg-card p-4 text-left shadow-card transition hover:border-line-hi"
          >
            <div className="flex items-start gap-2">
              <span className="mt-0.5 shrink-0 font-mono text-xs font-bold text-cat-purple">{g.key}</span>
              <span className="line-clamp-2 flex-1 text-sm font-semibold leading-5 text-txt-hi">{g.name}</span>
            </div>
            <div className="mt-2.5 flex items-center gap-2">
              <Pill tone="brand">{g.period}</Pill>
              <Pill tone={goalTone[g.status]}>{goalStatusText[g.status]}</Pill>
              <span className="flex-1" />
              <ProgressRing value={g.progress} size={44} stroke={5} color={g.status === 'at_risk' ? 'var(--color-cat-pink)' : 'var(--color-brand)'} />
            </div>
            <div className="mt-2 truncate text-xs text-txt-mid" title={g.targetMetric}>衡量口径：{g.targetMetric ?? '—'}</div>
            <div className="mt-2 flex flex-wrap items-center gap-1">
              {g.productIds.map((pid) => (
                <span key={pid} className="rounded bg-brand-bg px-1.5 py-px text-[11px] font-semibold text-cat-purple">{productById(pid)?.name}</span>
              ))}
            </div>
            <div className="mt-3 flex items-center gap-2 border-t border-line pt-2.5 text-xs text-txt-low">
              <CalendarDays size={12} />{g.deadline ? `截止 ${g.deadline}` : '无截止'}
              <span className="flex-1" />
              <Avatar userId={g.ownerId} size={20} />
            </div>
          </button>
        ))}
      </div>
    </div>
  )
}

// ---------------- 详情：顶部 + 五层下钻树 + 话题卡 ----------------
function GoalDetail({ goal, nav, onBack }: { goal: StrategicGoal; nav: Nav; onBack: () => void }) {
  const [open, setOpen] = useState<Set<string>>(new Set())
  const toggle = (k: string) => setOpen((p) => { const n = new Set(p); if (n.has(k)) n.delete(k); else n.add(k); return n })
  const owner = userById(goal.ownerId)
  const rms = goal.roadmapItemIds.map((rid) => roadmapItems.find((r) => r.id === rid)).filter(Boolean) as RoadmapItem[]
  const chev = (on: boolean) => (on ? <ChevronDown size={14} className="shrink-0 text-txt-low" /> : <ChevronRight size={14} className="shrink-0 text-txt-low" />)

  return (
    <div>
      <button type="button" onClick={onBack} className="mb-3 inline-flex cursor-pointer items-center gap-1 text-sm text-txt-mid hover:text-brand">
        <ArrowLeft size={14} />返回目标列表
      </button>

      {/* 顶部：名称 + 进度环 + 衡量口径 + owner + deadline */}
      <Card className="mb-4 p-5">
        <div className="flex flex-wrap items-center gap-5">
          <ProgressRing value={goal.progress} size={64} stroke={7} color={goal.status === 'at_risk' ? 'var(--color-cat-pink)' : 'var(--color-brand)'} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm font-bold text-cat-purple">{goal.key}</span>
              <h2 className="text-lg font-bold text-txt-hi">{goal.name}</h2>
              <Pill tone={goalTone[goal.status]}>{goalStatusText[goal.status]}</Pill>
            </div>
            <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-txt-mid">
              <span>衡量口径：{goal.targetMetric ?? '—'}</span>
              <span className="flex items-center gap-1"><CalendarDays size={12} />{goal.period} · 截止 {goal.deadline ?? '—'}</span>
              <span className="flex items-center gap-1.5">{owner && <Avatar userId={owner.id} size={18} />}{owner?.name ?? '—'}</span>
              <span className="flex items-center gap-1">
                关联产品：
                {goal.productIds.map((pid) => (
                  <span key={pid} className="rounded bg-brand-bg px-1.5 text-[11px] font-semibold text-cat-purple">{productById(pid)?.name}</span>
                ))}
              </span>
            </div>
          </div>
          <Btn onClick={() => nav.go('roadmap')}>下钻到 RoadMap <ArrowRight size={13} /></Btn>
        </div>
      </Card>

      <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[1fr_280px]">
        {/* 五层下钻树 */}
        <Card>
          <CardHeader title="五层下钻 · Goal → RoadMap → 版本 → 迭代 → 工作项" extra={<span className="text-xs text-txt-low">点击逐级展开 / 收起</span>} />
          <div className="px-2 py-1">
            {rms.length === 0 && <Empty text="该目标暂未关联 RoadMap 条目" />}
            {rms.map((rm) => {
              const rel = rm.releaseId ? releaseById(rm.releaseId) : undefined
              const spIds = [...new Set([...(rel ? sprints.filter((s) => s.releaseId === rel.id).map((s) => s.id) : []), ...rm.sprintIds])]
              const sps = spIds.map((sid) => sprintById(sid)).filter(Boolean) as Sprint[]
              return (
                <div key={rm.id} className="border-b border-line/60 last:border-0">
                  <button type="button" onClick={() => toggle(rm.id)} className="flex w-full cursor-pointer items-center gap-2 px-2 py-2.5 text-left hover:bg-ink-700">
                    {chev(open.has(rm.id))}
                    <span className="min-w-0 flex-1 truncate text-sm font-medium text-txt-hi">{rm.name}</span>
                    {rel && <span className="shrink-0 rounded bg-info-bg px-1.5 text-[11px] font-semibold text-cat-teal">{rel.name}</span>}
                    <span className="w-20 shrink-0"><Bar value={rm.progress} tone={rm.progress >= 80 ? 'ok' : 'brand'} /></span>
                    <span className="w-9 shrink-0 text-right text-xs font-bold tabular-nums text-txt-mid">{rm.progress}%</span>
                    {rm.ownerIds.slice(0, 2).map((oid) => <Avatar key={oid} userId={oid} size={18} />)}
                  </button>
                  {open.has(rm.id) && (
                    <div className="pb-2 pl-6 pr-2">
                      {rel ? (
                        <>
                          <button type="button" onClick={() => toggle(rel.id)} className="flex w-full cursor-pointer items-center gap-2 rounded px-1.5 py-1.5 text-left hover:bg-ink-700">
                            {chev(open.has(rel.id))}
                            <span className="font-mono text-xs font-bold text-cat-teal">{rel.name}</span>
                            <Pill tone={rel.status === 'released' ? 'ok' : rel.status === 'testing' ? 'warn' : 'neutral'}>{{ released: '已发布', testing: '测试中', coding: '编码中', planned: '已规划' }[rel.status]}</Pill>
                            <span className="text-[11px] text-txt-low">计划 {rel.planDate}</span>
                            <span className="flex-1" />
                            <span className="w-16"><Bar value={rel.progress} tone="brand" /></span>
                            <span className="w-9 text-right text-xs tabular-nums text-txt-mid">{rel.progress}%</span>
                          </button>
                          {open.has(rel.id) && (
                            <div className="pl-5">
                              {sps.length === 0 && <div className="py-1 text-xs text-txt-low">该版本暂无迭代</div>}
                              {sps.map((sp) => {
                                const pr = sprintProgress(sp.id)
                                return (
                                  <div key={sp.id}>
                                    <button type="button" onClick={() => toggle(sp.id)} className="flex w-full cursor-pointer items-center gap-2 rounded px-1.5 py-1.5 text-left hover:bg-ink-700">
                                      {chev(open.has(sp.id))}
                                      <span className="min-w-0 flex-1 truncate text-xs font-medium text-txt-hi">{sp.name}</span>
                                      <span className="shrink-0 text-[11px] tabular-nums text-txt-low">{sp.start.slice(5)} ~ {sp.end.slice(5)}</span>
                                      <span className="shrink-0 text-[11px] tabular-nums text-txt-mid">{pr.done}/{pr.total} 点</span>
                                      <span className="w-14 shrink-0"><Bar value={pr.pct} tone={pr.pct >= 100 ? 'ok' : 'brand'} /></span>
                                      <span className="w-8 shrink-0 text-right text-[11px] tabular-nums text-txt-mid">{pr.pct}%</span>
                                    </button>
                                    {open.has(sp.id) && (
                                      <div className="ml-4 space-y-0.5 border-l border-line py-1 pl-3">
                                        {workItems.filter((w) => w.sprintId === sp.id).map((w) => (
                                          <button
                                            key={w.id}
                                            type="button"
                                            onClick={() => nav.go(w.type === 'defect' ? 'defects' : 'tasks', w.id)}
                                            className="flex w-full cursor-pointer items-center gap-2 rounded px-1.5 py-1 text-left hover:bg-ink-700"
                                          >
                                            <span className={`shrink-0 rounded px-1 text-[10px] font-semibold ${typeCls(w)}`}>{typeLabel[w.type]}</span>
                                            <span className="shrink-0 font-mono text-[11px] text-txt-low">{w.key}</span>
                                            <span className="min-w-0 flex-1 truncate text-xs text-txt-hi">{w.title}</span>
                                            <Pill tone={statusTone(w)}>{statusText(w)}</Pill>
                                          </button>
                                        ))}
                                      </div>
                                    )}
                                  </div>
                                )
                              })}
                            </div>
                          )}
                        </>
                      ) : (
                        <div className="py-1 text-xs text-txt-low">该条目未计划归属版本</div>
                      )}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </Card>

        <GoalTopicCard goalId={goal.id} nav={nav} />
      </div>
    </div>
  )
}

// ---------------- 右侧话题卡 ----------------
function GoalTopicCard({ goalId, nav }: { goalId: string; nav: Nav }) {
  const list = topicsOfTarget('goal', goalId)
  return (
    <Card>
      <CardHeader title="目标话题" extra={<Hash size={14} className="text-txt-low" />} />
      {list.length === 0 ? (
        <div className="px-4 py-6 text-xs leading-5 text-txt-low">
          该目标暂无话题。话题会在目标创建或关键事件时自动建立（干系人自动拉入），也可在话题中心手动发起讨论。
        </div>
      ) : (
        list.map((t) => {
          const last = t.messages[t.messages.length - 1]
          return (
            <div key={t.id} className="border-b border-line/60 p-3.5 last:border-0">
              <div className="flex items-start gap-2">
                <span className="min-w-0 flex-1 text-sm font-medium leading-5 text-txt-hi">{t.title}</span>
                {t.autoCreated && <Pill tone="purple">自动</Pill>}
              </div>
              <div className="mt-2.5 flex items-center -space-x-1.5">
                {t.participantIds.slice(0, 5).map((pid) => <Avatar key={pid} userId={pid} size={22} />)}
                {t.participantIds.length > 5 && <span className="ml-2 text-[11px] text-txt-low">+{t.participantIds.length - 5}</span>}
              </div>
              {last && (
                <div className="mt-2 line-clamp-2 text-xs leading-5 text-txt-low">
                  <span className="text-txt-mid">{userById(last.authorId)?.name}：</span>{last.text}
                </div>
              )}
              <button type="button" onClick={() => nav.go('topics', t.id)} className="mt-2.5 inline-flex cursor-pointer items-center gap-1 text-xs font-medium text-brand hover:underline">
                进入话题 <ArrowRight size={11} />
              </button>
            </div>
          )
        })
      )}
    </Card>
  )
}
