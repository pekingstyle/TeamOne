// CI/CD 流水线列表：状态过滤 + 运行统计（对接自研流水线后端接口与离线降级兼容）
import { useState } from 'react'
import { GitBranch, GitCommitHorizontal, Loader2, Sparkles } from 'lucide-react'
import type { PipelineTrigger, RunStatus } from '../../data/types'
import { pipelines, repoById, useStore } from '../../data/store'
import { usePipelines } from '../../api/queries'
import { Avatar, Badge, Card, Empty, PageHeader, StatusDot, runStatusText } from '../../components/ui'
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
}

/**
 * CI/CD 流水线全量列表展示与状态过滤视图
 */
export default function PipelinesPage({ nav }: PageProps) {
  useStore()
  // 当前选中的状态过滤器（all / passed / failed / running）
  const [filter, setFilter] = useState<FilterKey>('all')

  // 远端流水线数据查询（通过 React Query 自动缓存与刷新）
  const { data: remoteData, isLoading } = usePipelines()
  const remotePipelines = remoteData?.items
  const isLive = !!remotePipelines && remotePipelines.length > 0

  // 数据源归一化：若后端自研内核有数据则优先渲染后端数据，否则优雅降级至本地内置 Mock 数据
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
          status: p.status,
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

  return (
    <div>
      <PageHeader title="CI/CD 流水线" desc="构建 · 测试 · 质量扫描 · 制品 · 部署 一条龙执行记录" />

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
              <li key={p.id}>
                <button
                  type="button"
                  onClick={() => nav.go('pipeline', p.id)}
                  className="flex w-full cursor-pointer items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-ink-800"
                >
                  <StatusDot status={p.status as any} size={10} />
                  <span className={`w-11 shrink-0 text-xs font-medium ${statusColor[p.status] ?? 'text-txt-mid'}`}>
                    {runStatusText[p.status as RunStatus] ?? p.status}
                  </span>
                  <div className="w-60 min-w-0 shrink-0">
                    <div className="truncate text-sm font-medium text-txt-hi">
                      <span className="font-mono">{p.num}</span>
                      {p.commitMsg && <span> · {p.commitMsg}</span>}
                    </div>
                    <div className="mt-0.5 truncate font-mono text-xs text-txt-low">{p.repoName}</div>
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
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}

