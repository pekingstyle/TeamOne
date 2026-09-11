// 代码域 · 仓库列表页
import { useState } from 'react'
import { FolderGit2, GitBranch, GitCommitHorizontal, Lock, Package, Plus, Star } from 'lucide-react'
import { branches, commits, componentById, productById, repos, useStore, userById } from '../../data/store'
import type { Repo } from '../../data/types'
import { Avatar, Badge, Btn, Card, PageHeader } from '../../components/ui'
import type { PageProps } from '../../nav'

const langColor: Record<string, string> = { Java: '#b07219', TypeScript: '#3178c6', Shell: '#89e051' }

/** 近似解析 store 中的格式化时间（M-D HH:mm 或 YYYY-M-D HH:mm），返回距现在的毫秒数 */
function agoMs(s: string): number {
  const spaceAt = s.indexOf(' ')
  const datePart = spaceAt === -1 ? s : s.slice(0, spaceAt)
  const timePart = spaceAt === -1 ? '0:0' : s.slice(spaceAt + 1)
  const d = datePart.split('-').map(Number)
  const t = timePart.split(':').map(Number)
  const now = new Date()
  const [y, mo, day] = d.length === 3 ? d : [now.getFullYear(), d[0], d[1]]
  return now.getTime() - new Date(y, mo - 1, day, t[0] ?? 0, t[1] ?? 0).getTime()
}

export default function ReposPage({ nav }: PageProps) {
  useStore()
  const [toast, setToast] = useState<string | undefined>(undefined)

  const weekCommits = commits.filter((c) => agoMs(c.date) < 7 * 86400000).length
  const showToast = () => {
    setToast('原型演示 · 新建仓库功能暂未开放')
    window.setTimeout(() => setToast(undefined), 1600)
  }

  const stats = [
    { label: '仓库总数', value: repos.length, icon: FolderGit2 },
    { label: '分支总数', value: branches.length, icon: GitBranch },
    { label: '本周提交', value: weekCommits, icon: GitCommitHorizontal },
  ]

  return (
    <div>
      <PageHeader
        title="代码仓库"
        desc="团队代码托管 · 分支保护与 CI 门禁全覆盖"
        actions={
          <Btn onClick={showToast}>
            <Plus size={15} />新建仓库
          </Btn>
        }
      />

      {/* 统计条 */}
      <div className="mb-5 grid grid-cols-3 gap-4">
        {stats.map((s) => (
          <Card key={s.label} className="flex items-center gap-3 px-4 py-3">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-brand/12 text-brand">
              <s.icon size={17} />
            </span>
            <div>
              <div className="text-lg font-bold leading-6 tabular-nums text-txt-hi">{s.value}</div>
              <div className="text-xs text-txt-low">{s.label}</div>
            </div>
          </Card>
        ))}
      </div>

      {/* 仓库卡片网格 */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {repos.map((r) => (
          <RepoCard key={r.id} repo={r} onOpen={() => nav.go('repo', r.id)} />
        ))}
      </div>

      {toast && (
        <div className="fixed bottom-8 left-1/2 z-50 -translate-x-1/2 rounded-md border border-line-hi bg-ink-800 px-4 py-2 text-sm text-txt-hi shadow-xl">
          {toast}
        </div>
      )}
    </div>
  )
}

function RepoCard({ repo, onOpen }: { repo: Repo; onOpen: () => void }) {
  const lead = userById(repo.leadId)
  const product = repo.productId ? productById(repo.productId) : undefined
  const comp = repo.componentId ? componentById(repo.componentId) : undefined
  const branchCount = branches.filter((b) => b.repoId === repo.id).length
  return (
    <button
      type="button"
      onClick={onOpen}
      className="cursor-pointer rounded-lg border border-line bg-ink-850 p-4 text-left transition-colors hover:border-line-hi hover:bg-ink-700"
    >
      <div className="flex items-center gap-2">
        <FolderGit2 size={16} className="shrink-0 text-brand" />
        <span className="truncate font-mono text-[15px] font-semibold text-txt-hi">{repo.name}</span>
        {repo.visibility === 'private' && (
          <span title="私有仓库" className="flex shrink-0">
            <Lock size={12} className="text-txt-low" />
          </span>
        )}
        {product && (
          <span className="flex shrink-0">
            <Badge tone="brand">
              <Package size={10} />
              {product.name}
            </Badge>
          </span>
        )}
        {comp && (
          <span className="flex shrink-0">
            <Badge>{comp.name}</Badge>
          </span>
        )}
        <span className="flex-1" />
        {repo.ciEnabled && <Badge tone="ok">CI 已启用</Badge>}
      </div>
      <p className="mt-2 truncate text-sm text-txt-mid">{repo.description}</p>
      <div className="mt-3.5 flex items-center gap-4 text-xs text-txt-low">
        <span className="flex shrink-0 items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-full" style={{ background: langColor[repo.language] ?? '#5d6b86' }} />
          <span className="text-txt-mid">{repo.language}</span>
        </span>
        <span className="flex shrink-0 items-center gap-1 tabular-nums">
          <Star size={12} />
          {repo.stars}
        </span>
        <span className="flex shrink-0 items-center gap-1 tabular-nums">
          <GitBranch size={12} />
          {branchCount}
        </span>
        {lead && (
          <span className="flex shrink-0 items-center gap-1.5">
            <Avatar userId={lead.id} size={18} />
            <span className="text-txt-mid">{lead.name}</span>
          </span>
        )}
        <span className="ml-auto shrink-0 tabular-nums">更新于 {repo.updatedAt}</span>
      </div>
    </button>
  )
}
