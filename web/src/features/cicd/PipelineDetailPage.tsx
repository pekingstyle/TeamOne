// 流水线详情：阶段轨道 + Job 卡片 + 日志面板
import { Fragment, useState } from 'react'
import { ArrowLeft, CalendarClock, GitBranch, GitCommitHorizontal, Play, Timer } from 'lucide-react'
import type { Job, RunStatus, Stage } from '../../data/types'
import { pipelineById, repoById, runPipeline, useStore, userById } from '../../data/store'
import { Avatar, Badge, Btn, Card, CardHeader, Empty, StatusDot, runStatusText } from '../../components/ui'
import type { PageProps } from '../../nav'

function fmtDur(sec: number | undefined): string {
  if (sec === undefined) return '—'
  if (sec < 60) return `${sec}秒`
  return `${Math.floor(sec / 60)}分${String(sec % 60).padStart(2, '0')}秒`
}

/** 由 jobs 推导阶段状态 */
function stageStatus(jobs: Job[]): RunStatus {
  if (jobs.some((j) => j.status === 'failed')) return 'failed'
  if (jobs.some((j) => j.status === 'running')) return 'running'
  if (jobs.length > 0 && jobs.every((j) => j.status === 'passed')) return 'passed'
  if (jobs.length > 0 && jobs.every((j) => j.status === 'skipped' || j.status === 'canceled')) return 'skipped'
  return 'pending'
}

const bigStatusStyle: Record<RunStatus, string> = {
  passed: 'bg-ok/12 text-ok',
  failed: 'bg-bad/12 text-bad',
  running: 'bg-brand/12 text-brand',
  pending: 'bg-ink-700 text-txt-mid',
  skipped: 'bg-ink-700 text-txt-low',
  canceled: 'bg-ink-700 text-txt-mid',
}

export default function PipelineDetailPage({ nav, id }: PageProps) {
  useStore()
  const [selId, setSelId] = useState<string | null>(null)
  const p = id ? pipelineById(id) : undefined

  if (!p) {
    return (
      <div>
        <button type="button" onClick={() => nav.go('pipelines')} className="mb-4 flex cursor-pointer items-center gap-1 text-xs text-txt-low hover:text-brand">
          <ArrowLeft size={13} /> 返回流水线列表
        </button>
        <Empty text="未找到该流水线" />
      </div>
    )
  }

  const repo = repoById(p.repoId)
  const triggerUser = userById(p.triggerUserId)
  const allJobs = p.stages.flatMap((s) => s.jobs)
  const autoJob =
    allJobs.find((j) => j.status === 'failed') ?? allJobs.find((j) => j.status === 'running') ?? allJobs[0]
  const sel = allJobs.find((j) => j.id === selId) ?? autoJob
  const isRunning = p.status === 'running'

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
            <span className={`inline-flex items-center gap-2 rounded-full px-3 py-1 text-sm font-semibold ${bigStatusStyle[p.status]}`}>
              <StatusDot status={p.status} />
              {runStatusText[p.status]}
              {isRunning && <span className="tabular-nums">…</span>}
            </span>
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-txt-mid">
            <span className="font-mono text-txt-low">{repo?.name ?? p.repoId}</span>
            <span className="inline-flex items-center gap-1 font-mono"><GitBranch size={11} className="text-txt-low" />{p.branch}</span>
            <span className="inline-flex items-center gap-1 font-mono"><GitCommitHorizontal size={11} className="text-txt-low" />{p.commitShort}</span>
            <span className="inline-flex items-center gap-1.5">
              <Avatar userId={p.triggerUserId} size={18} />
              {triggerUser?.name ?? '未知'} 触发
            </span>
            <span className="inline-flex items-center gap-1 tabular-nums"><Timer size={11} className="text-txt-low" />{fmtDur(p.durationSec)}</span>
            <span className="inline-flex items-center gap-1 tabular-nums"><CalendarClock size={11} className="text-txt-low" />{p.startedAt}</span>
          </div>
        </div>
        <Btn variant="primary" disabled={isRunning} onClick={() => runPipeline(p.id)}>
          {isRunning ? (
            <>
              <span className="h-2 w-2 animate-pulse rounded-full bg-ink-950" />
              运行中…
            </>
          ) : (
            <>
              <Play size={14} />
              运行流水线
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
