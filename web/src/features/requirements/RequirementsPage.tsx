// 需求管理：需求池（列表/看板）+ 评审流程（提交→通过/驳回→受理→排期）+ 目标/版本/迭代/任务 关联链路
import { useMemo, useState } from 'react'
import { ArrowRight, Check, FileText, Hash, Plus, X } from 'lucide-react'
import type { PageProps } from '../../nav'
import type { ReqStatus, Requirement } from '../../data/types'
import {
  createRequirement, CURRENT_USER_ID, goals, linkTaskToRequirement,
  releaseById, releases, requirements, reviewRequirement, roadmapItems, scheduleRequirement,
  setRequirementStatus, sprints, submitRequirement, tasks, topicById, useStore, userById,
  users,
} from '../../data/store'
import { Avatar, Bar, Btn, Card, Empty, PageHeader, Pill, PriorityBadge } from '../../components/ui'

const statusText: Record<ReqStatus, string> = {
  draft: '草稿', pending_review: '待评审', accepted: '已受理', in_dev: '开发中',
  delivered: '已交付', closed: '已验收', rejected: '已拒绝',
}
const statusTone: Record<ReqStatus, 'neutral' | 'warn' | 'brand' | 'info' | 'ok' | 'bad'> = {
  draft: 'neutral', pending_review: 'warn', accepted: 'brand', in_dev: 'info',
  delivered: 'ok', closed: 'ok', rejected: 'bad',
}
const FLOW: ReqStatus[] = ['draft', 'pending_review', 'accepted', 'in_dev', 'delivered', 'closed']

const inputCls = 'w-full rounded-input border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none placeholder:text-txt-low/70 focus:border-brand'

export default function RequirementsPage({ nav, id }: PageProps) {
  useStore()
  const [view, setView] = useState<'list' | 'board'>('list')
  const [openId, setOpenId] = useState<string | undefined>(id)
  const [showNew, setShowNew] = useState(false)
  const [fStatus, setFStatus] = useState<'all' | ReqStatus>('all')
  const [fProduct, setFProduct] = useState<'all' | string>('all')

  const list = useMemo(() => requirements
    .filter((r) => (fStatus === 'all' || r.status === fStatus) && (fProduct === 'all' || r.productId === fProduct))
    .slice()
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
  [fStatus, fProduct])

  const open = (rid: string) => setOpenId(rid)
  const rq = openId ? requirements.find((r) => r.id === openId) : undefined

  return (
    <div>
      <PageHeader
        title="需求管理"
        desc="层级：战略目标（为什么做） > 需求（做什么） > 版本/迭代（何时交付） > 任务（怎么执行） · 需求评审通过后方可排期"
        actions={<Btn variant="primary" onClick={() => setShowNew(true)}><Plus size={14} /> 新建需求</Btn>}
      />

      {/* 统计条 */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {FLOW.map((s) => {
          const n = requirements.filter((r) => r.status === s).length
          return (
            <button key={s} type="button" onClick={() => setFStatus(fStatus === s ? 'all' : s)}
              className={`flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1 text-xs transition-colors ${fStatus === s ? 'border-brand bg-brand-bg text-brand-deep' : 'border-line bg-card text-txt-mid hover:border-line-hi'}`}>
              {statusText[s]} <span className="font-bold tabular-nums">{n}</span>
            </button>
          )
        })}
        <span className="flex-1" />
        <select value={fProduct} onChange={(e) => setFProduct(e.target.value)} className="rounded-input border border-line bg-card px-2 py-1 text-xs text-txt-mid">
          <option value="all">全部产品</option>
          <option value="p1">TeamOne 平台</option>
          <option value="p2">TeamOne 桌面端</option>
        </select>
        <div className="flex items-center rounded-full border border-line bg-card p-1">
          {([['list', '列表'], ['board', '看板']] as const).map(([k, label]) => (
            <button key={k} type="button" onClick={() => setView(k)}
              className={`cursor-pointer rounded-full px-3 py-1 text-xs font-semibold ${view === k ? 'bg-brand text-white' : 'text-txt-mid hover:text-txt-hi'}`}>{label}</button>
          ))}
        </div>
      </div>

      {view === 'list' ? (
        <Card className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-txt-low">
                {['Key', '标题', '状态', '优先级', '需求负责人', '交付版本', '战略目标', '拆解任务', '更新'].map((h) => <th key={h} className="px-3 py-2.5 font-medium">{h}</th>)}
              </tr>
            </thead>
            <tbody>
              {list.map((r) => {
                const linked = tasks.filter((t) => t.requirementId === r.id)
                const goal = r.goalId ? goals.find((g) => g.id === r.goalId) : undefined
                return (
                  <tr key={r.id} className="cursor-pointer border-b border-line/60 last:border-0 hover:bg-ink-700" onClick={() => open(r.id)}>
                    <td className="px-3 py-2.5 font-mono text-xs font-bold text-cat-purple">{r.key}</td>
                    <td className="max-w-64 truncate px-3 py-2.5 text-txt-hi">{r.title}</td>
                    <td className="px-3 py-2.5"><Pill tone={statusTone[r.status]}>{statusText[r.status]}</Pill></td>
                    <td className="px-3 py-2.5"><PriorityBadge p={r.priority} /></td>
                    <td className="px-3 py-2.5"><div className="flex items-center gap-1.5"><Avatar userId={r.ownerId} size={20} /><span className="text-xs text-txt-mid">{userById(r.ownerId)?.name}</span></div></td>
                    <td className="px-3 py-2.5 font-mono text-xs text-cat-teal">{r.releaseId ? releaseById(r.releaseId)?.name : '—'}</td>
                    <td className="px-3 py-2.5 font-mono text-xs text-cat-purple">{goal?.key ?? '—'}</td>
                    <td className="px-3 py-2.5 text-xs tabular-nums text-txt-mid">{linked.filter((t) => t.status === 'done' || t.status === 'closed').length}/{linked.length}</td>
                    <td className="px-3 py-2.5 text-xs tabular-nums text-txt-low">{r.updatedAt}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {list.length === 0 && <Empty text="没有符合条件的需求" />}
        </Card>
      ) : (
        <div className="grid grid-cols-4 gap-3 xl:grid-cols-7">
          {FLOW.map((s) => {
            const col = list.filter((r) => r.status === s)
            return (
              <div key={s} className="rounded-card border border-line bg-card p-2">
                <div className="mb-2 flex items-center justify-between px-1">
                  <span className="text-xs font-semibold text-txt-hi">{statusText[s]}</span>
                  <span className="text-xs tabular-nums text-txt-low">{col.length}</span>
                </div>
                <div className="space-y-2">
                  {col.map((r) => (
                    <button key={r.id} type="button" onClick={() => open(r.id)} className="w-full cursor-pointer rounded-lg border border-line bg-canvas p-2 text-left hover:border-brand">
                      <div className="flex items-center gap-1.5">
                        <span className="font-mono text-[10px] font-bold text-cat-purple">{r.key}</span>
                        <span className="flex-1" />
                        <PriorityBadge p={r.priority} />
                      </div>
                      <div className="mt-1 line-clamp-2 text-xs leading-4 text-txt-hi">{r.title}</div>
                      <div className="mt-1.5 flex items-center gap-1">
                        <Avatar userId={r.ownerId} size={16} />
                        <span className="flex-1" />
                        <span className="font-mono text-[10px] text-cat-teal">{r.releaseId ? releaseById(r.releaseId)?.name : ''}</span>
                      </div>
                    </button>
                  ))}
                  {col.length === 0 && <div className="py-4 text-center text-[11px] text-txt-low">空</div>}
                </div>
              </div>
            )
          })}
        </div>
      )}

      {/* 过滤条件为空时的兜底 */}
      {view === 'list' && fProduct !== 'all' && list.length === 0 && (
        <div className="mt-2 text-xs text-txt-low">当前产品暂无需求</div>
      )}

      {rq && <ReqDrawer rq={rq} nav={nav} onClose={() => setOpenId(undefined)} />}
      {showNew && <NewReqModal onClose={() => setShowNew(false)} onDone={() => setShowNew(false)} />}
    </div>
  )
}

// ==================== 详情抽屉：链路 + 评审流程 + 拆解任务 ====================
function ReqDrawer({ rq, nav, onClose }: { rq: Requirement; nav: PageProps['nav']; onClose: () => void }) {
  useStore()
  const meIsReviewer = rq.reviewerIds.includes(CURRENT_USER_ID)
  const myReview = rq.reviews.find((r) => r.userId === CURRENT_USER_ID)
  const canVote = rq.status === 'pending_review' && meIsReviewer && !myReview
  const [rejecting, setRejecting] = useState(false)
  const [comment, setComment] = useState('')
  const linked = tasks.filter((t) => t.requirementId === rq.id)
  const doneCount = linked.filter((t) => t.status === 'done' || t.status === 'closed').length
  const goal = rq.goalId ? goals.find((g) => g.id === rq.goalId) : undefined
  const rm = rq.roadmapItemId ? roadmapItems.find((x) => x.id === rq.roadmapItemId) : undefined
  const rel = rq.releaseId ? releaseById(rq.releaseId) : undefined
  const sp = rq.sprintId ? sprints.find((x) => x.id === rq.sprintId) : undefined
  const topic = rq.topicId ? topicById(rq.topicId) : undefined
  const [schedRel, setSchedRel] = useState(rq.releaseId ?? '')
  const [schedRm, setSchedRm] = useState(rq.roadmapItemId ?? '')
  const [schedSp, setSchedSp] = useState(rq.sprintId ?? '')

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-txt-hi/25" onClick={onClose} />
      <aside className="relative flex h-full w-[480px] max-w-full flex-col overflow-y-auto border-l border-line bg-card p-5">
        <div className="flex items-start justify-between gap-2">
          <div>
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs font-bold text-cat-purple">{rq.key}</span>
              <Pill tone={statusTone[rq.status]}>{statusText[rq.status]}</Pill>
              <PriorityBadge p={rq.priority} />
            </div>
            <h2 className="mt-1.5 text-base font-bold leading-6 text-txt-hi">{rq.title}</h2>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"><X size={16} /></button>
        </div>

        {/* 描述 */}
        <div className="mt-3 rounded-lg bg-canvas p-3 text-xs leading-5 whitespace-pre-wrap text-txt-mid">{rq.description || '（无描述）'}</div>

        {/* 元信息 */}
        <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-txt-mid">
          <div className="flex items-center gap-1.5">提出人 <Avatar userId={rq.proposerId} size={18} /> {userById(rq.proposerId)?.name}</div>
          <div className="flex items-center gap-1.5">负责人 <Avatar userId={rq.ownerId} size={18} /> {userById(rq.ownerId)?.name}</div>
          <div>粗估 {rq.estimatePoints ?? '—'} 点</div>
          <div>创建 {rq.createdAt}</div>
        </div>

        {/* 关联链路：目标 → RoadMap → 版本 → 迭代 */}
        <div className="mt-4">
          <div className="mb-1.5 text-[11px] font-semibold tracking-wide text-txt-low">关联链路（目标 → 条目 → 版本 → 迭代）</div>
          <div className="space-y-1">
            {goal
              ? <LinkRow label={`战略目标 ${goal.key} · ${goal.name}`} onClick={() => nav.go('goals', goal.id)} tone="text-cat-purple" />
              : <div className="text-xs text-txt-low">未关联战略目标</div>}
            {rm && <LinkRow label={`RoadMap 条目 · ${rm.name}`} onClick={() => nav.go('roadmap')} tone="text-brand" />}
            {rel && <LinkRow label={`交付版本 ${rel.name} · 计划 ${rel.planDate.slice(5)}`} onClick={() => nav.go('delivery', rel.id)} tone="text-cat-teal" />}
            {sp && <LinkRow label={`迭代 ${sp.name}`} onClick={() => nav.go('tasks', sp.id)} tone="text-info" />}
          </div>
        </div>

        {/* 评审区 */}
        <div className="mt-4 rounded-card border border-line bg-canvas p-3">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-xs font-semibold text-txt-hi">评审流程</span>
            <span className="text-[11px] text-txt-low">评审人 {rq.reviewerIds.length} 人 · 已收 {rq.reviews.length} 份</span>
          </div>
          <div className="space-y-1.5">
            {rq.reviews.length === 0 && <div className="text-xs text-txt-low">尚未收到评审意见</div>}
            {rq.reviews.map((rv, i) => (
              <div key={i} className="flex items-start gap-2 rounded-lg bg-card p-2 text-xs">
                <Avatar userId={rv.userId} size={20} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <span className="font-semibold text-txt-hi">{userById(rv.userId)?.name}</span>
                    <Pill tone={rv.result === 'approved' ? 'ok' : 'bad'}>{rv.result === 'approved' ? '通过' : '驳回'}</Pill>
                    <span className="ml-auto text-txt-low tabular-nums">{rv.at}</span>
                  </div>
                  <div className="mt-0.5 text-txt-mid">{rv.comment}</div>
                </div>
              </div>
            ))}
          </div>

          {/* 动作区（按状态） */}
          <div className="mt-3 border-t border-line pt-3">
            {(rq.status === 'draft' || rq.status === 'rejected') && (
              <div>
                {rq.status === 'rejected' && <div className="mb-2 text-[11px] text-bad-deep">已被驳回，可补充材料后重新提交评审。</div>}
                <Btn variant="primary" onClick={() => submitRequirement(rq.id)}><Check size={13} /> 提交评审</Btn>
                <span className="ml-2 text-[11px] text-txt-low">提交后自动创建话题并拉入干系人</span>
              </div>
            )}
            {rq.status === 'pending_review' && (
              canVote ? (
                <div>
                  {!rejecting ? (
                    <div className="flex gap-2">
                      <Btn variant="primary" onClick={() => reviewRequirement(rq.id, 'approved', '同意，按计划排期。')}>通过</Btn>
                      <Btn variant="danger" onClick={() => setRejecting(true)}>驳回</Btn>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      <textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={2} placeholder="请填写驳回意见（必填）" className={inputCls} />
                      <div className="flex gap-2">
                        <Btn variant="danger" disabled={!comment.trim()} onClick={() => { reviewRequirement(rq.id, 'rejected', comment); setRejecting(false); setComment('') }}>确认驳回</Btn>
                        <Btn variant="ghost" onClick={() => setRejecting(false)}>取消</Btn>
                      </div>
                    </div>
                  )}
                </div>
              ) : (
                <div className="text-xs text-txt-low">评审进行中：等待 {rq.reviewerIds.filter((u) => !rq.reviews.some((r) => r.userId === u)).map((u) => userById(u)?.name).join('、') || '—'} 提交意见；全部通过后自动受理。</div>
              )
            )}
            {rq.status === 'accepted' && (
              <div className="space-y-2">
                <div className="text-xs text-txt-low">已受理，选择排期（RoadMap 条目 / 版本 / 迭代）：</div>
                <div className="grid grid-cols-3 gap-2">
                  <select value={schedRm} onChange={(e) => setSchedRm(e.target.value)} className={inputCls}>
                    <option value="">RoadMap…</option>
                    {roadmapItems.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                  </select>
                  <select value={schedRel} onChange={(e) => setSchedRel(e.target.value)} className={inputCls}>
                    <option value="">版本…</option>
                    {releases.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                  </select>
                  <select value={schedSp} onChange={(e) => setSchedSp(e.target.value)} className={inputCls}>
                    <option value="">迭代…</option>
                    {sprints.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                  </select>
                </div>
                <Btn variant="primary" onClick={() => scheduleRequirement(rq.id, { roadmapItemId: schedRm || undefined, releaseId: schedRel || undefined, sprintId: schedSp || undefined })}>确认排期</Btn>
              </div>
            )}
            {rq.status === 'in_dev' && (
              <Btn onClick={() => setRequirementStatus(rq.id, 'delivered')}>标记已交付（随版本发布）</Btn>
            )}
            {rq.status === 'delivered' && (
              <Btn variant="primary" onClick={() => setRequirementStatus(rq.id, 'closed')}>验收通过，关闭需求</Btn>
            )}
            {(rq.status === 'closed' || rq.status === 'delivered') && (
              <div className="text-xs text-txt-low">{rq.status === 'closed' ? '需求已完成闭环（评审 → 排期 → 交付 → 验收）。' : '已随版本交付，待产品验收。'}</div>
            )}
          </div>
        </div>

        {/* 拆解任务 */}
        <div className="mt-4">
          <div className="mb-1.5 flex items-center justify-between">
            <span className="text-[11px] font-semibold tracking-wide text-txt-low">拆解任务（{doneCount}/{linked.length} 完成）</span>
            <Bar value={linked.length ? (doneCount / linked.length) * 100 : 0} tone="ok" className="w-24" />
          </div>
          <div className="space-y-1">
            {linked.map((t) => (
              <button key={t.id} type="button" onClick={() => nav.go('tasks', t.id)} className="flex w-full cursor-pointer items-center gap-2 rounded-lg border border-line bg-canvas px-2 py-1.5 text-left text-xs hover:border-brand">
                <span className="font-mono font-bold text-txt-mid">{t.key}</span>
                <span className="min-w-0 flex-1 truncate text-txt-hi">{t.title}</span>
                <Pill tone={t.status === 'done' || t.status === 'closed' ? 'ok' : t.status === 'in_progress' ? 'info' : t.status === 'in_review' ? 'warn' : 'neutral'}>{t.status === 'done' ? '完成' : t.status === 'closed' ? '关闭' : t.status === 'in_progress' ? '进行中' : t.status === 'in_review' ? '待验收' : '待处理'}</Pill>
              </button>
            ))}
            {linked.length === 0 && <div className="text-xs text-txt-low">尚未拆解任务。受理后可在「迭代与任务」中创建任务并关联本需求。</div>}
          </div>
          {/* 挂载未关联任务 */}
          <TaskLinker rqId={rq.id} excludeIds={linked.map((t) => t.id)} />
        </div>

        {/* 话题入口 */}
        <div className="mt-4 border-t border-line pt-3">
          {topic ? (
            <button type="button" onClick={() => nav.go('topics', topic.id)} className="flex w-full cursor-pointer items-center gap-2 rounded-lg border border-line bg-canvas px-2.5 py-2 text-left text-xs text-brand hover:bg-brand-bg">
              <Hash size={13} /> 进入需求话题「{topic.title}」<ArrowRight size={11} className="ml-auto shrink-0" />
            </button>
          ) : (
            <div className="text-xs text-txt-low">提交评审后将自动创建需求话题（按干系人规则拉人）。</div>
          )}
        </div>
      </aside>
    </div>
  )
}

function TaskLinker({ rqId, excludeIds }: { rqId: string; excludeIds: string[] }) {
  const candidates = tasks.filter((t) => !t.requirementId && !excludeIds.includes(t.id))
  const [val, setVal] = useState('')
  if (candidates.length === 0) return null
  return (
    <div className="mt-2 flex items-center gap-2">
      <select value={val} onChange={(e) => setVal(e.target.value)} className={inputCls}>
        <option value="">挂载已有任务…</option>
        {candidates.map((t) => <option key={t.id} value={t.id}>{t.key} {t.title}</option>)}
      </select>
      <Btn variant="ghost" disabled={!val} onClick={() => { linkTaskToRequirement(val, rqId); setVal('') }}>挂载</Btn>
    </div>
  )
}

// ==================== 新建需求 ====================
function NewReqModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [title, setTitle] = useState('')
  const [desc, setDesc] = useState('')
  const [priority, setPriority] = useState<'P0' | 'P1' | 'P2' | 'P3'>('P2')
  const [goalId, setGoalId] = useState('')
  const [points, setPoints] = useState('5')
  const [reviewers, setReviewers] = useState<string[]>(['u6', 'u1'])
  const [err, setErr] = useState('')
  const toggleReviewer = (uid: string) => setReviewers((p) => (p.includes(uid) ? p.filter((x) => x !== uid) : [...p, uid]))
  const submit = () => {
    if (!title.trim()) { setErr('标题必填'); return }
    if (reviewers.length === 0) { setErr('至少选择 1 名评审人'); return }
    createRequirement({ title: title.trim(), description: desc, priority, reviewerIds: reviewers, goalId: goalId || undefined, estimatePoints: Number(points) || undefined })
    onDone()
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-txt-hi/25" onClick={onClose} />
      <div className="relative w-[480px] max-w-full rounded-card border border-line bg-canvas p-5 shadow-xl">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="flex items-center gap-1.5 text-base font-bold text-txt-hi"><FileText size={16} className="text-cat-purple" /> 新建需求（草稿）</h3>
          <button type="button" onClick={onClose} className="cursor-pointer rounded-md p-1 text-txt-mid hover:bg-ink-700 hover:text-txt-hi"><X size={16} /></button>
        </div>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-txt-mid">标题（必填）</label>
            <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="一句话说清需求" className={inputCls} />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-txt-mid">背景 / 价值 / 验收标准</label>
            <textarea value={desc} onChange={(e) => setDesc(e.target.value)} rows={3} placeholder={'背景：…\n价值：…\n验收标准：…'} className={inputCls} />
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="mb-1 block text-xs font-medium text-txt-mid">优先级</label>
              <select value={priority} onChange={(e) => setPriority(e.target.value as never)} className={inputCls}>
                {['P0', 'P1', 'P2', 'P3'].map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-txt-mid">关联目标</label>
              <select value={goalId} onChange={(e) => setGoalId(e.target.value)} className={inputCls}>
                <option value="">暂不关联</option>
                {goals.map((g) => <option key={g.id} value={g.id}>{g.key} {g.name}</option>)}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-txt-mid">粗估（点）</label>
              <input value={points} onChange={(e) => setPoints(e.target.value)} className={inputCls} />
            </div>
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-txt-mid">评审人（多选，全部通过后受理）</label>
            <div className="flex flex-wrap gap-1.5">
              {users.filter((u) => ['u6', 'u1', 'u3', 'u5', 'u4'].includes(u.id)).map((u) => (
                <button key={u.id} type="button" onClick={() => toggleReviewer(u.id)}
                  className={`flex cursor-pointer items-center gap-1 rounded-full border px-2 py-0.5 text-xs ${reviewers.includes(u.id) ? 'border-brand bg-brand-bg text-brand-deep' : 'border-line bg-card text-txt-mid hover:border-line-hi'}`}>
                  <Avatar userId={u.id} size={14} /> {u.name}
                </button>
              ))}
            </div>
          </div>
          {err && <div className="text-xs text-bad-deep">{err}</div>}
          <p className="text-[11px] text-txt-low">创建后为草稿，提交评审通过并受理后，才能排入 RoadMap 条目与交付版本。</p>
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <Btn variant="ghost" onClick={onClose}>取消</Btn>
          <Btn variant="primary" onClick={submit}><Plus size={13} /> 创建</Btn>
        </div>
      </div>
    </div>
  )
}

function LinkRow({ label, onClick, tone }: { label: string; onClick: () => void; tone: string }) {
  return (
    <button type="button" onClick={onClick} className="flex w-full cursor-pointer items-center gap-1.5 rounded-lg border border-line bg-canvas px-2 py-1.5 text-left text-xs hover:border-brand">
      <span className={`min-w-0 flex-1 truncate font-medium ${tone}`}>{label}</span>
      <ArrowRight size={10} className="shrink-0 text-txt-low" />
    </button>
  )
}
