// 工作台 v3（M2-W4 P1-1 双数据源收敛）：全部数据来自 GET /api/v1/me/summary（TanStack Query）
// 红线：本页不再渲染 store 业务数据；问候语按浏览器本地时段
// D-91 收口：新增「最近动态」真实数据卡——GET /notifications 最近 5 条（unread=false 含已读）
// + 我负责工作项最近更新 5 条（GET /work-items 按 assignee 过滤、updatedAt 倒序），合并按时间排序；
// 原 store.activities 原型动态流已删除（store.ts 死代码清理同批）。
import { ArrowRight, AtSign, Bell, CircleDot, Hash, Package, PackageCheck, Target, Workflow } from 'lucide-react'
import { useMeSummary, useRecentNotifications, useWorkItems } from '../../api/queries'
import { Bar, Card, CardHeader, Empty, PageHeader, Pill, Spinner } from '../../components/ui'
import { ApiError } from '../../api/client'
import { useAuth } from '../../api/AuthContext'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, type ReactNode } from 'react'
import type { Nav } from '../../nav'

/** 问候时段（浏览器本地时间）：5-11 早上好 / 11-14 中午好 / 14-18 下午好 / 其余 晚上好 */
function greeting(): string {
  const h = new Date().getHours()
  if (h >= 5 && h < 11) return '早上好'
  if (h >= 11 && h < 14) return '中午好'
  if (h >= 14 && h < 18) return '下午好'
  return '晚上好'
}

const TODO_TONE: Record<string, 'bad' | 'warn' | 'neutral'> = { P0: 'bad', P1: 'warn', P2: 'neutral', P3: 'neutral' }
const STATUS_TONE: Record<string, 'info' | 'warn' | 'ok' | 'bad' | 'neutral'> = {
  新建: 'neutral', 修复中: 'info', 已修复: 'warn', 回归通过: 'ok', 已关闭: 'neutral', 重新打开: 'bad',
  todo: 'neutral', in_progress: 'info', in_review: 'warn', blocked: 'bad', done: 'ok',
  draft: 'neutral', pending_review: 'warn', accepted: 'info', in_dev: 'info', delivered: 'ok', closed: 'neutral',
  pending: 'neutral', passed: 'ok', failed: 'bad',
}

/** 「最近动态」行视图模型：通知 / 工作项更新统一为一个可点击行 */
interface FeedRow {
  key: string
  at: number
  timeText: string
  icon: ReactNode
  title: ReactNode
  trailing?: ReactNode
  unread?: boolean
  go: () => void
}

/** ISO 时间 → MM-DD HH:mm（非法值原样返回） */
function fmtTime(iso: string | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 通知 → 动态行（payload 渲染事实全随行：requirement.review/release.gate/im.mention 三类已知） */
function notifRow(n: import('../../api/queries').RemoteNotification, nav: Nav): FeedRow {
  const at = Date.parse(n.createdAt)
  const base = { key: `n-${n.id}`, at, timeText: fmtTime(n.createdAt), unread: !n.readAt }
  const p = (n.payload ?? {}) as Record<string, string | undefined>
  if (n.kind === 'requirement.review') {
    return {
      ...base,
      icon: <Bell size={15} className="shrink-0 text-cat-purple" />,
      title: <>评审邀请：<span className="mr-1 font-mono text-txt-mid">{p.key}</span>{p.title}</>,
      go: () => nav.go('requirements', p.requirementId),
    }
  }
  if (n.kind === 'release.gate') {
    return {
      ...base,
      icon: <PackageCheck size={15} className="shrink-0 text-ok" />,
      title: '版本发布门禁解除（阻塞缺陷已清零）',
      go: () => nav.go('delivery', p.releaseId),
    }
  }
  if (n.kind === 'im.mention') {
    return {
      ...base,
      icon: <AtSign size={15} className="shrink-0 text-brand" />,
      title: <>{p.senderName || '同事'} 在话题中提到了你：{p.preview}</>,
      go: () => nav.go('im', p.conversationId),
    }
  }
  return {
    ...base,
    icon: <Bell size={15} className="shrink-0 text-txt-mid" />,
    title: `系统通知（${n.kind}）`,
    go: () => undefined,
  }
}

export default function DashboardPage({ nav }: { nav: Nav }) {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  // 每次进入工作台强制重查一次（路由无卸载失效场景，保证待办/迭代/最近动态口径新鲜）
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: ['me', 'summary'] })
    void queryClient.invalidateQueries({ queryKey: ['notifications', 'recent'] })
    void queryClient.invalidateQueries({ queryKey: ['work-items', 'all'] })
  }, [queryClient])
  const { data: s, isLoading, isError, error } = useMeSummary()
  // 最近动态真实数据源：通知最近 5 条 + 我负责工作项最近更新 5 条（合并后按时间倒序）
  const { data: recentNotifs } = useRecentNotifications(5)
  const { data: allWorkItems } = useWorkItems()
  const feedRows: FeedRow[] = [
    ...(recentNotifs?.items ?? []).slice(0, 5).map((n) => notifRow(n, nav)),
    ...(allWorkItems ?? [])
      .filter((w) => !!user?.id && w.assigneeId === user.id)
      .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
      .slice(0, 5)
      .map((w): FeedRow => ({
        key: `w-${w.id}`,
        at: Date.parse(w.updatedAt),
        timeText: fmtTime(w.updatedAt),
        icon: <CircleDot size={15} className={`shrink-0 ${w.type === 'defect' ? 'text-cat-orange' : w.type === 'test_task' ? 'text-cat-teal' : 'text-cat-blue'}`} />,
        title: <><span className="mr-1.5 font-mono text-txt-mid">{w.key}</span>{w.title}</>,
        trailing: <Pill tone={STATUS_TONE[w.status] ?? 'neutral'}>{w.status}</Pill>,
        go: () => nav.go(w.type === 'defect' ? 'defects' : 'tasks', w.id),
      })),
  ].sort((a, b) => (isNaN(b.at) ? 0 : b.at) - (isNaN(a.at) ? 0 : a.at))

  if (isLoading) {
    return <Card><Empty text="工作台数据加载中…" icon={<Spinner />} /></Card>
  }
  if (isError || !s) {
    const reason = error instanceof ApiError ? `${error.code} ${error.message}` : '网络异常'
    return (
      <div>
        <PageHeader title={greeting()} desc={new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })} />
        <Card><Empty text={`工作台聚合暂不可用（${reason}），请稍后刷新重试`} /></Card>
      </div>
    )
  }

  const sprint = s.activeSprint
  const gate = s.releaseGate
  const today = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
  const sprintLeft = sprint?.dueDate ? Math.max(0, Math.ceil((new Date(sprint.dueDate).getTime() - Date.now()) / 86400000)) : undefined

  return (
    <div>
      <PageHeader
        title={`${greeting()}，${s.greetingName || '同事'}`}
        desc={`${today} · ${sprint ? `当前迭代「${sprint.name}」${sprintLeft != null ? `· 剩余 ${sprintLeft} 天` : ''}` : '当前无活跃迭代'}`}
      />

      {/* 指标卡 */}
      <div className="mb-6 grid grid-cols-2 gap-4 xl:grid-cols-4">
        <Card className="p-4">
          <div className="flex items-center justify-between text-txt-mid"><span className="text-sm">战略目标进度</span><Target size={15} /></div>
          <div className="mt-3 space-y-2.5">
            {s.goals.length === 0 && <div className="py-2 text-xs text-txt-low">暂无战略目标</div>}
            {s.goals.map((g) => (
              <button key={g.id} type="button" onClick={() => nav.go('goals', g.id)} className="flex w-full cursor-pointer items-center gap-2 text-left">
                {/* PM-1：strategic_goal 无 key 列（me/summary 的 key=id 前 8 位），不再当编号拼在标题前；
                    卡标题优先完整目标名，超长由 truncate 截断（title 悬浮看全名） */}
                <span className="min-w-0 flex-1 truncate text-xs text-txt-mid hover:text-brand" title={g.name}>{g.name}</span>
                <Bar value={g.progress} tone={g.status === 'at_risk' ? 'warn' : 'brand'} className="w-16" />
                <span className="w-8 text-right text-xs font-bold tabular-nums text-txt-hi">{g.progress}%</span>
              </button>
            ))}
          </div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between text-txt-mid"><span className="text-sm">我的红色冲突</span><AlertIcon /></div>
          <div className="mt-2 text-2xl font-bold tabular-nums text-bad">{s.myRedConflicts}</div>
          <div className="mt-1 truncate text-xs text-txt-low">{s.myRedConflicts > 0 ? '存在未消解的红色冲突' : '暂无红色冲突'}</div>
          <button type="button" onClick={() => nav.go('conflicts')} className="mt-2 flex cursor-pointer items-center gap-1 text-xs text-brand hover:underline">冲突中心 <ArrowRight size={11} /></button>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between text-txt-mid"><span className="text-sm">迭代容量 · {sprint?.name ?? '—'}</span><Workflow size={15} /></div>
          <div className="mt-2 text-2xl font-bold tabular-nums text-txt-hi">{sprint ? Math.round(sprint.progress) : 0}%</div>
          <Bar value={sprint?.progress ?? 0} tone={(sprint?.allocatedHours ?? 0) > (sprint?.capacityHours ?? 0) ? 'bad' : 'brand'} className="mt-3" />
          <div className="mt-2 text-xs text-txt-low">已分配 {Math.round(sprint?.allocatedHours ?? 0)}h / 容量 {sprint?.capacityHours ?? 0}h</div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center justify-between text-txt-mid"><span className="text-sm">发布门禁</span><Package size={15} /></div>
          {gate ? (
            <>
              <div className="mt-2 flex items-center gap-2">
                {gate.blocked ? <Pill tone="bad">已阻塞</Pill> : <Pill tone="ok">畅通</Pill>}
                <span className="truncate font-mono text-sm font-bold text-txt-hi">{gate.key}</span>
              </div>
              <div className="mt-1.5 text-xs text-txt-low">
                {gate.blockingCount > 0 ? `未关闭阻塞缺陷 ${gate.blockingCount} 个 · ` : ''}计划 {gate.planDate?.slice(5) ?? '—'}
              </div>
            </>
          ) : (
            <div className="mt-2"><Pill tone="ok">暂无待发布版本</Pill></div>
          )}
          <button type="button" onClick={() => nav.go('delivery', gate?.id)} className="mt-2 flex cursor-pointer items-center gap-1 text-xs text-brand hover:underline">版本与发布 <ArrowRight size={11} /></button>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title="我的待办" extra={<span className="text-xs text-txt-low">指派给我 · 未完结 · 截止近的在前</span>} />
          <ul className="divide-y divide-line">
            {s.myTodos.map((t) => (
              <li key={t.id}>
                <button type="button" onClick={() => nav.go(t.type === 'defect' ? 'defects' : 'tasks', t.id)} className="flex w-full cursor-pointer items-center gap-3 px-4 py-2.5 text-left hover:bg-ink-700">
                  <CircleDot size={15} className={`shrink-0 ${t.type === 'defect' ? 'text-cat-orange' : t.type === 'test_task' ? 'text-cat-teal' : 'text-cat-blue'}`} />
                  <span className="min-w-0 flex-1 truncate text-sm text-txt-hi"><span className="mr-1.5 font-mono text-txt-mid">{t.key}</span>{t.title}</span>
                  {t.dueDate && <span className="shrink-0 text-xs tabular-nums text-txt-low">截止 {t.dueDate.slice(5)}</span>}
                  {t.priority && <Pill tone={TODO_TONE[t.priority] ?? 'neutral'}>{t.priority}</Pill>}
                  <Pill tone={STATUS_TONE[t.status] ?? 'neutral'}>{t.status}</Pill>
                </button>
              </li>
            ))}
            {s.myTodos.length === 0 && <li className="px-4 py-8 text-center text-sm text-txt-low">全部处理完毕 🎉</li>}
          </ul>
        </Card>

        <Card>
          <CardHeader title="我参与的话题" extra={<Pill tone="purple">{s.myTopics.length} 个进行中</Pill>} />
          <ul className="divide-y divide-line">
            {s.myTopics.map((t) => (
              <li key={t.id}>
                <button type="button" onClick={() => nav.go('im', t.id)} className="w-full cursor-pointer px-4 py-2.5 text-left hover:bg-ink-700">
                  <div className="flex items-center gap-2">
                    <Hash size={13} className="shrink-0 text-brand" />
                    <span className="min-w-0 flex-1 truncate text-sm font-medium text-txt-hi">{t.title ?? `会话 ${t.id.slice(0, 4)}`}</span>
                  </div>
                  {t.lastActivity && <div className="mt-1 text-xs text-txt-low">最近活跃 {t.lastActivity.slice(0, 16).replace('T', ' ')}</div>}
                </button>
              </li>
            ))}
            {s.myTopics.length === 0 && <li className="px-4 py-8"><Empty text="暂无进行中的话题" size="sm" /></li>}
          </ul>
        </Card>
      </div>

      {/* 最近动态（D-91 收口）：真实数据源 = 通知（GET /notifications 最近5，含已读）+ 我负责工作项最近更新（5）
          合并按时间倒序；点击行跳转对应对象。原 store.activities 原型数据已删除，本卡为唯一动态流。 */}
      <Card className="mt-4">
        <CardHeader title="最近动态" extra={<span className="text-xs text-txt-low">通知 + 我负责的工作项 · 按时间倒序</span>} />
        <ul className="divide-y divide-line">
          {feedRows.map((a) => (
            <li key={a.key}>
              <button type="button" onClick={a.go} className="flex w-full cursor-pointer items-center gap-3 px-4 py-2.5 text-left hover:bg-ink-700">
                {a.icon}
                <span className="min-w-0 flex-1 truncate text-sm text-txt-hi">{a.title}</span>
                {a.trailing}
                {a.unread && <Pill tone="brand">新</Pill>}
                <span className="shrink-0 text-xs tabular-nums text-txt-low">{a.timeText}</span>
              </button>
            </li>
          ))}
          {feedRows.length === 0 && <li className="px-4 py-8 text-center text-sm text-txt-low">暂无动态</li>}
        </ul>
      </Card>
    </div>
  )
}

function AlertIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="text-bad"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 20h16a2 2 0 0 0 1.73-2" /><path d="M12 9v4" /><path d="M12 17h.01" /></svg>
}
