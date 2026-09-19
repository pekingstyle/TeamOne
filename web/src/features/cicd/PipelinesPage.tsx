// CI/CD 流水线列表：buildSystem 徽标 + 运行中自动刷新 + 行展开作业时间线 + 日志抽屉 + 触发入口
// （对接自研流水线后端接口与离线降级兼容；⑥k-B 流水线页真实化）
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  ArrowUpRight, ChevronDown, Coffee, GitBranch, GitCommitHorizontal, HelpCircle, Hexagon, Loader2, Play, Sparkles, TerminalSquare, X,
} from 'lucide-react'
import type { PipelineTrigger, RunStatus } from '../../data/types'
import { pipelines, repoById, useStore } from '../../data/store'
import { ApiError } from '../../api/client'
import {
  normRunStatus, pipelinesApi, usePipeline, usePipelines, useRepoMyPermissions, useRepos,
  type RemotePipelineJob,
} from '../../api/queries'
import { Avatar, Badge, Btn, Card, Empty, PageHeader, Pill, StatusDot, runStatusText } from '../../components/ui'
import type { PageProps } from '../../nav'

/** 秒数格式化为中文耗时字符串（如 "1分30秒"） */
function fmtDur(sec: number | undefined): string {
  if (sec === undefined) return '—'
  if (sec < 60) return `${sec}秒`
  return `${Math.floor(sec / 60)}分${String(sec % 60).padStart(2, '0')}秒`
}

/** 触发源显示文案映射 */
const triggerText: Record<PipelineTrigger, string> = { push: '推送', manual: '手动', schedule: '定时', mr: 'MR 触发' }
/** 触发源徽章色调映射 */
const triggerTone: Record<PipelineTrigger, 'brand' | 'vio' | 'warn' | 'neutral'> = { push: 'brand', manual: 'vio', schedule: 'warn', mr: 'neutral' }
/** 运行状态文字颜色映射 */
const statusColor: Record<string, string> = { passed: 'text-ok', failed: 'text-bad', running: 'text-brand' }

/** 状态过滤维度 */
type FilterKey = 'all' | 'passed' | 'failed' | 'running'

/** 构建体系徽标（⑥k-B 契约 buildSystem：maven=Java 橙调 / npm=node 绿调 / unknown=灰） */
function BuildSystemBadge({ bs }: { bs: string | undefined }) {
  if (bs === 'maven') return <Pill tone="orange"><Coffee size={10} />Maven</Pill>
  if (bs === 'npm') return <Pill tone="ok"><Hexagon size={10} />npm</Pill>
  return <Pill tone="neutral"><HelpCircle size={10} />unknown</Pill>
}

/** 作业/run 状态胶囊（success 已在数据入口归一化为 passed；running 带转圈） */
function JobStatusPill({ st }: { st: RunStatus }) {
  const tone: 'ok' | 'bad' | 'brand' | 'neutral' = st === 'passed' ? 'ok' : st === 'failed' ? 'bad' : st === 'running' ? 'brand' : 'neutral'
  return (
    <Pill tone={tone}>
      {st === 'running' && <Loader2 size={10} className="animate-spin" />}
      {runStatusText[st] ?? st}
    </Pill>
  )
}

/** 作业耗时：契约 job 无 durationSec，由 startedAt/finishedAt 推算；running 时按当前时刻计已进行时长 */
function jobDurSec(j: RemotePipelineJob): number | undefined {
  if (!j.startedAt) return undefined
  const s = new Date(j.startedAt).getTime()
  const e = j.finishedAt ? new Date(j.finishedAt).getTime() : Date.now()
  if (isNaN(s) || isNaN(e)) return undefined
  return Math.max(0, Math.round((e - s) / 1000))
}

/** 统一的流水线视图展示接口（抹平后端真实 API 与前端 Mock 数据差异） */
interface DisplayPipeline {
  id: string
  num: string
  title: string
  repoName: string
  branch: string
  trigger: PipelineTrigger
  commitShort: string
  commitMsg: string
  durationSec: number
  startedAt: string
  triggerUserId: string
  status: 'passed' | 'failed' | 'running' | 'pending' | 'skipped' | 'canceled'
  /** 构建体系标识（远端行恒有：缺字段按 unknown；Mock 行无此概念不展示） */
  buildSystem?: string
}

/**
 * CI/CD 流水线全量列表展示与状态过滤视图
 */
export default function PipelinesPage({ nav }: PageProps) {
  useStore()
  const queryClient = useQueryClient()
  // 当前选中的状态过滤器（all / passed / failed / running）
  const [filter, setFilter] = useState<FilterKey>('all')
  // 当前行展开的 run id（作业时间线）
  const [expandedId, setExpandedId] = useState<string | undefined>(undefined)
  // 日志抽屉目标（runId + jobId；job 数据从共享 usePipeline 缓存实时解析，running 时随 3s 轮询更新）
  const [logTarget, setLogTarget] = useState<{ runId: string; jobId: string } | undefined>(undefined)
  // 触发流水线：仓库下拉选中项
  const [trigRepoId, setTrigRepoId] = useState('')
  // 信封 toast（沿用 DefectsPage 提示样式）
  const [toast, setToast] = useState<{ ok: boolean; text: string } | undefined>()
  const showToast = (ok: boolean, text: string) => {
    setToast({ ok, text })
    setTimeout(() => setToast(undefined), 4200)
  }

  // 远端流水线数据查询（存在 running/pending 的 run 时 refetchInterval 3s 自动刷新，全部落定即关闭）
  const { data: remoteData, isLoading } = usePipelines()
  const remotePipelines = remoteData?.items
  const isLive = !!remotePipelines && remotePipelines.length > 0

  // 数据源归一化：若后端自研内核有数据则优先渲染后端数据，否则优雅降级至本地内置 Mock 数据
  // （run.status 真实内核语义 pending/running/success/failed，success 经 normRunStatus 折叠为前端 passed 口径）
  const displayList: DisplayPipeline[] = isLive
    ? remotePipelines.map((p) => {
        const [num, ...rest] = p.title.split('·')
        return {
          id: p.id,
          num: num.trim(),
          title: p.title,
          repoName: p.repoName,
          branch: p.branch,
          trigger: (p.trigger in triggerText ? p.trigger : 'manual') as PipelineTrigger,
          commitShort: p.commitShort ?? (p.commitSha ? p.commitSha.slice(0, 7) : '-'),
          commitMsg: rest.length > 0 ? rest.join('·').trim() : '',
          durationSec: p.durationSec ?? 0,
          startedAt: p.startedAt ? p.startedAt.slice(0, 19).replace('T', ' ') : '-',
          triggerUserId: p.triggerUserId || 'u-eng-lead',
          status: normRunStatus(p.status),
          buildSystem: p.buildSystem ?? 'unknown',
        }
      })
    : pipelines.map((p) => {
        const repo = repoById(p.repoId)
        const [num] = p.title.split('·')
        return {
          id: p.id,
          num: num.trim(),
          title: p.title,
          repoName: repo?.name ?? p.repoId,
          branch: p.branch,
          trigger: p.trigger,
          commitShort: p.commitShort,
          commitMsg: p.commitMsg,
          durationSec: p.durationSec,
          startedAt: p.startedAt,
          triggerUserId: p.triggerUserId,
          status: p.status,
        }
      })

  // 整体运行统计指标计算
  const total = displayList.length
  const passedCount = displayList.filter((p) => p.status === 'passed').length
  const failedCount = displayList.filter((p) => p.status === 'failed').length
  const runningCount = displayList.filter((p) => p.status === 'running').length
  const successRate = total > 0 ? Math.round((passedCount / total) * 100) : 0
  const withDur = displayList.filter((p) => p.durationSec > 0)
  const avgDur = withDur.length > 0 ? Math.round(withDur.reduce((s, p) => s + p.durationSec, 0) / withDur.length) : 0

  // 状态筛选 Tab 标签栏定义
  const tabs: { key: FilterKey; label: string; count: number }[] = [
    { key: 'all', label: '全部', count: total },
    { key: 'passed', label: '成功', count: passedCount },
    { key: 'failed', label: '失败', count: failedCount },
    { key: 'running', label: '运行中', count: runningCount },
  ]

  // 按状态过滤展示的流水线列表
  const list = displayList.filter((p) => filter === 'all' || p.status === filter)

  // ---------------- 触发流水线（仓库下拉 + trigger-pipeline 能力位） ----------------
  const { data: reposData } = useRepos()
  const repos = reposData?.items ?? []
  // 下拉未显式选择时兜底首个仓库
  const selRepo = repos.find((r) => r.id === trigRepoId) ?? repos[0]
  // 能力位判定：仓库 trigger-pipeline；缺省安全——能力位未就绪（加载中/端点未部署）时按有权限，后端 403 兜底
  const permQ = useRepoMyPermissions(selRepo?.id)
  const permsReady = permQ.isSuccess && !!permQ.data
  const canTrigger =
    !permsReady ||
    permQ.data!.platformAdmin === true ||
    (permQ.data!.capabilities ?? []).includes('trigger-pipeline')

  const triggerMut = useMutation({
    mutationFn: async () => {
      if (!selRepo) throw new Error('未选择仓库')
      return pipelinesApi.trigger(selRepo.id, { trigger: 'manual' })
    },
    onSuccess: (run) => {
      // 成功后失效列表缓存并展开新 run（新 run 初始 pending，切回「全部」保证可见）
      void queryClient.invalidateQueries({ queryKey: ['pipelines'] })
      setFilter('all')
      setExpandedId(run.id)
      showToast(true, `已触发：${run.title}`)
    },
    onError: (err) => {
      const text = err instanceof ApiError ? `触发被拒绝：${err.message}` : `触发失败：${(err as Error).message}`
      showToast(false, text)
    },
  })

  return (
    <div>
      <PageHeader
        title="CI/CD 流水线"
        desc="构建 · 测试 · 质量扫描 · 制品 · 部署 一条龙执行记录"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={selRepo?.id ?? ''}
              onChange={(e) => setTrigRepoId(e.target.value)}
              disabled={repos.length === 0}
              title="选择要触发的仓库"
              className="cursor-pointer rounded-input border border-line bg-canvas px-2 py-1.5 text-xs text-txt-hi outline-none focus:border-brand disabled:cursor-not-allowed disabled:opacity-50"
            >
              {repos.length === 0 && <option value="">暂无仓库</option>}
              {repos.map((r) => (
                <option key={r.id} value={r.id}>{r.name}</option>
              ))}
            </select>
            <Btn
              variant="primary"
              disabled={!selRepo || !canTrigger || triggerMut.isPending}
              onClick={() => triggerMut.mutate()}
            >
              {triggerMut.isPending ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}
              触发流水线
            </Btn>
            {permsReady && !canTrigger && (
              <span className="text-[11px] text-txt-low">无 trigger-pipeline 权限</span>
            )}
          </div>
        }
      />

      {/* 统计 chips */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge tone="neutral">总运行 <span className="tabular-nums">{total}</span></Badge>
        <Badge tone="ok">成功率 <span className="tabular-nums">{successRate}%</span></Badge>
        <Badge tone="brand">平均耗时 <span className="tabular-nums">{fmtDur(avgDur)}</span></Badge>
        {isLive && (
          <Badge tone="brand">
            <Sparkles size={12} className="mr-0.5" />自研流水线内核已接通
          </Badge>
        )}
        {isLoading && <Loader2 size={13} className="animate-spin text-brand ml-1" />}
      </div>

      {/* 状态过滤 tabs */}
      <div className="mb-3 flex items-center gap-1 border-b border-line">
        {tabs.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => setFilter(t.key)}
            className={`-mb-px cursor-pointer border-b-2 px-3 pb-2 pt-1 text-sm transition-colors ${
              filter === t.key ? 'border-brand font-medium text-brand' : 'border-transparent text-txt-mid hover:text-txt-hi'
            }`}
          >
            {t.label}
            <span className="ml-1.5 rounded-full bg-ink-700 px-1.5 py-px text-[11px] tabular-nums text-txt-mid">{t.count}</span>
          </button>
        ))}
      </div>

      <Card>
        {list.length === 0 ? (
          <Empty text="没有符合条件的流水线运行" />
        ) : (
          <ul className="divide-y divide-line">
            {list.map((p) => (
              <li key={p.id} className={expandedId === p.id ? 'bg-ink-800/50' : ''}>
                {/* 行主体：点击展开/收起作业时间线 */}
                <div
                  role="button"
                  tabIndex={0}
                  onClick={() => setExpandedId(expandedId === p.id ? undefined : p.id)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setExpandedId(expandedId === p.id ? undefined : p.id) }}
                  className="flex w-full cursor-pointer items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-ink-800"
                >
                  <StatusDot status={p.status as RunStatus} size={10} />
                  <span className={`w-11 shrink-0 text-xs font-medium ${statusColor[p.status] ?? 'text-txt-mid'}`}>
                    {runStatusText[p.status as RunStatus] ?? p.status}
                  </span>
                  <div className="w-56 min-w-0 shrink-0">
                    <div className="truncate text-sm font-medium text-txt-hi">
                      <span className="font-mono">{p.num}</span>
                      {p.commitMsg && <span> · {p.commitMsg}</span>}
                    </div>
                    <div className="mt-0.5 flex items-center gap-1.5 text-xs text-txt-low">
                      <span className="truncate font-mono">{p.repoName}</span>
                      {p.buildSystem && (
                        <span className="shrink-0 font-sans"><BuildSystemBadge bs={p.buildSystem} /></span>
                      )}
                    </div>
                  </div>
                  <span className="inline-flex shrink-0 items-center gap-1 rounded border border-line-hi bg-ink-800 px-1.5 py-0.5 font-mono text-[11px] text-txt-mid">
                    <GitBranch size={11} className="text-txt-low" />
                    {p.branch}
                  </span>
                  <Badge tone={triggerTone[p.trigger]}>{triggerText[p.trigger]}</Badge>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-xs text-txt-mid">
                      <span className="font-mono text-txt-low">{p.commitShort}</span> {p.commitMsg}
                    </div>
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    <div className="text-right">
                      <div className="flex items-center justify-end gap-1 text-xs tabular-nums text-txt-mid">
                        <GitCommitHorizontal size={11} className="text-txt-low" />
                        {fmtDur(p.durationSec)}
                      </div>
                      <div className="mt-0.5 text-[11px] tabular-nums text-txt-low">{p.startedAt}</div>
                    </div>
                    <Avatar userId={p.triggerUserId} size={26} />
                    {/* 打开整页详情（保留原跳转入口） */}
                    <button
                      type="button"
                      title="打开详情页"
                      onClick={(e) => { e.stopPropagation(); nav.go('pipeline', p.id) }}
                      className="cursor-pointer rounded p-1 text-txt-low transition-colors hover:bg-ink-700 hover:text-brand"
                    >
                      <ArrowUpRight size={14} />
                    </button>
                    <ChevronDown size={14} className={`shrink-0 text-txt-low transition-transform ${expandedId === p.id ? 'rotate-180' : ''}`} />
                  </div>
                </div>
                {/* 行展开区：作业时间线（真实 run 走详情接口；Mock/模拟时代 run 无作业明细） */}
                {expandedId === p.id &&
                  (isLive ? (
                    <RunExpand runId={p.id} onOpenLog={(jobId) => setLogTarget({ runId: p.id, jobId })} />
                  ) : (
                    <div className="border-t border-line bg-ink-900/40 px-4 py-3 text-xs text-txt-low">
                      模拟运行（无作业明细）
                    </div>
                  ))}
              </li>
            ))}
          </ul>
        )}
      </Card>

      {/* 日志抽屉 */}
      {logTarget && (
        <LogDrawer runId={logTarget.runId} jobId={logTarget.jobId} onClose={() => setLogTarget(undefined)} />
      )}

      {/* 信封 toast */}
      {toast && (
        <div className="fixed bottom-6 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-2 rounded-full border border-line bg-canvas px-4 py-2.5 text-sm text-txt-hi shadow-lg">
          <span className={`h-2 w-2 rounded-full ${toast.ok ? 'bg-ok' : 'bg-bad'}`}/>{toast.text}
        </div>
      )}
    </div>
  )
}

/**
 * 行展开区：单条 run 的真实执行作业时间线（GET /api/v1/pipelines/{id} 的 jobs 字段）。
 * run 或任一作业 running/pending 时由 usePipeline 的 refetchInterval 3s 自动刷新。
 * 历史兼容：无 jobs 字段（模拟时代 run）→「模拟运行（无作业明细）」；jobs 空数组 → 空态。
 */
function RunExpand({ runId, onOpenLog }: { runId: string; onOpenLog: (jobId: string) => void }) {
  const { data: run, isLoading, error } = usePipeline(runId)

  if (isLoading && !run) {
    return (
      <div className="flex items-center gap-2 border-t border-line px-4 py-3 text-xs text-txt-low">
        <Loader2 size={13} className="animate-spin text-brand" />加载作业明细…
      </div>
    )
  }
  if (!run) {
    const hint = error instanceof ApiError && error.status === 404 ? '（运行不存在）' : ''
    return <div className="border-t border-line px-4 py-3 text-xs text-txt-low">作业明细加载失败{hint}</div>
  }

  const jobs = run.jobs
  const runSt = normRunStatus(run.status)
  const anyActive = runSt === 'running' || runSt === 'pending'
  return (
    <div className="border-t border-line bg-ink-900/40 px-4 py-3">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-[11px] font-semibold text-txt-low">作业时间线（Build → Test 链式执行）</span>
        {anyActive && <span className="text-[11px] text-txt-low">运行中 · 3s 自动刷新</span>}
      </div>
      {jobs === undefined ? (
        <div className="py-2 text-xs text-txt-low">模拟运行（无作业明细）</div>
      ) : jobs.length === 0 ? (
        <Empty size="sm" text="该运行暂无作业明细" />
      ) : (
        <ol className="space-y-1.5">
          {jobs.map((j) => (
            <JobTimelineRow key={j.id} job={j} onOpenLog={() => onOpenLog(j.id)} />
          ))}
        </ol>
      )}
    </div>
  )
}

/** 时间线单作业行：stage 徽标 + name + 状态胶囊 + exitCode + 耗时 + 日志按钮 */
function JobTimelineRow({ job, onOpenLog }: { job: RemotePipelineJob; onOpenLog: () => void }) {
  const st = normRunStatus(job.status)
  const dur = jobDurSec(job)
  return (
    <li className="flex flex-wrap items-center gap-2 rounded-md border border-line bg-ink-850 px-3 py-2">
      <Pill tone={job.stage === 'test' ? 'teal' : 'info'}>{job.stage === 'test' ? 'Test' : 'Build'}</Pill>
      <span className="min-w-0 max-w-[280px] shrink truncate text-xs font-medium text-txt-hi">{job.name || job.id}</span>
      <JobStatusPill st={st} />
      {job.exitCode != null && (
        <span className={`font-mono text-[11px] tabular-nums ${job.exitCode === 0 ? 'text-txt-low' : 'text-bad'}`}>
          exit {job.exitCode}
        </span>
      )}
      <span className="text-[11px] tabular-nums text-txt-low">
        {st === 'running' && dur !== undefined ? `${fmtDur(dur)}…` : fmtDur(dur)}
      </span>
      <button
        type="button"
        onClick={onOpenLog}
        className="ml-auto inline-flex shrink-0 cursor-pointer items-center gap-1 rounded border border-line px-1.5 py-0.5 text-[11px] text-txt-mid transition-colors hover:border-brand/50 hover:text-brand"
      >
        <TerminalSquare size={11} />日志
      </button>
    </li>
  )
}

/**
 * 日志抽屉：显示作业 logTail 尾部文本（等宽 pre-wrap）。
 * job 对象从 usePipeline 共享缓存实时解析——running 作业的日志尾部随 3s 轮询自动追加刷新。
 */
function LogDrawer({ runId, jobId, onClose }: { runId: string; jobId: string; onClose: () => void }) {
  const { data: run } = usePipeline(runId)
  const job = run?.jobs?.find((j) => j.id === jobId)
  if (!job) {
    return (
      <div className="fixed inset-0 z-50 bg-black/30" onClick={onClose}>
        <div
          className="absolute inset-y-0 right-0 flex w-[560px] max-w-full flex-col justify-center border-l border-line bg-canvas p-5 shadow-2xl"
          onClick={(e) => e.stopPropagation()}
        >
          <Empty text="未找到该作业" />
        </div>
      </div>
    )
  }
  const st = normRunStatus(job.status)
  return (
    <div className="fixed inset-0 z-50 bg-black/30" onClick={onClose}>
      <div
        className="absolute inset-y-0 right-0 flex w-[560px] max-w-full flex-col border-l border-line bg-canvas shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start gap-2 border-b border-line px-4 py-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <Pill tone={job.stage === 'test' ? 'teal' : 'info'}>{job.stage === 'test' ? 'Test' : 'Build'}</Pill>
              <JobStatusPill st={st} />
              {job.exitCode != null && (
                <span className="font-mono text-[11px] tabular-nums text-txt-low">exit {job.exitCode}</span>
              )}
              <span className="text-[11px] tabular-nums text-txt-low">{fmtDur(jobDurSec(job))}</span>
            </div>
            <h3 className="mt-1.5 truncate text-sm font-bold text-txt-hi">{job.name || job.id}</h3>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer text-txt-low hover:text-txt-hi">
            <X size={16} />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto p-4">
          {st === 'running' && (
            <div className="mb-2 flex items-center gap-1.5 text-xs text-brand">
              <Loader2 size={12} className="animate-spin" />运行中，日志尾部随 3s 轮询更新…
            </div>
          )}
          {job.logTail ? (
            <pre className="whitespace-pre-wrap break-words rounded-md bg-ink-950 p-3 font-mono text-xs leading-5 text-txt-mid">
              {job.logTail}
            </pre>
          ) : (
            job.errorMsg ? (
              <div className="rounded border border-bad/30 bg-bad-bg px-3 py-2 text-xs text-bad-deep">{job.errorMsg}</div>
            ) : (
              <Empty text="暂无日志（模拟运行或未开始）" />
            )
          )}
        </div>
      </div>
    </div>
  )
}
