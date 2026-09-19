// 需求管理：需求池（列表/看板）+ 评审流程（提交→逐人会签→受理→排期）+ 目标/版本/迭代/任务 关联链路
// R-6（B4 批 · docs/v2/12 §1 R-6 / 裁决 D2）：评审流全量真实 API——
//   * 列表 = GET /work-items?type=requirement 真实行（dogfooding 切换：store 原型行兜底已移除）；
//   * 评审人：提交评审时多选（POST /submit reviewerIds，round+1 驳回重提）；
//   * 评审记录：GET /work-items/{key}/review-rounds 按轮次分组（round/评审人/结果/意见/时间/结论），无 store 兜底；
//   * 纪要：platform.file 两步制上传（POST/PUT /review-rounds/{round}/minutes），查看走既有下载通道。
import { Fragment, useMemo, useRef, useState } from 'react'
import { ArrowRight, Check, ChevronDown, ChevronRight, Eye, FileCode, FileText, Hash, Paperclip, Plus, Upload, X } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import type { PageProps } from '../../nav'
import type { ReqStatus, Requirement } from '../../data/types'
import {
  filesApi, parseFieldError, remoteToRequirement, requirementsApi,
  useConversations, useGoals, useProducts, useRequirementReviewRounds, useRequirementReviews,
  useRequirements, useReleases, useRoadmapItems, useSprints, useWorkItems, workItemsApi,
} from '../../api/queries'
import type { RemoteRequirementRound } from '../../api/queries'
import { useAuth } from '../../api/AuthContext'
import { toBrief, useUserBriefs } from '../../api/users'
import { Avatar, Bar, Btn, Card, Empty, PageHeader, Pill, PriorityBadge, Spinner } from '../../components/ui'

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
  const [view, setView] = useState<'list' | 'board'>('list')
  const [openId, setOpenId] = useState<string | undefined>(id)
  const [showNew, setShowNew] = useState(false)
  const [fStatus, setFStatus] = useState<'all' | ReqStatus>('all')
  const [fProduct, setFProduct] = useState<'all' | string>('all')
  const [dragOverCol, setDragOverCol] = useState<ReqStatus | undefined>()

  // dogfooding 切换：需求列表仅真实 API（GET /work-items?type=requirement）；动作后的状态补丁在本页暂存，
  // 服务端重取后自然一致。
  const reqQ = useRequirements()
  const remoteRows = useMemo(() => (reqQ.data?.items ?? []).map(remoteToRequirement), [reqQ.data])
  const [patches, setPatches] = useState<Record<string, Partial<Requirement>>>({})
  const applyPatch = (rid: string, patch: Partial<Requirement>) =>
    setPatches((p) => ({ ...p, [rid]: { ...p[rid], ...patch } }))

  const allRequirements = useMemo<Requirement[]>(
    () => remoteRows.map((r) => ({ ...r, ...(patches[r.id] ?? {}) })),
    [remoteRows, patches],
  )

  // 关联任务计数（拆解任务列）：真实任务 GET /work-items?type=task 客户端按 requirementId 归组
  const tasksQ = useWorkItems('task')
  const goalQ = useGoals()
  const goalName = (gid?: string) => goalQ.data?.find((g) => g.id === gid)?.name
  const { data: relRows } = useReleases()
  const queryClientPage = useQueryClient()
  // 看板快捷推进/拖拽：真实流转 POST /{id}/transition + 本页补丁暂存（服务端重取后一致）；
  // 失败信封提示（QA 复审 MUST-FIX：非法迁移/422 不再静默回弹）
  const [flowToast, setFlowToast] = useState<{ ok: boolean; text: string } | undefined>()
  const advanceReal = async (rid: string, to: ReqStatus) => {
    try {
      const updated = await workItemsApi.transition(rid, to)
      applyPatch(rid, { status: updated.status as ReqStatus })
      void queryClientPage.invalidateQueries({ queryKey: ['requirements'] })
      setFlowToast({ ok: true, text: '已流转' })
    } catch (e) {
      setFlowToast({ ok: false, text: e instanceof Error ? e.message : '状态流转失败' })
      void queryClientPage.invalidateQueries({ queryKey: ['requirements'] })
    }
  }

  // 产品过滤选项：真实产品名 + 列表内出现过的产品 id（替换原 store 硬编码 p1/p2）
  const { data: productRows } = useProducts()
  const productLabel = useMemo(() => {
    const m = new Map<string, string>()
    for (const p of productRows ?? []) m.set(p.id, p.name)
    for (const r of allRequirements) if (!m.has(r.productId)) m.set(r.productId, `产品 ${r.productId.slice(0, 4)}`)
    return m
  }, [productRows, allRequirements])
  const productOptions = useMemo(
    () => [...new Set(allRequirements.map((r) => r.productId))],
    [allRequirements],
  )

  const list = useMemo(() => allRequirements
    .filter((r) => (fStatus === 'all' || r.status === fStatus) && (fProduct === 'all' || r.productId === fProduct))
    .slice()
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
  [allRequirements, fStatus, fProduct])

  // P2-4：行展开「最近驳回意见」
  const [expandedId, setExpandedId] = useState<string | undefined>()

  const open = (rid: string) => setOpenId(rid)
  const rq = openId ? allRequirements.find((r) => r.id === openId) : undefined

  // 姓名解析：真实需求（uuid 账号）走 /users/briefs
  const { data: briefRows } = useUserBriefs()
  const pageBriefs = toBrief(briefRows)
  const nameOf = (uid: string) => pageBriefs.get(uid)?.name ?? '—'

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
          const n = allRequirements.filter((r) => r.status === s).length
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
          {productOptions.map((pid) => <option key={pid} value={pid}>{productLabel.get(pid) ?? pid}</option>)}
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
                {['', 'Key', '标题', '状态', '优先级', '需求负责人', '交付版本', '战略目标', '拆解任务', '更新'].map((h, i) => <th key={i} className="px-3 py-2.5 font-medium">{h}</th>)}
              </tr>
            </thead>
            <tbody>
              {list.map((r) => {
                const linked = (tasksQ.data ?? []).filter((t) => t.requirementId === r.id)
                const goalNameText = r.goalId ? goalName(r.goalId) : undefined
                const expanded = expandedId === r.id
                const hasRejected = r.reviews.some((v) => v.result === 'rejected')
                const toggle = () => setExpandedId(expanded ? undefined : r.id)
                return (
                  <Fragment key={r.id}>
                    <tr className="cursor-pointer border-b border-line/60 hover:bg-ink-700" onClick={() => open(r.id)}>
                      <td className="px-3 py-2.5">
                        <button
                          type="button"
                          title={hasRejected ? '展开最近驳回意见' : '展开评审意见'}
                          onClick={(e) => { e.stopPropagation(); toggle() }}
                          className="cursor-pointer rounded p-0.5 text-txt-low hover:bg-ink-700 hover:text-txt-hi"
                        >
                          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                        </button>
                      </td>
                      <td className="px-3 py-2.5 font-mono text-xs font-bold text-cat-purple">{r.key}</td>
                      <td className="max-w-64 truncate px-3 py-2.5 text-txt-hi">
                        <div className="flex items-center gap-1.5">
                          <span className="truncate">{r.title}</span>
                          {r.docContent && <span className="shrink-0 rounded bg-brand/10 px-1 py-0.2 text-[9px] font-semibold text-brand">PRD</span>}
                        </div>
                      </td>
                      <td className="px-3 py-2.5"><Pill tone={statusTone[r.status]}>{statusText[r.status]}</Pill></td>
                      <td className="px-3 py-2.5"><PriorityBadge p={r.priority} /></td>
                      <td className="px-3 py-2.5"><div className="flex items-center gap-1.5"><Avatar userId={r.ownerId} size={20} /><span className="text-xs text-txt-mid">{nameOf(r.ownerId)}</span></div></td>
                      <td className="px-3 py-2.5 font-mono text-xs text-cat-teal">{r.releaseId ? (relRows?.find((x) => x.id === r.releaseId)?.name ?? '—') : '—'}</td>
                      <td className="px-3 py-2.5 max-w-32 truncate text-xs text-cat-purple" title={goalNameText}>{goalNameText ?? '—'}</td>
                      <td className="px-3 py-2.5 text-xs tabular-nums text-txt-mid">{linked.filter((t) => t.status === 'done' || t.status === 'closed').length}/{linked.length}</td>
                      <td className="px-3 py-2.5 text-xs tabular-nums text-txt-low">{r.updatedAt}</td>
                    </tr>
                    {expanded && (
                      <tr className="border-b border-line/60 last:border-0">
                        <td colSpan={10} className="bg-ink-800/40 px-6 py-3">
                          <RejectedReviewBlock r={r} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
          {list.length === 0 && !reqQ.isLoading && <Empty text="没有符合条件的需求" />}
          {reqQ.isLoading && <Empty text="需求加载中…" size="sm" icon={<Spinner />} />}
        </Card>
      ) : (
        /* 看板视图：支持跨列拖拽流转状态 + 卡片悬浮快捷推进 */
        <div className="grid grid-cols-4 gap-3 xl:grid-cols-7">
          {FLOW.map((s) => {
            const col = list.filter((r) => r.status === s)
            return (
              <div
                key={s}
                onDragOver={(e) => {
                  e.preventDefault()
                  e.dataTransfer.dropEffect = 'move'
                }}
                onDragEnter={() => setDragOverCol(s)}
                onDragLeave={(e) => {
                  if (!e.currentTarget.contains(e.relatedTarget as Node)) {
                    setDragOverCol(undefined)
                  }
                }}
                onDrop={(e) => {
                  e.preventDefault()
                  setDragOverCol(undefined)
                  const targetId = e.dataTransfer.getData('text/plain')
                  if (targetId) void advanceReal(targetId, s)
                }}
                className={`rounded-card border p-2 transition-all min-h-[300px] ${
                  dragOverCol === s
                    ? 'border-brand ring-2 ring-brand/30 bg-brand-bg/20'
                    : 'border-line bg-card'
                }`}
              >
                <div className="mb-2 flex items-center justify-between px-1">
                  <span className="text-xs font-semibold text-txt-hi">{statusText[s]}</span>
                  <span className="text-xs tabular-nums text-txt-low">{col.length}</span>
                </div>
                <div className="space-y-2">
                  {col.map((r) => (
                    <div
                      key={r.id}
                      draggable
                      onDragStart={(e) => {
                        e.dataTransfer.setData('text/plain', r.id)
                        e.dataTransfer.effectAllowed = 'move'
                      }}
                      onClick={() => open(r.id)}
                      className="group relative w-full cursor-grab active:cursor-grabbing rounded-lg border border-line bg-canvas p-2.5 text-left hover:border-brand shadow-card transition-all"
                    >
                      <div className="flex items-center gap-1.5">
                        <span className="font-mono text-[10px] font-bold text-cat-purple">{r.key}</span>
                        <span className="flex-1" />
                        <PriorityBadge p={r.priority} />
                      </div>
                      <div className="mt-1 line-clamp-2 text-xs leading-4 font-medium text-txt-hi">{r.title}</div>
                      <div className="mt-1.5 flex items-center gap-1">
                        <Avatar userId={r.ownerId} size={16} />
                        <span className="text-[10px] text-txt-low">{nameOf(r.ownerId)}</span>
                        <span className="flex-1" />
                        {r.docContent && (
                          <span title="包含详细 PRD 文档" className="rounded bg-brand/10 px-1 py-0.2 text-[9px] font-semibold text-brand">PRD</span>
                        )}
                        {r.docFileName && (
                          <span title={`附件文档：${r.docFileName}`} className="rounded bg-cat-teal/10 px-1 py-0.2 text-[9px] font-semibold text-cat-teal">文档</span>
                        )}
                        <span className="font-mono text-[10px] text-cat-teal">{r.releaseId ? (relRows?.find((x) => x.id === r.releaseId)?.name ?? '') : ''}</span>
                      </div>
                      {/* 悬浮快捷推进按钮 */}
                      {r.status !== 'closed' && (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation()
                            const curIdx = FLOW.indexOf(r.status)
                            if (curIdx >= 0 && curIdx < FLOW.length - 1) {
                              void advanceReal(r.id, FLOW[curIdx + 1])
                            }
                          }}
                          title={`推进到「${statusText[FLOW[Math.min(FLOW.indexOf(r.status) + 1, FLOW.length - 1)]]}」`}
                          className="absolute right-1.5 bottom-1.5 hidden cursor-pointer items-center gap-0.5 rounded border border-brand/30 bg-card px-1.5 py-0.5 text-[10px] font-semibold text-brand shadow-sm hover:bg-brand hover:text-white group-hover:inline-flex"
                        >
                          → 推进
                        </button>
                      )}
                    </div>
                  ))}
                  {col.length === 0 && <div className="py-8 text-center text-[11px] text-txt-low">拖放卡片到此处</div>}
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

      {rq && <ReqDrawer rq={rq} nav={nav} onClose={() => setOpenId(undefined)} onPatched={applyPatch} />}
      {showNew && <NewReqModal onClose={() => setShowNew(false)} onDone={() => setShowNew(false)} />}
      {flowToast && (
        <div className="fixed bottom-6 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-2 rounded-full border border-line bg-canvas px-4 py-2.5 text-sm text-txt-hi shadow-lg">
          <span className={`h-2 w-2 rounded-full ${flowToast.ok ? 'bg-ok' : 'bg-bad'}`}/>{flowToast.text}
        </div>
      )}
    </div>
  )
}

// ==================== 详情抽屉：链路 + 评审流程 + 拆解任务 ====================
/**
 * 评审流程区（R-6/D2）全量真实 API：
 * - 轮次记录 GET /work-items/{key}/review-rounds（round/评审人/结果/意见/时间/结论），无 store 兜底；
 * - 提交评审：多选评审人 POST /submit（draft 可重提，服务端 round+1）；
 * - 逐人批：POST /review（任一驳回→draft、全员通过→accepted）；
 * - 纪要：两步制上传挂 platform.file（POST/PUT /review-rounds/{round}/minutes），下载走既有通道。
 * dogfooding 切换：列表仅真实需求，原型行分支（isReal）已移除。
 */
function ReqDrawer({ rq, nav, onClose, onPatched }: {
  rq: Requirement
  nav: PageProps['nav']
  onClose: () => void
  onPatched: (rid: string, patch: Partial<Requirement>) => void
}) {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const myId = user?.id
  const [busy, setBusy] = useState(false)
  const [flowErr, setFlowErr] = useState('')
  const invalidateFlow = async () => {
    await queryClient.invalidateQueries({ queryKey: ['requirementRounds'] })
    await queryClient.invalidateQueries({ queryKey: ['requirements'] })
  }

  // —— 轮次数据（真实 API 唯一数据源） ——
  const roundsQ = useRequirementReviewRounds(rq.key)
  const rounds = roundsQ.data ?? []
  const currentRound = rounds.at(-1)
  const myRow = currentRound?.reviews.find((v) => v.reviewerId === myId)
  const canVote = rq.status === 'pending_review' && myRow?.result === 'pending'
  const waitList = currentRound?.reviews.filter((v) => v.result === 'pending') ?? []

  // —— 提交评审（多评审人选择） ——
  const { data: briefRows } = useUserBriefs()
  const briefs = toBrief(briefRows)
  const nameOf = (uid: string) => briefs.get(uid)?.name ?? '—'
  const [reviewers, setReviewers] = useState<string[]>([])
  const toggleReviewer = (uid: string) =>
    setReviewers((p) => (p.includes(uid) ? p.filter((x) => x !== uid) : [...p, uid]))
  const submitReview = async () => {
    if (reviewers.length === 0) {
      setFlowErr('至少选择 1 名评审人')
      return
    }
    setBusy(true)
    setFlowErr('')
    try {
      await requirementsApi.submit(rq.key, reviewers)
      await invalidateFlow()
      onPatched(rq.id, { status: 'pending_review', reviewerIds: reviewers })
      setReviewers([])
    } catch (e) {
      setFlowErr(e instanceof Error ? e.message : '提交失败')
    } finally {
      setBusy(false)
    }
  }

  // —— 逐人批（通过/驳回） ——
  const [rejecting, setRejecting] = useState(false)
  const [comment, setComment] = useState('')
  const vote = async (result: 'approved' | 'rejected', cmt?: string) => {
    setBusy(true)
    setFlowErr('')
    try {
      const updated = await requirementsApi.review(rq.key, result, cmt)
      await invalidateFlow()
      onPatched(rq.id, { status: updated.status as Requirement['status'] })
      setRejecting(false)
      setComment('')
    } catch (e) {
      setFlowErr(e instanceof Error ? e.message : '评审操作失败')
    } finally {
      setBusy(false)
    }
  }

  // —— 后段流转（in_dev→delivered→closed）：真实转移表接口 ——
  const advance = async (to: 'delivered' | 'closed') => {
    setBusy(true)
    setFlowErr('')
    try {
      const updated = await workItemsApi.transition(rq.id, to)
      onPatched(rq.id, { status: updated.status as Requirement['status'] })
    } catch (e) {
      setFlowErr(e instanceof Error ? e.message : '流转失败')
    } finally {
      setBusy(false)
    }
  }

  // 拆解任务/关联链路：全部真实数据源（dogfooding 切换：store 数组与 topicById 兜底移除）
  const tasksQ = useWorkItems('task')
  const linked = useMemo(() => (tasksQ.data ?? []).filter((t) => t.requirementId === rq.id), [tasksQ.data, rq.id])
  const doneCount = linked.filter((t) => t.status === 'done' || t.status === 'closed').length
  const goalQ = useGoals()
  const goal = rq.goalId ? goalQ.data?.find((g) => g.id === rq.goalId) : undefined
  const rmQ = useRoadmapItems()
  const rm = rq.roadmapItemId ? rmQ.data?.find((x) => x.id === rq.roadmapItemId) : undefined
  const relQ = useReleases()
  const rel = rq.releaseId ? relQ.data?.find((x) => x.id === rq.releaseId) : undefined
  const spQ = useSprints()
  const sp = rq.sprintId ? spQ.data?.find((x) => x.id === rq.sprintId) : undefined
  const [editingPrd, setEditingPrd] = useState(false)
  const [editPrdText, setEditPrdText] = useState(rq.docContent ?? '')

  /** 保存 PRD 正文：PUT /work-items/{id}（description=现有简述 + PRD 分节，If-Match 乐观锁），真实 API 持久化 */
  const savePrd = async () => {
    setBusy(true)
    setFlowErr('')
    try {
      const baseDesc = (rq.description ?? '').split('\n## PRD 详细说明')[0].trim()
      const description = editPrdText.trim()
        ? [baseDesc, `## PRD 详细说明\n\n${editPrdText.trim()}`].filter(Boolean).join('\n\n')
        : baseDesc
      await workItemsApi.update(rq.id, { description }, rq.version)
      await invalidateFlow()
      setEditingPrd(false)
    } catch (e) {
      setFlowErr(e instanceof Error ? e.message : '保存失败')
    } finally {
      setBusy(false)
    }
  }

  /** 附件下载：真实文件（含 fileId）走既有下载通道取临时 URL */
  const downloadAttachment = async (att: { fileId?: string; name: string }) => {
    if (!att.fileId) {
      setFlowErr(`「${att.name}」缺少服务端文件（fileId），无法下载`)
      return
    }
    try {
      const d = await filesApi.downloadUrl(att.fileId)
      window.open(d.downloadUrl, '_blank')
    } catch (e) {
      setFlowErr(e instanceof Error ? e.message : '下载失败')
    }
  }

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

        {/* 详细 PRD 需求正文与文档附件 */}
        <div className="mt-3 rounded-lg border border-line bg-canvas p-3.5 text-xs">
          <div className="mb-2 flex items-center justify-between">
            <div className="flex items-center gap-1.5 font-bold text-txt-hi">
              <FileText size={14} className="text-cat-purple" />
              <span>需求详细说明 (PRD)</span>
            </div>
            <div className="flex items-center gap-1.5">
              {rq.docFileName && (
                <span className="rounded bg-cat-purple/10 px-1.5 py-0.5 text-[10px] font-semibold text-cat-purple">
                  {rq.docFileName}
                </span>
              )}
              {!editingPrd && (
                <button
                  type="button"
                  onClick={() => { setEditPrdText(rq.docContent ?? ''); setEditingPrd(true) }}
                  className="cursor-pointer text-[11px] text-brand hover:underline font-semibold"
                >
                  {rq.docContent ? '编辑' : '补充正文'}
                </button>
              )}
            </div>
          </div>
          {editingPrd ? (
            <div className="space-y-2">
              <textarea
                value={editPrdText}
                onChange={(e) => setEditPrdText(e.target.value)}
                rows={7}
                placeholder="在此输入或补充 PRD 详细说明（Markdown 格式）…"
                className={`${inputCls} font-mono text-xs leading-5`}
              />
              <div className="flex justify-end gap-2">
                <Btn variant="ghost" onClick={() => setEditingPrd(false)}>取消</Btn>
                <Btn variant="primary" disabled={busy} onClick={() => void savePrd()}>保存正文</Btn>
              </div>
            </div>
          ) : rq.docContent ? (
            <div className="max-h-64 overflow-y-auto rounded-md border border-line bg-card p-3 leading-5 text-txt-mid whitespace-pre-wrap font-sans text-xs">
              {rq.docContent}
            </div>
          ) : (
            <div className="rounded-md border border-dashed border-line bg-card/60 p-3 text-center text-txt-low">
              暂未填写详细 PRD 正文（点击右上角「补充正文」添加）
            </div>
          )}

          {/* 关联附件清单 */}
          {rq.attachments && rq.attachments.length > 0 && (
            <div className="mt-3 space-y-1.5 border-t border-line/70 pt-2">
              <div className="text-[11px] font-semibold text-txt-low">关联文档与附件（{rq.attachments.length}）</div>
              {rq.attachments.map((att, idx) => (
                <div key={idx} className="flex items-center gap-2 rounded border border-line bg-card px-2.5 py-1.5 text-xs">
                  <FileText size={13} className="text-brand shrink-0" />
                  <span className="min-w-0 flex-1 truncate font-medium text-txt-hi">{att.name}</span>
                  <span className="text-[10px] tabular-nums text-txt-low">{att.size}</span>
                  <span className="text-[10px] text-txt-low">{att.uploadedAt}</span>
                  <button
                    type="button"
                    onClick={() => void downloadAttachment(att)}
                    className="cursor-pointer text-[11px] font-semibold text-brand hover:underline"
                  >
                    下载
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 元信息 */}
        <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-txt-mid">
          <div className="flex items-center gap-1.5">提出人 <Avatar userId={rq.proposerId} size={18} /> {nameOf(rq.proposerId)}</div>
          <div className="flex items-center gap-1.5">负责人 <Avatar userId={rq.ownerId} size={18} /> {nameOf(rq.ownerId)}</div>
          <div>粗估 {rq.estimatePoints ?? '—'} 点</div>
          <div>创建 {rq.createdAt}</div>
        </div>

        {/* 关联链路：目标 → RoadMap → 版本 → 迭代 */}
        <div className="mt-4">
          <div className="mb-1.5 text-[11px] font-semibold tracking-wide text-txt-low">关联链路（目标 → 条目 → 版本 → 迭代）</div>
          <div className="space-y-1">
            {goal
              ? <LinkRow label={`战略目标 · ${goal.name}`} onClick={() => nav.go('goals', goal.id)} tone="text-cat-purple" />
              : <div className="text-xs text-txt-low">未关联战略目标</div>}
            {rm && <LinkRow label={`RoadMap 条目 · ${rm.name}`} onClick={() => nav.go('roadmap')} tone="text-brand" />}
            {rel && <LinkRow label={rel.planDate ? `交付版本 ${rel.name} · 计划 ${rel.planDate.slice(5)}` : `交付版本 ${rel.name}`} onClick={() => nav.go('delivery', rel.id)} tone="text-cat-teal" />}
            {sp && <LinkRow label={`迭代 ${sp.name}`} onClick={() => nav.go('tasks', sp.id)} tone="text-info" />}
          </div>
        </div>

        {/* 评审区（R-6：轮次分组真实 API，无 store 兜底） */}
        <div className="mt-4 rounded-card border border-line bg-canvas p-3">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-xs font-semibold text-txt-hi">评审流程（会签：全员通过受理，任一驳回退回）</span>
            {roundsQ.isLoading && <span className="flex items-center gap-1 text-[11px] text-txt-low"><Spinner size={12} />加载中…</span>}
          </div>

          {roundsQ.isError || (!roundsQ.isLoading && rounds.length === 0) ? (
            <div className="rounded-lg border border-dashed border-line bg-card/60 p-3 text-xs text-txt-low">暂无评审记录（尚未提交评审）</div>
          ) : null}

          {/* 轮次卡：round / 逐人记录 / 结论 / 纪要 */}
          {rounds.map((rd) => (
            <RoundCard
              key={rd.round}
              rqKey={rq.key}
              round={rd}
              briefs={briefs}
              busy={busy}
              onChanged={() => void invalidateFlow()}
              onFlowErr={(m) => setFlowErr(m)}
            />
          ))}

          {/* 动作区（按状态） */}
          <div className="mt-3 border-t border-line pt-3">
            {(rq.status === 'draft' || rq.status === 'rejected') && (
              <div>
                {rq.status === 'rejected' && (
                  <div className="mb-2 text-[11px] text-bad-deep">
                    已被驳回（第 {rounds.length} 轮）。可修改后重新提交评审，重新提交将进入第 {rounds.length + 1} 轮。
                  </div>
                )}
                <div className="mb-2">
                  <div className="mb-1 text-[11px] font-semibold text-txt-low">评审人（多选，全部通过后受理）</div>
                  <div className="flex flex-wrap gap-1.5">
                    {[...briefs.values()].map((u) => (
                      <button key={u.id} type="button" onClick={() => toggleReviewer(u.id)}
                        className={`flex cursor-pointer items-center gap-1 rounded-full border px-2 py-0.5 text-xs ${reviewers.includes(u.id) ? 'border-brand bg-brand-bg text-brand-deep' : 'border-line bg-card text-txt-mid hover:border-line-hi'}`}>
                        <Avatar userId={u.id} size={14} /> {u.name}
                      </button>
                    ))}
                    {briefs.size === 0 && <span className="text-[11px] text-txt-low">评审人列表加载中…</span>}
                  </div>
                </div>
                <Btn variant="primary" disabled={busy} onClick={() => void submitReview()}>
                  <Check size={13} /> {rq.status === 'rejected' ? `重新提交评审（第 ${rounds.length + 1} 轮）` : '提交评审'}
                </Btn>
                <span className="ml-2 text-[11px] text-txt-low">提交后自动创建话题并拉入干系人</span>
              </div>
            )}
            {rq.status === 'pending_review' && (
              canVote ? (
                <div>
                  {!rejecting ? (
                    <div className="flex gap-2">
                      <Btn variant="primary" disabled={busy} onClick={() => void vote('approved', '同意，按计划排期。')}>通过</Btn>
                      <Btn variant="danger" disabled={busy} onClick={() => setRejecting(true)}>驳回</Btn>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      <textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={2} placeholder="请填写驳回意见（必填）" className={inputCls} />
                      <div className="flex gap-2">
                        <Btn variant="danger" disabled={!comment.trim() || busy} onClick={() => void vote('rejected', comment)}>确认驳回</Btn>
                        <Btn variant="ghost" onClick={() => setRejecting(false)}>取消</Btn>
                      </div>
                    </div>
                  )}
                </div>
              ) : (
                <div className="text-xs text-txt-low">
                  评审进行中（第 {currentRound?.round ?? '—'} 轮）：等待 {waitList.map((v) => briefs.get(v.reviewerId)?.name ?? '评审人').join('、') || '—'} 提交意见；全部通过后自动受理。
                </div>
              )
            )}
            {rq.status === 'accepted' && (
              <RealScheduleBox rq={rq} onPatched={onPatched} onFlowErr={(m) => setFlowErr(m)} />
            )}
            {rq.status === 'in_dev' && (
              <Btn disabled={busy} onClick={() => void advance('delivered')}>标记已交付（随版本发布）</Btn>
            )}
            {rq.status === 'delivered' && (
              <Btn variant="primary" disabled={busy} onClick={() => void advance('closed')}>验收通过，关闭需求</Btn>
            )}
            {(rq.status === 'closed' || rq.status === 'delivered') && (
              <div className="text-xs text-txt-low">{rq.status === 'closed' ? '需求已完成闭环（评审 → 排期 → 交付 → 验收）。' : '已随版本交付，待产品验收。'}</div>
            )}
            {flowErr && <div className="mt-2 rounded bg-bad-bg px-2.5 py-1.5 text-[11px] text-bad-deep">{flowErr}</div>}
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
                <Pill tone={t.status === 'done' || t.status === 'closed' ? 'ok' : t.status === 'in_progress' ? 'info' : 'neutral'}>{t.status === 'done' ? '完成' : t.status === 'closed' ? '关闭' : t.status === 'in_progress' ? '进行中' : '待处理'}</Pill>
              </button>
            ))}
            {linked.length === 0 && <div className="text-xs text-txt-low">尚未拆解任务。受理后可在「迭代与任务」中创建任务并关联本需求。</div>}
          </div>
        </div>

        {/* 话题入口：真实话题（会话 REST 按 targetType/targetId 反查，跳即时沟通） */}
        <ReqTopicLink requirementId={rq.id} title={rq.title} nav={nav} />
      </aside>
    </div>
  )
}

/** 需求话题反查（dogfooding 切换）：GET /conversations 列表内按 target 匹配，命中则跳 IM 会话 */
function ReqTopicLink({ requirementId, title, nav }: { requirementId: string; title: string; nav: PageProps['nav'] }) {
  const { data: convs } = useConversations(false)
  const topic = (convs ?? []).find((c) => c.type === 'topic' && c.targetType === 'requirement' && c.targetId === requirementId)
  return (
    <div className="mt-4 border-t border-line pt-3">
      {topic ? (
        <button type="button" onClick={() => nav.go('im', topic.id)} className="flex w-full cursor-pointer items-center gap-2 rounded-lg border border-line bg-canvas px-2.5 py-2 text-left text-xs text-brand hover:bg-brand-bg">
          <Hash size={13} /> 进入需求话题「{topic.name || title}」<ArrowRight size={11} className="ml-auto shrink-0" />
        </button>
      ) : (
        <div className="text-xs text-txt-low">提交评审后将自动创建需求话题（按干系人规则拉人）。</div>
      )}
    </div>
  )
}

// ==================== R-6：评审轮次卡（round/逐人记录/结论/纪要） ====================
const ROUND_META: Record<RemoteRequirementRound['conclusion'], { label: string; tone: 'ok' | 'bad' | 'warn' }> = {
  approved: { label: '全员通过 · 已受理', tone: 'ok' },
  rejected: { label: '任一驳回 · 已退回', tone: 'bad' },
  pending: { label: '评审中', tone: 'warn' },
}

function RoundCard({ rqKey, round, briefs, busy, onChanged, onFlowErr }: {
  rqKey: string
  round: RemoteRequirementRound
  briefs: Map<string, import('../../api/users').RemoteUserBrief>
  busy: boolean
  onChanged: () => void
  onFlowErr: (message: string) => void
}) {
  const meta = ROUND_META[round.conclusion]
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const [summary, setSummary] = useState('')

  /** 纪要上传：platform.file 两步制（presign→PUT→complete）拿 fileId 再挂轮次 */
  const uploadMinutes = async (file: File) => {
    setUploading(true)
    onFlowErr('')
    try {
      const att = await filesApi.upload(file)
      await requirementsApi.uploadMinutes(rqKey, round.round, att.fileId, summary.trim() || undefined)
      onChanged()
      setSummary('')
    } catch (e) {
      onFlowErr(e instanceof Error ? e.message : '纪要上传失败')
    } finally {
      setUploading(false)
    }
  }

  /** 纪要下载：走既有文件下载通道（临时 URL） */
  const downloadMinutes = async () => {
    if (!round.minutesFileId) return
    try {
      const d = await filesApi.downloadUrl(round.minutesFileId)
      window.open(d.downloadUrl, '_blank')
    } catch (e) {
      onFlowErr(e instanceof Error ? e.message : '纪要下载失败')
    }
  }

  return (
    <div className="mb-2 rounded-lg border border-line bg-card p-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded bg-brand/10 px-1.5 py-0.5 text-[10px] font-bold text-brand">第 {round.round} 轮</span>
        <Pill tone={meta.tone}>{meta.label}</Pill>
        {round.concludedAt && (
          <span className="text-[10px] tabular-nums text-txt-low">结论 {round.concludedAt.slice(0, 16).replace('T', ' ')}</span>
        )}
        <span className="flex-1" />
        {/* 纪要（D2：可选但展示；权限=提交人或管理员，后端校验） */}
        {round.minutesFileId ? (
          <button type="button" disabled={busy} onClick={() => void downloadMinutes()}
            className="cursor-pointer text-[11px] font-semibold text-brand hover:underline">
            <FileText size={11} className="mr-0.5 inline" />下载纪要
          </button>
        ) : (
          <button type="button" disabled={uploading || busy} onClick={() => fileInputRef.current?.click()}
            className="cursor-pointer text-[11px] font-semibold text-brand hover:underline disabled:text-txt-low">
            <Upload size={11} className="mr-0.5 inline" />{uploading ? '上传中…' : '上传纪要'}
          </button>
        )}
        <input ref={fileInputRef} type="file" className="hidden"
          onChange={(e) => { const f = e.target.files?.[0]; if (f) void uploadMinutes(f); e.target.value = '' }} />
      </div>
      {/* 逐人记录：评审人 / 结果 / 意见 / 时间 */}
      <div className="mt-1.5 space-y-1">
        {round.reviews.map((rv) => (
          <div key={rv.id} className="flex items-start gap-2 rounded-md bg-canvas p-2 text-xs">
            <Avatar userId={rv.reviewerId} size={18} />
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="font-semibold text-txt-hi">{briefs.get(rv.reviewerId)?.name ?? '评审人'}</span>
                <Pill tone={rv.result === 'approved' ? 'ok' : rv.result === 'rejected' ? 'bad' : 'neutral'}>
                  {rv.result === 'approved' ? '通过' : rv.result === 'rejected' ? '驳回' : '待评'}
                </Pill>
                <span className="ml-auto text-txt-low tabular-nums">
                  {(rv.decidedAt ?? rv.createdAt ?? '').slice(0, 16).replace('T', ' ')}
                </span>
              </div>
              {rv.comment && <div className="mt-0.5 text-txt-mid">{rv.comment}</div>}
            </div>
          </div>
        ))}
      </div>
      {/* 轮次摘要：已存 summary 展示；未传纪要时可随上传一并填写 */}
      {round.summary && (
        <div className="mt-1.5 rounded-md bg-canvas px-2 py-1.5 text-[11px] leading-4 text-txt-mid">
          <span className="font-semibold text-txt-low">纪要摘要：</span>{round.summary}
        </div>
      )}
      {!round.minutesFileId && !round.summary && (
        <input value={summary} onChange={(e) => setSummary(e.target.value)} placeholder="纪要摘要（可选，随上传一并保存）"
          className="mt-1.5 w-full rounded-input border border-line bg-canvas px-2 py-1 text-[11px] text-txt-hi outline-none focus:border-brand" />
      )}
    </div>
  )
}

// ==================== 排期：真实需求走 POST /schedule；原型行走 store ====================

/** 真实需求排期（版本/迭代为真实实体；RoadMap 条目选择不在本批范围，留空跳过） */
function RealScheduleBox({ rq, onPatched, onFlowErr }: {
  rq: Requirement
  onPatched: (rid: string, patch: Partial<Requirement>) => void
  onFlowErr: (message: string) => void
}) {
  const { data: relRows = [] } = useReleases()
  const { data: spRows = [] } = useSprints()
  const [releaseId, setReleaseId] = useState('')
  const [sprintId, setSprintId] = useState('')
  const [busy, setBusy] = useState(false)
  const schedule = async () => {
    setBusy(true)
    onFlowErr('')
    try {
      const updated = await requirementsApi.schedule(rq.key, {
        releaseId: releaseId || undefined,
        sprintId: sprintId || undefined,
      })
      onPatched(rq.id, {
        status: updated.status as Requirement['status'],
        releaseId: releaseId || undefined,
        sprintId: sprintId || undefined,
      })
    } catch (e) {
      onFlowErr(e instanceof Error ? e.message : '排期失败')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="space-y-2">
      <div className="text-xs text-txt-low">已受理，选择排期（版本 / 迭代，真实 API）：</div>
      <div className="grid grid-cols-2 gap-2">
        <select value={releaseId} onChange={(e) => setReleaseId(e.target.value)} className={inputCls}>
          <option value="">版本…</option>
          {relRows.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
        </select>
        <select value={sprintId} onChange={(e) => setSprintId(e.target.value)} className={inputCls}>
          <option value="">迭代…</option>
          {spRows.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
        </select>
      </div>
      <Btn variant="primary" disabled={busy} onClick={() => void schedule()}>确认排期</Btn>
    </div>
  )
}

const PRD_TEMPLATE = `## 1. 业务背景与用户痛点
描述需求产生的业务背景、目标用户群体以及当前解决方式的缺陷。

## 2. 目标与预期价值
- **量化指标**：如操作耗时减少 30%、支持千级并发。
- **业务收益**：提升客户满意度与系统可用性。

## 3. 功能详细说明与用例
### 3.1 核心功能流转
1. 用户进入页面触发操作。
2. 系统校验参数完整性与权限。
3. 执行核心逻辑并持久化数据。

### 3.2 界面交互与 UI 要求
- 支持拖拽排序与实时反馈。
- 关键变更操作二次弹窗确认。

## 4. 边界异常与非功能性要求
- **网络异常**：超时提示重试并保留本地表单草稿。
- **并发控制**：加锁防重复提交。

## 5. 验收标准 (Definition of Done)
- [ ] 核心流程冒烟用例通过
- [ ] 边界异常测试覆盖率达标
- [ ] 文档与接口定义更新完成`

// ==================== 新建需求（R-6：POST /work-items 真实创建；PRD 正文并入 description；附件走两步制真实上传） ====================
function NewReqModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [tab, setTab] = useState<'base' | 'prd' | 'files'>('base')
  const [title, setTitle] = useState('')
  const [desc, setDesc] = useState('')
  const [docContent, setDocContent] = useState('')
  const [attachments, setAttachments] = useState<{ fileId: string; name: string; size: string; uploadedAt: string }[]>([])
  const [prdView, setPrdView] = useState<'edit' | 'preview'>('edit')
  const [priority, setPriority] = useState<'P0' | 'P1' | 'P2' | 'P3'>('P2')
  // R-6：目标/产品用真实数据（productId 或 goalId 是后端 path 根硬约束）；评审人移至「提交评审」时多选
  const { data: goalRows } = useGoals()
  const { data: productRows } = useProducts()
  const [goalId, setGoalId] = useState('')
  const [productId, setProductId] = useState('')
  const [points, setPoints] = useState('5')
  const [busy, setBusy] = useState(false)
  const [fieldErr, setFieldErr] = useState<Record<string, string>>({})
  const [err, setErr] = useState('')
  const queryClient = useQueryClient()

  /** 处理本地文件选择：.md/.txt 解析正文；全部附件走 platform.file 两步制真实上传 */
  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    const sizeStr =
      file.size > 1024 * 1024
        ? `${(file.size / (1024 * 1024)).toFixed(1)} MB`
        : `${Math.round(file.size / 1024)} KB`
    const isMd = file.name.endsWith('.md') || file.name.endsWith('.markdown')
    const isTxt = file.name.endsWith('.txt')
    if (isMd || isTxt) {
      const text = await file.text()
      setDocContent(text)
      setTab('prd') // 自动切到 PRD 标签查看解析出的文档正文
    }
    try {
      const att = await filesApi.upload(file) // presign → PUT → complete，落 platform.file
      setAttachments((prev) => [...prev, { fileId: att.fileId, name: att.name ?? file.name, size: sizeStr, uploadedAt: '刚刚' }])
    } catch (err2) {
      setErr(err2 instanceof Error ? err2.message : '附件上传失败')
    }
  }

  const submit = async () => {
    setFieldErr({})
    setErr('')
    if (!title.trim()) { setFieldErr({ title: '标题必填' }); return }
    if (!productId && !goalId) { setErr('请选择产品或关联目标（二者必选其一，作为需求归属）'); return }
    setBusy(true)
    try {
      // PRD 正文并入 description（服务端需求无独立正文列）；评审人在抽屉「提交评审」时多选
      const description = [
        desc.trim(),
        docContent.trim() ? `## PRD 详细说明\n\n${docContent.trim()}` : '',
      ].filter(Boolean).join('\n\n')
      await requirementsApi.create({
        title: title.trim(),
        description: description || undefined,
        priority,
        productId: productId || undefined,
        goalId: goalId || undefined,
        storyPoints: Number(points) || undefined,
      })
      await queryClient.invalidateQueries({ queryKey: ['requirements'] })
      onDone()
    } catch (e) {
      const parsed = parseFieldError(e)
      if (parsed.field) setFieldErr({ [parsed.field]: parsed.message })
      else setErr(parsed.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-txt-hi/25" onClick={onClose} />
      <div className="relative w-[560px] max-w-full rounded-card border border-line bg-canvas p-5 shadow-2xl">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="flex items-center gap-1.5 text-base font-bold text-txt-hi">
            <FileText size={16} className="text-cat-purple" /> 新建需求（草稿）
          </h3>
          <button type="button" onClick={onClose} className="cursor-pointer rounded-md p-1 text-txt-mid hover:bg-ink-700 hover:text-txt-hi"><X size={16} /></button>
        </div>

        {/* 顶部标签切换 */}
        <div className="mb-3 flex items-center gap-1 border-b border-line pb-2">
          <button
            type="button"
            onClick={() => setTab('base')}
            className={`cursor-pointer rounded px-3 py-1 text-xs font-semibold transition ${
              tab === 'base' ? 'bg-brand text-white' : 'text-txt-mid hover:text-txt-hi'
            }`}
          >
            基础信息
          </button>
          <button
            type="button"
            onClick={() => setTab('prd')}
            className={`flex cursor-pointer items-center gap-1 rounded px-3 py-1 text-xs font-semibold transition ${
              tab === 'prd' ? 'bg-brand text-white' : 'text-txt-mid hover:text-txt-hi'
            }`}
          >
            <FileCode size={12} />
            PRD 详细说明 {docContent && '✓'}
          </button>
          <button
            type="button"
            onClick={() => setTab('files')}
            className={`flex cursor-pointer items-center gap-1 rounded px-3 py-1 text-xs font-semibold transition ${
              tab === 'files' ? 'bg-brand text-white' : 'text-txt-mid hover:text-txt-hi'
            }`}
          >
            <Paperclip size={12} />
            文档与附件 ({attachments.length})
          </button>
        </div>

        {tab === 'base' && (
          <div className="space-y-3">
            <div>
              <label className="mb-1 block text-xs font-medium text-txt-mid">标题（必填）</label>
              <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="一句话说清需求" className={inputCls} />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-txt-mid">背景 / 价值 / 验收标准（简述）</label>
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
                <label className="mb-1 block text-xs font-medium text-txt-mid">所属产品</label>
                <select value={productId} onChange={(e) => setProductId(e.target.value)} className={`${inputCls} ${fieldErr.productId ? 'border-bad' : ''}`}>
                  <option value="">选择产品…</option>
                  {(productRows ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
                {fieldErr.productId && <div className="mt-1 text-xs text-bad">{fieldErr.productId}</div>}
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-txt-mid">关联目标</label>
                <select value={goalId} onChange={(e) => setGoalId(e.target.value)} className={inputCls}>
                  <option value="">暂不关联</option>
                  {(goalRows ?? []).map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
                </select>
              </div>
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className="mb-1 block text-xs font-medium text-txt-mid">粗估（点）</label>
                <input value={points} onChange={(e) => setPoints(e.target.value)} className={inputCls} />
              </div>
              <div className="col-span-2 flex items-end text-[11px] leading-4 text-txt-low">
                评审人在创建后于需求详情「提交评审」时多选（会签：全部通过受理，任一驳回退回重提，轮次 +1）。
              </div>
            </div>
          </div>
        )}

        {tab === 'prd' && (
          <div className="space-y-2">
            <div className="flex items-center justify-between text-xs">
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setPrdView('edit')}
                  className={`cursor-pointer font-semibold ${prdView === 'edit' ? 'text-brand underline' : 'text-txt-mid'}`}
                >
                  编辑 Markdown
                </button>
                <span>·</span>
                <button
                  type="button"
                  onClick={() => setPrdView('preview')}
                  className={`flex cursor-pointer items-center gap-1 font-semibold ${prdView === 'preview' ? 'text-brand underline' : 'text-txt-mid'}`}
                >
                  <Eye size={12} /> 实时预览
                </button>
              </div>
              <button
                type="button"
                onClick={() => setDocContent(PRD_TEMPLATE)}
                className="cursor-pointer text-[11px] text-brand hover:underline"
              >
                插入标准 PRD 模板
              </button>
            </div>
            {prdView === 'edit' ? (
              <textarea
                value={docContent}
                onChange={(e) => setDocContent(e.target.value)}
                rows={11}
                placeholder="在此直接编写 Markdown 格式的 PRD 需求文档，或通过「文档与附件」标签导入 .md / .txt 文件…"
                className={`${inputCls} font-mono text-xs leading-5`}
              />
            ) : (
              <div className="h-[230px] overflow-y-auto rounded-md border border-line bg-card p-3 text-xs leading-5 whitespace-pre-wrap text-txt-mid">
                {docContent || '（PRD 正文为空，请切换到编辑模式输入或导入）'}
              </div>
            )}
          </div>
        )}

        {tab === 'files' && (
          <div className="space-y-3">
            <div className="relative flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-line p-5 text-center transition hover:border-brand">
              <Upload size={24} className="text-brand mb-1" />
              <div className="text-xs font-semibold text-txt-hi">点击或拖拽上传需求文档</div>
              <div className="text-[11px] text-txt-low mt-0.5">支持 .md、.markdown、.docx、.doc、.txt、.pdf</div>
              <input
                type="file"
                accept=".md,.markdown,.docx,.doc,.txt,.pdf"
                onChange={handleFileUpload}
                className="absolute inset-0 cursor-pointer opacity-0"
              />
            </div>
            {attachments.length > 0 && (
              <div className="space-y-1.5 max-h-36 overflow-y-auto">
                <div className="text-[11px] font-semibold text-txt-mid">已附加文档：</div>
                {attachments.map((att, idx) => (
                  <div key={idx} className="flex items-center justify-between rounded border border-line bg-card px-2.5 py-1.5 text-xs">
                    <span className="truncate font-medium text-txt-hi">{att.name}</span>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] text-txt-low">{att.size}</span>
                      <button
                        type="button"
                        onClick={() => setAttachments((prev) => prev.filter((_, i) => i !== idx))}
                        className="cursor-pointer text-txt-low hover:text-bad"
                      >
                        <X size={13} />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {err && <div className="mt-2 text-xs text-bad-deep">{err}</div>}
        <div className="mt-4 flex items-center justify-between border-t border-line pt-3">
          <p className="text-[11px] text-txt-low">创建后为草稿，支持完整文档链路追溯。</p>
          <div className="flex gap-2">
            <Btn variant="ghost" onClick={onClose}>取消</Btn>
            <Btn variant="primary" disabled={busy} onClick={() => void submit()}><Plus size={13} /> {busy ? '创建中…' : '创建需求'}</Btn>
          </div>
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

// ==================== P2-4：最近驳回意见（行展开区，R-6 起无 store 兜底） ====================
/**
 * 最近一轮 rejected 评审的 comment：数据源 = GET /api/v1/work-items/{key}/reviews（按轮次取最大 rejected 轮）。
 * 原型需求 key 未入库（404）→ 显示「暂无驳回记录」空态，不再回退 store 评审记录。
 */
function RejectedReviewBlock({ r }: { r: Requirement }) {
  const { data, isLoading } = useRequirementReviews(r.key)
  const { data: briefRows } = useUserBriefs()
  const briefs = toBrief(briefRows)

  const rejected = (() => {
    const hit = [...(data ?? [])].filter((v) => v.result === 'rejected').sort((a, b) => b.round - a.round)[0]
    return hit
      ? { round: hit.round as number | undefined, comment: hit.comment ?? '', who: briefs.get(hit.reviewerId)?.name ?? '评审人', at: hit.decidedAt?.slice(0, 10) }
      : undefined
  })()

  if (isLoading) {
    return <div className="text-xs text-txt-low">评审记录加载中…</div>
  }
  return (
    <div>
      <div className="mb-1.5 text-[11px] font-semibold text-txt-low">最近驳回意见</div>
      {rejected ? (
        <div className="rounded-lg border border-bad/30 bg-bad-bg/60 px-3 py-2">
          <div className="flex flex-wrap items-center gap-2 text-[11px] text-bad-deep">
            <Pill tone="bad">已驳回</Pill>
            {rejected.round != null && <span className="font-mono">第 {rejected.round} 轮</span>}
            <span>{rejected.who}</span>
            {rejected.at && <span className="text-txt-low">{rejected.at}</span>}
          </div>
          <p className="mt-1.5 whitespace-pre-wrap text-xs leading-5 text-txt-hi">{rejected.comment || '（未填写意见）'}</p>
        </div>
      ) : (
        <div className="text-xs text-txt-low">暂无驳回记录</div>
      )}
    </div>
  )
}
