// 冲突中心（R3 核心 · 真实 API（M2-INC-1 W2））：冲突快照列表（CF-1~6 过滤、红优先）+ 负载热力图 + 管理员重算
// 数据源：GET /api/v1/conflicts（V7 conflict_snapshot 快照只读，INC-1 红线②不实时计算）、
//        GET /api/v1/conflicts/heatmap（人员×日负载小时原料，红线②无判决字段）、
//        POST /api/v1/conflicts/recompute（admin）。store.ts 零改动（双数据源纪律）。
import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, CalendarClock, RefreshCw, X } from 'lucide-react'
import type { PageProps } from '../../nav'
import { conflictsApi, useConflicts, useHeatmap } from '../../api/queries'
import { api } from '../../api/client'
import { useAuth } from '../../api/AuthContext'
import { useUserBriefs, toBrief } from '../../api/users'
import { GlossaryButton } from '../../components/GlossaryButton'
import { Btn, Card, CardHeader, Empty, PageHeader, Pill, Spinner } from '../../components/ui'

type Kind = 'CF-1' | 'CF-2' | 'CF-3' | 'CF-4' | 'CF-5' | 'CF-6'

const KINDS: { kind: Kind; label: string }[] = [
  { kind: 'CF-1', label: '人员超载' },
  { kind: 'CF-2', label: '时间区间重叠' },
  { kind: 'CF-3', label: '里程碑挤压' },
  { kind: 'CF-4', label: '跨项目争用' },
  { kind: 'CF-5', label: '依赖倒挂' },
  { kind: 'CF-6', label: 'Deadline越级' },
]
const kindLabel = (k: string) => KINDS.find((x) => x.kind === k)?.label ?? k

/** 每类冲突的「人话」解释与建议动作（用户视角：这是什么意思 / 我该做什么） */
const KIND_GUIDE: Record<Kind, { meaning: string; action: string }> = {
  'CF-1': { meaning: '这位成员某一天被排的工时超过了其每日可用容量，忙不过来', action: '把当天部分任务顺延或转派给他人' },
  'CF-2': { meaning: '同一人名下两个未完成任务的时间区间互相撞车（分属不同迭代，或同时进行中）', action: '错开两个任务的区间，或先集中完成其中一件' },
  'CF-3': { meaning: '同产品相邻两个版本：上一版的发布日与下一版的代码冻结日间隔不足 7 天', action: '评估合并这两个版本，或顺延下一版的冻结日期' },
  'CF-4': { meaning: '超载当天该成员的任务横跨多个产品线，多头作战效率低', action: '按产品优先级砍掉低优任务，当天集中投入一条线' },
  'CF-5': { meaning: '被阻塞任务的截止日早于阻塞它的任务——顺序反了，被阻塞的任务根本没法开工', action: '顺延被阻塞任务的截止日，或优先赶完阻塞任务' },
  'CF-6': { meaning: '任务截止日超出了所属迭代（或版本）的截止日，注定延期', action: '把任务移出当前迭代，或与负责人确认整体延期' },
}

/** 热力着色基准（纯前端显示口径：默认容量 8h/日；服务端热力图不含判决字段） */
const REF_HOURS = 8

function heatColor(hours: number): string {
  if (hours <= 0) return 'var(--color-ink-700)'
  const ratio = hours / REF_HOURS
  if (ratio > 1) return 'var(--color-bad)'
  if (ratio >= 0.85) return 'var(--color-warn)'
  return 'var(--color-ok)'
}

/** 可点击主体 / 关联任务小卡 */
function Chip({ children, onClick, active }: { children: ReactNode; onClick?: () => void; active?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`inline-flex max-w-[260px] cursor-pointer items-center gap-1 truncate rounded border px-1.5 py-0.5 text-xs transition-colors ${
        active
          ? 'border-brand bg-brand-bg font-semibold text-brand-deep'
          : 'border-line bg-canvas text-txt-mid hover:border-brand hover:text-brand'
      }`}
    >
      {children}
    </button>
  )
}

const shortId = (id: string) => id.slice(0, 8)

export default function ConflictsPage({ nav }: PageProps) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [kindFilter, setKindFilter] = useState<Kind | '全部'>('全部')
  const [selUser, setSelUser] = useState<string | null>(null)

  const conflicts = useConflicts(kindFilter === '全部' ? undefined : kindFilter)
  const heatmap = useHeatmap(90)

  // 人话化：UUID → 姓名 / 任务键+标题（用户视角不含任何裸 ID）
  // UT-28：走 /users/briefs（仅需登录态）——/users 需 platform:user:list，普通用户 403 会导致姓名映射为空
  const userMap = toBrief(useUserBriefs().data)
  const nameOf = (id: string) => userMap.get(id)?.name ?? `${shortId(id)}…`
  const humanize = (text: string) => {
    let out = text
    for (const [id, brief] of userMap) out = out.split(id).join(brief.name)
    return out
  }
  const workItems = useQuery({
    queryKey: ['work-items', 'all'],
    queryFn: () => api<{ items: { id: string; key: string; title: string }[] }>('/api/v1/work-items?page=1&size=200'),
    staleTime: 60_000,
  })
  const taskLabel = (id: string) => {
    const it = workItems.data?.items.find((w) => w.id === id)
    return it ? `${it.key} ${it.title}` : `任务 ${shortId(id)}…`
  }

  const recompute = useMutation({
    mutationFn: () => conflictsApi.recompute(),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['conflicts'] })
      void queryClient.invalidateQueries({ queryKey: ['work-items'] })
    },
  })

  const rows = useMemo(() => {
    const list = [...(conflicts.data ?? [])]
    list.sort((a, b) => {
      if (a.payload.severity !== b.payload.severity) return a.payload.severity === 'red' ? -1 : 1
      return a.kind < b.kind ? -1 : 1
    })
    return selUser ? list.filter((c) => c.payload.userId === selUser) : list
  }, [conflicts.data, selUser])

  const redCount = (conflicts.data ?? []).filter((c) => c.payload.severity === 'red').length
  const isAdmin = user?.platformRole === 'OWNER' || user?.platformRole === 'ADMIN'

  const lastDetectedAt = conflicts.data?.[0]?.detectedAt

  /** cells → Map（50 人×90 格直查 O(1)，避免逐格 find 的 4500² 扫描） */
  const cellMap = useMemo(() => {
    const m = new Map<string, number>()
    for (const c of heatmap.data?.cells ?? []) m.set(`${c.userId}|${c.date}`, Number(c.hours))
    return m
  }, [heatmap.data])

  return (
    <div>
      <PageHeader
        title="冲突中心"
        desc="六级冲突快照（每日 02:00 全量 + 变更增量批算）· 负载热力图 · 红色冲突 5 分钟内推送当事人"
        actions={
          <div className="flex items-center gap-2">
            <GlossaryButton />
            {isAdmin && (
              <Btn
                onClick={() => recompute.mutate()}
                disabled={recompute.isPending}
              >
                <RefreshCw size={14} className={recompute.isPending ? 'animate-spin' : ''} />
                {recompute.isPending ? '重算中…' : '重算冲突'}
              </Btn>
            )}
          </div>
        }
      />

      {/* 说明条 */}
      <div className="mb-4 flex items-start gap-2.5 rounded-card border border-line bg-info-bg px-4 py-3">
        <AlertTriangle size={16} className="mt-0.5 shrink-0 text-info" />
        <p className="min-w-0 flex-1 text-xs leading-5 text-txt-mid">
          真实 API（M2）：CF-1 人员超载 · CF-2 区间重叠 · CF-3 里程碑挤压（&lt;7 天）· CF-4 跨产品争用 ·
          CF-5 依赖倒挂 · CF-6 Deadline 越级。
          <span className="font-semibold text-txt-hi">红色需立即处理，黄色为预警</span>
          ；红色冲突新增将站内通知并 WS 推送当事人。
        </p>
        <span className="shrink-0 text-xs tabular-nums text-txt-mid">
          检测于 {lastDetectedAt ? new Date(lastDetectedAt).toLocaleTimeString() : '--:--'}
        </span>
      </div>

      {recompute.data && (
        <div className="mb-4 rounded-card border border-line bg-ok-bg px-4 py-2.5 text-xs text-ok-deep">
          重算完成：命中 {recompute.data.total} 条（红 {recompute.data.red} / 黄 {recompute.data.yellow}），
          红色新增 {recompute.data.redNew} 条，已通知 {recompute.data.notifiedUsers} 名当事人。
        </div>
      )}
      {recompute.isError && (
        <div className="mb-4 rounded-card border border-bad/30 bg-bad-bg px-4 py-2.5 text-xs text-bad-deep">
          重算失败：{(recompute.error as Error).message}
        </div>
      )}

      {/* 上半区：人员负载热力图（原料矩阵） */}
      <Card className="mb-4">
        <CardHeader
          title="人员负载热力图 · 未来 90 天"
          extra={(
            <div className="flex items-center gap-3 text-xs text-txt-mid">
              <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-ok opacity-70" />有负载</span>
              <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-warn opacity-70" />≥85%</span>
              <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-bad opacity-90" />超 8h</span>
              <span className="text-txt-low">小时原料（非服务端判决）· 点击行筛选当事人</span>
            </div>
          )}
        />
        <div className="overflow-x-auto px-4 py-3">
          {heatmap.isLoading ? (
            <Empty text="热力图加载中…" size="sm" icon={<Spinner />} />
          ) : (heatmap.data?.users.length ?? 0) === 0 ? (
            <Empty text="暂无负载原料（无带工时的开放工作项）" />
          ) : (
            <div className="space-y-0.5" style={{ minWidth: 760 }}>
              <div className="flex items-center gap-1 pl-28 text-[10px] text-txt-low">
                {heatmap.data!.days.map((d, i) => (
                  <span key={d} className="w-2 shrink-0 text-center tabular-nums" title={d}>
                    {i % 7 === 0 ? d.slice(8) : ''}
                  </span>
                ))}
              </div>
              {heatmap.data!.users.map((u) => (
                <button
                  key={u.userId}
                  type="button"
                  onClick={() => { setSelUser(selUser === u.userId ? null : u.userId) }}
                  className={`flex w-full cursor-pointer items-center gap-1 rounded px-1 py-0.5 text-left hover:bg-ink-700 ${selUser === u.userId ? 'bg-brand-bg/60' : ''}`}
                >
                  <span className="w-28 shrink-0 truncate text-[10px] font-medium text-txt-mid" title={nameOf(u.userId)}>{nameOf(u.userId)}</span>
                  {heatmap.data!.days.map((d) => {
                    const h = cellMap.get(`${u.userId}|${d}`) ?? 0
                    return (
                      <span
                        key={d}
                        title={`${nameOf(u.userId)} ${d}：负载 ${h.toFixed(1)}h（容量 8h/日）`}
                        className="h-4 w-2 shrink-0 rounded-[2px]"
                        style={{ background: heatColor(h), opacity: h > 0 ? 0.85 : 1 }}
                      />
                    )
                  })}
                </button>
              ))}
            </div>
          )}
        </div>
      </Card>

      {/* 下半区：冲突快照列表 */}
      <Card>
        <CardHeader
          title={`冲突快照 · ${rows.length} 条${redCount > 0 ? ` · 红色 ${redCount}` : ''}`}
          extra={selUser ? (
            <button
              type="button"
              onClick={() => setSelUser(null)}
              className="inline-flex cursor-pointer items-center gap-1 rounded-full bg-bad-bg px-2 py-0.5 text-xs font-semibold text-bad-deep hover:opacity-80"
            >
              筛选：{shortId(selUser)}… <X size={11} />
            </button>
          ) : (
            <span className="text-xs text-txt-low">红色置顶 · detected_at 降序</span>
          )}
        />
        <div className="flex flex-wrap items-center gap-1.5 border-b border-line px-3 py-2">
          {(['全部', ...KINDS.map((k) => k.kind)] as (Kind | '全部')[]).map((k) => {
            const active = kindFilter === k
            return (
              <button
                key={k}
                type="button"
                onClick={() => setKindFilter(k)}
                className={`cursor-pointer rounded-full px-2.5 py-1 text-xs transition-colors ${
                  active ? 'bg-brand-bg font-semibold text-brand-deep' : 'text-txt-mid hover:bg-ink-700 hover:text-txt-hi'
                }`}
              >
                {k === '全部' ? '全部' : `${k} ${kindLabel(k)}`}
              </button>
            )
          })}
        </div>
        {conflicts.isLoading ? (
          <Empty text="快照加载中…" size="sm" icon={<Spinner />} />
        ) : rows.length === 0 ? (
          <Empty text="当前筛选下无冲突快照（可请管理员触发重算）" />
        ) : (
          <ul>
            {rows.map((c) => (
              <li key={`${c.payload.fp}|${c.detectedAt}`} className="flex items-start gap-2.5 border-b border-line px-4 py-2.5 last:border-b-0">
                <Pill tone={c.payload.severity === 'red' ? 'bad' : 'warn'}>
                  {c.payload.severity === 'red' ? '红' : '黄'}
                </Pill>
                <Pill tone={c.kind === 'CF-6' ? 'info' : c.kind === 'CF-3' ? 'warn' : 'purple'}>{c.kind} {kindLabel(c.kind)}</Pill>
                <div className="min-w-0 flex-1">
                  <div className="text-sm leading-5 text-txt-hi">{humanize(c.payload.detail)}</div>
                  <div className="mt-1 space-y-0.5 text-xs leading-4">
                    <div className="text-txt-mid"><span className="text-txt-low">含义：</span>{KIND_GUIDE[c.kind as Kind]?.meaning ?? '—'}</div>
                    <div className="text-brand-deep"><span className="text-txt-low">建议：</span>{KIND_GUIDE[c.kind as Kind]?.action ?? '—'}</div>
                    {/* UT-27：冲突时间窗口（缺省安全——旧快照无 window 则不渲染该行；单日 start===end 只显示一天） */}
                    {c.payload.window && c.payload.window.start && c.payload.window.end && (
                      <div className="text-txt-mid">
                        <span className="text-txt-low">时间窗口：</span>
                        <span className="tabular-nums">
                          {c.payload.window.start === c.payload.window.end
                            ? c.payload.window.start
                            : `${c.payload.window.start} ~ ${c.payload.window.end}`}
                        </span>
                      </div>
                    )}
                  </div>
                  {/* UT-27：参与冲突的事件列表（缺省安全；每个事件一行，样式与任务 chip 一致） */}
                  {(c.payload.events?.length ?? 0) > 0 && (
                    <div className="mt-1.5 space-y-1">
                      {c.payload.events!.map((ev) => (
                        <div key={ev.key} className="flex">
                          <span className="inline-flex max-w-[320px] items-center gap-1 rounded border border-line bg-canvas px-1.5 py-0.5 text-xs text-txt-mid">
                            <span className="shrink-0 font-mono font-semibold text-brand">{ev.key}</span>
                            <span className="min-w-0 truncate">{ev.title}</span>
                            {(ev.start || ev.due) && (
                              <span className="shrink-0 tabular-nums text-txt-low">
                                {ev.start?.slice(0, 10) ?? '—'} ~ {ev.due?.slice(0, 10) ?? '—'}
                              </span>
                            )}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    {c.payload.subjectType === 'user' && (
                      <Chip active={selUser === c.payload.subjectId}
                        onClick={() => setSelUser(selUser === c.payload.subjectId ? null : c.payload.subjectId)}>
                        当事人 {nameOf(c.payload.subjectId)}
                      </Chip>
                    )}
                    {c.payload.subjectType === 'release' && (
                      <Chip onClick={() => nav.go('delivery')}>
                        <CalendarClock size={11} /> 版本
                      </Chip>
                    )}
                    {c.payload.relatedTaskIds.map((tid) => (
                      <Chip key={tid} onClick={() => nav.go('tasks', tid)}>{taskLabel(tid)}</Chip>
                    ))}
                  </div>
                </div>
                <span className="shrink-0 text-xs tabular-nums text-txt-low">
                  {new Date(c.detectedAt).toLocaleString()}
                  {c.resolvedAt ? ' · 已消解' : ''}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}
