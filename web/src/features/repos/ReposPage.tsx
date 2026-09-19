// 代码域 · 仓库列表页（M2-INC-3 自研 Git 仓储接通；⑥h 建仓批：「新建仓库」假按钮改真弹窗）
import { useState } from 'react'
import { FolderGit2, GitBranch, GitCommitHorizontal, Loader2, Lock, Package, Plus, Star, Sparkles, X } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { branches, commits, componentById, productById, repos, useStore, userById } from '../../data/store'
import type { Repo } from '../../data/types'
import { reposApi, useRepos, type RemoteRepo } from '../../api/queries'
import { Avatar, Badge, Btn, Card, PageHeader } from '../../components/ui'
import type { PageProps } from '../../nav'

const langColor: Record<string, string> = { Java: '#b07219', TypeScript: '#3178c6', Shell: '#89e051', Rust: '#dea584' }

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
  const qc = useQueryClient()
  const [toast, setToast] = useState<string | undefined>(undefined)
  const [createOpen, setCreateOpen] = useState(false)
  const { data: remoteData } = useRepos()
  const remoteItems = remoteData?.items ?? []

  const weekCommits = commits.filter((c) => agoMs(c.date) < 7 * 86400000).length
  const totalBranches = branches.length + remoteItems.reduce((acc, r) => acc + (r.branchCount ?? 0), 0)
  const totalRepos = repos.length + remoteItems.filter((r) => !repos.some((m) => m.name === r.name)).length

  const showToast = (msg: string) => {
    setToast(msg)
    window.setTimeout(() => setToast(undefined), 2400)
  }

  const stats = [
    { label: '仓库总数', value: totalRepos, icon: FolderGit2 },
    { label: '分支总数', value: totalBranches, icon: GitBranch },
    { label: '本周提交', value: weekCommits, icon: GitCommitHorizontal },
  ]

  return (
    <div>
      <PageHeader
        title="代码仓库"
        desc="团队代码托管 · 分支保护与 CI 门禁全覆盖 · 自研 Git 内核"
        actions={
          <Btn variant="primary" onClick={() => setCreateOpen(true)}>
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

      {/* 仓库卡片网格：先展示自研真实裸库，后展示模拟仓库 */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {remoteItems.map((r) => (
          <RemoteRepoCard key={r.id} repo={r} onOpen={() => nav.go('repo', r.name)} />
        ))}
        {repos
          .filter((r) => !remoteItems.some((rem) => rem.name === r.name))
          .map((r) => (
            <RepoCard key={r.id} repo={r} onOpen={() => nav.go('repo', r.id)} />
          ))}
      </div>

      {createOpen && (
        <CreateRepoDialog
          onClose={() => setCreateOpen(false)}
          onCreated={(name) => {
            setCreateOpen(false)
            void qc.invalidateQueries({ queryKey: ['repos'] })
            showToast(`仓库 ${name} 已创建（GitFlow 分支规则与默认分支保护已即席生效）`)
            nav.go('repo', name)
          }}
        />
      )}

      {toast && (
        <div className="fixed bottom-8 left-1/2 z-50 -translate-x-1/2 rounded-md border border-line-hi bg-ink-800 px-4 py-2 text-sm text-txt-hi shadow-xl">
          {toast}
        </div>
      )}
    </div>
  )
}

// ==================== 新建仓库弹窗（⑥h 建仓批：真实现，走 POST /api/v1/repos） ====================
function CreateRepoDialog({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (name: string) => void
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [defaultBranch, setDefaultBranch] = useState('main')
  const [creating, setCreating] = useState(false)
  const [formErr, setFormErr] = useState<string | undefined>(undefined)

  // 名称口径与后端白名单一致（字母/数字/_/-，1~64），先本地提示后端兜底
  const nameOk = /^[a-zA-Z0-9_-]{1,64}$/.test(name.trim())
  const branchOk = /^[a-zA-Z0-9_./-]{1,100}$/.test(defaultBranch.trim()) && !defaultBranch.includes('..')

  const handleSubmit = async () => {
    setFormErr(undefined)
    if (!nameOk) {
      setFormErr('仓库名称限字母/数字/_/-，长度 1~64')
      return
    }
    if (!branchOk) {
      setFormErr('默认分支名非法')
      return
    }
    setCreating(true)
    try {
      await reposApi.create({
        name: name.trim(),
        description: description.trim() || undefined,
        defaultBranch: defaultBranch.trim() || undefined,
      })
      onCreated(name.trim())
    } catch (err: any) {
      // 400=名称非法 / 409=同名仓库已存在：直接读服务端 message toast
      setFormErr(err?.message ?? '创建失败，请稍后重试')
    } finally {
      setCreating(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div
        className="w-full max-w-md rounded-lg border border-line-hi bg-ink-850 p-5 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-line pb-3">
          <span className="flex items-center gap-1.5 text-sm font-semibold text-txt-hi">
            <FolderGit2 size={15} className="text-brand" />新建仓库
          </span>
          <button type="button" onClick={onClose} className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi">
            <X size={15} />
          </button>
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault()
            void handleSubmit()
          }}
          className="mt-4 space-y-3"
        >
          {formErr && <div className="rounded border border-bad/20 bg-bad-bg p-2 text-xs text-bad-deep">{formErr}</div>}
          <div>
            <label className="mb-1 block text-xs text-txt-mid">仓库名称 *</label>
            <input
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如: teamone-web（字母/数字/_/-）"
              className="w-full rounded border border-line bg-card px-2.5 py-1.5 font-mono text-xs text-txt-hi focus:border-brand focus:outline-none"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs text-txt-mid">描述</label>
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="仓库用途一句话说明（可留空）"
              className="w-full rounded border border-line bg-card px-2.5 py-1.5 text-xs text-txt-hi focus:border-brand focus:outline-none"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs text-txt-mid">默认分支</label>
            <input
              value={defaultBranch}
              onChange={(e) => setDefaultBranch(e.target.value)}
              placeholder="main"
              className="w-full rounded border border-line bg-card px-2.5 py-1.5 font-mono text-xs text-txt-hi focus:border-brand focus:outline-none"
            />
          </div>
          <p className="text-[11px] leading-4 text-txt-low">
            创建时将在自研 Git 内核落盘 bare 裸库，并自动套用 GitFlow 分支规则模板与默认分支标准保护（MR + 1 批准 + 单测门禁 + 禁强推）。
          </p>
          <div className="flex justify-end gap-2 border-t border-line pt-3">
            <Btn variant="ghost" onClick={onClose} disabled={creating}>
              取消
            </Btn>
            <Btn variant="primary" onClick={() => void handleSubmit()} disabled={creating || !name.trim()}>
              {creating ? <Loader2 size={13} className="animate-spin" /> : <Plus size={13} />}
              创建仓库
            </Btn>
          </div>
        </form>
      </div>
    </div>
  )
}

function RemoteRepoCard({ repo, onOpen }: { repo: RemoteRepo; onOpen: () => void }) {
  const formattedDate = repo.updatedAt ? repo.updatedAt.slice(0, 10) : '刚刚'
  return (
    <button
      type="button"
      onClick={onOpen}
      className="cursor-pointer rounded-lg border border-brand/40 bg-ink-850 p-4 text-left transition-colors hover:border-brand hover:bg-ink-700 relative overflow-hidden"
    >
      <div className="flex items-center gap-2">
        <FolderGit2 size={16} className="shrink-0 text-brand" />
        <span className="truncate font-mono text-[15px] font-semibold text-txt-hi">{repo.name}</span>
        {repo.visibility === 'INTERNAL' && <Badge tone="neutral">内部</Badge>}
        {repo.visibility === 'private' && (
          <span title="私有仓库" className="flex shrink-0">
            <Lock size={12} className="text-txt-low" />
          </span>
        )}
        <span className="flex-1" />
        <Badge tone="brand">
          <Sparkles size={11} className="mr-0.5" />自研内核
        </Badge>
        {repo.ciEnabled && <Badge tone="ok">CI 已启用</Badge>}
      </div>
      <p className="mt-2 truncate text-sm text-txt-mid">{repo.description ?? '自研一体化研发协作平台主裸库'}</p>
      <div className="mt-3.5 flex items-center gap-4 text-xs text-txt-low">
        <span className="flex shrink-0 items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-full bg-[#b07219]" />
          <span className="text-txt-mid">Java/TS</span>
        </span>
        <span className="flex shrink-0 items-center gap-1 tabular-nums">
          <GitBranch size={12} />
          {repo.branchCount ?? 1} 分支
        </span>
        <span className="flex shrink-0 items-center gap-1 tabular-nums">
          <GitCommitHorizontal size={12} />
          {repo.commitCount ?? 0} 提交
        </span>
        <span className="ml-auto shrink-0 tabular-nums">更新于 {formattedDate}</span>
      </div>
    </button>
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
