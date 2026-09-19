// 工程底座 · MR 详情：单测检测门禁（R8）/ rebase / 冲突解决 / 基线关联（R9）/ 自研内核真机接通
// B2：数据链路去 mock（404 → 错误态）/ draft 可评审（UT-33）/ 评审人「我」标记 / 规则透明化（UT-34）
import { useState, useEffect, useMemo } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  ChevronDown,
  FileCode2,
  FlaskConical,
  GitBranch,
  GitMerge,
  GitPullRequest,
  Lock,
  RotateCcw,
  ShieldCheck,
  Undo2,
  Sparkles,
  History,
  Loader2,
  XCircle,
  Zap,
} from 'lucide-react'
import { baselineById, repoById, useStore, userById, workItemByKey } from '../../data/store'
import { useBriefMap } from '../../api/users'
import type { FileDiff, MergeRequest } from '../../data/types'
import {
  useMergeRequest,
  useMrDiff,
  mrsApi,
  type RemoteMergeRequest,
  type RemoteBlameResult,
  type RemoteBlameLine,
} from '../../api/queries'
import { ApiError } from '../../api/client'
import { useAuth } from '../../api/AuthContext'
import { Avatar, Badge, Btn, Card, CardHeader, Empty, Pill, Spinner, StatusDot, runStatusText } from '../../components/ui'
import { GlossaryButton } from '../../components/GlossaryButton'
import type { PageProps } from '../../nav'
import { UnitTestBadge } from './ReviewPage'

type Nav = PageProps['nav']
const isTestPath = (p: string) => p.includes('/test/') || p.includes('.test.') || p.includes('Test.java')
const MR_STATUS: Record<MergeRequest['status'], { tone: 'ok' | 'neutral' | 'purple' | 'bad'; text: string }> = {
  open: { tone: 'ok', text: '开启中' },
  draft: { tone: 'neutral', text: '草稿' },
  merged: { tone: 'purple', text: '已合并' },
  closed: { tone: 'bad', text: '已关闭' },
}
const BL_TEXT = { draft: '草稿', in_review: '审批中', approved: '已定版', superseded: '已废止' } as const

export default function MrDetailPage({ nav, id }: PageProps) {
  useStore()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const { data: remoteMr, refetch, isLoading, error } = useMergeRequest(id)
  const { data: remoteDiffData } = useMrDiff(id)
  const [tab, setTab] = useState<'diffs' | 'comments'>('diffs')
  const [actionErr, setActionErr] = useState<string | undefined>(undefined)

  // S-1': MR 打开时自动触发后台异步预热逐文件 Blame 缓存
  useEffect(() => {
    if (id && remoteMr) {
      mrsApi.preloadBlame(id).catch((err) => {
        console.warn('MR blame preload failed:', err)
      })
    }
  }, [id, remoteMr])

  // 缺少 MR 标识
  if (!id) {
    return (
      <div>
        <Btn variant="ghost" onClick={() => nav.go('review')} className="mb-4 -ml-3">
          <ArrowLeft size={14} />返回评审列表
        </Btn>
        <Empty text="缺少评审标识" />
      </div>
    )
  }

  // B2 去 mock：远程 404/失败 → 错误态，不再回退 store 假数据
  if (error) {
    const notFound = error instanceof ApiError && error.status === 404
    return (
      <div>
        <Btn variant="ghost" onClick={() => nav.go('review')} className="mb-4 -ml-3">
          <ArrowLeft size={14} />返回评审列表
        </Btn>
        <Empty text={notFound ? '未找到该评审' : `评审加载失败：${error.message}`} />
      </div>
    )
  }

  if (isLoading || !remoteMr) {
    return (
      <div>
        <Btn variant="ghost" onClick={() => nav.go('review')} className="mb-4 -ml-3">
          <ArrowLeft size={14} />返回评审列表
        </Btn>
        <Empty text="评审加载中…" size="sm" icon={<Spinner />} />
      </div>
    )
  }

  // 映射远程数据为前端 MergeRequest 格式（数据源已唯一：自研内核远程 API）
  const isRemote = true
  const r: RemoteMergeRequest = remoteMr
  // UT-34 规则透明化：门禁覆盖率阈值读 gateConfig，缺省时按 60/80 兜底
  const gates = {
    totalCoverage: r.gateConfig?.totalCoverage ?? 60,
    patchCoverage: r.gateConfig?.patchCoverage ?? 80,
  }
  const diffsToUse: FileDiff[] = (remoteDiffData?.files ?? r.diffs ?? []).map((fd) => ({
    path: fd.path,
    status: fd.status,
    additions: fd.additions,
    deletions: fd.deletions,
    lines: fd.lines.map((l) => ({
      type: l.type,
      oldNo: l.oldNo,
      newNo: l.newNo,
      text: l.text,
    })),
  }))

  const mr: MergeRequest = {
    id: r.id,
    number: r.number,
    title: r.title,
    description: r.description ?? '',
    repoId: r.repoId,
    sourceBranch: r.sourceBranch,
    targetBranch: r.targetBranch,
    authorId: r.authorId,
    reviewers: r.reviewers,
    status: r.status,
    checks: (r.checks ?? []).map((c) => ({
      name: c.name,
      status: (c.status === 'passed' ? 'passed' : c.status === 'failed' ? 'failed' : 'pending') as any,
    })),
    conflicts: r.conflicts,
    createdAt: r.createdAt ? r.createdAt.slice(0, 16).replace('T', ' ') : '刚刚',
    diffs: diffsToUse,
    comments: (r.comments ?? []).map((c) => ({
      id: c.id,
      authorId: c.authorId,
      text: c.text,
      createdAt: c.createdAt ? c.createdAt.slice(0, 16).replace('T', ' ') : '',
    })),
    linkedWorkItemKey: r.linkedWorkItemKey,
    additions: remoteDiffData ? remoteDiffData.totalAdditions : r.additions,
    deletions: remoteDiffData ? remoteDiffData.totalDeletions : r.deletions,
    unitTestCheck: {
      hasTests: r.unitTestCheck.hasTests,
      testFiles: r.unitTestCheck.testFiles ?? [],
      passed: r.unitTestCheck.passed,
      coverageTotal: r.unitTestCheck.coverageTotal,
      coverageDelta: r.unitTestCheck.coverageDelta,
      gatePassed: r.unitTestCheck.gatePassed,
      exempt: r.unitTestCheck.exempt
        ? {
            reason: (r.unitTestCheck.exempt as any).reason ?? '已豁免',
            approvedById: (r.unitTestCheck.exempt as any).approvedById ?? r.authorId,
          }
        : undefined,
    },
    conflictFiles: r.conflictFiles ?? [],
    conflictResolutions: (r.conflictResolutions ?? []).map((cr) => ({
      filePath: cr.filePath,
      solution: cr.solution,
      confirmedById: cr.confirmedById ?? r.authorId,
      reviewedById: cr.reviewedById ?? r.authorId,
      resolvedAt: cr.resolvedAt ? cr.resolvedAt.slice(0, 16).replace('T', ' ') : '',
    })),
    basedOnBaselineId: r.basedOnBaselineId,
    rebaseRequired: r.rebaseRequired,
  }

  // 动作处理：全部走自研内核远程 API（不再回退 store）；失败记录日志，页面保持当前状态不崩
  const handleReview = async (state: 'approved' | 'changes_requested') => {
    try {
      await mrsApi.review(mr.id, state)
      await refetch()
    } catch (err) {
      console.error('MR review failed:', err)
    }
  }

  const handleExempt = async (reason: string) => {
    try {
      await mrsApi.exemptUnitTest(mr.id, reason)
      await refetch()
    } catch (err) {
      console.error('MR exempt failed:', err)
    }
  }

  const handleResolveConflict = async (filePath: string, solution: string) => {
    try {
      await mrsApi.resolveConflict(mr.id, filePath, solution)
      await refetch()
    } catch (err) {
      console.error('MR conflict resolve failed:', err)
    }
  }

  const handleRebase = async () => {
    try {
      await mrsApi.rebase(mr.id)
      await refetch()
    } catch (err) {
      console.error('MR rebase failed:', err)
    }
  }

  const handleMerge = async (): Promise<{ ok: boolean; reasons: string[] }> => {
    try {
      const res = await mrsApi.merge(mr.id)
      if (res.ok) {
        await refetch()
        return { ok: true, reasons: [] }
      }
      return { ok: false, reasons: [res.message] }
    } catch (err: any) {
      const msg = err?.message || '合并门禁检查未通过'
      return { ok: false, reasons: [msg] }
    }
  }

  const handleAddComment = async (text: string) => {
    try {
      await mrsApi.addComment(mr.id, text)
      await refetch()
    } catch (err) {
      console.error('MR addComment failed:', err)
    }
  }

  // ⑥h 评审生命周期补全：关闭/重开（权限=作者本人或管理员，与服务端口径一致；非法迁移由后端 4xx 拦截）
  const isMrAuthor = !!user && user.id === mr.authorId
  const isPlatformAdmin = user?.platformRole === 'OWNER' || user?.platformRole === 'ADMIN'
  const canManageMr = isMrAuthor || isPlatformAdmin

  const handleClose = async () => {
    setActionErr(undefined)
    try {
      await mrsApi.close(mr.id)
      await refetch()
      // 列表/角标同刷（QA 复审 NICE）：关闭改变 open 计数，侧栏「代码评审」角标需同步
      await queryClient.invalidateQueries({ queryKey: ['mrs'] })
    } catch (err: any) {
      setActionErr(err?.message || '关闭评审失败')
    }
  }

  const handleReopen = async () => {
    setActionErr(undefined)
    try {
      await mrsApi.reopen(mr.id)
      await refetch()
      await queryClient.invalidateQueries({ queryKey: ['mrs'] })
    } catch (err: any) {
      setActionErr(err?.message || '重新打开失败')
    }
  }

  return (
    <div>
      <Btn variant="ghost" onClick={() => nav.go('review')} className="mb-3 -ml-3">
        <ArrowLeft size={14} />返回评审列表
      </Btn>

      <MrHeader
        mr={mr}
        nav={nav}
        isRemote={isRemote}
        actions={
          canManageMr && (mr.status === 'open' || mr.status === 'draft') ? (
            <ConfirmActionBtn label="关闭评审" confirmLabel="确认关闭？" icon={<XCircle size={13} />} variant="danger" onConfirm={handleClose} />
          ) : canManageMr && mr.status === 'closed' ? (
            <ConfirmActionBtn label="重新打开" confirmLabel="确认重开？" icon={<RotateCcw size={13} />} onConfirm={handleReopen} />
          ) : undefined
        }
      />

      {mr.status === 'merged' && (
        <div className="mb-4 flex items-center gap-2 rounded-lg border border-brand/25 bg-brand-bg px-4 py-3 text-sm font-medium text-brand-deep">
          <GitMerge size={16} />
          此 MR 已合并到 {mr.targetBranch}，评审与单测门禁记录已归档留痕。
        </div>
      )}

      {mr.status === 'closed' && (
        <div className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-bad/25 bg-bad-bg px-4 py-3 text-sm font-medium text-bad-deep">
          <XCircle size={16} />
          此评审已关闭，合并门禁与评审操作全部停用。
          {canManageMr && <span className="text-xs font-normal opacity-80">可在右上角「重新打开」恢复评审流程。</span>}
        </div>
      )}

      {actionErr && (
        <div className="mb-4 rounded-md bg-bad-bg px-3 py-2 text-xs text-bad-deep">{actionErr}</div>
      )}

      <UnitTestCard mr={mr} gates={gates} onExempt={handleExempt} />
      <UnitTestRulesPanel gates={gates} />
      {mr.status !== 'merged' && mr.status !== 'closed' && (
        <GateBar
          mr={mr}
          onMerge={handleMerge}
          onRebase={handleRebase}
          onResolveConflict={handleResolveConflict}
        />
      )}

      <div className="mt-4 grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(0,1fr)_17rem]">
        <Card className="min-w-0">
          <div className="flex gap-1 border-b border-line px-3 pt-1">
            {([['diffs', `变更 ${mr.diffs.length}`], ['comments', `评论 ${mr.comments.length}`]] as const).map(
              ([key, label]) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => setTab(key)}
                  className={`-mb-px cursor-pointer border-b-2 px-3.5 py-2.5 text-sm transition-colors ${
                    tab === key
                      ? 'border-brand font-semibold text-txt-hi'
                      : 'border-transparent text-txt-mid hover:text-txt-hi'
                  }`}
                >
                  {label}
                </button>
              )
            )}
          </div>
          {tab === 'diffs' ? (
            mr.diffs.length === 0 ? (
              <Empty text="无变更文件" />
            ) : (
              mr.diffs.map((d) => (
                <DiffCard key={d.path} d={d} mrId={mr?.id} isRemote={isRemote} />
              ))
            )
          ) : (
            <CommentsPanel mr={mr} onAddComment={handleAddComment} />
          )}
        </Card>

        <aside className="space-y-4">
          <ReviewersCard mr={mr} onReview={handleReview} />
          <ChecksCard mr={mr} />
          <BaselineCard mr={mr} nav={nav} />
        </aside>
      </div>
    </div>
  )
}

// ==================== 头部 ====================
function MrHeader({ mr, nav, isRemote, actions }: { mr: MergeRequest; nav: Nav; isRemote?: boolean; actions?: ReactNode }) {
  const repo = repoById(mr.repoId)
  const briefOf = useBriefMap()
  const author = briefOf(mr.authorId) ?? userById(mr.authorId)
  const wi = mr.linkedWorkItemKey ? workItemByKey(mr.linkedWorkItemKey) : undefined
  const st = MR_STATUS[mr.status]
  return (
    <div className="mb-4">
      <div className="flex flex-wrap items-center gap-2.5">
        <GitPullRequest
          size={20}
          className={
            mr.status === 'open'
              ? 'text-cat-green'
              : mr.status === 'merged'
              ? 'text-cat-purple'
              : 'text-txt-low'
          }
        />
        <h1 className="text-xl font-bold text-txt-hi">{mr.title}</h1>
        <span className="font-mono text-sm text-txt-low">!{mr.number}</span>
        {isRemote && (
          <span className="inline-flex items-center gap-1 rounded bg-brand/10 px-2 py-0.5 text-xs font-semibold text-brand">
            <Sparkles size={11} />自研内核
          </span>
        )}
        <Pill tone={st.tone}>{st.text}</Pill>
        <UnitTestBadge utc={mr.unitTestCheck} />
        {/* 头部操作区：按状态与权限渲染「关闭评审 / 重新打开」（确认式，⑥h 批） */}
        {actions && <span className="ml-auto">{actions}</span>}
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-txt-mid">
        <span className="font-mono">{repo?.name ?? 'teamone'}</span>
        <span className="flex items-center gap-1 font-mono">
          <GitBranch size={11} />
          {mr.sourceBranch} → {mr.targetBranch}
        </span>
        <span className="flex items-center gap-1.5">
          <Avatar userId={mr.authorId} size={18} />
          {author?.name ?? '管理员'} 创建于 {mr.createdAt}
        </span>
        {wi && (
          <button
            type="button"
            onClick={() => nav.go(wi.key.startsWith('D-') ? 'defects' : 'tasks', wi.id)}
            title={wi.title}
            className="cursor-pointer font-mono text-brand-deep hover:underline"
          >
            {wi.key}
          </button>
        )}
      </div>
      <p className="mt-2.5 whitespace-pre-wrap text-sm text-txt-mid">{mr.description}</p>
    </div>
  )
}

// ==================== 确认式动作按钮（⑥h 批：关闭/重开两步确认，防误触） ====================
function ConfirmActionBtn({
  label,
  confirmLabel,
  icon,
  variant = 'default',
  onConfirm,
}: {
  label: string
  confirmLabel: string
  icon?: ReactNode
  variant?: 'default' | 'danger'
  onConfirm: () => Promise<void>
}) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  if (!confirming) {
    return (
      <Btn variant={variant} onClick={() => setConfirming(true)}>
        {icon}
        {label}
      </Btn>
    )
  }
  return (
    <span className="inline-flex items-center gap-1.5">
      <Btn
        variant={variant}
        disabled={busy}
        onClick={async () => {
          setBusy(true)
          try {
            await onConfirm()
          } finally {
            setBusy(false)
            setConfirming(false)
          }
        }}
      >
        {busy ? <Loader2 size={13} className="animate-spin" /> : icon}
        {confirmLabel}
      </Btn>
      <Btn variant="ghost" disabled={busy} onClick={() => setConfirming(false)}>
        取消
      </Btn>
    </span>
  )
}

// ==================== R8 单测检测卡 ====================
function UnitTestCard({
  mr,
  gates,
  onExempt,
}: {
  mr: MergeRequest
  gates: { totalCoverage: number; patchCoverage: number }
  onExempt: (reason: string) => Promise<void>
}) {
  const utc = mr.unitTestCheck
  const briefOf = useBriefMap()
  const [applying, setApplying] = useState(false)
  const [reason, setReason] = useState('')
  const fails: string[] = []
  if (!utc.hasTests) fails.push('未检测到关联单测文件（启发式 A 未命中）')
  if (!utc.passed) fails.push('单测运行未通过')
  if (utc.coverageTotal < gates.totalCoverage) fails.push(`整体覆盖率 ${utc.coverageTotal}% 低于门禁 ${gates.totalCoverage}%`)
  if (utc.coverageDelta < gates.patchCoverage) fails.push(`patch 覆盖率 ${utc.coverageDelta}% 低于门禁 ${gates.patchCoverage}%`)

  const doSubmitExempt = async () => {
    if (!reason.trim()) return
    await onExempt(reason.trim())
    setApplying(false)
  }

  return (
    <Card>
      <CardHeader
        title={
          <span className="flex items-center gap-1.5">
            <FlaskConical size={14} className="text-cat-teal" />
            单测检测（R8 门禁：整体 ≥{gates.totalCoverage}% · patch ≥{gates.patchCoverage}%）
          </span>
        }
        extra={
          utc.gatePassed ? (
            utc.exempt ? (
              <Pill tone="teal">豁免放行</Pill>
            ) : (
              <Pill tone="ok">
                <CheckCircle2 size={11} />门禁通过
              </Pill>
            )
          ) : (
            <Pill tone="warn">
              <AlertTriangle size={11} />门禁未通过
            </Pill>
          )
        }
      />
      <div className="px-4 py-3">
        {utc.exempt ? (
          <div className="flex flex-wrap items-center gap-2 rounded-md bg-info-bg px-3 py-2.5 text-sm text-cat-teal">
            <CheckCircle2 size={14} className="shrink-0" />
            <span className="font-medium">已豁免：{utc.exempt.reason}</span>
            <span className="flex items-center gap-1.5 text-xs text-txt-mid">
              批准人 <Avatar userId={utc.exempt.approvedById} size={18} />{' '}
              {briefOf(utc.exempt.approvedById)?.name ?? userById(utc.exempt.approvedById)?.name ?? '管理员'}
            </span>
          </div>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-sm">
              <span className="flex items-center gap-1.5 font-medium">
                {utc.hasTests ? (
                  <>
                    <CheckCircle2 size={15} className="text-ok" />
                    <span className="text-ok-deep">含单测 ✓</span>
                  </>
                ) : (
                  <>
                    <AlertTriangle size={15} className="text-cat-orange" />
                    <span className="font-semibold text-cat-orange">无单测 ⚠</span>
                  </>
                )}
              </span>
              <span className="text-txt-mid">
                单测运行：
                {utc.passed ? (
                  <span className="font-medium text-ok-deep">passed ✓</span>
                ) : (
                  <span className="font-medium text-bad-deep">failed ✗</span>
                )}
              </span>
              <CovItem label="整体覆盖率" value={utc.coverageTotal} gate={gates.totalCoverage} />
              <CovItem label="patch 覆盖率" value={utc.coverageDelta} gate={gates.patchCoverage} />
            </div>
            {utc.testFiles.length > 0 && (
              <div className="mt-2.5 flex flex-wrap items-center gap-1.5 text-xs text-txt-low">
                测试文件：
                {utc.testFiles.map((f) => (
                  <span key={f} className="rounded bg-ink-700 px-1.5 py-0.5 font-mono text-txt-mid">
                    {f}
                  </span>
                ))}
              </div>
            )}
            {!utc.gatePassed && (
              <div className="mt-3 rounded-md bg-warn-bg px-3 py-2.5 text-sm text-warn-deep">
                <div className="flex items-center gap-1.5 font-semibold">
                  <AlertTriangle size={14} />未达门禁项
                </div>
                <ul className="mt-1 list-disc pl-5 text-xs leading-5">
                  {fails.map((f) => (
                    <li key={f}>{f}</li>
                  ))}
                </ul>
                {!applying ? (
                  <Btn variant="ghost" className="mt-1.5" onClick={() => setApplying(true)}>
                    申请豁免
                  </Btn>
                ) : (
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    <input
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                      placeholder="填写豁免理由（如：启发式误报 / 纯依赖升级无逻辑变更）"
                      className="min-w-[14rem] flex-1 rounded-md border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none focus:border-brand"
                    />
                    <Btn variant="primary" disabled={!reason.trim()} onClick={doSubmitExempt}>
                      提交豁免
                    </Btn>
                    <Btn variant="ghost" onClick={() => setApplying(false)}>
                      取消
                    </Btn>
                  </div>
                )}
                <p className="mt-1.5 text-[11px] opacity-80">
                  豁免需评审人批准并留审计记录；未通过或未豁免时合并按钮保持禁用。
                </p>
              </div>
            )}
          </>
        )}
      </div>
    </Card>
  )
}

function CovItem({ label, value, gate }: { label: string; value: number; gate: number }) {
  const ok = value >= gate
  return (
    <span className="flex items-center gap-1.5">
      <span className="text-xs text-txt-low">{label}</span>
      <span className={`text-sm font-semibold tabular-nums ${ok ? 'text-ok-deep' : 'text-bad-deep'}`}>{value}%</span>
      <span
        className={`rounded px-1 py-px text-[10px] font-medium ${
          ok ? 'bg-ok-bg text-ok-deep' : 'bg-bad-bg text-bad-deep'
        }`}
      >
        门禁 ≥{gate}%
      </span>
    </span>
  )
}

// ==================== 检查规则说明面板（B2 · UT-34 规则透明化） ====================
/**
 * 可折叠「检查规则说明」：说清单测检测的数据来源、启发式 A 规则、覆盖率口径、
 * 当前阈值（读 mr.gateConfig，缺省 60/80）与豁免流程；头部放全站 <GlossaryButton />。
 */
function UnitTestRulesPanel({ gates }: { gates: { totalCoverage: number; patchCoverage: number } }) {
  const [open, setOpen] = useState(false)
  return (
    <Card className="mt-4">
      <div className="flex items-center justify-between px-4 py-2.5">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="flex cursor-pointer items-center gap-1.5 text-sm font-semibold text-txt-hi"
        >
          <ChevronDown size={14} className={`text-txt-low transition-transform ${open ? '' : '-rotate-90'}`} />
          检查规则说明
        </button>
        <GlossaryButton />
      </div>
      {open && (
        <div className="space-y-3 border-t border-line px-4 py-3 text-xs leading-5 text-txt-mid">
          <div>
            <div className="mb-0.5 font-semibold text-txt-low">数据来源</div>
            检测数据由 CI 单测作业执行完成后回传（用例结果与覆盖率指标）；演示环境展示的是种子数据。
          </div>
          <div>
            <div className="mb-0.5 font-semibold text-txt-low">启发式 A：单测文件识别</div>
            按路径约定识别关联单测——变更路径含 <span className="font-mono">/test/</span>、文件名含{' '}
            <span className="font-mono">.test.</span> 或以 <span className="font-mono">Test.java</span> 结尾视为测试文件；
            未命中时判定「无单测」，可在上方检测卡申请豁免。
          </div>
          <div>
            <div className="mb-0.5 font-semibold text-txt-low">覆盖率口径</div>
            整体覆盖率 = 全量可执行行中被测试覆盖的比例；patch 覆盖率 = 本次 MR 新增行中被测试覆盖的比例，只约束增量代码。
          </div>
          <div>
            <div className="mb-0.5 font-semibold text-txt-low">当前门禁阈值</div>
            整体覆盖率 ≥ <span className="font-mono font-semibold text-txt-hi">{gates.totalCoverage}%</span> · patch 覆盖率 ≥{' '}
            <span className="font-mono font-semibold text-txt-hi">{gates.patchCoverage}%</span>
            （仓库合并门禁配置 <span className="font-mono">gateConfig</span>，未配置时默认 60% / 80%）。
          </div>
          <div>
            <div className="mb-0.5 font-semibold text-txt-low">豁免流程</div>
            未达门禁时在检测卡「申请豁免」并填写理由，由评审人批准放行；豁免记录留审计（批准人与时间），
            未通过且未豁免时合并按钮保持禁用。
          </div>
        </div>
      )}
    </Card>
  )
}

// ==================== 合并门禁操作条 ====================
function GateBar({
  mr,
  onMerge,
  onRebase,
  onResolveConflict,
}: {
  mr: MergeRequest
  onMerge: () => Promise<{ ok: boolean; reasons: string[] }>
  onRebase: () => Promise<void>
  onResolveConflict: (filePath: string, solution: string) => Promise<void>
}) {
  const [solutions, setSolutions] = useState<Record<string, string>>({})
  const [mergeErr, setMergeErr] = useState<string[] | undefined>(undefined)
  const blockers: string[] = []
  // UT-33：如实说明阻塞原因，不再附加与实际不符的操作引导
  if (mr.reviewers.some((r) => r.state !== 'approved')) blockers.push('评审未全部批准')
  const failed = mr.checks.filter((c) => c.status === 'failed')
  if (failed.length > 0) blockers.push(`失败检查项：${failed.map((c) => c.name).join('、')}`)
  if (!mr.unitTestCheck.gatePassed) blockers.push('单测门禁未通过（见上方检测卡，可申请豁免）')
  const canMerge = mr.status === 'open' && blockers.length === 0 && mr.conflictFiles.length === 0

  const doMerge = async () => {
    const res = await onMerge()
    setMergeErr(res.ok ? undefined : res.reasons)
  }

  return (
    <Card className="mt-4">
      <CardHeader
        title={
          <span className="flex items-center gap-1.5">
            <ShieldCheck size={14} />合并门禁
          </span>
        }
        extra={
          <Pill tone={canMerge ? 'ok' : 'warn'}>
            {canMerge ? '全部满足' : `${blockers.length + mr.conflictFiles.length} 项未满足`}
          </Pill>
        }
      />
      <div className="space-y-2 px-4 py-3">
        {mr.rebaseRequired && (
          <div className="flex flex-wrap items-center gap-2 rounded-md bg-warn-bg px-3 py-2 text-sm text-warn-deep">
            <AlertTriangle size={14} className="shrink-0" />
            <span className="min-w-0 flex-1">源分支落后目标分支 {mr.targetBranch}，合并前需先 rebase</span>
            <Btn onClick={onRebase}>
              <Undo2 size={13} />标记已 rebase
            </Btn>
          </div>
        )}
        {mr.conflictFiles.map((f) => (
          <div key={f} className="flex flex-wrap items-center gap-2 rounded-md bg-bad-bg px-3 py-2">
            <AlertTriangle size={14} className="shrink-0 text-bad" />
            <span className="font-mono text-xs text-bad-deep">{f}</span>
            <input
              value={solutions[f] ?? ''}
              onChange={(e) => setSolutions({ ...solutions, [f]: e.target.value })}
              placeholder="填写冲突解决方案说明（确认后经评审人复核留痕）"
              className="min-w-[12rem] flex-1 rounded-md border border-line bg-canvas px-2 py-1 text-xs text-txt-hi outline-none focus:border-brand"
            />
            <Btn
              variant="danger"
              disabled={!(solutions[f] ?? '').trim()}
              onClick={async () => {
                await onResolveConflict(f, solutions[f] ?? '')
                setSolutions({ ...solutions, [f]: '' })
              }}
            >
              标记已解决
            </Btn>
          </div>
        ))}
        {(mr.conflictResolutions?.length ?? 0) > 0 && (
          <div className="rounded-md border border-line px-3 py-2.5">
            <div className="text-xs font-semibold text-txt-low">冲突解决记录（确认 → 复核留痕）</div>
            {mr.conflictResolutions!.map((r, i) => (
              <div key={i} className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
                <span className="font-mono text-txt-mid">{r.filePath}</span>
                <span className="text-txt-mid">{r.solution}</span>
                <span className="flex items-center gap-1 text-txt-low">
                  确认 <Avatar userId={r.confirmedById} size={15} />
                </span>
                <span className="flex items-center gap-1 text-txt-low">
                  复核 <Avatar userId={r.reviewedById} size={15} />
                </span>
                <span className="text-txt-low tabular-nums">{r.resolvedAt}</span>
              </div>
            ))}
          </div>
        )}
        {blockers.map((t) => (
          <div key={t} className="flex items-center gap-2 text-xs text-txt-mid">
            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-warn" />
            {t}
          </div>
        ))}
        <div className="flex flex-wrap items-center gap-3 border-t border-line pt-3">
          <Btn variant="primary" disabled={!canMerge} onClick={doMerge}>
            <GitMerge size={15} />合并到 {mr.targetBranch}
          </Btn>
          {mr.status !== 'open' ? (
            <span className="text-xs text-txt-mid">当前状态「{MR_STATUS[mr.status].text}」，不可合并</span>
          ) : canMerge ? (
            <span className="flex items-center gap-1 text-xs font-medium text-ok-deep">
              <CheckCircle2 size={13} />全部门禁满足，可执行合并
            </span>
          ) : (
            <span className="text-xs text-txt-mid">满足全部门禁后可合并（评审 / 检查 / 单测 / rebase / 冲突）</span>
          )}
        </div>
        {mergeErr && (
          <div className="rounded-md bg-bad-bg px-3 py-2 text-xs text-bad-deep">合并被拦截：{mergeErr.join('；')}</div>
        )}
      </div>
    </Card>
  )
}

// ==================== 变更 diff ====================
function DiffCard({ d, mrId, isRemote }: { d: FileDiff; mrId?: string; isRemote?: boolean }) {
  const [showBlame, setShowBlame] = useState(false)
  const [blameData, setBlameData] = useState<RemoteBlameResult | null>(null)
  const [loadingBlame, setLoadingBlame] = useState(false)
  const [blameError, setBlameError] = useState<string | null>(null)

  const handleToggleBlame = async () => {
    if (!showBlame) {
      setShowBlame(true)
      if (!blameData && mrId && isRemote) {
        setLoadingBlame(true)
        setBlameError(null)
        try {
          const res = await mrsApi.getBlame(mrId, d.path)
          setBlameData(res)
        } catch (e: any) {
          setBlameError(e?.message || 'Blame 加载失败')
        } finally {
          setLoadingBlame(false)
        }
      }
    } else {
      setShowBlame(false)
    }
  }

  const blameMap = useMemo(() => {
    const map = new Map<number, RemoteBlameLine>()
    if (blameData?.lines) {
      for (const line of blameData.lines) {
        map.set(line.lineNo, line)
      }
    }
    return map
  }, [blameData])

  return (
    <div className="border-b border-line last:border-b-0">
      <div className="flex flex-wrap items-center gap-2 border-b border-line bg-card px-4 py-2">
        <FileCode2 size={13} className="shrink-0 text-txt-low" />
        <span className="min-w-0 truncate font-mono text-xs font-medium text-txt-hi">{d.path}</span>
        <Badge tone={d.status === 'added' ? 'ok' : d.status === 'removed' ? 'bad' : 'neutral'}>
          {d.status === 'added' ? '新增' : d.status === 'removed' ? '删除' : '修改'}
        </Badge>
        {isTestPath(d.path) ? <Badge tone="ok">有测试</Badge> : <Badge tone="warn">缺测试</Badge>}

        {/* S-1' 逐文件 Blame 溯源与缓存开关 */}
        {isRemote && mrId && d.status !== 'removed' && (
          <button
            type="button"
            onClick={handleToggleBlame}
            className={`inline-flex items-center gap-1 rounded border px-2 py-0.5 text-xs font-medium transition-colors cursor-pointer ${
              showBlame
                ? 'border-brand bg-brand/10 text-brand'
                : 'border-line bg-ink-800 text-txt-mid hover:text-txt-hi hover:border-line-hi'
            }`}
            title="行级追溯提交作者、时间与消息摘要（L1/L2两级缓存加速）"
          >
            {loadingBlame ? (
              <Loader2 size={12} className="animate-spin text-brand" />
            ) : (
              <History size={12} />
            )}
            <span>Blame 溯源</span>
          </button>
        )}

        {showBlame && blameData && (
          <span className="inline-flex items-center gap-1 text-[11px] font-mono">
            {blameData.fromCache ? (
              <span className="inline-flex items-center gap-0.5 text-brand font-semibold">
                <Zap size={11} />
                缓存命中 ({blameData.durationMs}ms)
              </span>
            ) : (
              <span className="text-txt-mid">
                内核计算 ({blameData.durationMs}ms)
              </span>
            )}
          </span>
        )}

        {blameError && (
          <span className="text-[11px] text-bad-deep">{blameError}</span>
        )}

        <span className="ml-auto shrink-0 text-xs tabular-nums">
          <span className="text-ok-deep">+{d.additions}</span> <span className="text-bad-deep">-{d.deletions}</span>
        </span>
      </div>
      <table className="w-full table-fixed border-collapse font-mono text-xs leading-5">
        <tbody>
          {d.lines.map((l, i) => {
            const blame = l.newNo ? blameMap.get(l.newNo) : undefined
            return (
              <tr key={i} className={l.type === 'add' ? 'bg-ok-bg' : l.type === 'del' ? 'bg-bad-bg' : ''}>
                {showBlame && (
                  <td className="w-48 select-none border-r border-line bg-ink-900/40 px-2 py-0.5 text-left align-top text-[11px] text-txt-low whitespace-nowrap overflow-hidden text-ellipsis">
                    {loadingBlame ? (
                      <span className="text-txt-low/50">...</span>
                    ) : blame ? (
                      <span
                        className="inline-flex items-center gap-1.5"
                        title={`${blame.commitSha}\n作者: ${blame.authorName} <${blame.authorEmail}>\n日期: ${blame.committedAt}\n说明: ${blame.summary}`}
                      >
                        <span className="font-mono text-brand font-medium">{blame.commitShort}</span>
                        <span className="text-txt-mid truncate max-w-[70px]">{blame.authorName}</span>
                      </span>
                    ) : l.type === 'add' ? (
                      <span className="text-ok-deep/70 text-[10px]">本次新增</span>
                    ) : (
                      <span className="text-txt-low/40">-</span>
                    )}
                  </td>
                )}
                <td className="w-10 select-none border-r border-line px-2 text-right align-top text-txt-low">{l.oldNo ?? ''}</td>
                <td className="w-10 select-none border-r border-line px-2 text-right align-top text-txt-low">{l.newNo ?? ''}</td>
                <td
                  className={`whitespace-pre-wrap break-all px-3 ${
                    l.type === 'add' ? 'text-ok-deep' : l.type === 'del' ? 'text-bad-deep' : 'text-txt-mid'
                  }`}
                >
                  {l.text}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

// ==================== 评论 ====================
function CommentsPanel({
  mr,
  onAddComment,
}: {
  mr: MergeRequest
  onAddComment: (text: string) => Promise<void>
}) {
  const briefOf = useBriefMap()
  const [text, setText] = useState('')

  const doSubmit = async () => {
    if (!text.trim()) return
    await onAddComment(text.trim())
    setText('')
  }

  return (
    <div className="px-4 py-3">
      <ul className="space-y-3">
        {mr.comments.map((c) => (
          <li key={c.id} className="flex gap-2.5">
            <Avatar userId={c.authorId} size={26} />
            <div className="min-w-0 flex-1 rounded-lg border border-line bg-canvas px-3 py-2">
              <div className="flex items-center gap-2 text-xs">
                <span className="font-semibold text-txt-hi">{briefOf(c.authorId)?.name ?? userById(c.authorId)?.name ?? '评审人'}</span>
                <span className="text-txt-low tabular-nums">{c.createdAt}</span>
              </div>
              <p className="mt-1 whitespace-pre-wrap text-sm text-txt-mid">{c.text}</p>
            </div>
          </li>
        ))}
        {mr.comments.length === 0 && <li className="py-8 text-center text-sm text-txt-low">暂无评论，发起第一条讨论吧</li>}
      </ul>
      <div className="mt-4 flex items-center gap-2">
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') doSubmit()
          }}
          placeholder="写下评审意见…"
          className="min-w-0 flex-1 rounded-md border border-line bg-canvas px-3 py-2 text-sm text-txt-hi outline-none focus:border-brand"
        />
        <Btn variant="primary" disabled={!text.trim()} onClick={doSubmit}>
          发表评论
        </Btn>
      </div>
    </div>
  )
}

// ==================== 侧栏 ====================
function ReviewersCard({
  mr,
  onReview,
}: {
  mr: MergeRequest
  onReview: (state: 'approved' | 'changes_requested') => Promise<void>
}) {
  // UT-33：批准/请求修改对 draft 与 open 均可用；当前登录用户是评审人时高亮并标记「我」
  const { user } = useAuth()
  const briefOf = useBriefMap()
  const canReview = mr.status === 'open' || mr.status === 'draft'
  const pill: Record<MergeRequest['reviewers'][number]['state'], ReactNode> = {
    approved: <Pill tone="ok">已批准</Pill>,
    changes_requested: <Pill tone="bad">请求修改</Pill>,
    pending: <Pill tone="neutral">待评审</Pill>,
  }
  return (
    <Card>
      <CardHeader
        title="评审人"
        extra={
          <span className="text-xs tabular-nums text-txt-low">
            {mr.reviewers.filter((r) => r.state === 'approved').length}/{mr.reviewers.length} 批准
          </span>
        }
      />
      <div className="space-y-2.5 px-4 py-3">
        {mr.reviewers.map((r) => {
          const u = briefOf(r.userId) ?? userById(r.userId)
          const isMe = !!user && r.userId === user.id
          return (
            <div
              key={r.userId}
              className={`flex items-center gap-2 rounded-md px-1.5 py-1 ${
                isMe ? 'bg-brand-bg ring-1 ring-brand/30' : ''
              }`}
            >
              <Avatar userId={r.userId} size={24} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="truncate text-sm text-txt-hi">{u?.name ?? '评审人'}</span>
                  {isMe && <Badge tone="brand">我</Badge>}
                </div>
                <div className="truncate text-[11px] text-txt-low">{u?.title ?? '开发工程师'}</div>
              </div>
              {pill[r.state]}
            </div>
          )
        })}
        {canReview && (
          <div className="flex gap-2 pt-1">
            <Btn className="flex-1" onClick={() => onReview('approved')}>
              <CheckCircle2 size={13} />批准
            </Btn>
            <Btn variant="danger" className="flex-1" onClick={() => onReview('changes_requested')}>
              请求修改
            </Btn>
          </div>
        )}
      </div>
    </Card>
  )
}

function ChecksCard({ mr }: { mr: MergeRequest }) {
  return (
    <Card>
      <CardHeader title="检查项" />
      <div className="space-y-2 px-4 py-3">
        {mr.checks.map((c) => (
          <div key={c.name} className="flex items-center gap-2 text-sm">
            <StatusDot status={c.status} />
            <span className="min-w-0 flex-1 truncate text-txt-mid">{c.name}</span>
            <span className={`shrink-0 text-xs ${c.status === 'failed' ? 'font-medium text-bad-deep' : 'text-txt-low'}`}>
              {runStatusText[c.status]}
            </span>
          </div>
        ))}
        {mr.checks.length === 0 && <div className="text-xs text-txt-low">无检查项</div>}
      </div>
    </Card>
  )
}

function BaselineCard({ mr, nav }: { mr: MergeRequest; nav: Nav }) {
  const bl = mr.basedOnBaselineId ? baselineById(mr.basedOnBaselineId) : undefined
  return (
    <Card>
      <CardHeader title="基线关联" />
      <div className="px-4 py-3">
        {bl ? (
          <button
            type="button"
            onClick={() => nav.go('repo', `${mr.repoId}#baselines`)}
            className="w-full cursor-pointer rounded-md border border-line px-3 py-2.5 text-left transition-colors hover:bg-ink-700"
          >
            <div className="flex items-center gap-1.5">
              <Lock size={12} className="shrink-0 text-cat-teal" />
              <span className="min-w-0 truncate text-sm font-medium text-txt-hi">{bl.name}</span>
            </div>
            <div className="mt-1.5 flex items-center gap-2 text-xs text-txt-low">
              <span className="font-mono">{bl.tagRef}</span>
              {bl.status === 'approved' ? (
                <Pill tone="ok">
                  <Lock size={10} />
                  {BL_TEXT[bl.status]}
                </Pill>
              ) : (
                <Pill tone={bl.status === 'in_review' ? 'warn' : 'neutral'}>{BL_TEXT[bl.status]}</Pill>
              )}
            </div>
            <div className="mt-1.5 text-[11px] font-medium text-brand-deep">查看仓库基线 Tab →</div>
          </button>
        ) : (
          <div className="text-xs text-txt-low">该 MR 未关联基线</div>
        )}
      </div>
    </Card>
  )
}
