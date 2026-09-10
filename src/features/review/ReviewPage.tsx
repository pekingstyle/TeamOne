// 工程底座 · 代码评审（MR 列表：单测门禁徽章 / rebase / 冲突标识）
import { useState } from 'react'
import { AlertTriangle, GitBranch, GitPullRequest, MessageSquare } from 'lucide-react'
import { mergeRequests, repoById, useStore, userById, workItemByKey } from '../../data/store'
import type { MergeRequest, UnitTestCheck } from '../../data/types'
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
  const count = (f: Filter) => (f === 'all' ? mergeRequests.length : mergeRequests.filter((m) => m.status === f).length)
  const list = filter === 'all' ? mergeRequests : mergeRequests.filter((m) => m.status === filter)
  return (
    <div>
      <PageHeader
        title="代码评审"
        desc="MR 评审 · 单测检测门禁（整体 ≥60% / patch ≥80%）· 冲突解决留痕"
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
          {list.map((m) => <MrRow key={m.id} m={m} nav={nav} />)}
          {list.length === 0 && <li><Empty text="没有符合条件的 MR" /></li>}
        </ul>
      </Card>
    </div>
  )
}

function MrRow({ m, nav }: { m: MergeRequest; nav: Nav }) {
  const repo = repoById(m.repoId)
  const author = userById(m.authorId)
  const wi = m.linkedWorkItemKey ? workItemByKey(m.linkedWorkItemKey) : undefined
  const passed = m.checks.filter((c) => c.status === 'passed').length
  const failed = m.checks.filter((c) => c.status === 'failed').length
  const checkStatus = failed > 0 ? 'failed' : m.checks.length > 0 && passed === m.checks.length ? 'passed' : 'running'
  return (
    <li>
      <button type="button" onClick={() => nav.go('mr', m.id)} className="w-full cursor-pointer px-4 py-3 text-left transition-colors hover:bg-ink-700">
        <div className="flex items-center gap-2.5">
          <GitPullRequest size={16} className={`shrink-0 ${STATUS_ICON[m.status]}`} />
          <span className="shrink-0 font-mono text-sm font-semibold text-brand-deep">!{m.number}</span>
          <span className={`min-w-0 truncate text-sm font-medium ${m.status === 'draft' ? 'text-txt-mid' : 'text-txt-hi'}`}>{m.title}</span>
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
            {m.reviewers.map((r) => <Avatar key={r.userId} userId={r.userId} size={20} />)}
          </span>
        </div>
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 pl-[26px] text-xs text-txt-low">
          <span className="font-mono">{repo?.name}</span>
          <span className="flex items-center gap-1 font-mono"><GitBranch size={11} />{m.sourceBranch} → {m.targetBranch}</span>
          <span className="flex items-center gap-1.5">
            <StatusDot status={checkStatus} size={7} />
            {failed > 0 ? <span className="font-medium text-bad-deep">{failed} 项检查失败</span> : <span>{passed}/{m.checks.length} 检查通过</span>}
          </span>
          {wi && <span className="font-mono text-brand-deep" title={wi.title}>{wi.key}</span>}
          <span className="flex items-center gap-1"><Avatar userId={m.authorId} size={14} />{author?.name}</span>
          {m.comments.length > 0 && (
            <span className="flex items-center gap-0.5"><MessageSquare size={11} />{m.comments.length}</span>
          )}
          <span className="ml-auto tabular-nums">{m.createdAt}</span>
        </div>
      </button>
    </li>
  )
}
