// 冲突中心（R3 核心）：人员负载热力图（未来 10 个工作日 × 全员）+ 六级冲突列表
// 算法依据 docs/v2/03-产品设计文档-v2.md §5.1（CF-1~CF-6）；热力图摊平逻辑与 computeConflicts 步骤 1 一致
import { Fragment, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { AlertTriangle, CalendarClock, X } from 'lucide-react'
import type { PageProps } from '../../nav'
import type { ConflictItem, ConflictType } from '../../data/types'
import {
  computeConflicts, dateStr, daysBetween, releaseById, userById, useStore,
  users, workItemById, workItems, workdays,
} from '../../data/store'
import { Avatar, Card, CardHeader, Empty, HeatCell, PageHeader, Pill } from '../../components/ui'

/** 未来 10 个工作日（剔除周末与节假日，与 store.workdays 同一套日历） */
const DAYS = workdays(dateStr(0), dateStr(30)).slice(0, 10)

const TYPES: ConflictType[] = ['人员超载', '时间区间重叠', '里程碑挤压', '跨项目争用', '依赖倒挂', 'Deadline越级']

const typeTone: Record<ConflictType, 'pink' | 'purple' | 'warn' | 'bad' | 'orange' | 'info'> = {
  人员超载: 'pink', 时间区间重叠: 'purple', 里程碑挤压: 'warn', 跨项目争用: 'bad', 依赖倒挂: 'orange', Deadline越级: 'info',
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

export default function ConflictsPage({ nav }: PageProps) {
  const version = useStore() // 订阅 store，数据变化时重算摊平与冲突
  const [sel, setSel] = useState<{ userId: string; date: string } | null>(null)
  const [typeFilter, setTypeFilter] = useState<ConflictType | '全部'>('全部')
  const [hiId, setHiId] = useState<string | null>(null)

  const conflicts = computeConflicts()

  // 工时摊平：成员 × 日期 → 当日未完成任务 estimateHours 均摊（对齐 computeConflicts 步骤 1）
  const load = useMemo(() => {
    const m = new Map<string, Map<string, number>>()
    for (const w of workItems) {
      const closed = w.type === 'defect'
        ? w.status === '已关闭' || w.status === '回归通过'
        : w.status === 'done' || w.status === 'closed'
      if (closed || !w.startDate || !w.dueDate) continue
      const span = workdays(w.startDate, w.dueDate)
      if (span.length === 0) continue
      const per = w.estimateHours / span.length
      const byDay = m.get(w.assigneeId) ?? new Map<string, number>()
      for (const d of span) byDay.set(d, (byDay.get(d) ?? 0) + per)
      m.set(w.assigneeId, byDay)
    }
    return m
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [version])

  // 热力图选中格 → 该人当日相关冲突（无当日命中则退回该人全部冲突）
  const base = sel ? conflicts.filter((c) => c.subjectId === sel.userId) : conflicts
  const dayRows = sel ? base.filter((c) => c.detail.includes(sel.date.slice(5))) : base
  const personRows = dayRows.length > 0 ? dayRows : base
  const rows = personRows.filter((c) => typeFilter === '全部' || c.type === typeFilter)

  const subjectChip = (c: ConflictItem) => {
    if (c.subjectType === 'task') {
      const w = workItemById(c.subjectId)
      return w ? <Chip onClick={() => nav.go('tasks', w.id)}>{w.key} {w.title}</Chip> : null
    }
    if (c.subjectType === 'release') {
      const r = releaseById(c.subjectId)
      return r ? <Chip onClick={() => nav.go('delivery', r.id)}><CalendarClock size={11} /> {r.name}（{r.planDate.slice(5)} 发布）</Chip> : null
    }
    if (c.subjectType === 'user') {
      const u = userById(c.subjectId)
      return u ? <Chip active={hiId === c.id} onClick={() => setHiId(hiId === c.id ? null : c.id)}>{u.name}（点击高亮）</Chip> : null
    }
    return <span className="text-xs text-txt-mid">{c.subjectId}</span>
  }

  return (
    <div>
      <PageHeader title="冲突中心" desc="六级冲突实时检测 · 负载热力图与冲突清单联动" />

      {/* 说明条 */}
      <div className="mb-4 flex items-start gap-2.5 rounded-card border border-line bg-info-bg px-4 py-3">
        <AlertTriangle size={16} className="mt-0.5 shrink-0 text-info" />
        <p className="min-w-0 flex-1 text-xs leading-5 text-txt-mid">
          六级冲突：① 人员超载（日负载 ≥85% 容量）② 时间区间重叠（同人任务撞期）③ 里程碑挤压（相邻发布冻结间距 &lt; 7 天）
          ④ 跨项目争用（跨产品负载超容）⑤ 依赖倒挂（任务截止早于其依赖）⑥ Deadline 越级（截止晚于迭代 / 版本容器）。
          <span className="font-semibold text-txt-hi">红色需立即处理，黄色为预警。</span>
        </p>
        <span className="shrink-0 text-xs tabular-nums text-txt-mid">检测于 {conflicts[0]?.detectedAt ?? '--:--'}</span>
      </div>

      {/* 上半区：人员负载热力图 */}
      <Card className="mb-4">
        <CardHeader
          title="人员负载热力图"
          extra={(
            <div className="flex items-center gap-3 text-xs text-txt-mid">
              <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-ok opacity-70" />正常</span>
              <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-warn opacity-70" />≥ 85%</span>
              <span className="flex items-center gap-1"><span className="h-2.5 w-2.5 rounded-sm bg-bad opacity-90" />超载</span>
              <span className="text-txt-low">点击红 / 黄格查看当日冲突</span>
            </div>
          )}
        />
        <div className="overflow-x-auto px-4 py-3">
          <div className="grid min-w-[760px] items-center gap-y-1" style={{ gridTemplateColumns: '140px repeat(10, minmax(52px, 1fr))' }}>
            <div className="text-xs text-txt-low">成员 / 日期</div>
            {DAYS.map((d) => {
              const diff = daysBetween(dateStr(0), d)
              return (
                <div key={d} className={`px-0.5 text-center text-[11px] font-medium ${diff <= 1 ? 'text-brand-deep' : 'text-txt-mid'}`}>
                  {d.slice(5)}{diff === 0 ? ' · 今' : diff === 1 ? ' · 明' : ''}
                </div>
              )
            })}
            {users.map((u) => (
              <Fragment key={u.id}>
                <div className="flex items-center gap-2 pr-2">
                  <Avatar userId={u.id} size={22} />
                  <span className="truncate text-xs text-txt-hi">{u.name}</span>
                </div>
                {DAYS.map((d) => {
                  const h = load.get(u.id)?.get(d) ?? 0
                  const ratio = h / u.dailyCapacityHours
                  const hot = ratio >= 0.85
                  const activeCell = sel?.userId === u.id && sel.date === d
                  return (
                    <button
                      key={d}
                      type="button"
                      onClick={() => { if (hot) { setSel({ userId: u.id, date: d }); setTypeFilter('全部'); setHiId(null) } }}
                      title={`${u.name} ${d.slice(5)}：负载 ${h.toFixed(1)}h / 容量 ${u.dailyCapacityHours}h（${Math.round(ratio * 100)}%）`}
                      className={`h-6 cursor-default ${hot ? 'cursor-pointer' : ''} ${activeCell ? 'rounded-sm outline-2 outline-brand' : ''}`}
                    >
                      <HeatCell ratio={ratio} />
                    </button>
                  )
                })}
              </Fragment>
            ))}
          </div>
        </div>
      </Card>

      {/* 下半区：冲突列表 */}
      <Card>
        <CardHeader
          title={`冲突列表 · ${rows.length} 条`}
          extra={sel ? (
            <button
              type="button"
              onClick={() => setSel(null)}
              className="inline-flex cursor-pointer items-center gap-1 rounded-full bg-bad-bg px-2 py-0.5 text-xs font-semibold text-bad-deep hover:opacity-80"
            >
              筛选：{userById(sel.userId)?.name} {sel.date.slice(5)} <X size={11} />
            </button>
          ) : (
            <span className="text-xs text-txt-low">红色置顶 · 点击主体可跳转</span>
          )}
        />
        <div className="flex flex-wrap items-center gap-1.5 border-b border-line px-3 py-2">
          {(['全部', ...TYPES] as (ConflictType | '全部')[]).map((t) => {
            const n = t === '全部' ? conflicts.length : conflicts.filter((c) => c.type === t).length
            const activeTab = typeFilter === t
            return (
              <button
                key={t}
                type="button"
                onClick={() => setTypeFilter(t)}
                className={`cursor-pointer rounded-full px-2.5 py-1 text-xs transition-colors ${
                  activeTab ? 'bg-brand-bg font-semibold text-brand-deep' : 'text-txt-mid hover:bg-ink-700 hover:text-txt-hi'
                }`}
              >
                {t} <span className="tabular-nums opacity-70">{n}</span>
              </button>
            )
          })}
        </div>
        {rows.length === 0 && <Empty text="当前筛选下无冲突" />}
        <ul>
          {rows.map((c) => (
            <li
              key={c.id}
              className={`flex items-start gap-2.5 border-b border-line px-4 py-2.5 last:border-b-0 ${hiId === c.id ? 'bg-brand-bg/60' : ''}`}
            >
              <Pill tone={c.severity === 'red' ? 'bad' : 'warn'}>{c.severity === 'red' ? '红' : '黄'}</Pill>
              <Pill tone={typeTone[c.type]}>{c.type}</Pill>
              <div className="min-w-0 flex-1">
                <div className="text-sm leading-5 text-txt-hi">{c.detail}</div>
                <div className="mt-1 flex flex-wrap items-center gap-1.5">
                  {subjectChip(c)}
                  {c.relatedTaskIds.map((tid) => {
                    const w = workItemById(tid)
                    return <Chip key={tid} onClick={() => nav.go('tasks', tid)}>{w?.key ?? tid}</Chip>
                  })}
                </div>
              </div>
              <span className="shrink-0 text-xs tabular-nums text-txt-low">{c.detectedAt}</span>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  )
}
