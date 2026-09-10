// 产品 RoadMap v2.1：两种视角（目标视角 / 版本视角）+ 可横向拖移时间轴
// 层级语义：目标 > 产品版本 —— 目标泳道内条形挂版本标签，看「通过哪些版本实现目标」；点目标直达迭代与任务
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { AlertTriangle, ArrowRight, ListChecks, X } from 'lucide-react'
import type { RoadmapItem } from '../../data/types'
import {
  computeConflicts, dateStr, daysBetween, goalById, goals, productById, releaseById, releases,
  roadmapItems, sprintById, sprints, useStore,
} from '../../data/store'
import { Avatar, Bar, Card, PageHeader, Pill } from '../../components/ui'
import type { PageProps } from '../../nav'

// ---- 可横移时间轴：2026-07 ~ 2027-01，每月固定 300px，容器横向滚动 ----
const T0 = '2026-07-01'
const T1 = '2027-01-31'
const MONTH_W = 300
const PPX = MONTH_W / 30.44 // 每天像素
const TOTAL_PX = Math.round(daysBetween(T0, T1) * PPX)
const MONTHS = ['2026-07', '2026-08', '2026-09', '2026-10', '2026-11', '2026-12', '2027-01']
const px = (d: string) => daysBetween(T0, d) * PPX
function monthEnd(ym: string): string {
  const [y, m] = ym.split('-').map(Number)
  return `${y}-${String(m).padStart(2, '0')}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`
}
const LABEL_W = 200

function Grid({ children }: { children: ReactNode }) {
  return (
    <div className="relative h-full" style={{ width: TOTAL_PX }}>
      {MONTHS.slice(1).map((m) => (
        <span key={m} className="pointer-events-none absolute inset-y-0 w-px bg-line" style={{ left: px(`${m}-01`) }} />
      ))}
      <TodayLine />
      {children}
    </div>
  )
}
function TodayLine() {
  const p = px(dateStr(0))
  if (p < 0 || p > TOTAL_PX) return null
  return <span className="pointer-events-none absolute inset-y-0 z-10 w-px bg-bad/60"><span className="absolute top-0 -left-4 rounded-sm bg-bad px-1 text-[9px] font-bold text-white">今</span></span>
}
/** 左侧标签列（sticky）+ 右侧时间轴行 */
function Lane({ label, children, height }: { label: ReactNode; children: ReactNode; height: number }) {
  return (
    <div className="flex items-stretch border-t border-line">
      <div className="sticky left-0 z-20 shrink-0 border-r border-line bg-card" style={{ width: LABEL_W }}>{label}</div>
      <div className="relative py-1.5" style={{ width: TOTAL_PX, height }}>
        <Grid>{children}</Grid>
      </div>
    </div>
  )
}

export default function RoadmapPage({ nav }: { nav: PageProps['nav'] }) {
  useStore()
  const [view, setView] = useState<'goal' | 'release'>('goal')
  const [sel, setSel] = useState<string | undefined>()
  const scrollRef = useRef<HTMLDivElement>(null)
  // 打开页面自动横移到「今天」附近
  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollLeft = Math.max(0, px(dateStr(0)) - 420)
  }, [view])

  const relSorted = [...releases].sort((a, b) => a.planDate.localeCompare(b.planDate))
  const squeezes = computeConflicts()
    .filter((c) => c.type === '里程碑挤压')
    .map((c) => {
      const r2 = releaseById(c.subjectId)
      if (!r2) return null
      const same = relSorted.filter((r) => r.productId === r2.productId)
      const r1 = same[same.indexOf(r2) - 1]
      return r1 ? { from: r1.planDate, to: r2.codeFreezeDate, text: c.detail } : null
    })
    .filter(Boolean) as { from: string; to: string; text: string }[]
  const selItem = sel ? roadmapItems.find((i) => i.id === sel) : undefined

  return (
    <div>
      <PageHeader
        title="产品 RoadMap"
        desc="层级语义：战略目标 > 产品版本 —— 目标泳道内条形挂版本标签，看「通过哪些版本实现目标」 · 时间轴可横向拖移 · 红线 = 今天 · 黄带 = 发布挤压（CF-3）"
        actions={
          <div className="flex items-center gap-1 rounded-full border border-line bg-card p-1">
            {([['goal', '目标视角'], ['release', '版本视角']] as const).map(([k, label]) => (
              <button
                key={k}
                type="button"
                onClick={() => setView(k)}
                className={`cursor-pointer rounded-full px-3 py-1 text-xs font-semibold transition-colors ${
                  view === k ? 'bg-brand text-white' : 'text-txt-mid hover:text-txt-hi'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
        }
      />

      <div className="flex items-start gap-4">
        <div ref={scrollRef} className="min-w-0 flex-1 overflow-x-auto rounded-card border border-line bg-card shadow-card">
          <div style={{ minWidth: LABEL_W + TOTAL_PX }}>
            {/* 月份表头 */}
            <div className="flex border-b border-line-hi">
              <div className="sticky left-0 z-20 flex shrink-0 items-end bg-card py-1.5 pl-3 text-[11px] font-semibold text-txt-low" style={{ width: LABEL_W }}>
                {view === 'goal' ? '战略目标 / 月份' : '产品版本 / 月份'}
              </div>
              <div className="relative h-7" style={{ width: TOTAL_PX }}>
                {MONTHS.map((m) => (
                  <span key={m} className="absolute top-1.5 text-[11px] font-semibold text-txt-mid" style={{ left: px(`${m}-01`) + 6 }}>{m}</span>
                ))}
              </div>
            </div>

            {/* 发布挤压警示带（CF-3） */}
            <div className="flex border-b border-line bg-warn-bg/50">
              <div className="sticky left-0 z-20 flex shrink-0 items-center gap-1 border-r border-line bg-card py-2 pl-3 text-[11px] font-semibold text-warn-deep" style={{ width: LABEL_W }}>
                <AlertTriangle size={12} />发布挤压
              </div>
              <div className="relative h-9" style={{ width: TOTAL_PX }}>
                <Grid>
                  {squeezes.map((s, i) => (
                    <div
                      key={i}
                      title={s.text}
                      className="absolute top-1.5 flex h-6 items-center gap-1 overflow-hidden rounded border border-warn/60 bg-warn/25 px-1"
                      style={{ left: px(s.from), width: Math.max(px(s.to) - px(s.from), 24) }}
                    >
                      <AlertTriangle size={11} className="shrink-0 text-warn-deep" />
                      <span className="truncate text-[10px] text-warn-deep">{s.text}</span>
                    </div>
                  ))}
                </Grid>
              </div>
            </div>

            {view === 'goal' ? (
              <>
                {/* 目标视角：目标是大上级，条形挂版本标签 */}
                {goals.map((g) => {
                  const items = roadmapItems.filter((r) => r.goalId === g.id)
                  const goalSprint = sprints.find((s) => s.status === 'active' && g.productIds.includes(s.productId))
                  const h = Math.max(items.length, 1) * 34 + 10
                  return (
                    <Lane
                      key={g.id}
                      height={h}
                      label={
                        <div className="flex h-full flex-col justify-center gap-1 py-1 pl-3 pr-2">
                          <div className="flex items-center gap-1.5">
                            <span className="font-mono text-[11px] font-bold text-cat-purple">{g.key}</span>
                            <span className="min-w-0 flex-1 truncate text-xs font-semibold text-txt-hi" title={g.name}>{g.name}</span>
                          </div>
                          <div className="flex items-center gap-1">
                            <Bar value={g.progress} tone={g.status === 'at_risk' ? 'warn' : 'brand'} className="w-14" />
                            <span className="text-[10px] font-bold tabular-nums text-txt-mid">{g.progress}%</span>
                            <span className="flex-1" />
                            <button
                              type="button"
                              title={`查看「${g.name}」的迭代与任务`}
                              onClick={() => nav.go('tasks', goalSprint?.id)}
                              className="inline-flex cursor-pointer items-center gap-0.5 rounded border border-line bg-canvas px-1 py-0.5 text-[10px] text-brand hover:bg-brand-bg"
                            >
                              <ListChecks size={10} /> 迭代与任务
                            </button>
                          </div>
                        </div>
                      }
                    >
                      {items.length === 0 && <span className="absolute top-2 left-3 text-[11px] text-txt-low">暂无关联 RoadMap 条目</span>}
                      {items.map((rm, i) => (
                        <RmBar key={rm.id} rm={rm} selected={sel === rm.id} onSelect={() => setSel(rm.id)} top={4 + i * 34} showReleaseTag />
                      ))}
                    </Lane>
                  )
                })}
              </>
            ) : (
              <>
                {/* 版本视角：泳道 = 产品版本，条形 = 该版本承载的 RoadMap 条目 */}
                {relSorted.map((r) => {
                  const items = roadmapItems.filter((rm) => rm.releaseId === r.id)
                  const h = Math.max(items.length, 1) * 34 + 10
                  const prod = productById(r.productId)
                  return (
                    <Lane
                      key={r.id}
                      height={h}
                      label={
                        <div className="flex h-full items-center gap-1.5 py-1 pl-3 pr-2">
                          <span className="font-mono text-xs font-bold text-cat-teal">{r.name}</span>
                          <Pill tone={r.status === 'released' ? 'ok' : r.status === 'testing' ? 'info' : r.status === 'coding' ? 'brand' : 'neutral'}>
                            {{ planned: '已规划', coding: '开发中', testing: '测试中', released: '已发布' }[r.status]}
                          </Pill>
                          {r.blocked && <Pill tone="bad">锁定</Pill>}
                          <span className="flex-1" />
                          <span className="text-[10px] text-txt-low">{prod?.key}</span>
                        </div>
                      }
                    >
                      <button
                        type="button"
                        title={`${r.name} 计划发布 ${r.planDate}${r.blocked ? ' · 发布锁定' : ''}`}
                        onClick={() => nav.go('delivery', r.id)}
                        className="absolute top-1/2 z-20 -translate-y-1/2 cursor-pointer"
                        style={{ left: px(r.planDate) }}
                      >
                        <span className={`block h-2.5 w-2.5 rotate-45 ring-2 ring-canvas ${r.blocked ? 'bg-cat-red' : 'bg-cat-teal'}`} />
                      </button>
                      {items.length === 0 && <span className="absolute top-2 left-3 text-[11px] text-txt-low">该版本暂未挂载 RoadMap 条目</span>}
                      {items.map((rm, i) => (
                        <RmBar key={rm.id} rm={rm} selected={sel === rm.id} onSelect={() => setSel(rm.id)} top={4 + i * 34} />
                      ))}
                    </Lane>
                  )
                })}
              </>
            )}
          </div>
        </div>

        {selItem && <RmPanel rm={selItem} nav={nav} onClose={() => setSel(undefined)} />}
      </div>
    </div>
  )
}

/** 泳道条形：进度填充 + 版本标签（目标→版本 挂载）+ goalId/产品徽标 + owner */
function RmBar({ rm, selected, onSelect, top, showReleaseTag = false }: { rm: RoadmapItem; selected: boolean; onSelect: () => void; top: number; showReleaseTag?: boolean }) {
  const x1 = px(`${rm.start}-01`)
  const x2 = px(monthEnd(rm.end))
  const goal = rm.goalId ? goalById(rm.goalId) : undefined
  const prod = rm.productId ? productById(rm.productId) : undefined
  const rel = rm.releaseId ? releaseById(rm.releaseId) : undefined
  return (
    <button
      type="button"
      onClick={onSelect}
      title={`${rm.name} · ${rm.start} ~ ${rm.end} · 进度 ${rm.progress}%${rel ? ` · 交付版本 ${rel.name}` : ''}`}
      className={`absolute z-10 flex h-7 cursor-pointer items-center overflow-hidden rounded-md bg-cat-teal/15 ring-1 ring-cat-teal/40 transition hover:bg-cat-teal/25 ${selected ? 'ring-2 ring-brand' : ''}`}
      style={{ left: x1, width: Math.max(x2 - x1, 56), top }}
    >
      <div className="absolute inset-y-0 left-0 bg-cat-teal/30" style={{ width: `${rm.progress}%` }} />
      <span className="relative z-10 flex w-full items-center gap-1 px-1.5 text-[10px] leading-none">
        <span className="min-w-0 truncate font-semibold text-txt-hi">{rm.name}</span>
        {showReleaseTag && rel && (
          <span className="shrink-0 rounded bg-cat-teal px-1 py-px font-mono font-bold text-white" title={`交付版本 ${rel.name}`}>{rel.name}</span>
        )}
        {goal && <span className="shrink-0 font-mono font-bold text-cat-purple">{goal.key}</span>}
        {prod && <span className="shrink-0 text-cat-blue">{prod.key}</span>}
        <span className="min-w-1 flex-1" />
        {rm.ownerIds.slice(0, 2).map((o) => <Avatar key={o} userId={o} size={16} />)}
      </span>
    </button>
  )
}

/** 条目详情：名称 / 泳道 / 周期 / 进度 / 负责人 / 关联目标 / 关联版本 / 横切迭代 */
function RmPanel({ rm, nav, onClose }: { rm: RoadmapItem; nav: PageProps['nav']; onClose: () => void }) {
  const goal = rm.goalId ? goalById(rm.goalId) : undefined
  const rel = rm.releaseId ? releaseById(rm.releaseId) : undefined
  const prod = rm.productId ? productById(rm.productId) : undefined
  const sprintsOf = rm.sprintIds.map((sid) => sprintById(sid)).filter(Boolean)
  return (
    <aside className="w-72 shrink-0">
      <Card className="p-4">
        <div className="flex items-start gap-2">
          <h3 className="min-w-0 flex-1 text-sm font-bold leading-5 text-txt-hi">{rm.name}</h3>
          <button type="button" onClick={onClose} className="cursor-pointer text-txt-low hover:text-txt-hi"><X size={15} /></button>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <Pill tone="teal">{rm.track}</Pill>
          <Pill tone={rm.status === 'shipped' ? 'ok' : rm.status === 'in_progress' ? 'info' : 'neutral'}>
            {{ planned: '已规划', in_progress: '进行中', shipped: '已交付' }[rm.status]}
          </Pill>
          {prod && <Pill tone="brand">{prod.name}</Pill>}
        </div>
        <div className="mt-3 space-y-2 text-xs text-txt-mid">
          <div>周期：{rm.start} ~ {rm.end}</div>
          <div className="flex items-center gap-2">
            进度 <Bar value={rm.progress} tone={rm.progress >= 80 ? 'ok' : 'brand'} className="flex-1" />
            <span className="font-bold tabular-nums text-txt-hi">{rm.progress}%</span>
          </div>
          <div className="flex items-center gap-1.5">
            负责人 {rm.ownerIds.map((o) => <Avatar key={o} userId={o} size={18} />)}
            <span className="flex-1" />
            <span className="text-txt-low">工作项 {rm.issueCount} 件</span>
          </div>
        </div>
        <div className="mt-3 space-y-1.5 border-t border-line pt-3">
          <div className="text-[11px] font-semibold text-txt-low">关联链路（目标 → 版本 → 迭代）</div>
          {goal ? (
            <LinkBtn onClick={() => nav.go('goals', goal.id)}>目标 {goal.key} · {goal.name}</LinkBtn>
          ) : (
            <div className="text-xs text-txt-low">未关联战略目标</div>
          )}
          {rel && <LinkBtn onClick={() => nav.go('delivery', rel.id)}>交付版本 {rel.name} · 计划 {rel.planDate.slice(5)}</LinkBtn>}
          {sprintsOf.length === 0 && <div className="text-xs text-txt-low">无横切迭代</div>}
          {sprintsOf.map((sp) => (
            <LinkBtn key={sp!.id} onClick={() => nav.go('tasks', sp!.id)}>迭代 {sp!.name}</LinkBtn>
          ))}
        </div>
      </Card>
    </aside>
  )
}

function LinkBtn({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full cursor-pointer items-center gap-1 truncate rounded border border-line bg-canvas px-1.5 py-1 text-[11px] text-brand hover:bg-brand-bg"
    >
      <span className="min-w-0 flex-1 truncate text-left">{children}</span>
      <ArrowRight size={10} className="shrink-0" />
    </button>
  )
}
