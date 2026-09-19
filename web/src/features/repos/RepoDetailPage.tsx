// 代码域 · 仓库详情：文件 / 提交 / 分支 / 工作树（R7） / 基线（R9）
// M2-INC-3 U1~U3：自研 Git 仓储与文件/提交/分支浏览真机接通
// M3-INC-2 U4 / V-21：自研 Git 代码全文检索 Tab 接通
import { useState, useEffect, useMemo } from 'react'
import type { ReactNode } from 'react'
import {
  ArrowLeft,
  ChevronRight,
  FileCode2,
  Folder,
  FolderTree,
  GitBranch,
  GitCommitHorizontal,
  Lock,
  Network,
  Package,
  ShieldCheck,
  Star,
  X,
  Sparkles,
  Loader2,
  ChevronLeft,
  CheckCircle2,
  AlertTriangle,
  GitPullRequest,
  RefreshCw,
  Plus,
  Tag,
  History,
  Zap,
  Search,
  Trash2,
  Scissors,
  Info,
  Users,
  UserPlus,
} from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../api/AuthContext'
import { useUserBriefs } from '../../api/users'
import {
  approveBaseline,
  baselineById,
  baselineTypeText,
  baselines,
  branches,
  commits,
  componentById,
  fileTrees,
  mrById,
  productById,
  repoById,
  submitBaseline,
  useStore,
  userById,
  workItemById,
  workTrees,
  type WorkTreeItem,
} from '../../data/store'
import type { Baseline, FileNode } from '../../data/types'
import {
  useRepo,
  useRepoBranches,
  useRepoTags,
  useRepoCommits,
  useRepoTree,
  useRepoBlob,
  useRepoBlame,
  useRepoCodeSearch,
  useRepoCompare,
  useRepoBaselines,
  useRepoProtections,
  useRepoWorktrees,
  useRepoBranchRules,
  useRepoMembers,
  repoMembersApi,
  baselinesApi,
  branchProtectionsApi,
  branchRulesApi,
  cherryPickApi,
  mrsApi,
  reposApi,
  type RemoteTreeItem,
  type RemoteBranchProtection,
  type RemoteBranchRule,
  type RemoteCommit,
  type BranchModel,
  type RepoMemberItem,
  type RepoMemberRole,
} from '../../api/queries'
import type { PageProps } from '../../nav'
import { Avatar, Badge, Btn, Card, CardHeader, Empty, Pill } from '../../components/ui'
import { GlossaryButton } from '../../components/GlossaryButton'

type TabKey = 'files' | 'commits' | 'branches' | 'strategy' | 'members' | 'compare' | 'search' | 'worktrees' | 'baselines'
type Nav = PageProps['nav']
const wtDot: Record<WorkTreeItem['status'], string> = { active: 'bg-ok', merged: 'bg-txt-low/40', stale: 'bg-cat-pink' }
const wtText: Record<WorkTreeItem['status'], string> = { active: '活跃', merged: '已合并', stale: '已停滞' }
const blTone = { functional: 'info', allocated: 'brand', product: 'teal' } as const
const blText: Record<Baseline['status'], string> = { draft: '草稿', in_review: '审批中', approved: '已定版', superseded: '已废止' }
const chip = 'inline-flex items-center gap-1.5 rounded-full border border-line bg-card px-2.5 py-1 tabular-nums text-txt-mid'

function basedOnText(wt: WorkTreeItem): string {
  if (wt.basedOn.kind === 'branch') return `基于分支 ${wt.basedOn.id}`
  return `基于基线 ${baselineById(wt.basedOn.id)?.name ?? wt.basedOn.id}`
}
function basedOnShort(wt: WorkTreeItem): string {
  if (wt.basedOn.kind === 'branch') return wt.basedOn.id
  return `基线 ${baselineById(wt.basedOn.id)?.tagRef ?? wt.basedOn.id}`
}

/** 按扩展名返回合成预览片段（原型演示用） */
const SNIPPETS: Record<string, string> = {
  java: 'package com.teamone.scheduler;\n\npublic class PreviewDemo {\n    public static void main(String[] args) {\n        System.out.println("TeamOne 原型演示文件");\n    }\n}',
  tsx: 'export default function Demo() {\n  const [ready, setReady] = useState(false)\n  useEffect(() => setReady(true), [])\n  return <div className="p-4">{ready ? "TeamOne 原型演示" : "加载中…"}</div>\n}',
  ts: 'export interface RetryOptions {\n  maxAttempts: number\n  backoff: \'fixed\' | \'exponential\'\n}\n\nexport const DEFAULTS: RetryOptions = { maxAttempts: 3, backoff: \'exponential\' }',
  json: '{\n  "name": "teamone",\n  "version": "2.4.0",\n  "private": true,\n  "scripts": { "dev": "vite", "build": "tsc && vite build" }\n}',
  xml: '<project xmlns="http://maven.apache.org/POM/4.0.0">\n  <modelVersion>4.0.0</modelVersion>\n  <artifactId>teamone-server</artifactId>\n  <version>2.4.0-SNAPSHOT</version>\n</project>',
  md: '# TeamOne\n\n一站式研发协同平台 · 代码托管 / 流水线 / 评审\n\n## 快速开始\n\npnpm install && pnpm dev',
  yaml: 'stages:\n  - build\n  - test\n  - deploy\n\nbuild:\n  stage: build\n  script: mvn -B -DskipTests package',
  sh: '#!/usr/bin/env bash\nset -euo pipefail\n\necho "TeamOne deploy: $ENV_NAME"\nhelm upgrade teamone . --set image.tag="$TAG"',
  default: 'pipeline {\n  agent any\n  stages {\n    stage(\'Build\') { steps { sh \'mvn -B package\' } }\n    stage(\'Test\') { steps { sh \'mvn test\' } }\n  }\n}',
}
function snippetFor(name: string): string {
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : ''
  return SNIPPETS[ext] ?? SNIPPETS.default
}
/** 沿路径取出当前目录的子节点 */
function nodesAt(tree: FileNode[] | undefined, path: string[]): FileNode[] {
  let nodes = tree ?? []
  for (const seg of path) {
    const next = nodes.find((n) => n.name === seg && n.kind === 'dir')
    if (!next?.children) return []
    nodes = next.children
  }
  return nodes
}

function formatBytes(bytes?: number | null): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(iso?: string): string {
  if (!iso) return ''
  try {
    return iso.slice(0, 19).replace('T', ' ')
  } catch {
    return iso
  }
}

const SectionTitle = ({ children }: { children: ReactNode }) => (
  <div className="mb-1.5 text-xs font-semibold text-txt-low">{children}</div>
)

export default function RepoDetailPage({ nav, id }: PageProps) {
  useStore()
  const [repoId, tabHint = ''] = (id ?? '').split('#')
  const storeRepo = repoId ? repoById(repoId) : undefined
  const { data: remoteRepo, isLoading: isRemoteLoading } = useRepo(repoId)
  const isLive = !!remoteRepo

  const defaultBranch = remoteRepo?.defaultBranch ?? storeRepo?.defaultBranch ?? 'main'
  const [activeBranch, setActiveBranch] = useState<string>(defaultBranch)
  const [tab, setTab] = useState<TabKey>(
    tabHint === 'worktrees'
      ? 'worktrees'
      : tabHint === 'baselines'
        ? 'baselines'
        : tabHint === 'members'
          ? 'members'
          : 'files',
  )
  const [path, setPath] = useState<string[]>([])
  const [previewMock, setPreviewMock] = useState<FileNode | null>(null)
  const [previewRemote, setPreviewRemote] = useState<RemoteTreeItem | null>(null)
  const [commitPage, setCommitPage] = useState(1)
  // 「分支策略」→「分支保护」互链信号：置 true 后分支 Tab 自动弹出保护规则弹窗
  const [jumpProt, setJumpProt] = useState(false)

  useEffect(() => {
    if (remoteRepo?.defaultBranch) {
      setActiveBranch(remoteRepo.defaultBranch)
    }
  }, [remoteRepo?.defaultBranch])

  if (!remoteRepo && !storeRepo && !isRemoteLoading) {
    return (
      <div>
        <Btn variant="ghost" onClick={() => nav.go('repos')} className="mb-4 -ml-3">
          <ArrowLeft size={14} />返回仓库列表
        </Btn>
        <Empty text="仓库不存在或已被删除" />
      </div>
    )
  }

  const repoName = remoteRepo?.name ?? storeRepo?.name ?? ''
  const repoDesc = remoteRepo?.description ?? storeRepo?.description ?? ''
  const visibility = remoteRepo?.visibility ?? storeRepo?.visibility ?? 'private'
  const ciEnabled = remoteRepo?.ciEnabled ?? storeRepo?.ciEnabled ?? false
  const stars = storeRepo?.stars ?? 128

  const repoBranches = branches.filter((b) => b.repoId === storeRepo?.id)
  const repoCommits = commits.filter((c) => c.repoId === storeRepo?.id)
  const repoWorkTrees = workTrees.filter((w) => w.repoId === storeRepo?.id)
  const repoBaselines = baselines.filter((b) => b.repoId === storeRepo?.id)
  const { data: remoteBaselines } = useRepoBaselines(isLive ? repoName : undefined)
  const { data: protections } = useRepoProtections(isLive ? repoName : undefined)
  const { data: remoteWorktrees } = useRepoWorktrees(isLive ? repoName : undefined)
  const product = storeRepo?.productId ? productById(storeRepo.productId) : undefined
  const comp = storeRepo?.componentId ? componentById(storeRepo.componentId) : undefined
  const mockNodes = [...nodesAt(fileTrees[storeRepo?.id ?? ''], path)].sort((a, b) =>
    a.kind === b.kind ? 0 : a.kind === 'dir' ? -1 : 1,
  )

  const branchCount = isLive ? (remoteRepo.branchCount ?? 1) : repoBranches.length
  const commitCount = isLive ? (remoteRepo.commitCount ?? 0) : repoCommits.length
  const baselineCount = isLive ? (remoteBaselines?.length ?? 0) : repoBaselines.length
  const worktreeCount = (isLive && remoteWorktrees && remoteWorktrees.length > 0) ? remoteWorktrees.length : repoWorkTrees.length

  return (
    <div>
      <Btn variant="ghost" onClick={() => nav.go('repos')} className="mb-3 -ml-3">
        <ArrowLeft size={14} />返回仓库列表
      </Btn>

      {/* 仓库头部 */}
      <div className="mb-5">
        <div className="flex flex-wrap items-center gap-2.5">
          <h1 className="font-mono text-2xl font-bold text-txt-hi">{repoName}</h1>
          {isLive && (
            <Badge tone="brand">
              <Sparkles size={12} className="mr-0.5" />自研内核
            </Badge>
          )}
          <Badge tone={visibility === 'private' ? 'neutral' : 'ok'}>
            {visibility === 'private' ? '私有' : '公开'}
          </Badge>
          {ciEnabled && <Badge tone="brand">CI 已启用</Badge>}
        </div>
        <p className="mt-1.5 text-sm text-txt-mid">{repoDesc}</p>
        <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
          <span className={chip}>
            <Star size={12} />
            {stars} stars
          </span>
          <span className={chip}>
            <GitBranch size={12} />
            {branchCount} 分支
          </span>
          <span className={chip}>
            <GitCommitHorizontal size={12} />
            {commitCount} 提交
          </span>
          {product && (
            <span className={chip}>
              <Package size={12} className="text-brand" />所属产品 · {product.name}
            </span>
          )}
          {comp && <span className={chip}>所属组件 · {comp.name}</span>}
        </div>
      </div>

      {/* Tabs */}
      <div className="mb-4 flex gap-1 overflow-x-auto border-b border-line">
        {(
          [
            ['files', '文件'],
            ['commits', `提交 ${commitCount}`],
            ['branches', `分支 ${branchCount}`],
            ['strategy', '分支策略'],
            ['members', '成员权限'],
            ['compare', '对比 (Compare)'],
            ['search', '搜索 (Search)'],
            ['worktrees', `工作树 ${worktreeCount}`],
            ['baselines', `基线 ${baselineCount}`],
          ] as [TabKey, string][]
        ).map(([key, label]) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={`-mb-px shrink-0 cursor-pointer border-b-2 px-4 py-2 text-sm transition-colors ${
              tab === key
                ? 'border-brand font-semibold text-txt-hi'
                : 'border-transparent text-txt-mid hover:text-txt-hi'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {/* ==================== 1. 文件 TAB ==================== */}
      {tab === 'files' && (
        <FilesTab
          isLive={isLive}
          repoName={repoName}
          activeBranch={activeBranch}
          path={path}
          setPath={setPath}
          onSelectMockFile={(node) => setPreviewMock(node)}
          onSelectRemoteFile={(item) => setPreviewRemote(item)}
          mockNodes={mockNodes}
        />
      )}

      {/* ==================== 2. 提交 TAB ==================== */}
      {tab === 'commits' && (
        <CommitsTab
          isLive={isLive}
          repoName={repoName}
          activeBranch={activeBranch}
          page={commitPage}
          setPage={setCommitPage}
          mockCommits={repoCommits}
        />
      )}

      {/* ==================== 3. 分支 TAB ==================== */}
      {tab === 'branches' && (
        <BranchesTab
          isLive={isLive}
          repoName={repoName}
          activeBranch={activeBranch}
          defaultBranch={defaultBranch}
          onSwitchBranch={(b) => {
            setActiveBranch(b)
            setPath([])
          }}
          mockBranches={repoBranches}
          protections={protections}
          nav={nav}
          onOpenStrategy={() => setTab('strategy')}
          jumpProt={jumpProt}
          onJumpProtHandled={() => setJumpProt(false)}
        />
      )}

      {/* ==================== 3.5 分支策略 TAB（分支治理批） ==================== */}
      {tab === 'strategy' && (
        <BranchStrategyTab
          isLive={isLive}
          repoName={repoName}
          defaultBranch={defaultBranch}
          protections={protections}
          onGoProtections={() => {
            setTab('branches')
            setJumpProt(true)
          }}
          onGoMembers={() => setTab('members')}
        />
      )}

      {/* ==================== 3.6 成员权限 TAB（ACL 成员面板批 · docs/v2/13） ==================== */}
      {tab === 'members' && (
        <MembersTab isLive={isLive} repoName={repoName} onGoStrategy={() => setTab('strategy')} />
      )}

      {/* ==================== 4. 对比 TAB ==================== */}
      {tab === 'compare' && (
        <CompareTab
          isLive={isLive}
          repoName={repoName}
          defaultBranch={defaultBranch}
          activeBranch={activeBranch}
          mockBranches={repoBranches}
          nav={nav}
        />
      )}

      {/* ==================== 5. 搜索 TAB (U4) ==================== */}
      {tab === 'search' && (
        <CodeSearchTab
          isLive={isLive}
          repoName={repoName}
          activeBranch={activeBranch}
          onSelectRemoteFile={(item) => setPreviewRemote(item)}
        />
      )}

      {/* ==================== 5. 工作树 TAB ==================== */}
      {tab === 'worktrees' && (
        <WorkTreeTab
          repoId={storeRepo?.id ?? repoName}
          repoName={repoName}
          defaultBranch={defaultBranch}
          isLive={isLive}
          nav={nav}
        />
      )}

      {/* ==================== 6. 基线 TAB ==================== */}
      {tab === 'baselines' && (
        <BaselineTab
          repoId={storeRepo?.id ?? repoName}
          repoName={repoName}
          isLive={isLive}
        />
      )}

      {/* 模拟文件预览 Modal */}
      {previewMock && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-6"
          onClick={() => setPreviewMock(null)}
        >
          <div
            className="max-h-[80vh] w-full max-w-2xl overflow-hidden rounded-lg border border-line-hi bg-ink-850 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2 border-b border-line px-4 py-3">
              <FileCode2 size={15} className="text-brand" />
              <span className="min-w-0 flex-1 truncate font-mono text-sm text-txt-hi">
                {previewMock.name}
              </span>
              <button
                type="button"
                onClick={() => setPreviewMock(null)}
                className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"
              >
                <X size={15} />
              </button>
            </div>
            <pre className="overflow-auto bg-card p-4 font-mono text-xs leading-5 text-txt-mid max-h-[60vh]">
              {snippetFor(previewMock.name)}
            </pre>
          </div>
        </div>
      )}

      {/* 真实 Git 裸库文件预览 Modal */}
      {previewRemote && (
        <RemoteBlobModal
          repoName={repoName}
          branch={activeBranch}
          item={previewRemote}
          onClose={() => setPreviewRemote(null)}
        />
      )}
    </div>
  )
}

// ==================== 文件浏览子组件 ====================
function FilesTab({
  isLive,
  repoName,
  activeBranch,
  path,
  setPath,
  onSelectMockFile,
  onSelectRemoteFile,
  mockNodes,
}: {
  isLive: boolean
  repoName: string
  activeBranch: string
  path: string[]
  setPath: (p: string[]) => void
  onSelectMockFile: (node: FileNode) => void
  onSelectRemoteFile: (item: RemoteTreeItem) => void
  mockNodes: FileNode[]
}) {
  const currentPath = path.join('/')
  const { data: treeData, isLoading } = useRepoTree(
    isLive ? repoName : undefined,
    activeBranch,
    currentPath,
  )

  const items = treeData?.items ?? []

  return (
    <Card>
      {/* 面包屑导航栏 */}
      <div className="flex flex-wrap items-center gap-1.5 border-b border-line px-4 py-2.5 text-sm">
        <span className="flex items-center gap-1 rounded bg-ink-750 px-2 py-0.5 font-mono text-xs text-brand">
          <GitBranch size={12} />
          {activeBranch}
        </span>
        <button
          type="button"
          onClick={() => setPath([])}
          className="cursor-pointer font-mono text-brand-deep hover:underline"
        >
          {repoName}
        </button>
        {path.map((seg, i) => (
          <span key={i} className="flex items-center gap-1">
            <span className="text-txt-low">/</span>
            {i === path.length - 1 ? (
              <span className="font-mono text-txt-hi">{seg}</span>
            ) : (
              <button
                type="button"
                onClick={() => setPath(path.slice(0, i + 1))}
                className="cursor-pointer font-mono text-brand-deep hover:underline"
              >
                {seg}
              </button>
            )}
          </span>
        ))}
        {isLoading && <Loader2 size={13} className="animate-spin text-brand ml-2" />}
      </div>

      {/* 文件/目录树列表 */}
      <ul className="divide-y divide-line">
        {isLive ? (
          <>
            {items.map((it) => (
              <li key={it.name}>
                <button
                  type="button"
                  onClick={() =>
                    it.type === 'tree' ? setPath([...path, it.name]) : onSelectRemoteFile(it)
                  }
                  className="flex w-full cursor-pointer items-center gap-2.5 px-4 py-2.5 text-left hover:bg-ink-700 transition-colors"
                >
                  {it.type === 'tree' ? (
                    <Folder size={15} className="shrink-0 text-warn/80" />
                  ) : (
                    <FileCode2 size={15} className="shrink-0 text-txt-low" />
                  )}
                  <span
                    className={`w-64 shrink-0 truncate text-sm font-mono ${
                      it.type === 'tree' ? 'font-semibold text-txt-hi' : 'text-txt-mid'
                    }`}
                  >
                    {it.name}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-xs font-mono text-txt-low">
                    {it.sha.slice(0, 8)}
                  </span>
                  <span className="shrink-0 text-xs tabular-nums text-txt-low">
                    {formatBytes(it.size)}
                  </span>
                  {it.type === 'tree' && (
                    <ChevronRight size={14} className="shrink-0 text-txt-low/60" />
                  )}
                </button>
              </li>
            ))}
            {items.length === 0 && !isLoading && (
              <li className="px-4 py-10 text-center text-sm text-txt-low">此目录为空</li>
            )}
          </>
        ) : (
          <>
            {mockNodes.map((n) => (
              <li key={n.name}>
                <button
                  type="button"
                  onClick={() =>
                    n.kind === 'dir' ? setPath([...path, n.name]) : onSelectMockFile(n)
                  }
                  className="flex w-full cursor-pointer items-center gap-2.5 px-4 py-2.5 text-left hover:bg-ink-700"
                >
                  {n.kind === 'dir' ? (
                    <Folder size={15} className="shrink-0 text-warn/80" />
                  ) : (
                    <FileCode2 size={15} className="shrink-0 text-txt-low" />
                  )}
                  <span
                    className={`w-56 shrink-0 truncate text-sm ${
                      n.kind === 'dir' ? 'text-txt-hi' : 'text-txt-mid'
                    }`}
                  >
                    {n.name}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-xs text-txt-low">
                    {n.lastCommitMsg ?? ''}
                  </span>
                  <span className="shrink-0 text-xs tabular-nums text-txt-low">
                    {n.updatedAt ?? ''}
                  </span>
                  {n.kind === 'dir' && (
                    <ChevronRight size={14} className="shrink-0 text-txt-low/60" />
                  )}
                </button>
              </li>
            ))}
            {mockNodes.length === 0 && (
              <li className="px-4 py-10 text-center text-sm text-txt-low">此目录为空</li>
            )}
          </>
        )}
      </ul>
    </Card>
  )
}

// ==================== 真实 Git 文件预览 Modal ====================
function RemoteBlobModal({
  repoName,
  branch,
  item,
  onClose,
}: {
  repoName: string
  branch: string
  item: RemoteTreeItem
  onClose: () => void
}) {
  const { data: blob, isLoading } = useRepoBlob(repoName, branch, item.path)
  const [showBlame, setShowBlame] = useState(false)
  const { data: blameData, isLoading: isBlameLoading } = useRepoBlame(
    showBlame ? repoName : undefined,
    branch,
    item.path
  )

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-6"
      onClick={onClose}
    >
      <div
        className="max-h-[85vh] w-full max-w-5xl overflow-hidden rounded-lg border border-line-hi bg-ink-850 shadow-2xl flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2 border-b border-line px-4 py-3 bg-ink-800">
          <FileCode2 size={16} className="text-brand" />
          <span className="font-mono text-sm font-semibold text-txt-hi truncate">{item.path}</span>
          <span className="text-xs text-txt-low ml-2 tabular-nums">({formatBytes(item.size)})</span>
          <span className="flex-1" />

          {/* S-1' Blame 溯源开关与两级缓存状态 */}
          {!blob?.isBinary && (
            <button
              type="button"
              onClick={() => setShowBlame(!showBlame)}
              className={`inline-flex items-center gap-1 rounded border px-2.5 py-1 text-xs font-medium transition-colors cursor-pointer mr-2 ${
                showBlame
                  ? 'border-brand bg-brand/10 text-brand'
                  : 'border-line bg-ink-700 text-txt-mid hover:text-txt-hi hover:border-line-hi'
              }`}
              title="行级追溯提交作者、时间与消息摘要（L1/L2两级缓存加速）"
            >
              {isBlameLoading ? (
                <Loader2 size={12} className="animate-spin text-brand" />
              ) : (
                <History size={12} />
              )}
              <span>Blame 溯源</span>
            </button>
          )}

          {showBlame && blameData && (
            <span className="inline-flex items-center gap-1 text-xs font-mono mr-2">
              {blameData.fromCache ? (
                <Badge tone="brand">
                  <Zap size={10} className="mr-0.5 inline" />
                  缓存命中 ({blameData.durationMs}ms)
                </Badge>
              ) : (
                <Badge tone="neutral">
                  内核计算 ({blameData.durationMs}ms)
                </Badge>
              )}
            </span>
          )}

          <Badge tone="brand">{branch}</Badge>
          <button
            type="button"
            onClick={onClose}
            className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"
          >
            <X size={16} />
          </button>
        </div>

        <div className="flex-1 overflow-auto bg-[#0d1117] p-4 text-xs font-mono leading-5 text-txt-hi">
          {isLoading ? (
            <div className="py-12 text-center text-txt-low flex items-center justify-center gap-2">
              <Loader2 size={16} className="animate-spin text-brand" />
              正在从自研 Git 裸库读取对象...
            </div>
          ) : blob?.isBinary ? (
            <div className="py-12 text-center text-txt-low">二进制文件，暂不支持在线预览</div>
          ) : showBlame ? (
            isBlameLoading ? (
              <div className="py-12 text-center text-txt-low flex items-center justify-center gap-2">
                <Loader2 size={16} className="animate-spin text-brand" />
                正在加载文件 Blame 溯源信息（两级缓存加速）...
              </div>
            ) : blameData && blameData.lines && blameData.lines.length > 0 ? (
              <table className="w-full table-fixed border-collapse">
                <tbody>
                  {blameData.lines.map((line) => (
                    <tr key={line.lineNo} className="hover:bg-ink-800/40">
                      <td
                        className="w-56 select-none border-r border-line/40 bg-ink-900/40 px-2.5 py-0.5 text-left align-top text-[11px] text-txt-low whitespace-nowrap overflow-hidden text-ellipsis"
                        title={`${line.commitSha}\n作者: ${line.authorName} <${line.authorEmail}>\n日期: ${line.committedAt}\n说明: ${line.summary}`}
                      >
                        <span className="font-mono text-brand font-medium mr-2">{line.commitShort}</span>
                        <span className="text-txt-mid truncate inline-block max-w-[80px] mr-2">{line.authorName}</span>
                        <span className="text-txt-low/70">{line.committedAt ? line.committedAt.slice(0, 10) : ''}</span>
                      </td>
                      <td className="w-12 select-none border-r border-line/40 px-2 py-0.5 text-right align-top text-txt-low">
                        {line.lineNo}
                      </td>
                      <td className="whitespace-pre overflow-x-auto px-3 py-0.5 text-txt-hi selection:bg-brand/30">
                        {line.lineContent}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <pre className="whitespace-pre overflow-x-auto selection:bg-brand/30">
                {blob?.content ?? '空文件'}
              </pre>
            )
          ) : (
            <pre className="whitespace-pre overflow-x-auto selection:bg-brand/30">
              {blob?.content ?? '空文件'}
            </pre>
          )}
        </div>
      </div>
    </div>
  )
}

// ==================== 提交历史子组件 ====================
function CommitsTab({
  isLive,
  repoName,
  activeBranch,
  page,
  setPage,
  mockCommits,
}: {
  isLive: boolean
  repoName: string
  activeBranch: string
  page: number
  setPage: (p: number) => void
  mockCommits: any[]
}) {
  const { data: commitsData, isLoading } = useRepoCommits(
    isLive ? repoName : undefined,
    activeBranch,
    page,
    20,
  )

  // 分支治理批：摘取（cherry-pick）需要现有分支清单与保护规则（禁选受保护分支）
  const { data: branchesData } = useRepoBranches(isLive ? repoName : undefined)
  const { data: protections } = useRepoProtections(isLive ? repoName : undefined)
  const branchNames = useMemo(() => (branchesData ?? []).map((b) => b.name), [branchesData])
  const [pickCommit, setPickCommit] = useState<RemoteCommit | undefined>(undefined)

  // 行内 toast（摘取成功/失败提示，3.2s 自动消失）
  const [toast, setToast] = useState<{ ok: boolean; text: string } | undefined>(undefined)
  const showToast = (ok: boolean, text: string) => {
    setToast({ ok, text })
    window.setTimeout(() => setToast((cur) => (cur?.text === text ? undefined : cur)), 3200)
  }

  const items = commitsData?.items ?? []
  const total = commitsData?.total ?? 0
  const maxPage = Math.ceil(total / 20) || 1

  if (!isLive) {
    return (
      <Card>
        <ul className="divide-y divide-line">
          {mockCommits.map((c) => (
            <li key={c.id} className="flex items-center gap-3 px-4 py-3">
              <Avatar userId={c.authorId} size={26} />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm text-txt-hi">{c.message}</div>
                <div className="mt-0.5 flex items-center gap-2 text-xs text-txt-low">
                  <span className="font-mono text-brand-deep">{c.id}</span>
                  <span>{userById(c.authorId)?.name}</span>
                  <Badge>{c.branch}</Badge>
                </div>
              </div>
              <span className="shrink-0 text-xs tabular-nums text-ok-deep">+{c.additions}</span>
              <span className="shrink-0 text-xs tabular-nums text-bad-deep">-{c.deletions}</span>
              <span className="w-24 shrink-0 text-right text-xs tabular-nums text-txt-low">
                {c.date}
              </span>
            </li>
          ))}
          {mockCommits.length === 0 && (
            <li>
              <Empty text="暂无提交记录" />
            </li>
          )}
        </ul>
      </Card>
    )
  }

  return (
    <Card>
      <div className="flex items-center justify-between border-b border-line px-4 py-2 text-xs text-txt-low">
        <span>分支 {activeBranch} 提交总数：{total}</span>
        {isLoading && <Loader2 size={13} className="animate-spin text-brand" />}
      </div>
      <ul className="divide-y divide-line">
        {items.map((c) => (
          <li key={c.sha} className="flex items-center gap-3 px-4 py-3 hover:bg-ink-750/50">
            <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand/15 font-mono text-xs font-bold text-brand">
              {c.authorName ? c.authorName.slice(0, 1).toUpperCase() : 'G'}
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-medium text-txt-hi">{c.subject}</div>
              <div className="mt-0.5 flex items-center gap-2.5 text-xs text-txt-low">
                <span className="font-mono text-brand-deep font-semibold">{c.sha.slice(0, 8)}</span>
                <span className="text-txt-mid">{c.authorName ?? 'Git Author'}</span>
                <Badge tone="neutral">{activeBranch}</Badge>
              </div>
            </div>
            {isLive && (
              <span title="把该提交摘取到其它分支（如把 main 上的修复摘到 release 分支做回归）" className="flex shrink-0">
                <Btn variant="ghost" className="px-2 py-0.5 text-xs" onClick={() => setPickCommit(c)}>
                  <Scissors size={12} />摘取
                </Btn>
              </span>
            )}
            <span className="shrink-0 text-right text-xs tabular-nums text-txt-low">
              {formatDate(c.committedAt)}
            </span>
          </li>
        ))}
        {items.length === 0 && !isLoading && (
          <li>
            <Empty text="暂无提交记录" />
          </li>
        )}
      </ul>

      {maxPage > 1 && (
        <div className="flex items-center justify-between border-t border-line px-4 py-2.5 text-xs text-txt-low">
          <span>第 {page} / {maxPage} 页</span>
          <div className="flex items-center gap-2">
            <Btn
              variant="ghost"
              className="text-xs px-2.5 py-1"
              disabled={page <= 1}
              onClick={() => setPage(page - 1)}
            >
              <ChevronLeft size={13} />上一页
            </Btn>
            <Btn
              variant="ghost"
              className="text-xs px-2.5 py-1"
              disabled={page >= maxPage}
              onClick={() => setPage(page + 1)}
            >
              下一页<ChevronRight size={13} />
            </Btn>
          </div>
        </div>
      )}

      {/* 摘取（cherry-pick）弹窗：把单条提交摘到目标分支（分支治理批） */}
      {pickCommit && (
        <CherryPickModal
          repoName={repoName}
          commit={pickCommit}
          currentBranch={activeBranch}
          branches={branchNames}
          protections={protections}
          onClose={() => setPickCommit(undefined)}
          showToast={showToast}
        />
      )}

      {/* 行内 toast */}
      {toast && (
        <div
          className={`fixed bottom-6 right-6 z-[60] flex items-center gap-2 rounded-lg border px-4 py-2.5 text-sm shadow-lg ${
            toast.ok ? 'border-ok/30 bg-ok-bg text-ok-deep' : 'border-bad/30 bg-bad-bg text-bad-deep'
          }`}
        >
          <span className={`h-2 w-2 rounded-full ${toast.ok ? 'bg-ok' : 'bg-bad'}`} />
          {toast.text}
        </div>
      )}
    </Card>
  )
}

function matchesBranch(pattern: string, branch: string): boolean {
  if (pattern === '*' || pattern === branch) return true
  if (pattern.endsWith('/*')) {
    const prefix = pattern.slice(0, -1)
    return branch.startsWith(prefix)
  }
  return false
}

// ==================== 分支列表子组件（B2 · UT-32：新建/删除分支 · 保护规则 · 快捷发起评审） ====================
function BranchesTab({
  isLive,
  repoName,
  activeBranch,
  defaultBranch,
  onSwitchBranch,
  mockBranches,
  protections,
  nav,
  onOpenStrategy,
  jumpProt,
  onJumpProtHandled,
}: {
  isLive: boolean
  repoName: string
  activeBranch: string
  defaultBranch: string
  onSwitchBranch: (b: string) => void
  mockBranches: any[]
  protections?: RemoteBranchProtection[]
  nav: Nav
  onOpenStrategy: () => void
  /** 「分支策略」页互链信号：为 true 时自动弹出分支保护弹窗 */
  jumpProt: boolean
  onJumpProtHandled: () => void
}) {
  const qc = useQueryClient()
  const { data: branchesData, isLoading } = useRepoBranches(isLive ? repoName : undefined)
  const { data: tags } = useRepoTags(isLive ? repoName : undefined)
  // 分支治理批：加载分支策略规则（新建分支名前缀实时校验 + 起点默认 baseBranch）
  const { data: branchRules } = useRepoBranchRules(isLive ? repoName : undefined)
  const items = branchesData ?? []
  // 起点 ref 下拉：现有分支 + 标签去重
  const refOptions = useMemo(
    () => [...new Set([...items.map((b) => b.name), ...(tags ?? []).map((t) => t.name)])],
    [items, tags],
  )

  // 行内 toast（成功/失败提示，3.2s 自动消失）
  const [toast, setToast] = useState<{ ok: boolean; text: string } | undefined>(undefined)
  const showToast = (ok: boolean, text: string) => {
    setToast({ ok, text })
    window.setTimeout(() => setToast((cur) => (cur?.text === text ? undefined : cur)), 3200)
  }

  // ---- 新建分支 ----
  const [showCreate, setShowCreate] = useState(false)
  const [newName, setNewName] = useState('')
  const [startRef, setStartRef] = useState('')
  const [creating, setCreating] = useState(false)
  const [createErr, setCreateErr] = useState<string | null>(null)

  // ---- 分支策略联动（分支治理批）：分支名实时校验前缀，起点默认命中规则的 baseBranch ----
  const matchedRule = useMemo(() => {
    const name = newName.trim()
    if (!name || !branchRules || branchRules.length === 0) return undefined
    return branchRules.find((r) => matchesBranch(r.namePattern, name))
  }, [newName, branchRules])
  const allowedPrefixes = (branchRules ?? []).map((r) => r.namePattern).join('、')

  // 命中规则变化时把起点 ref 切到该规则的 baseBranch（用户仍可手动改选）
  useEffect(() => {
    if (matchedRule?.baseBranch) setStartRef(matchedRule.baseBranch)
  }, [matchedRule?.branchType, matchedRule?.baseBranch])

  const handleCreateBranch = async () => {
    const name = newName.trim()
    if (!name || creating) return
    // 前端先按分支策略拦截（后端 422 兜底，message 会列出允许前缀）
    if (branchRules && branchRules.length > 0 && !matchedRule) {
      setCreateErr(`分支名不符合分支策略：允许的前缀为 ${allowedPrefixes}`)
      return
    }
    setCreating(true)
    setCreateErr(null)
    try {
      await reposApi.createBranch(repoName, name, startRef || undefined)
      void qc.invalidateQueries({ queryKey: ['repo', repoName, 'branches'] })
      void qc.invalidateQueries({ queryKey: ['repos'] })
      setShowCreate(false)
      setNewName('')
      setStartRef('')
      showToast(true, `分支 ${name} 创建成功`)
    } catch (err: any) {
      setCreateErr(err?.message ?? '创建分支失败') // 409=分支已存在
    } finally {
      setCreating(false)
    }
  }

  // ---- 删除分支（两步确认；409=受保护） ----
  const [confirmDel, setConfirmDel] = useState<string | undefined>(undefined)
  const [deleting, setDeleting] = useState(false)
  const handleDeleteBranch = async (name: string) => {
    if (deleting) return
    setDeleting(true)
    try {
      await reposApi.deleteBranch(repoName, name)
      void qc.invalidateQueries({ queryKey: ['repo', repoName, 'branches'] })
      void qc.invalidateQueries({ queryKey: ['repos'] })
      showToast(true, `分支 ${name} 已删除`)
    } catch (err: any) {
      showToast(false, err?.message ?? `分支 ${name} 删除失败`) // 409=受保护分支
    } finally {
      setDeleting(false)
      setConfirmDel(undefined)
    }
  }

  // ---- 分支保护规则管理弹窗 ----
  const [showProt, setShowProt] = useState(false)

  // 「分支策略」页互链：带着 jumpProt 信号跳过来时自动弹出保护弹窗（两处互相链接）
  useEffect(() => {
    if (jumpProt) {
      setShowProt(true)
      onJumpProtHandled()
    }
  }, [jumpProt, onJumpProtHandled])

  // ---- 快捷发起评审弹窗（源分支 = 该行分支） ----
  const [mrBranch, setMrBranch] = useState<string | undefined>(undefined)

  if (!isLive) {
    return (
      <Card>
        <ul className="divide-y divide-line">
          {mockBranches.map((b) => (
            <li key={b.name} className="flex items-center gap-3 px-4 py-3">
              <GitBranch size={15} className="shrink-0 text-txt-low" />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="truncate font-mono text-sm text-txt-hi">{b.name}</span>
                  {b.protected && (
                    <span title="受保护分支" className="flex shrink-0">
                      <Lock size={11} className="text-warn" />
                    </span>
                  )}
                  {b.name === defaultBranch && <Badge tone="brand">默认</Badge>}
                </div>
                <div className="mt-0.5 truncate text-xs text-txt-low">{b.lastCommitMsg}</div>
              </div>
              <span className="flex shrink-0 items-center gap-1.5 text-xs tabular-nums">
                <span className="rounded bg-ink-700 px-1.5 py-0.5 text-ok-deep">↑{b.ahead}</span>
                <span className="rounded bg-ink-700 px-1.5 py-0.5 text-bad-deep">↓{b.behind}</span>
              </span>
              <span className="w-24 shrink-0 text-right text-xs tabular-nums text-txt-low">
                {b.updatedAt}
              </span>
              <span className="flex shrink-0 items-center gap-1.5">
                <Avatar userId={b.authorId} size={20} />
                <span className="text-xs text-txt-mid">{userById(b.authorId)?.name}</span>
              </span>
            </li>
          ))}
          {mockBranches.length === 0 && (
            <li>
              <Empty text="暂无分支" />
            </li>
          )}
        </ul>
      </Card>
    )
  }

  return (
    <Card>
      {/* Tab 头部操作条：新建分支 / 分支保护 */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-2.5">
        <span className="text-xs text-txt-low">
          共 {items.length} 个分支 · 保护规则 {protections?.length ?? 0} 条
          {branchRules && branchRules.length > 0 ? ` · 策略规则 ${branchRules.length} 条` : ''}
        </span>
        <div className="flex items-center gap-2">
          <Btn variant="ghost" className="text-xs px-2.5 py-1" onClick={onOpenStrategy}>
            <GitBranch size={13} />分支策略
          </Btn>
          <Btn variant="ghost" className="text-xs px-2.5 py-1" onClick={() => setShowProt(true)}>
            <ShieldCheck size={13} />分支保护
          </Btn>
          <Btn
            variant="primary"
            className="text-xs px-2.5 py-1"
            onClick={() => {
              setShowCreate((v) => !v)
              setCreateErr(null)
            }}
          >
            <Plus size={13} />新建分支
          </Btn>
        </div>
      </div>

      {/* 新建分支行内表单 */}
      {showCreate && (
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void handleCreateBranch()
          }}
          className="space-y-3 border-b border-line bg-canvas p-4"
        >
          <div className="flex items-center justify-between">
            <span className="flex items-center gap-1.5 text-xs font-semibold text-txt-hi">
              <GitBranch size={13} className="text-brand" />新建分支
            </span>
            <button
              type="button"
              onClick={() => setShowCreate(false)}
              className="cursor-pointer text-txt-low hover:text-txt-hi"
            >
              <X size={14} />
            </button>
          </div>
          {createErr && (
            <div className="rounded border border-bad/20 bg-bad-bg p-2 text-xs text-bad-deep">{createErr}</div>
          )}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs text-txt-mid">分支名 *</label>
              <input
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder="例如: feature/login-flow"
                className="w-full rounded border border-line bg-card px-2.5 py-1.5 font-mono text-xs text-txt-hi focus:border-brand focus:outline-none"
              />
              {/* 分支策略实时校验（分支治理批）：前缀不匹配给行内红字提示允许前缀 */}
              {newName.trim() && branchRules && branchRules.length > 0 && (
                matchedRule ? (
                  <div className="mt-1 text-[11px] text-ok-deep">
                    命中「{matchedRule.branchType}」规则 · 起点默认 {matchedRule.baseBranch}
                  </div>
                ) : (
                  <div className="mt-1 text-[11px] text-bad-deep">
                    不符合分支策略：允许的前缀为 {allowedPrefixes}
                  </div>
                )
              )}
            </div>
            <div>
              <label className="mb-1 block text-xs text-txt-mid">起点 ref（分支 / 标签，缺省默认分支）</label>
              <select
                value={startRef}
                onChange={(e) => setStartRef(e.target.value)}
                className="w-full cursor-pointer rounded border border-line bg-card px-2.5 py-1.5 font-mono text-xs text-txt-hi focus:border-brand focus:outline-none"
              >
                <option value="">默认起点（{defaultBranch}）</option>
                {refOptions.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
                {/* 策略命中的 baseBranch 可能尚未建分支（如 develop），兜底展示避免下拉空白 */}
                {startRef && !refOptions.includes(startRef) && (
                  <option value={startRef}>{startRef}（策略起点）</option>
                )}
              </select>
            </div>
          </div>
          <div className="flex justify-end gap-2 pt-1">
            <Btn variant="ghost" onClick={() => setShowCreate(false)}>
              取消
            </Btn>
            <Btn variant="primary" disabled={!newName.trim() || creating} onClick={() => void handleCreateBranch()}>
              {creating ? <Loader2 size={13} className="animate-spin" /> : <GitBranch size={13} />}
              创建分支
            </Btn>
          </div>
        </form>
      )}

      {isLoading && (
        <div className="flex items-center justify-center gap-2 py-6 text-center text-xs text-txt-low">
          <Loader2 size={14} className="animate-spin text-brand" />加载分支...
        </div>
      )}
      <ul className="divide-y divide-line">
        {items.map((b) => {
          const isDefault = b.name === defaultBranch
          const isCurrent = b.name === activeBranch
          const matchedProt = protections?.find((p) => matchesBranch(p.branchPattern, b.name))
          return (
            <li key={b.name} className="flex items-center gap-3 px-4 py-3 hover:bg-ink-750/50">
              <GitBranch size={15} className="shrink-0 text-brand" />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate font-mono text-sm font-semibold text-txt-hi">
                    {b.name}
                  </span>
                  {matchedProt && (
                    <span
                      title={`受保护分支：要求 MR 合并、≥${matchedProt.minApprovals} 人评审批准${matchedProt.requireUnitTest ? '、通过单元测试门禁' : ''}`}
                      className="inline-flex items-center gap-1 rounded bg-warn/15 px-1.5 py-0.5 text-[11px] font-medium text-warn border border-warn/30"
                    >
                      <Lock size={10} />
                      受保护 (≥{matchedProt.minApprovals}人)
                    </span>
                  )}
                  {isDefault && <Badge tone="brand">默认</Badge>}
                  {isCurrent && <Badge tone="ok">当前浏览</Badge>}
                </div>
                <div className="mt-1 flex items-center gap-2 text-xs text-txt-low">
                  <span className="truncate">{b.commitSubject ?? '最新提交'}</span>
                  <span className="font-mono text-brand-deep font-semibold">
                    {b.commitSha.slice(0, 8)}
                  </span>
                </div>
              </div>
              <span className="shrink-0 text-xs tabular-nums text-txt-low">
                {formatDate(b.committedAt)}
              </span>
              {!isCurrent && (
                <Btn variant="ghost" className="text-xs px-2 py-0.5" onClick={() => onSwitchBranch(b.name)}>
                  切换
                </Btn>
              )}
              {/* 快捷发起评审：源分支预填该行分支，目标分支默认主分支 */}
              {!isDefault && (
                <Btn variant="ghost" className="text-xs px-2 py-0.5" onClick={() => setMrBranch(b.name)}>
                  <GitPullRequest size={12} />发起评审
                </Btn>
              )}
              {/* 删除：非默认且未受保护分支才可删（后端 409 亦兜底提示） */}
              {!isDefault && !matchedProt && (
                confirmDel === b.name ? (
                  <span className="flex shrink-0 items-center gap-1">
                    <Btn
                      variant="danger"
                      className="text-xs px-2 py-0.5"
                      disabled={deleting}
                      onClick={() => void handleDeleteBranch(b.name)}
                    >
                      {deleting ? <Loader2 size={11} className="animate-spin" /> : null}确认删除
                    </Btn>
                    <Btn variant="ghost" className="text-xs px-2 py-0.5" onClick={() => setConfirmDel(undefined)}>
                      取消
                    </Btn>
                  </span>
                ) : (
                  <Btn variant="ghost" className="text-xs px-2 py-0.5" onClick={() => setConfirmDel(b.name)}>
                    <Trash2 size={12} />删除
                  </Btn>
                )
              )}
            </li>
          )
        })}
        {items.length === 0 && !isLoading && (
          <li>
            <Empty text="暂无分支" />
          </li>
        )}
      </ul>

      {/* 分支保护规则管理弹窗 */}
      {showProt && (
        <BranchProtectionModal
          repoName={repoName}
          protections={protections ?? []}
          onClose={() => setShowProt(false)}
          showToast={showToast}
        />
      )}

      {/* 快捷发起评审弹窗 */}
      {mrBranch && (
        <QuickMrModal
          repoName={repoName}
          sourceBranch={mrBranch}
          defaultBranch={defaultBranch}
          branches={items.map((b) => b.name)}
          onClose={() => setMrBranch(undefined)}
          nav={nav}
          showToast={showToast}
        />
      )}

      {/* 行内 toast */}
      {toast && (
        <div
          className={`fixed bottom-6 right-6 z-[60] flex items-center gap-2 rounded-lg border px-4 py-2.5 text-sm shadow-lg ${
            toast.ok ? 'border-ok/30 bg-ok-bg text-ok-deep' : 'border-bad/30 bg-bad-bg text-bad-deep'
          }`}
        >
          <span className={`h-2 w-2 rounded-full ${toast.ok ? 'bg-ok' : 'bg-bad'}`} />
          {toast.text}
        </div>
      )}
    </Card>
  )
}

/** 分支保护规则管理弹窗（列表 + 新建/编辑表单 + 删除，走 branchProtectionsApi） */
function BranchProtectionModal({
  repoName,
  protections,
  onClose,
  showToast,
}: {
  repoName: string
  protections: RemoteBranchProtection[]
  onClose: () => void
  showToast: (ok: boolean, text: string) => void
}) {
  const qc = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<RemoteBranchProtection | null>(null)
  const [pattern, setPattern] = useState('')
  const [minApprovals, setMinApprovals] = useState(2)
  const [requireMr, setRequireMr] = useState(true)
  const [requireUnitTest, setRequireUnitTest] = useState(true)
  const [blockForcePush, setBlockForcePush] = useState(true)
  const [saving, setSaving] = useState(false)
  const [formErr, setFormErr] = useState<string | null>(null)
  const [deletingId, setDeletingId] = useState<string | undefined>(undefined)

  const openCreate = () => {
    setEditing(null)
    setPattern('')
    setMinApprovals(2)
    setRequireMr(true)
    setRequireUnitTest(true)
    setBlockForcePush(true)
    setFormErr(null)
    setShowForm(true)
  }
  const openEdit = (p: RemoteBranchProtection) => {
    setEditing(p)
    setPattern(p.branchPattern)
    setMinApprovals(p.minApprovals)
    setRequireMr(p.requireMr)
    setRequireUnitTest(p.requireUnitTest)
    setBlockForcePush(p.blockForcePush)
    setFormErr(null)
    setShowForm(true)
  }

  const handleSave = async () => {
    if (!pattern.trim() || saving) return
    setSaving(true)
    setFormErr(null)
    try {
      await branchProtectionsApi.save(repoName, {
        branchPattern: pattern.trim(),
        requireMr,
        minApprovals,
        requireUnitTest,
        blockForcePush,
      })
      void qc.invalidateQueries({ queryKey: ['repo', repoName, 'protections'] })
      setShowForm(false)
      showToast(true, `保护规则 ${pattern.trim()} 已保存`)
    } catch (err: any) {
      setFormErr(err?.message ?? '保存保护规则失败')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (p: RemoteBranchProtection) => {
    if (deletingId) return
    setDeletingId(p.id)
    try {
      await branchProtectionsApi.delete(repoName, p.id)
      void qc.invalidateQueries({ queryKey: ['repo', repoName, 'protections'] })
      showToast(true, `保护规则 ${p.branchPattern} 已删除`)
    } catch (err: any) {
      showToast(false, err?.message ?? '删除保护规则失败')
    } finally {
      setDeletingId(undefined)
    }
  }

  const checkCls = 'h-3.5 w-3.5 cursor-pointer accent-[var(--color-brand)]'
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div
        className="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-lg border border-line-hi bg-ink-850 p-5 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-line pb-3">
          <span className="flex items-center gap-1.5 text-sm font-semibold text-txt-hi">
            <ShieldCheck size={15} className="text-brand" />分支保护规则 · {repoName}
          </span>
          <button type="button" onClick={onClose} className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi">
            <X size={15} />
          </button>
        </div>

        {!showForm && (
          <div className="mt-3 flex justify-end">
            <Btn variant="primary" className="text-xs px-2.5 py-1" onClick={openCreate}>
              <Plus size={13} />新建规则
            </Btn>
          </div>
        )}

        {/* 新建/编辑表单 */}
        {showForm && (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              void handleSave()
            }}
            className="mt-3 space-y-3 rounded-md border border-line bg-canvas p-3.5"
          >
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-txt-hi">{editing ? `编辑规则：${editing.branchPattern}` : '新建保护规则'}</span>
              <button type="button" onClick={() => setShowForm(false)} className="cursor-pointer text-txt-low hover:text-txt-hi">
                <X size={14} />
              </button>
            </div>
            {formErr && <div className="rounded border border-bad/20 bg-bad-bg p-2 text-xs text-bad-deep">{formErr}</div>}
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div>
                <label className="mb-1 block text-xs text-txt-mid">分支模式 *</label>
                <input
                  value={pattern}
                  onChange={(e) => setPattern(e.target.value)}
                  placeholder="例如: main 或 release/*"
                  className="w-full rounded border border-line bg-card px-2.5 py-1.5 font-mono text-xs text-txt-hi focus:border-brand focus:outline-none"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs text-txt-mid">最少批准人数</label>
                <input
                  type="number"
                  min={0}
                  max={10}
                  value={minApprovals}
                  onChange={(e) => setMinApprovals(Math.max(0, Number(e.target.value) || 0))}
                  className="w-full rounded border border-line bg-card px-2.5 py-1.5 text-xs tabular-nums text-txt-hi focus:border-brand focus:outline-none"
                />
              </div>
              <div className="flex flex-col justify-center gap-1 pt-1 text-xs text-txt-mid">
                <label className="flex cursor-pointer items-center gap-1.5">
                  <input type="checkbox" checked={requireMr} onChange={(e) => setRequireMr(e.target.checked)} className={checkCls} />
                  强制 MR 合并（禁直接 push）
                </label>
                <label className="flex cursor-pointer items-center gap-1.5">
                  <input type="checkbox" checked={requireUnitTest} onChange={(e) => setRequireUnitTest(e.target.checked)} className={checkCls} />
                  要求单元测试门禁
                </label>
                <label className="flex cursor-pointer items-center gap-1.5">
                  <input type="checkbox" checked={blockForcePush} onChange={(e) => setBlockForcePush(e.target.checked)} className={checkCls} />
                  禁止强推（force push）
                </label>
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <Btn variant="ghost" onClick={() => setShowForm(false)}>
                取消
              </Btn>
              <Btn variant="primary" disabled={!pattern.trim() || saving} onClick={() => void handleSave()}>
                {saving ? <Loader2 size={13} className="animate-spin" /> : <ShieldCheck size={13} />}
                保存规则
              </Btn>
            </div>
          </form>
        )}

        {/* 规则列表 */}
        {!showForm && (
          <ul className="mt-3 divide-y divide-line">
            {protections.map((p) => (
              <li key={p.id} className="flex flex-wrap items-center gap-x-3 gap-y-1.5 py-2.5">
                <span className="flex items-center gap-1.5 font-mono text-sm font-semibold text-txt-hi">
                  <Lock size={12} className="text-warn" />
                  {p.branchPattern}
                </span>
                <Badge tone="brand">≥{p.minApprovals} 人批准</Badge>
                {p.requireMr && <Badge tone="neutral">强制 MR</Badge>}
                {p.requireUnitTest && <Badge tone="ok">单测门禁</Badge>}
                {p.blockForcePush && <Badge tone="warn">禁强推</Badge>}
                <span className="flex-1" />
                <Btn variant="ghost" className="text-xs px-2 py-0.5" onClick={() => openEdit(p)}>
                  编辑
                </Btn>
                <Btn variant="ghost" className="text-xs px-2 py-0.5 text-bad-deep" disabled={deletingId === p.id} onClick={() => void handleDelete(p)}>
                  {deletingId === p.id ? <Loader2 size={11} className="animate-spin" /> : <Trash2 size={12} />}删除
                </Btn>
              </li>
            ))}
            {protections.length === 0 && (
              <li className="py-6 text-center text-xs text-txt-low">
                暂无保护规则，点击右上角「新建规则」为 main / release/* 等分支配置评审与门禁要求
              </li>
            )}
          </ul>
        )}
      </div>
    </div>
  )
}

/** 快捷发起评审弹窗（B2 · UT-32：源分支预填该行分支，目标分支默认主分支，成功后跳 MR 详情） */
function QuickMrModal({
  repoName,
  sourceBranch,
  defaultBranch,
  branches,
  onClose,
  nav,
  showToast,
}: {
  repoName: string
  sourceBranch: string
  defaultBranch: string
  branches: string[]
  onClose: () => void
  nav: Nav
  showToast: (ok: boolean, text: string) => void
}) {
  const qc = useQueryClient()
  const [targetBranch, setTargetBranch] = useState(defaultBranch)
  const [title, setTitle] = useState(`合并 ${sourceBranch} 到 ${defaultBranch}`)
  const [desc, setDesc] = useState('')
  const [creating, setCreating] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const handleCreate = async () => {
    if (!title.trim() || creating) return
    setCreating(true)
    setErr(null)
    try {
      const newMr = await mrsApi.create({
        repoId: repoName,
        title: title.trim(),
        description: desc.trim() || undefined,
        sourceBranch,
        targetBranch,
      })
      void qc.invalidateQueries({ queryKey: ['mrs'] })
      showToast(true, `MR !${newMr.number} 创建成功`)
      onClose()
      nav.go('mr', newMr.id)
    } catch (e: any) {
      setErr(e?.message ?? '创建合并请求失败')
    } finally {
      setCreating(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div
        className="w-full max-w-md overflow-hidden rounded-lg border border-line-hi bg-ink-850 p-5 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-line pb-3">
          <span className="flex items-center gap-1.5 text-sm font-semibold text-txt-hi">
            <GitPullRequest size={15} className="text-brand" />发起合并请求（MR）
          </span>
          <button type="button" onClick={onClose} className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi">
            <X size={15} />
          </button>
        </div>

        <div className="mt-4 space-y-3 text-xs">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-txt-low">源分支</span>
            <span className="rounded bg-ink-800 px-2 py-0.5 font-mono font-semibold text-brand">{sourceBranch}</span>
            <span className="text-txt-low">→ 目标分支</span>
            <select
              value={targetBranch}
              onChange={(e) => setTargetBranch(e.target.value)}
              className="cursor-pointer rounded border border-line bg-canvas px-2 py-1 font-mono text-xs font-medium text-txt-hi outline-none focus:border-brand"
            >
              {branches.map((b) => (
                <option key={b} value={b}>
                  {b}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="mb-1 block font-medium text-txt-mid">标题 *</label>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="简明扼要的合并说明"
              className="w-full rounded border border-line bg-canvas px-3 py-2 text-xs text-txt-hi outline-none focus:border-brand"
            />
          </div>

          <div>
            <label className="mb-1 block font-medium text-txt-mid">详细说明</label>
            <textarea
              rows={3}
              value={desc}
              onChange={(e) => setDesc(e.target.value)}
              placeholder="关联需求、架构改动、自测说明…"
              className="w-full rounded border border-line bg-canvas px-3 py-2 text-xs text-txt-hi outline-none focus:border-brand"
            />
          </div>

          {err && <div className="rounded bg-bad-bg p-2 text-bad-deep">{err}</div>}
        </div>

        <div className="mt-5 flex justify-end gap-2 border-t border-line pt-3">
          <Btn variant="ghost" onClick={onClose}>
            取消
          </Btn>
          <Btn variant="primary" disabled={!title.trim() || creating} onClick={() => void handleCreate()}>
            {creating ? <Loader2 size={13} className="animate-spin" /> : <GitPullRequest size={13} />}
            确认发起
          </Btn>
        </div>
      </div>
    </div>
  )
}

// ==================== 摘取提交弹窗（分支治理批：cherry-pick 回测场景） ====================
/**
 * CherryPickModal：把单条提交摘取（cherry-pick）到目标分支。
 * 产品回测场景：把 main 上的修复摘到 release 分支做回归（回测）。
 * 目标分支排除该提交所在分支；受保护分支保留选项但禁选（注明走 MR 流程合入）。
 * 409/422=冲突等业务错误，原样展示后端 message 并提示手工处理。
 */
function CherryPickModal({
  repoName,
  commit,
  currentBranch,
  branches,
  protections,
  onClose,
  showToast,
}: {
  repoName: string
  commit: RemoteCommit
  currentBranch: string
  branches: string[]
  protections?: RemoteBranchProtection[]
  onClose: () => void
  showToast: (ok: boolean, text: string) => void
}) {
  const qc = useQueryClient()
  // 该提交所在分支：接口每条 commit 自带 branch（缺省安全，回退当前浏览分支）
  const commitBranch = commit.branch ?? currentBranch
  const isProtected = (b: string) => (protections ?? []).some((p) => matchesBranch(p.branchPattern, b))

  // 候选目标分支：排除该提交所在分支；受保护分支保留但禁选
  const candidates = useMemo(() => branches.filter((b) => b !== commitBranch), [branches, commitBranch])
  const [target, setTarget] = useState(() => candidates.find((b) => !isProtected(b)) ?? '')
  const [picking, setPicking] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const handlePick = async () => {
    if (!target || picking) return
    setPicking(true)
    setErr(null)
    try {
      const res = await cherryPickApi.pick(repoName, commit.sha, target)
      // 失效提交列表（该 key 前缀覆盖所有 ref/分页变体），让目标分支能看到新提交
      void qc.invalidateQueries({ queryKey: ['repo', repoName, 'commits'] })
      showToast(true, `已摘取到 ${res.branch}，新提交 ${res.commitSha.slice(0, 8)}`)
      onClose()
    } catch (e: any) {
      // 409/422=冲突等业务错误：原样展示后端 message（提示手工处理）
      setErr(e?.message ?? '摘取失败，请稍后重试')
    } finally {
      setPicking(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div
        className="w-full max-w-md overflow-hidden rounded-lg border border-line-hi bg-ink-850 p-5 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-line pb-3">
          <span className="flex items-center gap-1.5 text-sm font-semibold text-txt-hi">
            <Scissors size={15} className="text-brand" />摘取提交（Cherry-pick）
          </span>
          <button type="button" onClick={onClose} className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi">
            <X size={15} />
          </button>
        </div>

        <div className="mt-3 space-y-3 text-xs">
          {/* 场景文案：点明回测用途 */}
          <p className="rounded bg-brand/10 px-2.5 py-2 leading-5 text-brand-deep">
            摘取会把该提交的改动复制为目标分支上的一个新提交（SHA 不同）。典型场景：把 main 上的修复摘取到 release 分支做回归（回测）。
          </p>

          <div className="rounded bg-ink-800 p-2.5">
            <div className="font-mono font-semibold text-brand-deep">{commit.sha.slice(0, 10)}</div>
            <div className="mt-0.5 truncate text-txt-hi">{commit.subject ?? '（无提交说明）'}</div>
            <div className="mt-0.5 text-txt-low">
              所在分支：{commitBranch} · {commit.authorName ?? 'Git Author'}
            </div>
          </div>

          <div>
            <label className="mb-1 block font-medium text-txt-mid">目标分支</label>
            <select
              value={target}
              onChange={(e) => setTarget(e.target.value)}
              className="w-full cursor-pointer rounded border border-line bg-canvas px-2.5 py-1.5 font-mono text-xs text-txt-hi focus:border-brand focus:outline-none"
            >
              <option value="" disabled>
                选择目标分支…
              </option>
              {candidates.map((b) => (
                <option key={b} value={b} disabled={isProtected(b)}>
                  {b}
                  {isProtected(b) ? '（受保护分支请在 MR 流程合入）' : ''}
                </option>
              ))}
            </select>
            {candidates.length === 0 && (
              <p className="mt-1 text-[11px] text-warn-deep">仓库暂无可选目标分支（已排除该提交所在分支）</p>
            )}
          </div>

          {err && (
            <div className="rounded border border-bad/30 bg-bad-bg p-2 leading-5 text-bad-deep">
              {err}
              <div className="mt-1 text-[11px] opacity-80">
                可能存在冲突或业务限制，请按上面提示手工处理（如先解决冲突再摘取，或改走 MR 流程）。
              </div>
            </div>
          )}
        </div>

        <div className="mt-5 flex justify-end gap-2 border-t border-line pt-3">
          <Btn variant="ghost" onClick={onClose}>
            取消
          </Btn>
          <Btn variant="primary" disabled={!target || picking} onClick={() => void handlePick()}>
            {picking ? <Loader2 size={13} className="animate-spin" /> : <Scissors size={13} />}
            确认摘取
          </Btn>
        </div>
      </div>
    </div>
  )
}

// ==================== 分支策略 Tab（分支治理批：模型解释 / 规则表 / 模板与编辑） ====================

/** GitFlow 推荐规则（一键套用；PUT 整仓替换） */
const GITFLOW_TEMPLATE: RemoteBranchRule[] = [
  { branchType: 'feature', namePattern: 'feature/*', baseBranch: 'develop', mergeTarget: 'develop', allowDirectPush: true, autoDeleteAfterMerge: true, description: '功能分支：从 develop 切出，完成后合回 develop' },
  { branchType: 'fix', namePattern: 'fix/*', baseBranch: 'develop', mergeTarget: 'develop', allowDirectPush: true, autoDeleteAfterMerge: true, description: '修复分支：从 develop 切出，完成后合回 develop' },
  { branchType: 'poc', namePattern: 'poc/*', baseBranch: 'develop', mergeTarget: 'develop', allowDirectPush: true, autoDeleteAfterMerge: true, description: '技术预研：验证通过转 feature，失败可弃' },
  { branchType: 'release', namePattern: 'release/*', baseBranch: 'develop', mergeTarget: 'main', allowDirectPush: false, autoDeleteAfterMerge: false, description: '发布分支：从 develop 切出，合入 main 并打 tag' },
  { branchType: 'hotfix', namePattern: 'hotfix/*', baseBranch: 'main', mergeTarget: 'main', allowDirectPush: false, autoDeleteAfterMerge: true, description: '线上热修：从 main 切出，合回 main 后同步 develop' },
]

/** GitHub Flow 推荐规则（一切从 main 切出、MR 合回 main） */
const GITHUB_FLOW_TEMPLATE: RemoteBranchRule[] = [
  { branchType: 'feature', namePattern: 'feature/*', baseBranch: 'main', mergeTarget: 'main', allowDirectPush: true, autoDeleteAfterMerge: true, description: '功能分支：从 main 切出，MR 评审后合回 main' },
  { branchType: 'fix', namePattern: 'fix/*', baseBranch: 'main', mergeTarget: 'main', allowDirectPush: true, autoDeleteAfterMerge: true, description: '修复分支：从 main 切出，MR 评审后合回 main' },
  { branchType: 'poc', namePattern: 'poc/*', baseBranch: 'main', mergeTarget: 'main', allowDirectPush: true, autoDeleteAfterMerge: false, description: '试验分支：验证后决定合入或丢弃' },
]

/** 按规则集形状推断当前模型（GET 只返回规则列表，无 model 字段） */
function inferModel(rules: RemoteBranchRule[]): BranchModel {
  if (rules.length === 0) return 'custom'
  const has = (t: string) => rules.some((r) => r.branchType === t)
  if (has('release') || has('hotfix')) return 'gitflow'
  if (rules.every((r) => r.baseBranch === 'main' && r.mergeTarget === 'main')) return 'github-flow'
  return 'custom'
}

/** 模型人话解释（策略页顶部；保护规则入口与「分支保护」互链） */
const MODEL_TEXT: Record<BranchModel, string> = {
  gitflow: 'GitFlow：feature / fix / poc 等工作分支从 develop 切出、完成后合回 develop；release/* 从 develop 切出，测试通过后合入 main 并打 tag 发布；hotfix/* 从 main 切出，修复后同时合回 main 与 develop。',
  'github-flow': 'GitHub Flow：所有工作分支一律从 main 切出，经 MR 评审后合回 main；主干常绿、适合持续部署的小团队。',
  custom: '自定义：按下方规则表约定的命名模式、起源与合入目标执行；仓库配置了规则后，新建分支名必须命中某条名称模式。',
}

const MODEL_LABEL: Record<BranchModel, string> = {
  gitflow: 'GitFlow',
  'github-flow': 'GitHub Flow',
  custom: '自定义',
}

/** 分支策略 Tab：顶部模型人话解释 + 规则表（类型/模式/起源/合入目标/开关/说明）+ 模板套用 + 编辑策略 */
function BranchStrategyTab({
  isLive,
  repoName,
  defaultBranch,
  protections,
  onGoProtections,
  onGoMembers,
}: {
  isLive: boolean
  repoName: string
  defaultBranch: string
  protections?: RemoteBranchProtection[]
  onGoProtections: () => void
  onGoMembers: () => void
}) {
  const qc = useQueryClient()
  const { data: rules = [], isLoading } = useRepoBranchRules(isLive ? repoName : undefined)
  // 现有分支清单：编辑弹窗里起源/合入目标下拉的选项来源
  const { data: branchesData } = useRepoBranches(isLive ? repoName : undefined)
  const branchNames = useMemo(() => (branchesData ?? []).map((b) => b.name), [branchesData])

  const [showEdit, setShowEdit] = useState(false)
  const [confirmDel, setConfirmDel] = useState<string | undefined>(undefined)
  const [deleting, setDeleting] = useState(false)
  const [toast, setToast] = useState<{ ok: boolean; text: string } | undefined>(undefined)
  const showToast = (ok: boolean, text: string) => {
    setToast({ ok, text })
    window.setTimeout(() => setToast((cur) => (cur?.text === text ? undefined : cur)), 3200)
  }

  const model = inferModel(rules)

  /** 空态一键套用模板（PUT 整仓替换） */
  const applyTemplate = async (m: 'gitflow' | 'github-flow') => {
    try {
      await branchRulesApi.saveAll(repoName, {
        model: m,
        rules: m === 'gitflow' ? GITFLOW_TEMPLATE : GITHUB_FLOW_TEMPLATE,
      })
      void qc.invalidateQueries({ queryKey: ['repo', repoName, 'branch-rules'] })
      showToast(true, `已套用${m === 'gitflow' ? ' GitFlow' : ' GitHub Flow'} 模板，可在「编辑策略」中微调`)
    } catch (err: any) {
      showToast(false, err?.message ?? '套用模板失败')
    }
  }

  /** 行内删除单条规则（走 DELETE，不影响其它规则） */
  const handleDeleteRule = async (branchType: string) => {
    if (deleting) return
    setDeleting(true)
    try {
      await branchRulesApi.remove(repoName, branchType)
      void qc.invalidateQueries({ queryKey: ['repo', repoName, 'branch-rules'] })
      showToast(true, `规则 ${branchType} 已删除`)
    } catch (err: any) {
      showToast(false, err?.message ?? '删除规则失败')
    } finally {
      setDeleting(false)
      setConfirmDel(undefined)
    }
  }

  if (!isLive) {
    return (
      <Card>
        <Empty text="原型演示仓库暂不支持分支策略，切换自研 Git 裸库后可配置" />
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      {/* 顶部：当前模型的人话解释 + 术语说明 + 与「分支保护」互链 */}
      <Card className="p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <Badge tone="brand">
                <Info size={11} />当前模型：{MODEL_LABEL[model]}
              </Badge>
              {isLoading && <Loader2 size={12} className="animate-spin text-brand" />}
            </div>
            <p className="mt-2 text-xs leading-5 text-txt-mid">{MODEL_TEXT[model]}</p>
            {rules.length > 0 && (
              <ul className="mt-2 space-y-1 text-xs leading-5 text-txt-low">
                {rules.map((r) => (
                  <li key={r.branchType} className="flex flex-wrap items-center gap-1">
                    <span className="rounded bg-ink-700 px-1.5 py-0.5 font-mono text-brand-deep">{r.namePattern}</span>
                    <span>从</span>
                    <span className="font-mono text-txt-mid">{r.baseBranch}</span>
                    <span>切出，完成后合入</span>
                    <span className="font-mono text-txt-mid">{r.mergeTarget}</span>
                    {!r.allowDirectPush && <Pill tone="warn">禁止直推</Pill>}
                    {r.autoDeleteAfterMerge && <Pill tone="info">合并后删除</Pill>}
                    {r.description && <span className="text-txt-low/80">· {r.description}</span>}
                  </li>
                ))}
              </ul>
            )}
          </div>
          <GlossaryButton />
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-line pt-2.5 text-xs text-txt-low">
          <Lock size={12} className="text-warn" />
          <span>
            受保护分支不可直推、不可删除；评审人数与门禁要求在「分支保护」里管理（当前 {protections?.length ?? 0} 条规则）。
          </span>
          <Btn variant="ghost" className="px-2 py-0.5 text-xs" onClick={onGoProtections}>
            前往分支保护<ChevronRight size={12} />
          </Btn>
          <Users size={12} className="ml-1 text-brand" />
          <span>「谁能动这个仓库」由成员权限决定。</span>
          <Btn variant="ghost" className="px-2 py-0.5 text-xs" onClick={onGoMembers}>
            前往成员权限<ChevronRight size={12} />
          </Btn>
        </div>
      </Card>

      {/* 规则表 */}
      <Card>
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-2.5">
          <span className="text-xs font-semibold text-txt-hi">分支规则表（{rules.length} 条）</span>
          <div className="flex items-center gap-2">
            {rules.length === 0 && (
              <>
                <Btn variant="primary" className="px-2.5 py-1 text-xs" onClick={() => void applyTemplate('gitflow')}>
                  <Sparkles size={13} />一键套用 GitFlow 模板
                </Btn>
                <Btn className="px-2.5 py-1 text-xs" onClick={() => void applyTemplate('github-flow')}>
                  一键套用 GitHub Flow 模板
                </Btn>
              </>
            )}
            <Btn variant={rules.length === 0 ? 'ghost' : 'primary'} className="px-2.5 py-1 text-xs" onClick={() => setShowEdit(true)}>
              <Plus size={13} />
              {rules.length === 0 ? '从零自定义' : '编辑策略'}
            </Btn>
          </div>
        </div>

        {rules.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-left text-xs">
              <thead>
                <tr className="border-b border-line text-txt-low">
                  <th className="px-4 py-2 font-medium">分支类型</th>
                  <th className="px-3 py-2 font-medium">名称模式</th>
                  <th className="px-3 py-2 font-medium">起源（切出自）</th>
                  <th className="px-3 py-2 font-medium">合入目标</th>
                  <th className="px-3 py-2 font-medium">允许直推</th>
                  <th className="px-3 py-2 font-medium">合并后删除</th>
                  <th className="px-3 py-2 font-medium">说明</th>
                  <th className="px-3 py-2 text-right font-medium">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {rules.map((r) => (
                  <tr key={r.branchType} className="hover:bg-ink-750/50">
                    <td className="px-4 py-2.5 font-mono font-semibold text-txt-hi">{r.branchType}</td>
                    <td className="px-3 py-2.5 font-mono text-brand-deep">{r.namePattern}</td>
                    <td className="px-3 py-2.5 font-mono text-txt-mid">{r.baseBranch}</td>
                    <td className="px-3 py-2.5 font-mono text-txt-mid">{r.mergeTarget}</td>
                    <td className="px-3 py-2.5">
                      {r.allowDirectPush ? <Pill tone="ok">允许</Pill> : <Pill tone="warn">禁止</Pill>}
                    </td>
                    <td className="px-3 py-2.5">
                      {r.autoDeleteAfterMerge ? <Pill tone="info">自动删除</Pill> : <Pill tone="neutral">保留</Pill>}
                    </td>
                    <td className="max-w-[220px] truncate px-3 py-2.5 text-txt-low" title={r.description}>
                      {r.description || '-'}
                    </td>
                    <td className="px-3 py-2.5 text-right">
                      {confirmDel === r.branchType ? (
                        <span className="inline-flex items-center gap-1">
                          <Btn
                            variant="danger"
                            className="px-2 py-0.5 text-xs"
                            disabled={deleting}
                            onClick={() => void handleDeleteRule(r.branchType)}
                          >
                            {deleting ? <Loader2 size={11} className="animate-spin" /> : null}确认删除
                          </Btn>
                          <Btn variant="ghost" className="px-2 py-0.5 text-xs" onClick={() => setConfirmDel(undefined)}>
                            取消
                          </Btn>
                        </span>
                      ) : (
                        <Btn
                          variant="ghost"
                          className="px-2 py-0.5 text-xs text-bad-deep"
                          onClick={() => setConfirmDel(r.branchType)}
                        >
                          <Trash2 size={12} />删除
                        </Btn>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          !isLoading && (
            <div className="px-4 py-8 text-center">
              <Empty text="尚未配置分支策略：先套用模板，或从零自定义规则" size="sm" />
              <p className="mt-1 text-xs text-txt-low">配置后，新建分支的名称必须命中某条名称模式（否则后端 422 拒绝创建）</p>
            </div>
          )
        )}
      </Card>

      {/* 编辑策略弹窗（模型选择 + 规则行编辑，PUT 整仓替换） */}
      {showEdit && (
        <BranchStrategyModal
          repoName={repoName}
          defaultBranch={defaultBranch}
          initialRules={rules}
          branches={branchNames}
          onClose={() => setShowEdit(false)}
          showToast={showToast}
        />
      )}

      {/* 行内 toast */}
      {toast && (
        <div
          className={`fixed bottom-6 right-6 z-[60] flex items-center gap-2 rounded-lg border px-4 py-2.5 text-sm shadow-lg ${
            toast.ok ? 'border-ok/30 bg-ok-bg text-ok-deep' : 'border-bad/30 bg-bad-bg text-bad-deep'
          }`}
        >
          <span className={`h-2 w-2 rounded-full ${toast.ok ? 'bg-ok' : 'bg-bad'}`} />
          {toast.text}
        </div>
      )}
    </div>
  )
}

/** 规则行的分支类型常用预置（下拉）；与后端 BRANCH_TYPES/V15 CHECK 八值对齐，不在预置内的回退手输框 */
const BRANCH_TYPE_PRESETS = ['feature', 'fix', 'poc', 'release', 'hotfix', 'develop', 'main', 'other']

/**
 * BranchStrategyModal：模型选择（GitFlow / GitHub Flow / 自定义）+ 规则行编辑。
 * 保存调 PUT 整仓替换；行内删除仅改草稿，需点「保存策略」生效（只删单条不动其它可在规则表行内删，走 DELETE）。
 */
function BranchStrategyModal({
  repoName,
  defaultBranch,
  initialRules,
  branches,
  onClose,
  showToast,
}: {
  repoName: string
  defaultBranch: string
  initialRules: RemoteBranchRule[]
  branches: string[]
  onClose: () => void
  showToast: (ok: boolean, text: string) => void
}) {
  const qc = useQueryClient()
  const [model, setModel] = useState<BranchModel>(() => inferModel(initialRules))
  const [draft, setDraft] = useState<RemoteBranchRule[]>(() => initialRules.map((r) => ({ ...r })))
  const [saving, setSaving] = useState(false)
  const [formErr, setFormErr] = useState<string | null>(null)

  const setRule = (i: number, patch: Partial<RemoteBranchRule>) =>
    setDraft((rows) => rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r)))

  const addRow = () =>
    setDraft((rows) => [
      ...rows,
      {
        branchType: 'feature',
        namePattern: 'feature/*',
        baseBranch: defaultBranch,
        mergeTarget: defaultBranch,
        allowDirectPush: true,
        autoDeleteAfterMerge: false,
        description: '',
      },
    ])

  const loadTemplate = (m: 'gitflow' | 'github-flow') => {
    setModel(m)
    setDraft((m === 'gitflow' ? GITFLOW_TEMPLATE : GITHUB_FLOW_TEMPLATE).map((r) => ({ ...r })))
    setFormErr(null)
  }

  /** 切换模型：草稿为空时自动载入该模型推荐规则（否则保留当前编辑内容） */
  const changeModel = (m: BranchModel) => {
    setModel(m)
    setFormErr(null)
    if (draft.length === 0 && m !== 'custom') {
      setDraft((m === 'gitflow' ? GITFLOW_TEMPLATE : GITHUB_FLOW_TEMPLATE).map((r) => ({ ...r })))
    }
  }

  const handleSave = async () => {
    const cleaned = draft.map((r) => ({
      ...r,
      branchType: r.branchType.trim(),
      namePattern: r.namePattern.trim(),
      baseBranch: r.baseBranch.trim() || defaultBranch,
      mergeTarget: r.mergeTarget.trim() || defaultBranch,
    }))
    if (cleaned.some((r) => !r.branchType || !r.namePattern)) {
      setFormErr('每条规则都需要「分支类型」与「名称模式」')
      return
    }
    const dupTypes = cleaned
      .filter((r, i) => cleaned.findIndex((x) => x.branchType === r.branchType) !== i)
      .map((r) => r.branchType)
    if (dupTypes.length > 0) {
      setFormErr(`分支类型重复：${[...new Set(dupTypes)].join('、')}（同一类型只能有一条规则）`)
      return
    }
    setSaving(true)
    setFormErr(null)
    try {
      await branchRulesApi.saveAll(repoName, { model, rules: cleaned })
      void qc.invalidateQueries({ queryKey: ['repo', repoName, 'branch-rules'] })
      showToast(true, '分支策略已保存（整仓替换）')
      onClose()
    } catch (err: any) {
      setFormErr(err?.message ?? '保存分支策略失败')
    } finally {
      setSaving(false)
    }
  }

  // 起源/合入目标下拉选项：现有分支 + 当前值兜底（模板里的 develop 等可能尚未建分支）
  const refOptions = (val: string) => [...new Set([val || defaultBranch, defaultBranch, ...branches])].filter(Boolean)

  const inputCls = 'w-full rounded border border-line bg-card px-2 py-1.5 font-mono text-xs text-txt-hi focus:border-brand focus:outline-none'
  const labelCls = 'mb-1 block text-[11px] text-txt-mid'
  const checkCls = 'h-3.5 w-3.5 cursor-pointer accent-[var(--color-brand)]'

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div
        className="max-h-[85vh] w-full max-w-4xl overflow-y-auto rounded-lg border border-line-hi bg-ink-850 p-5 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-line pb-3">
          <span className="flex items-center gap-1.5 text-sm font-semibold text-txt-hi">
            <GitBranch size={15} className="text-brand" />编辑分支策略 · {repoName}
          </span>
          <button type="button" onClick={onClose} className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi">
            <X size={15} />
          </button>
        </div>

        {/* 模型选择 + 推荐规则载入 */}
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <label className="text-xs text-txt-mid">分支模型</label>
          <select
            value={model}
            onChange={(e) => changeModel(e.target.value as BranchModel)}
            className="cursor-pointer rounded border border-line bg-card px-2 py-1.5 text-xs text-txt-hi focus:border-brand focus:outline-none"
          >
            <option value="gitflow">GitFlow（develop + release + hotfix）</option>
            <option value="github-flow">GitHub Flow（一切基于 main）</option>
            <option value="custom">自定义</option>
          </select>
          <Btn variant="ghost" className="px-2 py-0.5 text-xs" onClick={() => loadTemplate('gitflow')}>
            载入 GitFlow 推荐规则
          </Btn>
          <Btn variant="ghost" className="px-2 py-0.5 text-xs" onClick={() => loadTemplate('github-flow')}>
            载入 GitHub Flow 推荐规则
          </Btn>
        </div>
        <p className="mt-1.5 text-[11px] leading-4 text-txt-low">{MODEL_TEXT[model]}</p>

        {/* 规则行编辑 */}
        <ul className="mt-3 space-y-2.5">
          {draft.map((row, i) => (
            <li key={i} className="rounded-md border border-line bg-canvas p-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-txt-hi">规则 {i + 1}</span>
                <button
                  type="button"
                  onClick={() => setDraft((rows) => rows.filter((_, idx) => idx !== i))}
                  title="移除此行（点「保存策略」后随整仓替换生效）"
                  className="cursor-pointer text-txt-low hover:text-bad-deep"
                >
                  <Trash2 size={13} />
                </button>
              </div>
              <div className="mt-2 grid grid-cols-2 gap-2 md:grid-cols-4">
                <div>
                  <label className={labelCls}>分支类型</label>
                  {BRANCH_TYPE_PRESETS.includes(row.branchType) ? (
                    <select
                      value={row.branchType}
                      onChange={(e) => setRule(i, { branchType: e.target.value === '__custom__' ? 'other' : e.target.value })}
                      className={inputCls}
                    >
                      {BRANCH_TYPE_PRESETS.map((t) => (
                        <option key={t} value={t}>
                          {t}
                        </option>
                      ))}
                      <option value="__custom__">自定义…</option>
                    </select>
                  ) : (
                    <input
                      value={row.branchType}
                      onChange={(e) => setRule(i, { branchType: e.target.value })}
                      placeholder="如: support"
                      className={inputCls}
                    />
                  )}
                </div>
                <div>
                  <label className={labelCls}>名称模式</label>
                  <input
                    value={row.namePattern}
                    onChange={(e) => setRule(i, { namePattern: e.target.value })}
                    placeholder="feature/*"
                    className={inputCls}
                  />
                </div>
                <div>
                  <label className={labelCls}>起源（切出自）</label>
                  <select value={row.baseBranch} onChange={(e) => setRule(i, { baseBranch: e.target.value })} className={inputCls}>
                    {refOptions(row.baseBranch).map((b) => (
                      <option key={b} value={b}>
                        {b}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className={labelCls}>合入目标</label>
                  <select value={row.mergeTarget} onChange={(e) => setRule(i, { mergeTarget: e.target.value })} className={inputCls}>
                    {refOptions(row.mergeTarget).map((b) => (
                      <option key={b} value={b}>
                        {b}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-4">
                <label className="flex cursor-pointer items-center gap-1.5 text-xs text-txt-mid">
                  <input
                    type="checkbox"
                    checked={row.allowDirectPush}
                    onChange={(e) => setRule(i, { allowDirectPush: e.target.checked })}
                    className={checkCls}
                  />
                  允许直接推送
                </label>
                <label className="flex cursor-pointer items-center gap-1.5 text-xs text-txt-mid">
                  <input
                    type="checkbox"
                    checked={row.autoDeleteAfterMerge}
                    onChange={(e) => setRule(i, { autoDeleteAfterMerge: e.target.checked })}
                    className={checkCls}
                  />
                  合并后自动删除分支
                </label>
                <input
                  value={row.description ?? ''}
                  onChange={(e) => setRule(i, { description: e.target.value })}
                  placeholder="规则说明（如：功能分支）"
                  className="min-w-[180px] flex-1 rounded border border-line bg-card px-2 py-1.5 text-xs text-txt-hi focus:border-brand focus:outline-none"
                />
              </div>
            </li>
          ))}
        </ul>

        <div className="mt-3 flex items-center justify-between">
          <Btn variant="ghost" className="text-xs" onClick={addRow}>
            <Plus size={13} />添加规则行
          </Btn>
          <span className="text-[11px] text-txt-low">保存为整仓替换：以本表为准覆盖全部规则</span>
        </div>

        {formErr && <div className="mt-2 rounded border border-bad/20 bg-bad-bg p-2 text-xs text-bad-deep">{formErr}</div>}

        <div className="mt-4 flex justify-end gap-2 border-t border-line pt-3">
          <Btn variant="ghost" onClick={onClose}>
            取消
          </Btn>
          <Btn variant="primary" disabled={saving || draft.length === 0} onClick={() => void handleSave()}>
            {saving ? <Loader2 size={13} className="animate-spin" /> : <ShieldCheck size={13} />}
            保存策略
          </Btn>
        </div>
      </div>
    </div>
  )
}

// ==================== 成员权限 Tab（ACL 成员面板批 · docs/v2/13 §2 角色矩阵 / §5 授权流程） ====================

/** 四级角色人话口径（§1.2 定位 + §2.3 能力矩阵收敛为两条要点） */
const ROLE_META: Record<RepoMemberRole, { label: string; tone: 'purple' | 'teal' | 'info' | 'neutral'; desc: string; caps: string[] }> = {
  owner: {
    label: 'Owner',
    tone: 'purple',
    desc: '完全管理',
    caps: ['管理成员与角色、仓库设置', '包含 Maintainer 的全部能力'],
  },
  maintainer: {
    label: 'Maintainer',
    tone: 'teal',
    desc: '推送与合并',
    caps: ['合并 MR、管理分支保护、豁免门禁', '登记部署、基线定版会签'],
  },
  developer: {
    label: 'Developer',
    tone: 'info',
    desc: '开发与 MR',
    caps: ['推代码、建分支、发起并合并 MR', '触发流水线、创建基线'],
  },
  reporter: {
    label: 'Reporter',
    tone: 'neutral',
    desc: '只读',
    caps: ['浏览代码、评论、评审表态', '可从可读分支发起 MR（合并需 Developer+）'],
  },
}
const ROLE_ORDER: RepoMemberRole[] = ['owner', 'maintainer', 'developer', 'reporter']
/** 成员端点可授予的角色（⑥i-Q4 裁决）：Owner 为可授予角色（「先授新 Owner、再降旧 Owner」两步完成转移），唯一 Owner 保护由 guard/后端 409 承担 */
const GRANTABLE_ROLES: RepoMemberRole[] = ['owner', 'maintainer', 'developer', 'reporter']

/** 角色大小写兜底（契约小写，防后端枚举漂移） */
function normRole(role?: string): RepoMemberRole {
  const r = (role ?? '').toLowerCase() as RepoMemberRole
  return ROLE_META[r] ? r : 'reporter'
}

/**
 * 成员权限 Tab：角色说明头卡 + 成员表（只读 / 编辑两态）+ 添加成员。
 * - 查看：所有人；编辑（改角色/移除/添加）：仓库 Owner 角色 ∨ 平台 OWNER/ADMIN（后端 403 兜底）。
 * - 编辑态采用「逐条即时提交」（实现从简）：行内改角色即 PUT upsert、移除走两步确认后 DELETE。
 * - 前置拦截：INHERITED（建仓人）行锁定；降级/移除最后一个 Owner 先拦并 toast（后端 409 兜底）。
 */
function MembersTab({
  isLive,
  repoName,
  onGoStrategy,
}: {
  isLive: boolean
  repoName: string
  onGoStrategy: () => void
}) {
  const qc = useQueryClient()
  const { user } = useAuth()
  const { data: members = [], isLoading } = useRepoMembers(isLive ? repoName : undefined)
  const { data: briefs } = useUserBriefs()

  const [editing, setEditing] = useState(false)
  const [busyUserId, setBusyUserId] = useState<string | undefined>(undefined)
  const [confirmDel, setConfirmDel] = useState<string | undefined>(undefined)
  const [addUserId, setAddUserId] = useState('')
  const [addRole, setAddRole] = useState<RepoMemberRole>('developer')
  const [adding, setAdding] = useState(false)
  const [toast, setToast] = useState<{ ok: boolean; text: string } | undefined>(undefined)
  const showToast = (ok: boolean, text: string) => {
    setToast({ ok, text })
    window.setTimeout(() => setToast((cur) => (cur?.text === text ? undefined : cur)), 3200)
  }

  const ownerCount = members.filter((m) => normRole(m.role) === 'owner').length
  const isLastOwner = (m: RepoMemberItem) => normRole(m.role) === 'owner' && ownerCount <= 1
  /** 锁定口径（⑥i-Q4 裁决）：仅「唯一 Owner」行不可改角色/移除（防仓库无主）；INHERITED 建仓人徽标保留但角色可调 */
  const isLocked = (m: RepoMemberItem) => isLastOwner(m)

  const myRow = user ? members.find((m) => m.userId === user.id) : undefined
  const isPlatformAdmin = user?.platformRole === 'OWNER' || user?.platformRole === 'ADMIN'
  const canManage = isPlatformAdmin || normRole(myRow?.role) === 'owner'

  const refresh = () => void qc.invalidateQueries({ queryKey: ['repo', repoName, 'members'] })

  /** 最后 Owner 保护的前置拦截文案；null=放行（后端 409/403 兜底） */
  const guard = (m: RepoMemberItem, action: 'remove' | 'demote'): string | null => {
    if (action === 'remove' && isLastOwner(m)) return '仓库至少保留一名 Owner：请先将其他成员提升为 Owner，再移除该成员'
    if (action === 'demote' && isLastOwner(m)) return '仓库至少保留一名 Owner：请先提升其他成员为 Owner，再降级当前 Owner'
    return null
  }

  /** 行内改角色：逐条即时 PUT upsert；成功失效 members 缓存 */
  const handleRoleChange = async (m: RepoMemberItem, next: RepoMemberRole) => {
    const cur = normRole(m.role)
    if (next === cur) return
    const blocked = cur === 'owner' ? guard(m, 'demote') : null
    if (blocked) {
      showToast(false, blocked)
      return
    }
    setBusyUserId(m.userId)
    try {
      await repoMembersApi.upsert(repoName, m.userId, next)
      refresh()
      showToast(true, `已将 ${m.displayName || m.username} 的角色改为 ${ROLE_META[next].label}`)
    } catch (err: any) {
      showToast(false, err?.message ?? '修改角色失败')
    } finally {
      setBusyUserId(undefined)
    }
  }

  /** 移除成员：两步确认后 DELETE（确认入口处已 guard，此处兜底二次拦截） */
  const handleRemove = async (m: RepoMemberItem) => {
    const blocked = guard(m, 'remove')
    setConfirmDel(undefined)
    if (blocked) {
      showToast(false, blocked)
      return
    }
    setBusyUserId(m.userId)
    try {
      await repoMembersApi.remove(repoName, m.userId)
      refresh()
      showToast(true, `已移除成员 ${m.displayName || m.username}`)
    } catch (err: any) {
      showToast(false, err?.message ?? '移除成员失败')
    } finally {
      setBusyUserId(undefined)
    }
  }

  /** 添加成员：PUT upsert（已存在则等价改角色） */
  const handleAdd = async () => {
    if (!addUserId || adding) return
    setAdding(true)
    try {
      await repoMembersApi.upsert(repoName, addUserId, addRole)
      refresh()
      const u = briefs?.find((b) => b.id === addUserId)
      showToast(true, `已添加 ${u?.displayName || u?.username || '成员'} 为 ${ROLE_META[addRole].label}`)
      setAddUserId('')
      setAddRole('developer')
    } catch (err: any) {
      showToast(false, err?.message ?? '添加成员失败')
    } finally {
      setAdding(false)
    }
  }

  if (!isLive) {
    return (
      <Card>
        <Empty text="原型演示仓库暂不支持成员权限管理，切换自研 Git 裸库后可配置" />
      </Card>
    )
  }

  // 添加成员候选：全员 briefs 排除已在列表者；按显示名排序
  const candidates = (briefs ?? [])
    .filter((b) => !members.some((m) => m.userId === b.id))
    .slice()
    .sort((a, b) => (a.displayName || a.username).localeCompare(b.displayName || b.username, 'zh-Hans-CN'))

  const selectCls =
    'cursor-pointer rounded border border-line bg-card px-2 py-1 text-xs text-txt-hi focus:border-brand focus:outline-none disabled:cursor-not-allowed disabled:opacity-50'

  return (
    <div className="space-y-4">
      {/* 角色说明头卡：四级角色能力一览 + 与分支保护的关系 + 互链 */}
      <Card className="p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <Badge tone="brand">
                <Users size={11} />仓库四级角色
              </Badge>
              {isLoading && <Loader2 size={12} className="animate-spin text-brand" />}
            </div>
            <p className="mt-2 text-xs leading-5 text-txt-mid">
              <span className="font-semibold text-txt-hi">成员权限</span>管「谁能动仓库」，
              <span className="font-semibold text-txt-hi">分支保护</span>管「分支怎么合入」；
              两处独立判定、都通过才放行，互不替代、互不授予。
            </p>
          </div>
          <GlossaryButton />
        </div>
        <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-4">
          {ROLE_ORDER.map((r) => (
            <div
              key={r}
              className={`rounded-md border border-line bg-canvas p-2.5 ${r === 'owner' ? 'ring-1 ring-brand/30' : ''}`}
            >
              <div className="flex items-center gap-1.5">
                <Pill tone={ROLE_META[r].tone}>{ROLE_META[r].label}</Pill>
                <span className="truncate text-[11px] font-medium text-txt-mid">{ROLE_META[r].desc}</span>
              </div>
              <ul className="mt-1.5 space-y-0.5 text-[11px] leading-4 text-txt-mid">
                {ROLE_META[r].caps.map((c) => (
                  <li key={c} className="flex items-start gap-1">
                    <CheckCircle2 size={11} className="mt-0.5 shrink-0 text-ok" />
                    {c}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-line pt-2.5 text-xs text-txt-low">
          <Lock size={12} className="text-warn" />
          <span>两处叠加生效：Maintainer 有推送能力，受保护分支直推仍会被拦（引导走 MR）。</span>
          <Btn variant="ghost" className="px-2 py-0.5 text-xs" onClick={onGoStrategy}>
            前往分支策略<ChevronRight size={12} />
          </Btn>
        </div>
      </Card>

      {/* 成员表 */}
      <Card>
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-2.5">
          <span className="text-xs font-semibold text-txt-hi">
            成员列表（{members.length} 人）
            {!editing && canManage && (
              <span className="ml-2 font-normal text-txt-low">仅仓库 Owner 或平台管理员可管理成员</span>
            )}
          </span>
          <div className="flex items-center gap-2">
            {canManage &&
              (editing ? (
                <Btn
                  variant="primary"
                  className="px-2.5 py-1 text-xs"
                  onClick={() => {
                    setEditing(false)
                    setConfirmDel(undefined)
                  }}
                >
                  <CheckCircle2 size={13} />完成管理
                </Btn>
              ) : (
                <Btn variant="primary" className="px-2.5 py-1 text-xs" onClick={() => setEditing(true)}>
                  <ShieldCheck size={13} />管理成员
                </Btn>
              ))}
            {isLoading && <Loader2 size={12} className="animate-spin text-brand" />}
          </div>
        </div>

        {/* 添加成员（编辑态）：用户下拉（排除已在列表）+ 角色下拉 + PUT upsert */}
        {editing && canManage && (
          <div className="flex flex-wrap items-center gap-2 border-b border-line bg-canvas px-4 py-3">
            <UserPlus size={14} className="shrink-0 text-brand" />
            <span className="text-xs text-txt-mid">添加成员</span>
            <select value={addUserId} onChange={(e) => setAddUserId(e.target.value)} className={selectCls}>
              <option value="">{candidates.length > 0 ? '选择用户…' : '没有可添加的用户（名单加载中或已全员在列）'}</option>
              {candidates.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.displayName || u.username}（@{u.username}）
                </option>
              ))}
            </select>
            <select value={addRole} onChange={(e) => setAddRole(e.target.value as RepoMemberRole)} className={selectCls}>
              {GRANTABLE_ROLES.map((r) => (
                <option key={r} value={r}>
                  {ROLE_META[r].label} · {ROLE_META[r].desc}
                </option>
              ))}
            </select>
            <Btn variant="primary" className="px-2.5 py-1 text-xs" disabled={!addUserId || adding} onClick={() => void handleAdd()}>
              {adding ? <Loader2 size={12} className="animate-spin" /> : <Plus size={12} />}添加
            </Btn>
            <span className="text-[11px] text-txt-low">重复添加等价于改角色（upsert）</span>
          </div>
        )}

        {editing && canManage && (
          <div className="border-b border-line bg-brand-bg/40 px-4 py-1.5 text-[11px] text-txt-mid">
            编辑模式：行内修改角色或移除成员即时生效（逐条提交，无需保存）；「建仓人」行与最后一个 Owner 受保护。
          </div>
        )}

        {members.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[820px] text-left text-xs">
              <thead>
                <tr className="border-b border-line text-txt-low">
                  <th className="px-4 py-2 font-medium">成员</th>
                  <th className="px-3 py-2 font-medium">角色</th>
                  <th className="px-3 py-2 font-medium">来源</th>
                  <th className="px-3 py-2 font-medium">授予人</th>
                  <th className="px-3 py-2 font-medium">授予时间</th>
                  <th className="px-3 py-2 text-right font-medium">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {members.map((m) => {
                  const locked = isLocked(m)
                  const name = m.displayName || m.username
                  return (
                    <tr key={m.userId} className="hover:bg-ink-750/50">
                      <td className="px-4 py-2.5">
                        <span className="flex min-w-0 items-center gap-2">
                          <Avatar userId={m.userId} size={24} />
                          <span className="min-w-0">
                            <span className="block max-w-[140px] truncate font-semibold text-txt-hi" title={name}>
                              {name}
                            </span>
                            <span className="block max-w-[140px] truncate font-mono text-[11px] text-txt-low" title={`@${m.username}`}>
                              @{m.username}
                            </span>
                          </span>
                        </span>
                      </td>
                      <td className="px-3 py-2.5">
                        {editing && canManage && !locked ? (
                          <select
                            value={normRole(m.role)}
                            disabled={busyUserId === m.userId}
                            onChange={(e) => void handleRoleChange(m, e.target.value as RepoMemberRole)}
                            className={selectCls}
                          >
                            {GRANTABLE_ROLES.map((r) => (
                              <option key={r} value={r}>
                                {ROLE_META[r].label}
                              </option>
                            ))}
                          </select>
                        ) : (
                          <Pill tone={ROLE_META[normRole(m.role)].tone}>{ROLE_META[normRole(m.role)].label}</Pill>
                        )}
                      </td>
                      <td className="px-3 py-2.5">
                        {m.source === 'INHERITED' ? (
                          <Pill tone="brand">建仓人</Pill>
                        ) : m.source === 'GROUP' ? (
                          <Pill tone="warn">来自用户组</Pill>
                        ) : (
                          <Pill tone="neutral">直接授予</Pill>
                        )}
                      </td>
                      <td className="px-3 py-2.5 text-txt-mid">{m.grantedByName || '-'}</td>
                      <td className="px-3 py-2.5 tabular-nums text-txt-mid">{formatDate(m.grantedAt) || '-'}</td>
                      <td className="px-3 py-2.5 text-right">
                        {editing && canManage && !locked ? (
                          confirmDel === m.userId ? (
                            <span className="inline-flex items-center gap-1">
                              <Btn
                                variant="danger"
                                className="px-2 py-0.5 text-xs"
                                disabled={busyUserId === m.userId}
                                onClick={() => void handleRemove(m)}
                              >
                                {busyUserId === m.userId ? <Loader2 size={11} className="animate-spin" /> : null}确认移除
                              </Btn>
                              <Btn variant="ghost" className="px-2 py-0.5 text-xs" onClick={() => setConfirmDel(undefined)}>
                                取消
                              </Btn>
                            </span>
                          ) : (
                            <Btn
                              variant="ghost"
                              className="px-2 py-0.5 text-xs text-bad-deep"
                              onClick={() => {
                                const blocked = guard(m, 'remove')
                                if (blocked) {
                                  showToast(false, blocked)
                                  return
                                }
                                setConfirmDel(m.userId)
                              }}
                            >
                              <Trash2 size={12} />移除
                            </Btn>
                          )
                        ) : (
                          <span className="text-txt-low/50">{editing && locked ? '最后 Owner' : '—'}</span>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        ) : (
          !isLoading && (
            <div className="px-4 py-8 text-center">
              <Empty text="成员列表为空：当前仅平台 OWNER / ADMIN 依平台角色兜底管理本仓库" size="sm" />
              <p className="mt-1 text-xs text-txt-low">
                {canManage
                  ? '点「管理成员」后用「添加成员」为本仓库授予成员或 Owner 角色（唯一 Owner 受保护不可降级）'
                  : '请联系平台管理员为该仓库添加成员'}
              </p>
            </div>
          )
        )}
      </Card>

      {/* 行内 toast */}
      {toast && (
        <div
          className={`fixed bottom-6 right-6 z-[60] flex items-center gap-2 rounded-lg border px-4 py-2.5 text-sm shadow-lg ${
            toast.ok ? 'border-ok/30 bg-ok-bg text-ok-deep' : 'border-bad/30 bg-bad-bg text-bad-deep'
          }`}
        >
          <span className={`h-2 w-2 rounded-full ${toast.ok ? 'bg-ok' : 'bg-bad'}`} />
          {toast.text}
        </div>
      )}
    </div>
  )
}

// ==================== R7 工作树（Worktree 客户端上报与拓扑视图） ====================
/**
 * 客户端工作树（WorkTree）拓扑与状态视图
 * <p>
 * 支持查看各成员通过客户端（Worktree Reporter）上报的本地独立工作副本：
 * - 检出分支、本地绝对路径、关联任务与拥有人；
 * - 脏文件数量（未提交变更）、Ahead/Behind 领先与落后主线提交数；
 * - 交互式 SVG 拓扑网络图，直观呈现各工作树与主线分支的派生与合并关系。
 * </p>
 */
function WorkTreeTab({
  repoId,
  repoName,
  defaultBranch,
  isLive,
  nav,
}: {
  repoId: string
  repoName: string
  defaultBranch: string
  isLive: boolean
  nav: Nav
}) {
  // 当前选中的工作树 ID（用于右侧/下部卡片高亮与详情展示）
  const [sel, setSel] = useState<string | undefined>(undefined)

  // 查询远端服务端记录的工作树上报数据
  const { data: remoteWorktrees, isLoading } = useRepoWorktrees(isLive ? repoName : undefined)
  const isRemoteLive = isLive && !!remoteWorktrees && remoteWorktrees.length > 0

  // 数据源归一化：优先使用真实客户端上报的数据，无上报时回退展示演示 Mock 副本
  const list: (WorkTreeItem & { isRemote?: boolean; headCommitSha?: string; headCommitMessage?: string })[] = isRemoteLive
    ? remoteWorktrees.map((w) => ({
        id: w.id,
        name: w.branchName,
        repoId: repoId,
        branch: w.branchName,
        localPath: w.localPath,
        ownerId: w.ownerUserId,
        ahead: w.aheadCount,
        behind: w.behindCount,
        dirtyFileCount: w.dirtyFileCount,
        status: w.status === 'stale' ? 'stale' : (w.behindCount > 5 ? 'stale' : 'active'),
        lastCommitAt: w.lastActiveAt ? w.lastActiveAt.slice(0, 19).replace('T', ' ') : '-',
        basedOn: { kind: 'branch', id: w.baseRef || defaultBranch },
        isRemote: true,
        headCommitSha: w.lastCommitSha,
      }))
    : workTrees.filter((w) => w.repoId === repoId)

  const selWt = list.find((w) => w.id === sel) ?? list[0]
  const rowH = 84
  const trunkY = 42
  const H = list.length === 0 ? 130 : 100 + list.length * rowH
  return (
    <div className="flex flex-col gap-4 lg:flex-row">
      {/* 左：工作副本列表 */}
      <Card className="min-w-0 flex-1 self-start">
        <CardHeader
          title={
            <span className="flex items-center gap-1.5">
              <FolderTree size={14} />工作副本 · {list.length}
            </span>
          }
          extra={
            <span className="flex items-center gap-2 text-[11px] text-txt-low">
              {isRemoteLive && (
                <Badge tone="brand">
                  <Sparkles size={11} className="mr-0.5" />客户端上报 (Worktree Reporter)
                </Badge>
              )}
              {isLoading && <Loader2 size={11} className="animate-spin text-brand" />}
              <span>本机 git worktree</span>
            </span>
          }
        />
        <ul className="divide-y divide-line">
          {list.map((wt) => {
            const task = wt.relatedTaskId ? workItemById(wt.relatedTaskId) : undefined
            return (
              <li key={wt.id}>
                <div
                  onClick={() => setSel(wt.id)}
                  className={`cursor-pointer px-4 py-3 transition-colors hover:bg-ink-700 ${
                    sel === wt.id ? 'bg-brand-bg/60' : ''
                  }`}
                >
                  <div className="flex items-center gap-2.5">
                    <span
                      className={`h-2 w-2 shrink-0 rounded-full ${wtDot[wt.status]}`}
                      title={wtText[wt.status]}
                    />
                    <span className="truncate font-mono text-sm font-medium text-txt-hi">
                      {wt.name}
                    </span>
                    {wt.isRemote && (
                      <Badge tone="brand">自研内核</Badge>
                    )}
                    {wt.behind > 0 && <Pill tone="warn">落后，建议 rebase</Pill>}
                    {wt.dirtyFileCount > 0 && <Pill tone="orange">{wt.dirtyFileCount} 个未提交</Pill>}
                    <span className="flex-1" />
                    <Avatar userId={wt.ownerId} size={20} />
                    <span className="shrink-0 text-xs font-medium tabular-nums">
                      <span className="text-ok-deep">+{wt.ahead}</span>{' '}
                      <span className={wt.behind > 0 ? 'text-warn-deep' : 'text-txt-low'}>
                        -{wt.behind}
                      </span>
                    </span>
                  </div>
                  <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 pl-[18px] text-xs text-txt-low">
                    <span className="text-txt-mid">{userById(wt.ownerId)?.name ?? wt.ownerId}</span>
                    {task && (
                      <button
                        type="button"
                        title={task.title}
                        onClick={(e) => {
                          e.stopPropagation()
                          nav.go(task.key.startsWith('D-') ? 'defects' : 'tasks', task.id)
                        }}
                        className="cursor-pointer font-mono text-brand-deep hover:underline"
                      >
                        {task.key}
                      </button>
                    )}
                    <span>{basedOnText(wt)}</span>
                    <span className="font-mono">{wt.localPath}</span>
                    <span className="ml-auto tabular-nums">{wt.lastCommitAt}</span>
                  </div>
                </div>
              </li>
            )
          })}
          {list.length === 0 && (
            <li>
              <Empty text="暂无工作树上报数据" />
            </li>
          )}
        </ul>
      </Card>

      {/* 右：关系图 */}
      <Card className="w-full shrink-0 self-start lg:w-[27rem]">
        <CardHeader
          title={
            <span className="flex items-center gap-1.5">
              <Network size={14} />关系图
            </span>
          }
          extra={<span className="text-[11px] text-txt-low">节点=工作树 · 颜色=归属人</span>}
        />
        <div className="p-3">
          <svg viewBox={`0 0 640 ${H}`} className="w-full">
            <line x1={20} y1={trunkY} x2={620} y2={trunkY} stroke="var(--color-line-hi)" strokeWidth={2} />
            <text x={20} y={trunkY - 12} fontSize={11} className="fill-txt-mid font-mono">
              {defaultBranch}（主干）
            </text>
            {[160, 330, 510].map((x) => (
              <circle
                key={x}
                cx={x}
                cy={trunkY}
                r={4.5}
                fill="var(--color-canvas)"
                stroke="var(--color-line-hi)"
                strokeWidth={2}
              />
            ))}
            {list.map((wt, i) => {
              const ax = Math.min(96 + i * 128, 500)
              const nx = ax + 48
              const ny = 100 + i * rowH
              const active = sel === wt.id
              const left = nx > 460
              return (
                <g key={wt.id} onClick={() => setSel(wt.id)} className="cursor-pointer">
                  <circle cx={ax} cy={trunkY} r={3} fill="var(--color-line-hi)" />
                  <text
                    x={ax}
                    y={trunkY + 18}
                    fontSize={10}
                    textAnchor="middle"
                    className="fill-txt-low"
                  >
                    {basedOnShort(wt)}
                  </text>
                  <line
                    x1={ax}
                    y1={trunkY}
                    x2={nx}
                    y2={ny}
                    stroke={active ? 'var(--color-brand)' : 'var(--color-line-hi)'}
                    strokeWidth={active ? 2 : 1.5}
                  />
                  <text
                    x={(ax + nx) / 2 + 8}
                    y={(trunkY + ny) / 2}
                    fontSize={10}
                    className="fill-txt-mid tabular-nums"
                  >
                    +{wt.ahead} / -{wt.behind}
                  </text>
                  {active && (
                    <circle
                      cx={nx}
                      cy={ny}
                      r={13}
                      fill="none"
                      stroke="var(--color-brand)"
                      strokeWidth={1.5}
                      strokeDasharray="3 3"
                    />
                  )}
                  <circle
                    cx={nx}
                    cy={ny}
                    r={8}
                    fill={userById(wt.ownerId)?.color ?? 'var(--color-brand)'}
                    stroke="var(--color-canvas)"
                    strokeWidth={2}
                  />
                  <text
                    x={left ? nx - 16 : nx + 16}
                    y={ny - 1}
                    fontSize={12}
                    textAnchor={left ? 'end' : 'start'}
                    className="fill-txt-hi font-mono"
                  >
                    {wt.name}
                  </text>
                  <text
                    x={left ? nx - 16 : nx + 16}
                    y={ny + 14}
                    fontSize={10}
                    textAnchor={left ? 'end' : 'start'}
                    className="fill-txt-low"
                  >
                    {userById(wt.ownerId)?.name ?? wt.ownerId} · {wt.branch}
                  </text>
                  <title>{`${wt.name} · ${userById(wt.ownerId)?.name ?? wt.ownerId} · ${wt.branch}`}</title>
                </g>
              )
            })}
            {list.length === 0 && (
              <text x={320} y={trunkY + 44} fontSize={12} textAnchor="middle" className="fill-txt-low">
                暂无工作树
              </text>
            )}
          </svg>
        </div>
        {selWt && (
          <div className="border-t border-line px-4 py-3 text-xs">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
              <span className="font-mono text-sm font-semibold text-txt-hi">{selWt.name}</span>
              <span className="flex items-center gap-1.5">
                <Avatar userId={selWt.ownerId} size={18} />
                {userById(selWt.ownerId)?.name ?? selWt.ownerId}
              </span>
              <Pill
                tone={
                  selWt.status === 'active' ? 'ok' : selWt.status === 'stale' ? 'pink' : 'neutral'
                }
              >
                {wtText[selWt.status]}
              </Pill>
              <span className="text-ok-deep tabular-nums">+{selWt.ahead}</span>
              <span className="text-warn-deep tabular-nums">-{selWt.behind}</span>
              {selWt.dirtyFileCount > 0 && (
                <span className="font-medium text-cat-orange">
                  {selWt.dirtyFileCount} 个未提交
                </span>
              )}
              <span className="text-txt-mid">{basedOnText(selWt)}</span>
            </div>
            <div className="mt-1.5 flex flex-wrap items-center gap-x-3 text-txt-low">
              <span className="font-mono">分支 {selWt.branch}</span>
              <span className="font-mono">{selWt.localPath}</span>
              <span className="tabular-nums">最近提交/上报 {selWt.lastCommitAt}</span>
            </div>
            {selWt.headCommitSha && (
              <div className="mt-1 text-txt-low truncate">
                HEAD: <span className="font-mono text-txt-hi">{selWt.headCommitSha.slice(0, 7)}</span> {selWt.headCommitMessage}
              </div>
            )}
          </div>
        )}
      </Card>
    </div>
  )
}

// ==================== R9 基线（全流程管理与双人会签） ====================
/**
 * 基线管理视图组件（FR-v2-13 / R9 / U10）
 * <p>
 * 支持功能基线 (Functional)、分配基线 (Allocated)、产品基线 (Product) 的全生命周期管理：
 * 1. 新建基线（draft 草稿）；
 * 2. 提交审批（in_review 审核中）；
 * 3. 双人会签批准（达 2 人系统自动打 Git Annotated Tag 并定版冻结为 approved）；
 * 4. 版本废止与迭代（supersede）。
 * </p>
 */
function BaselineTab({
  repoId,
  repoName,
  isLive,
}: {
  repoId: string
  repoName: string
  isLive: boolean
}) {
  const qc = useQueryClient()
  const [openId, setOpenId] = useState<string | undefined>(undefined)
  const [showCreate, setShowCreate] = useState(false)
  const [newName, setNewName] = useState('')
  const [newTag, setNewTag] = useState('')
  const [newTarget, setNewTarget] = useState('main')
  const [newType, setNewType] = useState<'functional' | 'allocated' | 'product'>('functional')
  const [newDesc, setNewDesc] = useState('')
  const [newReleaseVer, setNewReleaseVer] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [actionErr, setActionErr] = useState<string | null>(null)

  const { data: remoteList = [], isLoading: isRemoteLoading } = useRepoBaselines(
    isLive ? repoName : undefined,
  )

  const mockList = baselines.filter((b) => b.repoId === repoId)

  async function handleCreate(e?: React.FormEvent) {
    e?.preventDefault()
    if (!newName.trim() || !newTag.trim() || !newTarget.trim()) return
    setIsSubmitting(true)
    setActionErr(null)
    try {
      await baselinesApi.create(repoName, {
        name: newName.trim(),
        tagRef: newTag.trim(),
        targetRefOrSha: newTarget.trim(),
        type: newType,
        description: newDesc.trim() || undefined,
        artifactVersion: newReleaseVer.trim() || undefined,
      })
      setShowCreate(false)
      setNewName('')
      setNewTag('')
      setNewDesc('')
      setNewReleaseVer('')
      qc.invalidateQueries({ queryKey: ['repo', repoName, 'baselines'] })
    } catch (err: any) {
      setActionErr(err?.message || '创建基线失败')
    } finally {
      setIsSubmitting(false)
    }
  }

  async function handleSubmitRemote(id: string) {
    setActionErr(null)
    setIsSubmitting(true)
    try {
      await baselinesApi.submit(id)
      qc.invalidateQueries({ queryKey: ['repo', repoName, 'baselines'] })
    } catch (err: any) {
      setActionErr(err?.message || '提交审批失败')
    } finally {
      setIsSubmitting(false)
    }
  }

  async function handleApproveRemote(id: string) {
    setActionErr(null)
    setIsSubmitting(true)
    try {
      await baselinesApi.approve(id)
      qc.invalidateQueries({ queryKey: ['repo', repoName, 'baselines'] })
    } catch (err: any) {
      setActionErr(err?.message || '批准基线失败')
    } finally {
      setIsSubmitting(false)
    }
  }

  if (isLive) {
    return (
      <Card>
        <CardHeader
          title={
            <span className="flex items-center gap-1.5">
              <Lock size={14} className="text-cat-teal" />基线管理（功能 / 分配 / 产品）
            </span>
          }
          extra={
            <div className="flex items-center gap-3">
              <span className="text-[11px] text-txt-low hidden sm:inline">
                ≥2 人批准自动定版生成 Git Tag 冻结 · 定版后不可变
              </span>
              <Btn
                variant="primary"
                className="text-xs px-2.5 py-1"
                onClick={() => setShowCreate(!showCreate)}
              >
                <Plus size={13} />新建基线
              </Btn>
            </div>
          }
        />

        {showCreate && (
          <form onSubmit={handleCreate} className="border-b border-line bg-canvas p-4 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-txt-hi flex items-center gap-1.5">
                <Tag size={13} className="text-brand" />新建基线（草稿）
              </span>
              <button
                type="button"
                onClick={() => setShowCreate(false)}
                className="text-txt-low hover:text-txt-hi"
              >
                <X size={14} />
              </button>
            </div>
            {actionErr && (
              <div className="rounded bg-bad-bg p-2 text-xs text-bad-deep border border-bad/20">
                {actionErr}
              </div>
            )}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div>
                <label className="block text-xs text-txt-mid mb-1">基线名称 *</label>
                <input
                  type="text"
                  required
                  placeholder="例如: BL-202609-RC1"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  className="w-full rounded border border-line bg-card px-2.5 py-1.5 text-xs text-txt-hi focus:border-brand focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs text-txt-mid mb-1">Git 标签 (Tag) *</label>
                <input
                  type="text"
                  required
                  placeholder="例如: v1.1.0-rc1"
                  value={newTag}
                  onChange={(e) => setNewTag(e.target.value)}
                  className="w-full rounded border border-line bg-card px-2.5 py-1.5 font-mono text-xs text-txt-hi focus:border-brand focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs text-txt-mid mb-1">目标分支/提交 *</label>
                <input
                  type="text"
                  required
                  placeholder="例如: main"
                  value={newTarget}
                  onChange={(e) => setNewTarget(e.target.value)}
                  className="w-full rounded border border-line bg-card px-2.5 py-1.5 font-mono text-xs text-txt-hi focus:border-brand focus:outline-none"
                />
              </div>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className="block text-xs text-txt-mid mb-1">基线类型</label>
                <select
                  value={newType}
                  onChange={(e) => setNewType(e.target.value as any)}
                  className="w-full rounded border border-line bg-card px-2.5 py-1.5 text-xs text-txt-hi focus:border-brand focus:outline-none"
                >
                  <option value="functional">功能基线 (Functional)</option>
                  <option value="allocated">分配基线 (Allocated)</option>
                  <option value="product">产品基线 (Product)</option>
                </select>
              </div>
              <div>
                <label className="block text-xs text-txt-mid mb-1">关联版本号 (可选)</label>
                <input
                  type="text"
                  placeholder="例如: v1.1.0"
                  value={newReleaseVer}
                  onChange={(e) => setNewReleaseVer(e.target.value)}
                  className="w-full rounded border border-line bg-card px-2.5 py-1.5 text-xs text-txt-hi focus:border-brand focus:outline-none"
                />
              </div>
            </div>
            <div>
              <label className="block text-xs text-txt-mid mb-1">基线描述 (可选)</label>
              <textarea
                rows={2}
                placeholder="说明基线包含的核心范围、交付目标或验收结论..."
                value={newDesc}
                onChange={(e) => setNewDesc(e.target.value)}
                className="w-full rounded border border-line bg-card px-2.5 py-1.5 text-xs text-txt-hi focus:border-brand focus:outline-none"
              />
            </div>
            <div className="flex justify-end gap-2 pt-1">
              <Btn variant="ghost" onClick={() => setShowCreate(false)}>
                取消
              </Btn>
              <Btn variant="primary" onClick={() => handleCreate()} disabled={isSubmitting}>
                {isSubmitting ? <Loader2 size={13} className="animate-spin" /> : <ShieldCheck size={13} />}
                创建基线
              </Btn>
            </div>
          </form>
        )}

        {isRemoteLoading && (
          <div className="py-6 text-center text-xs text-txt-low flex items-center justify-center gap-2">
            <Loader2 size={14} className="animate-spin text-brand" />加载基线列表...
          </div>
        )}

        <ul className="divide-y divide-line">
          {remoteList.map((b) => (
            <li key={b.id}>
              <div
                onClick={() => setOpenId(openId === b.id ? undefined : b.id)}
                className={`flex cursor-pointer flex-wrap items-center gap-2 px-4 py-3 transition-colors hover:bg-ink-700 ${
                  openId === b.id ? 'bg-ink-700/60' : ''
                }`}
              >
                <Pill tone={blTone[b.type] ?? 'info'}>
                  {baselineTypeText[b.type] ?? b.type}
                </Pill>
                <Lock size={12} className="shrink-0 text-cat-teal" />
                <span className="text-sm font-medium text-txt-hi">{b.name}</span>
                <span className="font-mono text-xs text-txt-mid">
                  {b.tagRef}
                  {b.artifactVersion ? ` · ${b.artifactVersion}` : ''}
                </span>
                <span className="font-mono text-xs text-txt-low">
                  {b.commitSha ? b.commitSha.slice(0, 8) : '-'}
                </span>
                <span className="flex-1" />
                <span className="flex -space-x-1.5" title={`批准人 ${b.approverIds.length} 人`}>
                  {b.approverIds.map((uid) => (
                    <Avatar key={uid} userId={uid} size={20} />
                  ))}
                </span>
                {b.status === 'approved' ? (
                  <Pill tone="ok">
                    <Lock size={10} />
                    {blText[b.status] ?? '已定版'}
                  </Pill>
                ) : b.status === 'in_review' ? (
                  <Pill tone="warn">
                    {blText[b.status] ?? '审批中'} {b.approverIds.length}/2
                  </Pill>
                ) : (
                  <Pill tone="neutral">{blText[b.status] ?? b.status}</Pill>
                )}
                <span className="w-24 shrink-0 text-right text-xs tabular-nums text-txt-low">
                  {formatDate(b.createdAt)}
                </span>
              </div>
              {openId === b.id && (
                <div className="border-t border-line bg-canvas px-4 py-3.5 space-y-3">
                  {actionErr && (
                    <div className="rounded bg-bad-bg p-2 text-xs text-bad-deep border border-bad/20">
                      {actionErr}
                    </div>
                  )}
                  <div className="grid grid-cols-1 gap-x-8 gap-y-3 md:grid-cols-2 text-xs">
                    <div className="space-y-2">
                      <div>
                        <span className="text-txt-low">基线目标：</span>
                        <span className="font-mono text-txt-hi ml-1">{b.tagRef}</span>
                      </div>
                      <div>
                        <span className="text-txt-low">定版提交：</span>
                        <span className="font-mono text-brand-deep ml-1">
                          {b.commitSha ? b.commitSha.slice(0, 8) : '待定版生成'}
                        </span>
                      </div>
                      <div>
                        <span className="text-txt-low">Git 标签：</span>
                        <span className="font-mono text-txt-hi ml-1">{b.tagRef}</span>
                      </div>
                      {b.artifactVersion && (
                        <div>
                          <span className="text-txt-low">关联版本：</span>
                          <span className="text-txt-hi ml-1">{b.artifactVersion}</span>
                        </div>
                      )}
                    </div>
                    <div className="space-y-2">
                      <div>
                        <span className="text-txt-low">批准进度：</span>
                        <span className="font-medium text-txt-hi ml-1">
                          {b.approverIds.length} / 2 人已批准
                        </span>
                      </div>
                      <div>
                        <span className="text-txt-low">需求快照：</span>
                        <span className="text-txt-mid ml-1">{b.requirementSnapshotId || '无'}</span>
                      </div>
                      {b.status === 'approved' && (
                        <div className="flex items-center gap-2 rounded-md bg-ok/10 border border-ok/30 px-3 py-2 text-xs font-medium text-ok-deep">
                          <CheckCircle2 size={13} className="shrink-0 text-ok-deep" />
                          基线已定版冻结（immutable）· 裸仓已自动创建 Annotated Tag: {b.tagRef}
                        </div>
                      )}
                    </div>
                  </div>

                  {(b.status === 'draft' || b.status === 'in_review') && (
                    <div className="flex flex-wrap items-center gap-3 border-t border-line pt-3">
                      {b.status === 'draft' ? (
                        <Btn onClick={() => handleSubmitRemote(b.id)} disabled={isSubmitting}>
                          <ShieldCheck size={14} />提交审批
                        </Btn>
                      ) : (
                        <>
                          <Btn variant="primary" onClick={() => handleApproveRemote(b.id)} disabled={isSubmitting}>
                            <Lock size={14} />批准定版
                          </Btn>
                          <span className="text-xs text-txt-low">
                            已批准 {b.approverIds.length}/2 · 满 2 人自动定版冻结并在 Git 裸仓生成标签
                          </span>
                        </>
                      )}
                    </div>
                  )}
                </div>
              )}
            </li>
          ))}
          {remoteList.length === 0 && !isRemoteLoading && (
            <li>
              <Empty text="暂无基线，点击右上角新建基线" />
            </li>
          )}
        </ul>
      </Card>
    )
  }

  // 模拟模式
  return (
    <Card>
      <CardHeader
        title={
          <span className="flex items-center gap-1.5">
            <Lock size={14} className="text-cat-teal" />基线管理（功能 / 分配 / 产品）
          </span>
        }
        extra={
          <span className="text-[11px] text-txt-low">
            ≥2 人批准自动定版冻结 · 定版后变更走变更单
          </span>
        }
      />
      <ul className="divide-y divide-line">
        {mockList.map((b) => (
          <li key={b.id}>
            <div
              onClick={() => setOpenId(openId === b.id ? undefined : b.id)}
              className={`flex cursor-pointer flex-wrap items-center gap-2 px-4 py-3 transition-colors hover:bg-ink-700 ${
                openId === b.id ? 'bg-ink-700/60' : ''
              }`}
            >
              <Pill tone={blTone[b.type]}>{baselineTypeText[b.type]}</Pill>
              <Lock size={12} className="shrink-0 text-cat-teal" />
              <span className="text-sm font-medium text-txt-hi">{b.name}</span>
              <span className="font-mono text-xs text-txt-mid">
                {b.tagRef}
                {b.artifactVersion ? ` · ${b.artifactVersion}` : ''}
              </span>
              <span className="font-mono text-xs text-txt-low">{b.commitShort}</span>
              <span className="flex-1" />
              <span className="flex -space-x-1.5" title={`批准人 ${b.approverIds.length} 人`}>
                {b.approverIds.map((uid) => (
                  <Avatar key={uid} userId={uid} size={20} />
                ))}
              </span>
              {b.status === 'approved' ? (
                <Pill tone="ok">
                  <Lock size={10} />
                  {blText[b.status]}
                </Pill>
              ) : b.status === 'in_review' ? (
                <Pill tone="warn">
                  {blText[b.status]} {b.approverIds.length}/2
                </Pill>
              ) : (
                <Pill tone="neutral">{blText[b.status]}</Pill>
              )}
              <span className="w-24 shrink-0 text-right text-xs tabular-nums text-txt-low">
                {b.createdAt}
              </span>
            </div>
            {openId === b.id && (
              <BaselineDetail
                b={b}
                onOpenSuperseded={() => setOpenId(b.supersededById)}
              />
            )}
          </li>
        ))}
        {mockList.length === 0 && (
          <li>
            <Empty text="暂无基线" />
          </li>
        )}
      </ul>
    </Card>
  )
}

function BaselineDetail({
  b,
  onOpenSuperseded,
}: {
  b: Baseline
  onOpenSuperseded: () => void
}) {
  return (
    <div className="border-t border-line bg-canvas px-4 py-3.5">
      <div className="grid grid-cols-1 gap-x-8 gap-y-4 md:grid-cols-2">
        <div>
          <SectionTitle>包含提交（{b.includesCommits.length}）</SectionTitle>
          <div className="flex flex-wrap gap-1.5">
            {b.includesCommits.map((c) => (
              <span
                key={c}
                className="rounded bg-ink-700 px-1.5 py-0.5 font-mono text-xs text-txt-mid"
              >
                {c}
              </span>
            ))}
          </div>
          <div className="mt-3.5">
            <SectionTitle>保护分支</SectionTitle>
            <div className="flex flex-wrap gap-1.5">
              {b.protectedBranches.map((p) => (
                <span
                  key={p}
                  className="inline-flex items-center gap-1 rounded bg-ink-700 px-1.5 py-0.5 font-mono text-xs text-txt-mid"
                >
                  <Lock size={10} className="text-cat-teal" />
                  {p}
                </span>
              ))}
            </div>
          </div>
          <div className="mt-3.5">
            <SectionTitle>关联 MR</SectionTitle>
            <div className="flex flex-wrap gap-1.5">
              {b.mrIds.length === 0 && <span className="text-xs text-txt-low">无</span>}
              {b.mrIds.map((mid) => {
                const m = mrById(mid)
                return m ? (
                  <span
                    key={mid}
                    title="演示数据：MR 详情仅支持自研内核真实 MR"
                    className="rounded bg-brand-bg px-1.5 py-0.5 font-mono text-xs text-brand-deep"
                  >
                    !{m.number}
                  </span>
                ) : null
              })}
            </div>
          </div>
        </div>
        <div>
          <SectionTitle>冲突解决记录</SectionTitle>
          {b.conflictResolved.length === 0 ? (
            <span className="text-xs text-txt-low">无</span>
          ) : (
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="text-txt-low">
                  <th className="py-1 pr-2 font-medium">文件</th>
                  <th className="pr-2 font-medium">解决方案</th>
                  <th className="pr-2 font-medium">确认</th>
                  <th className="pr-2 font-medium">复核</th>
                  <th className="font-medium">时间</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {b.conflictResolved.map((r, i) => (
                  <tr key={i}>
                    <td className="py-1.5 pr-2 font-mono text-txt-mid">{r.filePath}</td>
                    <td className="py-1.5 pr-2 text-txt-mid">{r.solution}</td>
                    <td className="py-1.5 pr-2">
                      <Avatar userId={r.confirmedById} size={16} />
                    </td>
                    <td className="py-1.5 pr-2">
                      <Avatar userId={r.reviewedById} size={16} />
                    </td>
                    <td className="py-1.5 text-txt-low tabular-nums">{r.resolvedAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {b.status === 'superseded' && b.supersededById && (
            <div className="mt-3 rounded-md bg-ink-700 px-3 py-2 text-xs text-txt-mid">
              本基线已废止，由{' '}
              <button
                type="button"
                onClick={onOpenSuperseded}
                className="cursor-pointer font-medium text-brand-deep hover:underline"
              >
                {baselineById(b.supersededById)?.name}
              </button>{' '}
              取代
            </div>
          )}
          {b.status === 'approved' && (
            <div className="mt-3 flex items-center gap-2 rounded-md bg-ok-bg px-3 py-2 text-xs font-medium text-ok-deep">
              <Lock size={12} className="shrink-0 text-cat-teal" />
              基线已冻结（immutable）· 后续变更请提交变更单，将生成新基线并废止本基线
            </div>
          )}
        </div>
      </div>
      {(b.status === 'draft' || b.status === 'in_review') && (
        <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-line pt-3">
          {b.status === 'draft' ? (
            <Btn onClick={() => submitBaseline(b.id)}>
              <ShieldCheck size={14} />提交审批
            </Btn>
          ) : (
            <>
              <Btn variant="primary" onClick={() => approveBaseline(b.id)}>
                <Lock size={14} />批准
              </Btn>
              <span className="text-xs text-txt-low">
                已批准 {b.approverIds.length}/2 · 达 2 人自动定版并冻结
              </span>
            </>
          )}
        </div>
      )}
    </div>
  )
}

// ==================== 6. 分支对比与三路 Diff 子组件 (CompareTab) ====================
function CompareTab({
  isLive,
  repoName,
  defaultBranch,
  activeBranch,
  mockBranches,
  nav,
}: {
  isLive: boolean
  repoName: string
  defaultBranch: string
  activeBranch: string
  mockBranches: any[]
  nav: Nav
}) {
  const queryClient = useQueryClient()
  const { data: remoteBranches = [] } = useRepoBranches(isLive ? repoName : undefined)
  const availableBranches = isLive
    ? remoteBranches.length > 0
      ? remoteBranches.map((b) => b.name)
      : [defaultBranch]
    : mockBranches.map((b) => b.name).length > 0
      ? mockBranches.map((b) => b.name)
      : [defaultBranch]

  const [targetBranch, setTargetBranch] = useState(defaultBranch)
  const [sourceBranch, setSourceBranch] = useState(() => {
    if (activeBranch && activeBranch !== defaultBranch && availableBranches.includes(activeBranch)) {
      return activeBranch
    }
    return availableBranches.find((b) => b !== defaultBranch) ?? defaultBranch
  })

  // 同步分支更新
  useEffect(() => {
    if (availableBranches.length > 0) {
      if (!availableBranches.includes(targetBranch)) {
        setTargetBranch(defaultBranch || availableBranches[0])
      }
      if (!availableBranches.includes(sourceBranch)) {
        const other = availableBranches.find((b) => b !== targetBranch) ?? availableBranches[0]
        setSourceBranch(other)
      }
    }
  }, [availableBranches, defaultBranch, targetBranch, sourceBranch])

  const isSame = targetBranch === sourceBranch

  const {
    data: compareData,
    isLoading,
    isFetching,
    refetch,
  } = useRepoCompare(isLive && !isSame ? repoName : undefined, targetBranch, sourceBranch)

  // 创建 MR Modal 状态
  const [showCreateMr, setShowCreateMr] = useState(false)
  const [mrTitle, setMrTitle] = useState('')
  const [mrDesc, setMrDesc] = useState('')
  const [creating, setCreating] = useState(false)
  const [createMsg, setCreateMsg] = useState<string | null>(null)

  const handleOpenCreateMr = () => {
    setMrTitle(`合并 ${sourceBranch} 到 ${targetBranch}`)
    setMrDesc(`从 ${sourceBranch} 分支合并至 ${targetBranch} 分支`)
    setShowCreateMr(true)
    setCreateMsg(null)
  }

  const handleDoCreateMr = async () => {
    if (!mrTitle.trim()) return
    setCreating(true)
    setCreateMsg(null)
    try {
      const newMr = await mrsApi.create({
        repoId: repoName,
        title: mrTitle.trim(),
        description: mrDesc.trim(),
        sourceBranch,
        targetBranch,
        reviewerIds: ['u1', 'u2'],
      })
      void queryClient.invalidateQueries({ queryKey: ['mrs'] })
      setShowCreateMr(false)
      nav.go('mr', newMr.id)
    } catch (e: any) {
      setCreateMsg(e?.message ?? '创建合并请求失败')
    } finally {
      setCreating(false)
    }
  }

  const handleSwap = () => {
    const tmp = targetBranch
    setTargetBranch(sourceBranch)
    setSourceBranch(tmp)
  }

  const isTestFile = (p: string) => p.includes('/test/') || p.includes('.test.') || p.includes('Test.java')

  return (
    <div className="space-y-4">
      {/* 分支选择与对比工具栏 */}
      <Card>
        <div className="flex flex-wrap items-center justify-between gap-3 p-4">
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1.5">
              <span className="text-xs text-txt-low">目标分支 (base):</span>
              <select
                value={targetBranch}
                onChange={(e) => setTargetBranch(e.target.value)}
                className="rounded-md border border-line bg-canvas px-2.5 py-1 text-xs font-mono font-medium text-txt-hi outline-none focus:border-brand"
              >
                {availableBranches.map((b) => (
                  <option key={b} value={b}>
                    {b}
                  </option>
                ))}
              </select>
            </div>

            <button
              type="button"
              onClick={handleSwap}
              title="交换目标分支与源分支"
              className="rounded p-1.5 text-txt-mid hover:bg-ink-700 hover:text-txt-hi transition-colors"
            >
              <RefreshCw size={13} />
            </button>

            <div className="flex items-center gap-1.5">
              <span className="text-xs text-txt-low">源分支 (compare):</span>
              <select
                value={sourceBranch}
                onChange={(e) => setSourceBranch(e.target.value)}
                className="rounded-md border border-line bg-canvas px-2.5 py-1 text-xs font-mono font-medium text-txt-hi outline-none focus:border-brand"
              >
                {availableBranches.map((b) => (
                  <option key={b} value={b}>
                    {b}
                  </option>
                ))}
              </select>
            </div>

            <Btn variant="ghost" className="text-xs px-2.5 py-1" onClick={() => refetch()} disabled={isLoading || isSame}>
              <RefreshCw size={12} className={isFetching ? 'animate-spin' : ''} />
              重新计算
            </Btn>
          </div>

          <div className="flex items-center gap-2">
            {!isLive && (
              <span className="rounded bg-ink-700 px-2 py-0.5 text-xs text-txt-low">
                当前仓库为原型，切换自研 Git 裸库可体验真机三路 Diff
              </span>
            )}
            <Btn
              variant="primary"
              className="text-xs"
              disabled={isSame || !isLive || (compareData && !compareData.mergeCheck.canMerge)}
              onClick={handleOpenCreateMr}
            >
              <GitPullRequest size={13} />发起合并请求 (MR)
            </Btn>
          </div>
        </div>

        {/* 状态与门禁检测条 */}
        {isSame ? (
          <div className="border-t border-line bg-ink-800/40 px-4 py-3 text-xs text-txt-mid">
            目标分支与源分支相同（均为 <span className="font-mono text-txt-hi">{targetBranch}</span>）。请选择两个不同的分支以执行 Git 三路对比与冲突检查。
          </div>
        ) : isLoading ? (
          <div className="flex items-center gap-2 border-t border-line bg-ink-800/40 px-4 py-4 text-xs text-txt-mid">
            <Loader2 size={14} className="animate-spin text-brand" />
            <span>正在通过底层 Git 内核计算三路对比 (git merge-base, git diff, git merge-tree)...</span>
          </div>
        ) : compareData ? (
          <div className="border-t border-line divide-y divide-line">
            {/* 合并检验与门禁状态 */}
            <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5 text-xs bg-ink-800/30">
              <div className="flex flex-wrap items-center gap-2">
                {compareData.mergeCheck.canMerge ? (
                  <span className="flex items-center gap-1.5 font-medium text-ok-deep">
                    <CheckCircle2 size={14} className="text-ok" />
                    可直接自动合并 (Clean Merge) · 三路合并树检验无冲突
                  </span>
                ) : (
                  <span className="flex items-center gap-1.5 font-medium text-bad-deep">
                    <AlertTriangle size={14} className="text-bad" />
                    存在冲突 ({compareData.mergeCheck.conflictFiles.length} 个文件) · 合并需人工解冲突
                  </span>
                )}

                {compareData.mergeCheck.rebaseRequired && (
                  <span className="flex items-center gap-1 rounded bg-warn-bg px-2 py-0.5 text-warn-deep font-medium">
                    <GitCommitHorizontal size={13} />
                    源分支落后目标分支，建议先 Rebase
                  </span>
                )}
              </div>

              <div className="flex items-center gap-3 text-txt-low font-mono">
                {compareData.baseSha && (
                  <span title={`公共基底 (merge-base): ${compareData.baseSha}`}>
                    merge-base: <span className="text-brand font-semibold">{compareData.baseSha.slice(0, 8)}</span>
                  </span>
                )}
                <span>
                  {compareData.commits.length} 提交 · {compareData.diff.files.length} 文件变更
                </span>
                <span>
                  <span className="text-ok-deep font-semibold">+{compareData.diff.totalAdditions}</span>{' '}
                  <span className="text-bad-deep font-semibold">-{compareData.diff.totalDeletions}</span>
                </span>
              </div>
            </div>

            {/* 冲突文件警告 */}
            {!compareData.mergeCheck.canMerge && compareData.mergeCheck.conflictFiles.length > 0 && (
              <div className="bg-bad-bg/30 px-4 py-2.5 text-xs text-bad-deep">
                <div className="font-semibold">冲突文件列表：</div>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {compareData.mergeCheck.conflictFiles.map((f) => (
                    <span key={f} className="rounded bg-bad-bg px-2 py-0.5 font-mono text-bad-deep border border-bad/30">
                      {f}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        ) : null}
      </Card>

      {/* 提交列表 */}
      {compareData && compareData.commits.length > 0 && (
        <Card>
          <CardHeader
            title={
              <span className="flex items-center gap-1.5 text-xs font-semibold">
                <GitCommitHorizontal size={14} className="text-brand" />
                包含的提交 ({compareData.commits.length})
              </span>
            }
          />
          <ul className="divide-y divide-line">
            {compareData.commits.map((c) => (
              <li key={c.sha} className="flex items-center gap-3 px-4 py-2.5 hover:bg-ink-750/40">
                <span className="font-mono text-xs font-semibold text-brand-deep">{c.sha.slice(0, 8)}</span>
                <div className="min-w-0 flex-1 truncate text-xs text-txt-hi">{c.subject}</div>
                <span className="text-xs text-txt-low">{c.authorName ?? 'Git Author'}</span>
                <span className="text-xs tabular-nums text-txt-low">{formatDate(c.committedAt)}</span>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {/* 文件 Diff 列表 */}
      {compareData && (
        <div>
          <div className="mb-2 flex items-center justify-between px-1">
            <span className="text-xs font-semibold text-txt-mid">
              文件变更列表 ({compareData.diff.files.length})
            </span>
            <span className="text-xs text-txt-low tabular-nums">
              总计 <span className="text-ok-deep font-medium">+{compareData.diff.totalAdditions}</span> /{' '}
              <span className="text-bad-deep font-medium">-{compareData.diff.totalDeletions}</span>
            </span>
          </div>

          {compareData.diff.files.length === 0 ? (
            <Card className="p-8 text-center text-xs text-txt-low">
              {isSame ? '请选择不同的分支以对比' : '未检测到任何文件差异'}
            </Card>
          ) : (
            <div className="space-y-3">
              {compareData.diff.files.map((d) => (
                <Card key={d.path} className="overflow-hidden">
                  <div className="flex flex-wrap items-center gap-2 border-b border-line bg-card px-4 py-2">
                    <FileCode2 size={13} className="shrink-0 text-txt-low" />
                    <span className="min-w-0 truncate font-mono text-xs font-medium text-txt-hi">{d.path}</span>
                    <Badge tone={d.status === 'added' ? 'ok' : d.status === 'removed' ? 'bad' : 'neutral'}>
                      {d.status === 'added' ? '新增' : d.status === 'removed' ? '删除' : '修改'}
                    </Badge>
                    {isTestFile(d.path) ? <Badge tone="ok">单测</Badge> : <Badge tone="neutral">源码</Badge>}
                    <span className="ml-auto shrink-0 text-xs tabular-nums font-mono">
                      <span className="text-ok-deep">+{d.additions}</span>{' '}
                      <span className="text-bad-deep">-{d.deletions}</span>
                    </span>
                  </div>
                  <table className="w-full table-fixed border-collapse font-mono text-xs leading-5">
                    <tbody>
                      {d.lines.map((l, i) => (
                        <tr
                          key={i}
                          className={l.type === 'add' ? 'bg-ok-bg' : l.type === 'del' ? 'bg-bad-bg' : ''}
                        >
                          <td className="w-10 select-none border-r border-line px-2 text-right align-top text-txt-low">
                            {l.oldNo ?? ''}
                          </td>
                          <td className="w-10 select-none border-r border-line px-2 text-right align-top text-txt-low">
                            {l.newNo ?? ''}
                          </td>
                          <td
                            className={`whitespace-pre-wrap break-all px-3 ${
                              l.type === 'add'
                                ? 'text-ok-deep'
                                : l.type === 'del'
                                ? 'text-bad-deep'
                                : 'text-txt-mid'
                            }`}
                          >
                            {l.text}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </Card>
              ))}
            </div>
          )}
        </div>
      )}

      {/* 创建合并请求 Modal */}
      {showCreateMr && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setShowCreateMr(false)}
        >
          <div
            className="w-full max-w-lg overflow-hidden rounded-lg border border-line-hi bg-ink-850 p-5 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-line pb-3">
              <span className="flex items-center gap-1.5 text-sm font-semibold text-txt-hi">
                <GitPullRequest size={15} className="text-brand" />发起合并请求 (Merge Request)
              </span>
              <button
                type="button"
                onClick={() => setShowCreateMr(false)}
                className="rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"
              >
                <X size={15} />
              </button>
            </div>

            <div className="mt-4 space-y-3 text-xs">
              <div className="flex items-center gap-2 rounded bg-ink-800 p-2 text-txt-mid">
                <span>从</span>
                <span className="font-mono font-semibold text-brand">{sourceBranch}</span>
                <span>合并至</span>
                <span className="font-mono font-semibold text-brand">{targetBranch}</span>
              </div>

              <div>
                <label className="mb-1 block font-medium text-txt-mid">标题 *</label>
                <input
                  type="text"
                  value={mrTitle}
                  onChange={(e) => setMrTitle(e.target.value)}
                  placeholder="简明扼要的合并说明"
                  className="w-full rounded border border-line bg-canvas px-3 py-2 text-xs text-txt-hi outline-none focus:border-brand"
                />
              </div>

              <div>
                <label className="mb-1 block font-medium text-txt-mid">详细说明</label>
                <textarea
                  rows={3}
                  value={mrDesc}
                  onChange={(e) => setMrDesc(e.target.value)}
                  placeholder="关联需求、架构改动、自测说明…"
                  className="w-full rounded border border-line bg-canvas px-3 py-2 text-xs text-txt-hi outline-none focus:border-brand"
                />
              </div>

              {createMsg && (
                <div className="rounded bg-bad-bg p-2 text-bad-deep">
                  {createMsg}
                </div>
              )}
            </div>

            <div className="mt-5 flex justify-end gap-2 border-t border-line pt-3">
              <Btn variant="ghost" onClick={() => setShowCreateMr(false)}>
                取消
              </Btn>
              <Btn
                variant="primary"
                disabled={!mrTitle.trim() || creating}
                onClick={handleDoCreateMr}
              >
                {creating ? <Loader2 size={13} className="animate-spin" /> : null}
                确认发起
              </Btn>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ==================== 7. 代码全文检索子组件 (CodeSearchTab - U4) ====================
/**
 * 代码全文检索 TAB（U4 / V-21 自研 Git 代码搜索）
 * 支持按分支/引用与路径模式进行行级正文检索，并支持点击行直达文件内容查看
 */
function CodeSearchTab({
  isLive,
  repoName,
  activeBranch,
  onSelectRemoteFile,
}: {
  isLive: boolean
  repoName: string
  activeBranch: string
  onSelectRemoteFile: (item: RemoteTreeItem) => void
}) {
  const [searchInput, setSearchInput] = useState('')
  const [pathFilter, setPathFilter] = useState('')
  const [query, setQuery] = useState('')
  const [activePath, setActivePath] = useState('')

  const { data, isLoading, error } = useRepoCodeSearch(
    isLive && query ? repoName : undefined,
    query,
    activeBranch,
    activePath || undefined,
    50
  )

  const handleSearch = (e?: React.FormEvent) => {
    if (e) e.preventDefault()
    if (!searchInput.trim()) return
    setQuery(searchInput.trim())
    setActivePath(pathFilter.trim())
  }

  const handleQuickKeyword = (kw: string) => {
    setSearchInput(kw)
    setQuery(kw)
    setActivePath(pathFilter.trim())
  }

  // 结果按文件路径归组
  const groupedMatches = useMemo(() => {
    if (!data?.matches) return []
    const map = new Map<string, typeof data.matches>()
    for (const m of data.matches) {
      const list = map.get(m.filePath) ?? []
      list.push(m)
      map.set(m.filePath, list)
    }
    return Array.from(map.entries())
  }, [data?.matches])

  return (
    <div className="space-y-4">
      {/* 搜索过滤控制面板 */}
      <Card className="p-4">
        <form onSubmit={handleSearch} className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative flex-1 min-w-[240px]">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-txt-low" />
              <input
                type="text"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="输入关键字搜索代码内容（如函数名、类名、配置项）..."
                className="w-full rounded-input border border-line bg-ink-800 py-2 pl-9 pr-8 text-sm text-txt-hi placeholder:text-txt-low focus:border-brand focus:outline-none"
              />
              {searchInput && (
                <button
                  type="button"
                  onClick={() => setSearchInput('')}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-txt-low hover:text-txt-hi"
                >
                  <X size={14} />
                </button>
              )}
            </div>

            <input
              type="text"
              value={pathFilter}
              onChange={(e) => setPathFilter(e.target.value)}
              placeholder="路径过滤（如 *.java 或 src/）"
              className="w-48 rounded-input border border-line bg-ink-800 px-3 py-2 text-sm text-txt-hi placeholder:text-txt-low focus:border-brand focus:outline-none"
            />

            <span className="inline-flex items-center gap-1 rounded border border-line bg-ink-850 px-2.5 py-1.5 text-xs text-txt-mid">
              <GitBranch size={13} className="text-brand" />
              {activeBranch}
            </span>

            <Btn variant="primary" disabled={isLoading || !searchInput.trim()} onClick={() => handleSearch()}>
              {isLoading ? <Loader2 size={14} className="animate-spin" /> : <Search size={14} />}
              搜索
            </Btn>
          </div>

          <div className="flex items-center gap-2 text-xs text-txt-low">
            <span>常用检索：</span>
            {['class', 'function', 'import', 'export', 'TODO', 'interface'].map((kw) => (
              <button
                key={kw}
                type="button"
                onClick={() => handleQuickKeyword(kw)}
                className="cursor-pointer rounded bg-ink-700 px-2 py-0.5 text-txt-mid hover:bg-ink-600 hover:text-txt-hi"
              >
                {kw}
              </button>
            ))}
          </div>
        </form>
      </Card>

      {/* 离线演示提示 */}
      {!isLive && (
        <Card className="p-4 text-center text-sm text-txt-mid">
          <div className="flex items-center justify-center gap-2 text-cat-orange">
            <AlertTriangle size={16} />
            <span>当前仓库处于离线演示模式，代码全文检索（U4）需连接自研 Git 裸仓库后端执行。</span>
          </div>
        </Card>
      )}

      {/* 搜索结果展示 */}
      {isLive && query && (
        <div>
          {isLoading && (
            <Card className="flex items-center justify-center p-8 text-txt-mid gap-2">
              <Loader2 size={18} className="animate-spin text-brand" />
              <span>正在自研 Git 仓库中检索 “{query}”...</span>
            </Card>
          )}

          {error && (
            <Card className="p-4 text-bad text-sm">
              检索失败：{error instanceof Error ? error.message : String(error)}
            </Card>
          )}

          {data && !isLoading && (
            <div className="space-y-4">
              <div className="flex items-center justify-between text-xs text-txt-low">
                <span>
                  检索关键字 <span className="font-semibold text-txt-hi">“{data.query}”</span> · 匹配{' '}
                  <span className="font-semibold text-brand">{data.totalMatches}</span> 处 · 耗时{' '}
                  <span className="tabular-nums font-semibold text-txt-hi">{data.durationMs}ms</span>
                </span>
                {data.truncated && (
                  <Badge tone="warn">匹配结果已达上限（仅展示前 50 条）</Badge>
                )}
              </div>

              {groupedMatches.length === 0 ? (
                <Empty text={`在分支 ${activeBranch} 中未检索到 “${query}”`} />
              ) : (
                <div className="space-y-3">
                  {groupedMatches.map(([filePath, matches]) => (
                    <Card key={filePath} className="overflow-hidden">
                      <div
                        className="flex cursor-pointer items-center justify-between border-b border-line bg-ink-800 px-4 py-2 hover:bg-ink-750"
                        onClick={() => {
                          const fileName = filePath.split('/').pop() || filePath
                          onSelectRemoteFile({
                            path: filePath,
                            name: fileName,
                            type: 'blob',
                            mode: '100644',
                            sha: '',
                          })
                        }}
                      >
                        <div className="flex items-center gap-2 text-sm font-medium text-txt-hi">
                          <FileCode2 size={15} className="text-brand" />
                          <span className="font-mono">{filePath}</span>
                        </div>
                        <Badge tone="brand">{matches.length} 处匹配</Badge>
                      </div>

                      <div className="divide-y divide-line/40 font-mono text-xs">
                        {matches.map((m, idx) => (
                          <div
                            key={idx}
                            className="flex cursor-pointer items-start hover:bg-brand/10 transition-colors"
                            onClick={() => {
                              const fileName = filePath.split('/').pop() || filePath
                              onSelectRemoteFile({
                                path: filePath,
                                name: fileName,
                                type: 'blob',
                                mode: '100644',
                                sha: '',
                              })
                            }}
                          >
                            <span className="w-14 shrink-0 select-none py-1.5 pr-3 text-right text-txt-low bg-ink-850/50">
                              {m.lineNo}
                            </span>
                            <pre className="flex-1 overflow-x-auto py-1.5 px-3 text-txt-mid whitespace-pre">
                              {m.lineContent}
                            </pre>
                          </div>
                        ))}
                      </div>
                    </Card>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

