// 代码域 · 仓库详情：文件 / 提交 / 分支 / 工作树（R7） / 基线（R9）
import { useState } from 'react'
import type { ReactNode } from 'react'
import { ArrowLeft, ChevronRight, FileCode2, Folder, FolderTree, GitBranch, GitCommitHorizontal, Lock, Network, Package, ShieldCheck, Star, X } from 'lucide-react'
import { approveBaseline, baselineById, baselineTypeText, baselines, branches, commits, componentById, fileTrees, mrById, productById, repoById, submitBaseline, useStore, userById, workItemById, workTrees, type WorkTreeItem } from '../../data/store'
import type { Baseline, FileNode } from '../../data/types'
import type { PageProps } from '../../nav'
import { Avatar, Badge, Btn, Card, CardHeader, Empty, Pill } from '../../components/ui'

type TabKey = 'files' | 'commits' | 'branches' | 'worktrees' | 'baselines'
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
const SectionTitle = ({ children }: { children: ReactNode }) => <div className="mb-1.5 text-xs font-semibold text-txt-low">{children}</div>

export default function RepoDetailPage({ nav, id }: PageProps) {
  useStore()
  const [repoId, tabHint = ''] = (id ?? '').split('#')
  const repo = repoId ? repoById(repoId) : undefined
  const [tab, setTab] = useState<TabKey>(tabHint === 'worktrees' ? 'worktrees' : tabHint === 'baselines' ? 'baselines' : 'files')
  const [path, setPath] = useState<string[]>([])
  const [preview, setPreview] = useState<FileNode | null>(null)

  if (!repo) {
    return (
      <div>
        <Btn variant="ghost" onClick={() => nav.go('repos')} className="mb-4 -ml-3"><ArrowLeft size={14} />返回仓库列表</Btn>
        <Empty text="仓库不存在或已被删除" />
      </div>
    )
  }

  const repoBranches = branches.filter((b) => b.repoId === repo.id)
  const repoCommits = commits.filter((c) => c.repoId === repo.id)
  const repoWorkTrees = workTrees.filter((w) => w.repoId === repo.id)
  const repoBaselines = baselines.filter((b) => b.repoId === repo.id)
  const product = repo.productId ? productById(repo.productId) : undefined
  const comp = repo.componentId ? componentById(repo.componentId) : undefined
  const nodes = [...nodesAt(fileTrees[repo.id], path)].sort((a, b) => (a.kind === b.kind ? 0 : a.kind === 'dir' ? -1 : 1))

  return (
    <div>
      <Btn variant="ghost" onClick={() => nav.go('repos')} className="mb-3 -ml-3"><ArrowLeft size={14} />返回仓库列表</Btn>

      {/* 仓库头部 */}
      <div className="mb-5">
        <div className="flex flex-wrap items-center gap-2.5">
          <h1 className="font-mono text-2xl font-bold text-txt-hi">{repo.name}</h1>
          <Badge tone={repo.visibility === 'private' ? 'neutral' : 'ok'}>{repo.visibility === 'private' ? '私有' : '公开'}</Badge>
          {repo.ciEnabled && <Badge tone="brand">CI 已启用</Badge>}
        </div>
        <p className="mt-1.5 text-sm text-txt-mid">{repo.description}</p>
        <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
          <span className={chip}><Star size={12} />{repo.stars} stars</span>
          <span className={chip}><GitBranch size={12} />{repoBranches.length} 分支</span>
          <span className={chip}><GitCommitHorizontal size={12} />{repoCommits.length} 提交</span>
          {product && <span className={chip}><Package size={12} className="text-brand" />所属产品 · {product.name}</span>}
          {comp && <span className={chip}>所属组件 · {comp.name}</span>}
        </div>
      </div>

      {/* Tabs */}
      <div className="mb-4 flex gap-1 overflow-x-auto border-b border-line">
        {([
          ['files', '文件'], ['commits', `提交 ${repoCommits.length}`], ['branches', `分支 ${repoBranches.length}`],
          ['worktrees', `工作树 ${repoWorkTrees.length}`], ['baselines', `基线 ${repoBaselines.length}`],
        ] as [TabKey, string][]).map(([key, label]) => (
          <button key={key} type="button" onClick={() => setTab(key)}
            className={`-mb-px shrink-0 cursor-pointer border-b-2 px-4 py-2 text-sm transition-colors ${tab === key ? 'border-brand font-semibold text-txt-hi' : 'border-transparent text-txt-mid hover:text-txt-hi'}`}>
            {label}
          </button>
        ))}
      </div>

      {tab === 'files' && (
        <Card>
          <div className="flex flex-wrap items-center gap-1 border-b border-line px-4 py-2.5 text-sm">
            <GitBranch size={13} className="text-txt-low" />
            <button type="button" onClick={() => setPath([])} className="cursor-pointer font-mono text-brand-deep hover:underline">{repo.defaultBranch}</button>
            {path.map((seg, i) => (
              <span key={i} className="flex items-center gap-1">
                <span className="text-txt-low">/</span>
                {i === path.length - 1
                  ? <span className="font-mono text-txt-hi">{seg}</span>
                  : <button type="button" onClick={() => setPath(path.slice(0, i + 1))} className="cursor-pointer font-mono text-brand-deep hover:underline">{seg}</button>}
              </span>
            ))}
          </div>
          <ul className="divide-y divide-line">
            {nodes.map((n) => (
              <li key={n.name}>
                <button type="button" onClick={() => (n.kind === 'dir' ? setPath([...path, n.name]) : setPreview(n))}
                  className="flex w-full cursor-pointer items-center gap-2.5 px-4 py-2.5 text-left hover:bg-ink-700">
                  {n.kind === 'dir' ? <Folder size={15} className="shrink-0 text-warn/80" /> : <FileCode2 size={15} className="shrink-0 text-txt-low" />}
                  <span className={`w-56 shrink-0 truncate text-sm ${n.kind === 'dir' ? 'text-txt-hi' : 'text-txt-mid'}`}>{n.name}</span>
                  <span className="min-w-0 flex-1 truncate text-xs text-txt-low">{n.lastCommitMsg ?? ''}</span>
                  <span className="shrink-0 text-xs tabular-nums text-txt-low">{n.updatedAt ?? ''}</span>
                  {n.kind === 'dir' && <ChevronRight size={14} className="shrink-0 text-txt-low/60" />}
                </button>
              </li>
            ))}
            {nodes.length === 0 && <li className="px-4 py-10 text-center text-sm text-txt-low">此目录为空</li>}
          </ul>
        </Card>
      )}

      {tab === 'commits' && (
        <Card>
          <ul className="divide-y divide-line">
            {repoCommits.map((c) => (
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
                <span className="w-24 shrink-0 text-right text-xs tabular-nums text-txt-low">{c.date}</span>
              </li>
            ))}
            {repoCommits.length === 0 && <li><Empty text="暂无提交记录" /></li>}
          </ul>
        </Card>
      )}

      {tab === 'branches' && (
        <Card>
          <ul className="divide-y divide-line">
            {repoBranches.map((b) => (
              <li key={b.name} className="flex items-center gap-3 px-4 py-3">
                <GitBranch size={15} className="shrink-0 text-txt-low" />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <span className="truncate font-mono text-sm text-txt-hi">{b.name}</span>
                    {b.protected && <span title="受保护分支" className="flex shrink-0"><Lock size={11} className="text-warn" /></span>}
                    {b.name === repo.defaultBranch && <Badge tone="brand">默认</Badge>}
                  </div>
                  <div className="mt-0.5 truncate text-xs text-txt-low">{b.lastCommitMsg}</div>
                </div>
                <span className="flex shrink-0 items-center gap-1.5 text-xs tabular-nums">
                  <span className="rounded bg-ink-700 px-1.5 py-0.5 text-ok-deep">↑{b.ahead}</span>
                  <span className="rounded bg-ink-700 px-1.5 py-0.5 text-bad-deep">↓{b.behind}</span>
                </span>
                <span className="w-24 shrink-0 text-right text-xs tabular-nums text-txt-low">{b.updatedAt}</span>
                <span className="flex shrink-0 items-center gap-1.5">
                  <Avatar userId={b.authorId} size={20} />
                  <span className="text-xs text-txt-mid">{userById(b.authorId)?.name}</span>
                </span>
              </li>
            ))}
            {repoBranches.length === 0 && <li><Empty text="暂无分支" /></li>}
          </ul>
        </Card>
      )}

      {tab === 'worktrees' && <WorkTreeTab repoId={repo.id} defaultBranch={repo.defaultBranch} nav={nav} />}
      {tab === 'baselines' && <BaselineTab repoId={repo.id} nav={nav} />}

      {/* 文件预览 Modal */}
      {preview && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-6" onClick={() => setPreview(null)}>
          <div className="max-h-[80vh] w-full max-w-2xl overflow-hidden rounded-lg border border-line-hi bg-ink-850 shadow-2xl" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center gap-2 border-b border-line px-4 py-3">
              <FileCode2 size={15} className="text-brand" />
              <span className="min-w-0 flex-1 truncate font-mono text-sm text-txt-hi">{preview.name}</span>
              <button type="button" onClick={() => setPreview(null)} className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"><X size={15} /></button>
            </div>
            <div className="flex items-center gap-2 border-b border-line px-4 py-2 text-xs text-txt-low">
              <GitCommitHorizontal size={12} className="shrink-0" />
              <span className="truncate">{preview.lastCommitMsg ?? '—'}</span>
              <span className="ml-auto shrink-0 tabular-nums">更新于 {preview.updatedAt ?? '—'}</span>
            </div>
            <pre className="overflow-auto bg-card p-4 font-mono text-xs leading-5 text-txt-mid">{snippetFor(preview.name)}</pre>
          </div>
        </div>
      )}
    </div>
  )
}

// ==================== R7 工作树 ====================
function WorkTreeTab({ repoId, defaultBranch, nav }: { repoId: string; defaultBranch: string; nav: Nav }) {
  const [sel, setSel] = useState<string | undefined>(undefined)
  const list = workTrees.filter((w) => w.repoId === repoId)
  const selWt = list.find((w) => w.id === sel)
  const rowH = 84
  const trunkY = 42
  const H = list.length === 0 ? 130 : 100 + list.length * rowH
  return (
    <div className="flex flex-col gap-4 lg:flex-row">
      {/* 左：工作副本列表 */}
      <Card className="min-w-0 flex-1 self-start">
        <CardHeader title={<span className="flex items-center gap-1.5"><FolderTree size={14} />工作副本 · {list.length}</span>} extra={<span className="text-[11px] text-txt-low">本机 git worktree</span>} />
        <ul className="divide-y divide-line">
          {list.map((wt) => {
            const task = wt.relatedTaskId ? workItemById(wt.relatedTaskId) : undefined
            return (
              <li key={wt.id}>
                <div onClick={() => setSel(wt.id)} className={`cursor-pointer px-4 py-3 transition-colors hover:bg-ink-700 ${sel === wt.id ? 'bg-brand-bg/60' : ''}`}>
                  <div className="flex items-center gap-2.5">
                    <span className={`h-2 w-2 shrink-0 rounded-full ${wtDot[wt.status]}`} title={wtText[wt.status]} />
                    <span className="truncate font-mono text-sm font-medium text-txt-hi">{wt.name}</span>
                    {wt.behind > 0 && <Pill tone="warn">落后，建议 rebase</Pill>}
                    {wt.dirtyFileCount > 0 && <Pill tone="orange">{wt.dirtyFileCount} 个未提交</Pill>}
                    <span className="flex-1" />
                    <Avatar userId={wt.ownerId} size={20} />
                    <span className="shrink-0 text-xs font-medium tabular-nums">
                      <span className="text-ok-deep">+{wt.ahead}</span> <span className={wt.behind > 0 ? 'text-warn-deep' : 'text-txt-low'}>-{wt.behind}</span>
                    </span>
                  </div>
                  <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 pl-[18px] text-xs text-txt-low">
                    <span className="text-txt-mid">{userById(wt.ownerId)?.name}</span>
                    {task && (
                      <button type="button" title={task.title} onClick={(e) => { e.stopPropagation(); nav.go(task.key.startsWith('D-') ? 'defects' : 'tasks', task.id) }}
                        className="cursor-pointer font-mono text-brand-deep hover:underline">{task.key}</button>
                    )}
                    <span>{basedOnText(wt)}</span>
                    <span className="font-mono">{wt.localPath}</span>
                    <span className="ml-auto tabular-nums">{wt.lastCommitAt}</span>
                  </div>
                </div>
              </li>
            )
          })}
          {list.length === 0 && <li><Empty text="暂无工作树" /></li>}
        </ul>
      </Card>

      {/* 右：关系图（纯 SVG：主干水平线 + 按 basedOn 挂斜线分支，节点着归属人颜色） */}
      <Card className="w-full shrink-0 self-start lg:w-[27rem]">
        <CardHeader title={<span className="flex items-center gap-1.5"><Network size={14} />关系图</span>} extra={<span className="text-[11px] text-txt-low">节点=工作树 · 颜色=归属人</span>} />
        <div className="p-3">
          <svg viewBox={`0 0 640 ${H}`} className="w-full">
            <line x1={20} y1={trunkY} x2={620} y2={trunkY} stroke="var(--color-line-hi)" strokeWidth={2} />
            <text x={20} y={trunkY - 12} fontSize={11} className="fill-txt-mid font-mono">{defaultBranch}（主干）</text>
            {[160, 330, 510].map((x) => <circle key={x} cx={x} cy={trunkY} r={4.5} fill="var(--color-canvas)" stroke="var(--color-line-hi)" strokeWidth={2} />)}
            {list.map((wt, i) => {
              const ax = Math.min(96 + i * 128, 500)
              const nx = ax + 48
              const ny = 100 + i * rowH
              const active = sel === wt.id
              const left = nx > 460
              return (
                <g key={wt.id} onClick={() => setSel(wt.id)} className="cursor-pointer">
                  <circle cx={ax} cy={trunkY} r={3} fill="var(--color-line-hi)" />
                  <text x={ax} y={trunkY + 18} fontSize={10} textAnchor="middle" className="fill-txt-low">{basedOnShort(wt)}</text>
                  <line x1={ax} y1={trunkY} x2={nx} y2={ny} stroke={active ? 'var(--color-brand)' : 'var(--color-line-hi)'} strokeWidth={active ? 2 : 1.5} />
                  <text x={(ax + nx) / 2 + 8} y={(trunkY + ny) / 2} fontSize={10} className="fill-txt-mid tabular-nums">+{wt.ahead} / -{wt.behind}</text>
                  {active && <circle cx={nx} cy={ny} r={13} fill="none" stroke="var(--color-brand)" strokeWidth={1.5} strokeDasharray="3 3" />}
                  <circle cx={nx} cy={ny} r={8} fill={userById(wt.ownerId)?.color ?? 'var(--color-txt-low)'} stroke="var(--color-canvas)" strokeWidth={2} />
                  <text x={left ? nx - 16 : nx + 16} y={ny - 1} fontSize={12} textAnchor={left ? 'end' : 'start'} className="fill-txt-hi font-mono">{wt.name}</text>
                  <text x={left ? nx - 16 : nx + 16} y={ny + 14} fontSize={10} textAnchor={left ? 'end' : 'start'} className="fill-txt-low">{userById(wt.ownerId)?.name} · {wt.branch}</text>
                  <title>{`${wt.name} · ${userById(wt.ownerId)?.name ?? ''} · ${wt.branch}`}</title>
                </g>
              )
            })}
            {list.length === 0 && <text x={320} y={trunkY + 44} fontSize={12} textAnchor="middle" className="fill-txt-low">暂无工作树</text>}
          </svg>
        </div>
        {selWt && (
          <div className="border-t border-line px-4 py-3 text-xs">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
              <span className="font-mono text-sm font-semibold text-txt-hi">{selWt.name}</span>
              <span className="flex items-center gap-1.5"><Avatar userId={selWt.ownerId} size={18} />{userById(selWt.ownerId)?.name}</span>
              <Pill tone={selWt.status === 'active' ? 'ok' : selWt.status === 'stale' ? 'pink' : 'neutral'}>{wtText[selWt.status]}</Pill>
              <span className="text-ok-deep tabular-nums">+{selWt.ahead}</span>
              <span className="text-warn-deep tabular-nums">-{selWt.behind}</span>
              {selWt.dirtyFileCount > 0 && <span className="font-medium text-cat-orange">{selWt.dirtyFileCount} 个未提交</span>}
              <span className="text-txt-mid">{basedOnText(selWt)}</span>
            </div>
            <div className="mt-1.5 flex flex-wrap items-center gap-x-3 text-txt-low">
              <span className="font-mono">分支 {selWt.branch}</span>
              <span className="font-mono">{selWt.localPath}</span>
              <span className="tabular-nums">最近提交 {selWt.lastCommitAt}</span>
            </div>
          </div>
        )}
      </Card>
    </div>
  )
}

// ==================== R9 基线 ====================
function BaselineTab({ repoId, nav }: { repoId: string; nav: Nav }) {
  const [openId, setOpenId] = useState<string | undefined>(undefined)
  const list = baselines.filter((b) => b.repoId === repoId)
  return (
    <Card>
      <CardHeader title={<span className="flex items-center gap-1.5"><Lock size={14} className="text-cat-teal" />基线管理（功能 / 分配 / 产品）</span>}
        extra={<span className="text-[11px] text-txt-low">≥2 人批准自动定版冻结 · 定版后变更走变更单</span>} />
      <ul className="divide-y divide-line">
        {list.map((b) => (
          <li key={b.id}>
            <div onClick={() => setOpenId(openId === b.id ? undefined : b.id)}
              className={`flex cursor-pointer flex-wrap items-center gap-2 px-4 py-3 transition-colors hover:bg-ink-700 ${openId === b.id ? 'bg-ink-700/60' : ''}`}>
              <Pill tone={blTone[b.type]}>{baselineTypeText[b.type]}</Pill>
              <Lock size={12} className="shrink-0 text-cat-teal" />
              <span className="text-sm font-medium text-txt-hi">{b.name}</span>
              <span className="font-mono text-xs text-txt-mid">{b.tagRef}{b.artifactVersion ? ` · ${b.artifactVersion}` : ''}</span>
              <span className="font-mono text-xs text-txt-low">{b.commitShort}</span>
              <span className="flex-1" />
              <span className="flex -space-x-1.5" title={`批准人 ${b.approverIds.length} 人`}>
                {b.approverIds.map((uid) => <Avatar key={uid} userId={uid} size={20} />)}
              </span>
              {b.status === 'approved'
                ? <Pill tone="ok"><Lock size={10} />{blText[b.status]}</Pill>
                : b.status === 'in_review'
                  ? <Pill tone="warn">{blText[b.status]} {b.approverIds.length}/2</Pill>
                  : <Pill tone="neutral">{blText[b.status]}</Pill>}
              <span className="w-24 shrink-0 text-right text-xs tabular-nums text-txt-low">{b.createdAt}</span>
            </div>
            {openId === b.id && <BaselineDetail b={b} nav={nav} onOpenSuperseded={() => setOpenId(b.supersededById)} />}
          </li>
        ))}
        {list.length === 0 && <li><Empty text="暂无基线" /></li>}
      </ul>
    </Card>
  )
}

function BaselineDetail({ b, nav, onOpenSuperseded }: { b: Baseline; nav: Nav; onOpenSuperseded: () => void }) {
  return (
    <div className="border-t border-line bg-canvas px-4 py-3.5">
      <div className="grid grid-cols-1 gap-x-8 gap-y-4 md:grid-cols-2">
        <div>
          <SectionTitle>包含提交（{b.includesCommits.length}）</SectionTitle>
          <div className="flex flex-wrap gap-1.5">
            {b.includesCommits.map((c) => <span key={c} className="rounded bg-ink-700 px-1.5 py-0.5 font-mono text-xs text-txt-mid">{c}</span>)}
          </div>
          <div className="mt-3.5">
            <SectionTitle>保护分支</SectionTitle>
            <div className="flex flex-wrap gap-1.5">
              {b.protectedBranches.map((p) => (
                <span key={p} className="inline-flex items-center gap-1 rounded bg-ink-700 px-1.5 py-0.5 font-mono text-xs text-txt-mid"><Lock size={10} className="text-cat-teal" />{p}</span>
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
                  <button key={mid} type="button" onClick={() => nav.go('mr', mid)} title={m.title}
                    className="cursor-pointer rounded bg-brand-bg px-1.5 py-0.5 font-mono text-xs text-brand-deep hover:underline">!{m.number}</button>
                ) : null
              })}
            </div>
          </div>
        </div>
        <div>
          <SectionTitle>冲突解决记录</SectionTitle>
          {b.conflictResolved.length === 0 ? <span className="text-xs text-txt-low">无</span> : (
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="text-txt-low">
                  <th className="py-1 pr-2 font-medium">文件</th><th className="pr-2 font-medium">解决方案</th>
                  <th className="pr-2 font-medium">确认</th><th className="pr-2 font-medium">复核</th><th className="font-medium">时间</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {b.conflictResolved.map((r, i) => (
                  <tr key={i}>
                    <td className="py-1.5 pr-2 font-mono text-txt-mid">{r.filePath}</td>
                    <td className="py-1.5 pr-2 text-txt-mid">{r.solution}</td>
                    <td className="py-1.5 pr-2"><Avatar userId={r.confirmedById} size={16} /></td>
                    <td className="py-1.5 pr-2"><Avatar userId={r.reviewedById} size={16} /></td>
                    <td className="py-1.5 text-txt-low tabular-nums">{r.resolvedAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {b.status === 'superseded' && b.supersededById && (
            <div className="mt-3 rounded-md bg-ink-700 px-3 py-2 text-xs text-txt-mid">
              本基线已废止，由{' '}
              <button type="button" onClick={onOpenSuperseded} className="cursor-pointer font-medium text-brand-deep hover:underline">{baselineById(b.supersededById)?.name}</button>
              {' '}取代
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
          {b.status === 'draft'
            ? <Btn onClick={() => submitBaseline(b.id)}><ShieldCheck size={14} />提交审批</Btn>
            : (
              <>
                <Btn variant="primary" onClick={() => approveBaseline(b.id)}><Lock size={14} />批准</Btn>
                <span className="text-xs text-txt-low">已批准 {b.approverIds.length}/2 · 达 2 人自动定版并冻结</span>
              </>
            )}
        </div>
      )}
    </div>
  )
}
