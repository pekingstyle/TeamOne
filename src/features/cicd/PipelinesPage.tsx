// CI/CD 流水线列表：状态过滤 + 运行统计
import { useState } from 'react'
import { GitBranch, GitCommitHorizontal } from 'lucide-react'
import type { PipelineTrigger } from '../../data/types'
import { pipelines, repoById, useStore } from '../../data/store'
import { Avatar, Badge, Card, Empty, PageHeader, StatusDot, runStatusText } from '../../components/ui'
import type { PageProps } from '../../nav'

/** 秒数格式化为 x分x秒 */
function fmtDur(sec: number | undefined): string {
  if (sec === undefined) return '—'
  if (sec < 60) return `${sec}秒`
  return `${Math.floor(sec / 60)}分${String(sec % 60).padStart(2, '0')}秒`
}

const triggerText: Record<PipelineTrigger, string> = { push: '推送', manual: '手动', schedule: '定时', mr: 'MR 触发' }
const triggerTone: Record<PipelineTrigger, 'brand' | 'vio' | 'warn' | 'neutral'> = { push: 'brand', manual: 'vio', schedule: 'warn', mr: 'neutral' }
const statusColor: Record<string, string> = { passed: 'text-ok', failed: 'text-bad', running: 'text-brand' }

type FilterKey = 'all' | 'passed' | 'failed' | 'running'

export default function PipelinesPage({ nav }: PageProps) {
  useStore()
  const [filter, setFilter] = useState<FilterKey>('all')

  const total = pipelines.length
  const passedCount = pipelines.filter((p) => p.status === 'passed').length
  const failedCount = pipelines.filter((p) => p.status === 'failed').length
  const runningCount = pipelines.filter((p) => p.status === 'running').length
  const successRate = total > 0 ? Math.round((passedCount / total) * 100) : 0
  const withDur = pipelines.filter((p) => p.durationSec > 0)
  const avgDur = withDur.length > 0 ? Math.round(withDur.reduce((s, p) => s + p.durationSec, 0) / withDur.length) : 0

  const tabs: { key: FilterKey; label: string; count: number }[] = [
    { key: 'all', label: '全部', count: total },
    { key: 'passed', label: '成功', count: passedCount },
    { key: 'failed', label: '失败', count: failedCount },
    { key: 'running', label: '运行中', count: runningCount },
  ]
  const list = pipelines.filter((p) => filter === 'all' || p.status === filter)

  return (
    <div>
      <PageHeader title="CI/CD 流水线" desc="构建 · 测试 · 质量扫描 · 制品 · 部署 一条龙执行记录" />

      {/* 统计 chips */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge tone="neutral">总运行 <span className="tabular-nums">{total}</span></Badge>
        <Badge tone="ok">成功率 <span className="tabular-nums">{successRate}%</span></Badge>
        <Badge tone="brand">平均耗时 <span className="tabular-nums">{fmtDur(avgDur)}</span></Badge>
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
            {list.map((p) => {
              const repo = repoById(p.repoId)
              const [num, ...rest] = p.title.split('·')
              return (
                <li key={p.id}>
                  <button
                    type="button"
                    onClick={() => nav.go('pipeline', p.id)}
                    className="flex w-full cursor-pointer items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-ink-800"
                  >
                    <StatusDot status={p.status} size={10} />
                    <span className={`w-11 shrink-0 text-xs font-medium ${statusColor[p.status] ?? 'text-txt-mid'}`}>
                      {runStatusText[p.status]}
                    </span>
                    <div className="w-60 min-w-0 shrink-0">
                      <div className="truncate text-sm font-medium text-txt-hi">
                        <span className="font-mono">{num.trim()}</span>
                        {rest.length > 0 && <span> · {rest.join('·').trim()}</span>}
                      </div>
                      <div className="mt-0.5 truncate font-mono text-xs text-txt-low">{repo?.name ?? p.repoId}</div>
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
                    </div>
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </Card>
    </div>
  )
}
