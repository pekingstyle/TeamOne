// CI/CD 流水线运行详情视图：阶段轨道 (Stage Track) + Job 步骤卡片 + 控制台日志输出面板
import { Fragment, useState } from 'react'
import { ArrowLeft, CalendarClock, GitBranch, GitCommitHorizontal, Play, Sparkles, Timer, Loader2 } from 'lucide-react'
import type { Job, RunStatus, Stage } from '../../data/types'
import { pipelineById, repoById, runPipeline, useStore, userById } from '../../data/store'
import { usePipeline, pipelinesApi } from '../../api/queries'
import { Avatar, Badge, Btn, Card, CardHeader, Empty, StatusDot, runStatusText } from '../../components/ui'
import type { PageProps } from '../../nav'

/**
 * 格式化持续时间（秒）为直观的中文展示文案（如 "42秒" 或 "1分58秒"）
 */
function fmtDur(sec: number | undefined): string {
  if (sec === undefined) return '—'
  if (sec < 60) return `${sec}秒`
  return `${Math.floor(sec / 60)}分${String(sec % 60).padStart(2, '0')}秒`
}

/**
 * 根据阶段内包含的各个 Job 状态推导阶段（Stage）的整体流转状态：
 * 1. 任意 Job 失败 -> 阶段失败 (failed)
 * 2. 存在运行中 Job -> 阶段运行中 (running)
 * 3. 所有 Job 均通过 -> 阶段通过 (passed)
 * 4. 所有 Job 均被跳过或取消 -> 阶段跳过 (skipped)
 * 5. 其余情况 -> 等待中 (pending)
 */
function stageStatus(jobs: Job[]): RunStatus {
  if (jobs.some((j) => j.status === 'failed')) return 'failed'
  if (jobs.some((j) => j.status === 'running')) return 'running'
  if (jobs.length > 0 && jobs.every((j) => j.status === 'passed')) return 'passed'
  if (jobs.length > 0 && jobs.every((j) => j.status === 'skipped' || j.status === 'canceled')) return 'skipped'
  return 'pending'
}

/** 详情页顶部大状态胶囊徽章样式映射 */
const bigStatusStyle: Record<RunStatus, string> = {
  passed: 'bg-ok/12 text-ok',
  failed: 'bg-bad/12 text-bad',
  running: 'bg-brand/12 text-brand',
  pending: 'bg-ink-700 text-txt-mid',
  skipped: 'bg-ink-700 text-txt-low',
  canceled: 'bg-ink-700 text-txt-mid',
}

/**
 * CI/CD 单次流水线执行详情页组件
 */
export default function PipelineDetailPage({ nav, id }: PageProps) {
  useStore()
  // 当前在控制台日志面板中选中的 Job ID
  const [selId, setSelId] = useState<string | null>(null)
  // 重新运行操作中的 Loading 状态
  const [isRerunning, setIsRerunning] = useState(false)

  // 远端流水线查询 Hook（根据当前流水线 UUID 查询）
  const { data: remotePipeline, isLoading: isRemoteLoading, refetch } = usePipeline(id)
  const mockPipeline = id ? pipelineById(id) : undefined

  // 判定是否已连接真实后端
  const isLive = !!remotePipeline
  // 数据格式统一：优先使用后端返回数据，无后端时优雅回退至本地 Store 中的 Mock 记录
  const p = remotePipeline
    ? {
        id: remotePipeline.id,
        title: remotePipeline.title,
        repoName: remotePipeline.repoName,
        branch: remotePipeline.branch,
        commitShort: remotePipeline.commitShort ?? (remotePipeline.commitSha ? remotePipeline.commitSha.slice(0, 7) : '-'),
        triggerUserId: remotePipeline.triggerUserId || 'u-eng-lead',
        durationSec: remotePipeline.durationSec ?? 0,
        startedAt: remotePipeline.startedAt ? remotePipeline.startedAt.slice(0, 19).replace('T', ' ') : '-',
        status: remotePipeline.status as RunStatus,
        stages: remotePipeline.stages.map((s) => ({
          name: s.name,
          jobs: s.jobs.map((j) => ({
            id: j.id,
            name: j.name,
            status: j.status as RunStatus,
            durationSec: j.durationSec,
            log: j.logs || [],
          })),
        })),
      }
    : mockPipeline
    ? {
        id: mockPipeline.id,
        title: mockPipeline.title,
        repoName: repoById(mockPipeline.repoId)?.name ?? mockPipeline.repoId,
        branch: mockPipeline.branch,
        commitShort: mockPipeline.commitShort,
        triggerUserId: mockPipeline.triggerUserId,
        durationSec: mockPipeline.durationSec,
        startedAt: mockPipeline.startedAt,
        status: mockPipeline.status,
        stages: mockPipeline.stages,
      }
    : undefined

  // 流水线未查到且加载完成状态
  if (!p && !isRemoteLoading) {
    return (
      <div>
        <button type="button" onClick={() => nav.go('pipelines')} className="mb-4 flex cursor-pointer items-center gap-1 text-xs text-txt-low hover:text-brand">
          <ArrowLeft size={13} /> 返回流水线列表
        </button>
        <Empty text="未找到该流水线" />
      </div>
    )
  }

  // 加载中过渡态
  if (!p) {
    return (
      <div className="py-12 text-center text-xs text-txt-low flex items-center justify-center gap-2">
        <Loader2 size={16} className="animate-spin text-brand" />加载流水线详情...
      </div>
    )
  }

  const triggerUser = userById(p.triggerUserId)
  const allJobs = p.stages.flatMap((s) => s.jobs)
  // 智能默认选中策略：优先高亮出错的 Job，其次高亮正在运行的 Job，否则默认选中首个 Job
  const autoJob =
    allJobs.find((j) => j.status === 'failed') ?? allJobs.find((j) => j.status === 'running') ?? allJobs[0]
  const sel = allJobs.find((j) => j.id === selId) ?? autoJob
  const isRunning = p.status === 'running' || isRerunning

  /**
   * 触发重新执行流水线操作（Rerun）
   */
  async function handleRun() {
    if (!id) return
    if (isLive) {
      setIsRerunning(true)
      try {
        await pipelinesApi.rerun(id)
        await refetch()
      } finally {
        setIsRerunning(false)
      }
    } else {
      runPipeline(id)
    }
  }

  return (
    <div>
      {/* 头部 */}
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <button type="button" onClick={() => nav.go('pipelines')} className="mb-1.5 flex cursor-pointer items-center gap-1 text-xs text-txt-low hover:text-brand">
            <ArrowLeft size={13} /> 返回流水线列表
          </button>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-xl font-bold text-txt-hi">{p.title}</h1>
            {isLive && (
              <Badge tone="brand">
                <Sparkles size={12} className="mr-0.5" />自研流水线
              </Badge>
            )}
            <span className={`inline-flex items-center gap-2 rounded-full px-3 py-1 text-sm font-semibold ${bigStatusStyle[p.status]}`}>
              <StatusDot status={p.status} />
              {runStatusText[p.status] ?? p.status}
              {isRunning && <span className="tabular-nums">…</span>}
            </span>
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-txt-mid">
            <span className="font-mono text-txt-low">{p.repoName}</span>
            <span className="inline-flex items-center gap-1 font-mono"><GitBranch size={11} className="text-txt-low" />{p.branch}</span>
            <span className="inline-flex items-center gap-1 font-mono"><GitCommitHorizontal size={11} className="text-txt-low" />{p.commitShort}</span>
            <span className="inline-flex items-center gap-1.5">
              <Avatar userId={p.triggerUserId} size={18} />
              {triggerUser?.name ?? '自动/系统'} 触发
            </span>
            <span className="inline-flex items-center gap-1 tabular-nums"><Timer size={11} className="text-txt-low" />{fmtDur(p.durationSec)}</span>
            <span className="inline-flex items-center gap-1 tabular-nums"><CalendarClock size={11} className="text-txt-low" />{p.startedAt}</span>
          </div>
        </div>
        <Btn variant="primary" disabled={isRunning} onClick={handleRun}>
          {isRunning ? (
            <>
              <span className="h-2 w-2 animate-pulse rounded-full bg-ink-950" />
              运行中…
            </>
          ) : (
            <>
              <Play size={14} />
              重新运行流水线
            </>
          )}
        </Btn>
      </div>


      {/* 阶段轨道 */}
      <Card className="mb-4">
        <CardHeader
          title="阶段轨道"
          extra={<span className="text-xs text-txt-low">点击 Job 查看日志</span>}
        />
        <div className="flex items-start overflow-x-auto p-4">
          {p.stages.map((s, i) => (
            <Fragment key={s.name}>
              {i > 0 && <div className="mt-3.5 h-px w-6 shrink-0 bg-line-hi" />}
              <StageColumn stage={s} selId={sel?.id ?? null} onSel={setSelId} />
            </Fragment>
          ))}
        </div>
      </Card>

      {/* 日志面板 */}
      {sel && (
        <Card>
          <CardHeader
            title={<span className="font-mono">{sel.name}</span>}
            extra={
              <span className="flex items-center gap-2 text-xs text-txt-low">
                <Badge tone={sel.status === 'passed' ? 'ok' : sel.status === 'failed' ? 'bad' : sel.status === 'running' ? 'brand' : 'neutral'}>
                  {runStatusText[sel.status]}
                </Badge>
                <span className="tabular-nums">{fmtDur(sel.durationSec)}</span>
              </span>
            }
          />
          <div className="max-h-72 overflow-y-auto rounded-b-lg bg-ink-950 p-4 font-mono text-xs leading-5">
            {sel.status === 'running' && (
              <div className="mb-1.5 flex animate-pulse items-center gap-2 font-medium text-brand">
                <span className="inline-block h-2 w-2 rounded-full bg-brand" />
                运行中…
              </div>
            )}
            {sel.log.map((line, i) => (
              <div
                key={i}
                className={line.startsWith('[ERROR]') ? 'text-bad' : line.startsWith('[WARN]') ? 'text-warn' : 'text-txt-mid'}
              >
                {line}
              </div>
            ))}
            {sel.log.length === 0 && (sel.status === 'pending' || isRunning) && <div className="text-txt-low">等待执行…</div>}
            {sel.log.length === 0 && sel.status === 'skipped' && <div className="text-txt-low">上游失败，已跳过</div>}
            {sel.log.length === 0 && sel.status === 'passed' && <div className="text-txt-low">（无日志输出）</div>}
          </div>
        </Card>
      )}
    </div>
  )
}

/** 阶段列：列头 + job 卡片 */
function StageColumn({ stage, selId, onSel }: { stage: Stage; selId: string | null; onSel: (id: string) => void }) {
  const st = stageStatus(stage.jobs)
  return (
    <div className="w-44 shrink-0">
      <div className="mb-2 flex items-center gap-2 border-b border-line pb-2">
        <StatusDot status={st} />
        <span className="truncate text-sm font-semibold text-txt-hi">{stage.name}</span>
        <span className="ml-auto shrink-0 text-[11px] text-txt-low">{runStatusText[st]}</span>
      </div>
      <div className="space-y-1.5">
        {stage.jobs.map((j) => (
          <button
            key={j.id}
            type="button"
            onClick={() => onSel(j.id)}
            className={`flex w-full cursor-pointer items-center gap-2 rounded-md border px-2.5 py-2 text-left transition-colors ${
              selId === j.id
                ? 'border-brand/60 bg-brand/12'
                : 'border-line bg-ink-850 hover:border-line-hi hover:bg-ink-800'
            }`}
          >
            <StatusDot status={j.status} />
            <span className="min-w-0 flex-1 truncate text-xs text-txt-hi">{j.name}</span>
            <span className="shrink-0 text-[11px] tabular-nums text-txt-low">
              {j.durationSec !== undefined ? fmtDur(j.durationSec) : j.status === 'running' ? '…' : '—'}
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}
