// 工程底座 · 代码评审（MR 列表：单测门禁徽章 / rebase / 冲突标识 / 自研内核）
import { useState } from 'react'
import { AlertTriangle, GitBranch, GitPullRequest, MessageSquare, Sparkles } from 'lucide-react'
import { repoById, useStore, userById, workItemByKey } from '../../data/store'
import type { MergeRequest, UnitTestCheck } from '../../data/types'
import { useMergeRequests, type RemoteMergeRequest } from '../../api/queries'
import { useBriefMap } from '../../api/users'
import { Avatar, Card, Empty, PageHeader, Pill, StatusDot } from '../../components/ui'
import type { PageProps } from '../../nav'

type Filter = 'all' | 'open' | 'draft' | 'merged' | 'closed'
type Nav = PageProps['nav']

const FILTERS: [Filter, string][] = [['all', '全部'], ['open', '待评审'], ['draft', '草稿'], ['merged', '已合并'], ['closed', '已关闭']]
const STATUS_ICON: Record<MergeRequest['status'], string> = {
  open: 'text-cat-green', draft: 'text-txt-low', merged: 'text-cat-purple', closed: 'text-bad',
}

/** 单测徽章（R8）：豁免 > 无单测 > 通过 / 覆盖率不足 */
export function UnitTestBadge({ utc }: { utc: UnitTestCheck }) {
  if (utc.exempt) return <Pill tone="teal">已豁免</Pill>
  if (!utc.hasTests) return <Pill tone="orange">无单测</Pill>
  if (utc.gatePassed) return <Pill tone="ok">单测✓</Pill>
  return <Pill tone="warn">覆盖率不足</Pill>
}

export default function ReviewPage({ nav }: PageProps) {
  useStore()
  const [filter, setFilter] = useState<Filter>('all')

  const { data: remoteData } = useMergeRequests(undefined, filter === 'all' ? undefined : filter)
  const remoteItems: RemoteMergeRequest[] = remoteData?.items ?? []

  // 映射 Remote MR 为前端 MergeRequest 结构
  const mappedRemote: MergeRequest[] = remoteItems.map((r) => ({
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
    createdAt: r.createdAt ? r.createdAt.slice(5, 16).replace('T', ' ') : '刚刚',
    diffs: (r.diffs ?? []) as any,
    comments: (r.comments ?? []).map((c) => ({
      id: c.id,
      authorId: c.authorId,
      text: c.text,
      createdAt: c.createdAt ? c.createdAt.slice(5, 16).replace('T', ' ') : '',
    })),
    linkedWorkItemKey: r.linkedWorkItemKey,
    additions: r.additions,
    deletions: r.deletions,
    unitTestCheck: {
      hasTests: r.unitTestCheck.hasTests,
      testFiles: r.unitTestCheck.testFiles ?? [],
      passed: r.unitTestCheck.passed,
      coverageTotal: r.unitTestCheck.coverageTotal,
      coverageDelta: r.unitTestCheck.coverageDelta,
      gatePassed: r.unitTestCheck.gatePassed,
      exempt: r.unitTestCheck.exempt ? {
        reason: (r.unitTestCheck.exempt as any).reason ?? '已豁免',
        approvedById: (r.unitTestCheck.exempt as any).approvedById ?? r.authorId,
      } : undefined,
    },
    conflictFiles: r.conflictFiles ?? [],
    conflictResolutions: (r.conflictResolutions ?? []).map((cr) => ({
      filePath: cr.filePath,
      solution: cr.solution,
      confirmedById: cr.confirmedById ?? r.authorId,
      reviewedById: cr.reviewedById ?? r.authorId,
      resolvedAt: cr.resolvedAt ? cr.resolvedAt.slice(5, 16).replace('T', ' ') : '',
    })),
    basedOnBaselineId: r.basedOnBaselineId,
    rebaseRequired: r.rebaseRequired,
  }))

  // B2 去 mock：仅展示自研内核远程 MR（本地 mock id 'mr1'~'mr6' 进详情页会 404，不再混入假数据）
  const combined = mappedRemote

  const count = (f: Filter) => {
    if (f === 'all') return combined.length
    return combined.filter((m) => m.status === f).length
  }

  return (
    <div>
      <PageHeader
        title="代码评审"
        desc="MR 评审 · 单测检测门禁（整体 ≥60% / patch ≥80%）· 冲突解决留痕 · 自研 Git 内核"
      />
      <div className="mb-4 flex gap-1 overflow-x-auto border-b border-line">
        {FILTERS.map(([key, label]) => (
          <button
            key={key}
            type="button"
            onClick={() => setFilter(key)}
            className={`-mb-px shrink-0 cursor-pointer border-b-2 px-4 py-2 text-sm transition-colors ${
              filter === key ? 'border-brand font-semibold text-txt-hi' : 'border-transparent text-txt-mid hover:text-txt-hi'
            }`}
          >
            {label} <span className="tabular-nums text-txt-low">{count(key)}</span>
          </button>
        ))}
      </div>
      <Card>
        <ul className="divide-y divide-line">
          {combined.map((m) => (
            <MrRow key={m.id} m={m} nav={nav} isRemote={mappedRemote.some((rm) => rm.id === m.id)} />
          ))}
          {combined.length === 0 && (
            <li>
              <Empty text="没有符合条件的 MR" />
            </li>
          )}
        </ul>
      </Card>
    </div>
  )
}

function MrRow({ m, nav, isRemote }: { m: MergeRequest; nav: Nav; isRemote?: boolean }) {
  const repo = repoById(m.repoId)
  const briefOf = useBriefMap()
  const author = briefOf(m.authorId) ?? userById(m.authorId)
  const wi = m.linkedWorkItemKey ? workItemByKey(m.linkedWorkItemKey) : undefined
  const passed = m.checks.filter((c) => c.status === 'passed').length
  const failed = m.checks.filter((c) => c.status === 'failed').length
  const checkStatus = failed > 0 ? 'failed' : m.checks.length > 0 && passed === m.checks.length ? 'passed' : 'running'

  return (
    <li>
      <button
        type="button"
        onClick={() => nav.go('mr', m.id)}
        className="w-full cursor-pointer px-4 py-3 text-left transition-colors hover:bg-ink-700"
      >
        <div className="flex items-center gap-2.5">
          <GitPullRequest size={16} className={`shrink-0 ${STATUS_ICON[m.status]}`} />
          <span className="shrink-0 font-mono text-sm font-semibold text-brand-deep">!{m.number}</span>
          <span className={`min-w-0 truncate text-sm font-medium ${m.status === 'draft' ? 'text-txt-mid' : 'text-txt-hi'}`}>
            {m.title}
          </span>
          {isRemote && (
            <span className="inline-flex items-center gap-1 rounded bg-brand/10 px-1.5 py-0.5 text-[10px] font-medium text-brand">
              <Sparkles size={10} />自研
            </span>
          )}
          <UnitTestBadge utc={m.unitTestCheck} />
          {m.rebaseRequired && <Pill tone="warn">需 rebase</Pill>}
          {m.conflictFiles.length > 0 && (
            <span className="flex shrink-0 items-center gap-1 text-xs font-medium text-bad-deep">
              <AlertTriangle size={11} />
              {m.conflictFiles.length} 个冲突文件
            </span>
          )}
          <span className="flex-1" />
          <span className="shrink-0 text-xs tabular-nums">
            <span className="text-ok-deep">+{m.additions}</span> <span className="text-bad-deep">-{m.deletions}</span>
          </span>
          <span className="flex -space-x-1.5" title="评审人">
            {m.reviewers.map((r) => (
              <Avatar key={r.userId} userId={r.userId} size={20} />
            ))}
          </span>
        </div>
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 pl-[26px] text-xs text-txt-low">
          <span className="font-mono">{repo?.name ?? 'teamone'}</span>
          <span className="flex items-center gap-1 font-mono">
            <GitBranch size={11} />
            {m.sourceBranch} → {m.targetBranch}
          </span>
          <span className="flex items-center gap-1.5">
            <StatusDot status={checkStatus} size={7} />
            {failed > 0 ? (
              <span className="font-medium text-bad-deep">{failed} 项检查失败</span>
            ) : (
              <span>{passed}/{m.checks.length} 检查通过</span>
            )}
          </span>
          {wi && (
            <span className="font-mono text-brand-deep" title={wi.title}>
              {wi.key}
            </span>
          )}
          <span className="flex items-center gap-1">
            <Avatar userId={m.authorId} size={14} />
            {author?.name ?? '管理员'}
          </span>
          {m.comments.length > 0 && (
            <span className="flex items-center gap-0.5">
              <MessageSquare size={11} />
              {m.comments.length}
            </span>
          )}
          <span className="ml-auto tabular-nums">{m.createdAt}</span>
        </div>
      </button>
    </li>
  )
}
