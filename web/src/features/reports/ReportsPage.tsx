// 统计报表中心（R10）：维度过滤 + 六图仪表盘（手写 SVG）+ 绩效视图
import { useState, type ReactNode } from 'react'
import { Bell, Download } from 'lucide-react'
import type { Defect, DefectSeverity, Product, Sprint, User, WorkItem } from '../../data/types'
import {
  componentById, departments, mergeRequests, productById, products, repoById, sprints, useStore, users, workItems,
} from '../../data/store'
import { Avatar, Bar, Btn, Card, CardHeader, Empty, PageHeader } from '../../components/ui'
import type { PageProps } from '../../nav'

const DAY = 86400000
const isDone = (w: WorkItem) => w.status === 'done' || w.status === 'closed'
const SEG_COLORS = ['var(--color-cat-blue)', 'var(--color-cat-teal)', 'var(--color-cat-purple)', 'var(--color-cat-orange)']
const selectCls = 'cursor-pointer rounded-input border border-line bg-card px-2 py-1 text-xs text-txt-mid'

/** 解析 store 时间（"M-D HH:MM" | "YYYY-M-D HH:MM" | "YYYY-MM-DD"）→ 自然日时间戳 */
function ts(s: string): number {
  const p = s.split(' ')[0].split('-').map(Number)
  return p.length === 3 ? new Date(p[0], p[1] - 1, p[2]).getTime() : new Date(new Date().getFullYear(), p[0] - 1, p[1]).getTime()
}
const md = (t: number) => { const d = new Date(t); return `${d.getMonth() + 1}-${d.getDate()}` }

function ChartCard({ no, title, note, extra, children }: { no: string; title: string; note: string; extra?: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <CardHeader title={<span>{no} {title}</span>} extra={extra} />
      <div className="px-4 pb-3 pt-2">
        <div className="mb-1.5 text-[11px] text-txt-low">口径：{note}</div>
        {children}
      </div>
    </Card>
  )
}

/* ---------- ① 迭代燃尽：实际折线 + 虚线理想 ---------- */
function BurndownChart({ sprint }: { sprint: Sprint }) {
  const data = sprint.burndown.length > 1 ? sprint.burndown : [sprint.burndown[0] ?? 0, 0]
  const W = 340, H = 150, L = 30, T = 10
  const iw = W - L - 10, ih = H - T - 20
  const max = Math.max(...data, 1)
  const x = (i: number) => L + (iw * i) / (data.length - 1)
  const y = (v: number) => T + ih * (1 - v / max)
  return (
    <svg viewBox="0 0 340 150" className="w-full">
      {[0, 0.25, 0.5, 0.75, 1].map((r) => <line key={r} x1={L} x2={W - 10} y1={T + ih * r} y2={T + ih * r} stroke="var(--color-line)" />)}
      <text x={L - 5} y={T + 4} textAnchor="end" className="fill-txt-low text-[9px]">{max}</text>
      <text x={L - 5} y={T + ih + 4} textAnchor="end" className="fill-txt-low text-[9px]">0</text>
      <polyline fill="none" stroke="var(--color-txt-low)" strokeWidth={1.5} strokeDasharray="4 3"
        points={data.map((_, i) => `${x(i)},${y((max * (data.length - 1 - i)) / (data.length - 1))}`).join(' ')} />
      <polyline fill="none" stroke="var(--color-cat-blue)" strokeWidth={2}
        points={data.map((v, i) => `${x(i)},${y(v)}`).join(' ')} />
      {data.map((v, i) => <circle key={i} cx={x(i)} cy={y(v)} r={2.5} fill="var(--color-cat-blue)" />)}
      <text x={L} y={H - 6} className="fill-txt-low text-[9px]">第1天</text>
      <text x={W - 10} y={H - 6} textAnchor="end" className="fill-txt-low text-[9px]">第{data.length}天</text>
    </svg>
  )
}

/* ---------- ② 累积流图 CFD：todo/wip/done 堆叠面积（polyline 近似） ---------- */
function CfdChart({ items }: { items: WorkItem[] }) {
  if (items.length === 0) return <Empty text="当前过滤无工作项" />
  const N = 16
  const start = Math.min(...items.map((w) => ts(w.createdAt)))
  const ds = Array.from({ length: N }, (_, i) => start + ((Date.now() - start) * i) / (N - 1))
  const done = ds.map((d) => items.filter((w) => isDone(w) && ts(w.updatedAt) <= d).length)
  const wip = ds.map((d) => items.filter((w) => ts(w.createdAt) <= d && w.status !== 'todo' && !(isDone(w) && ts(w.updatedAt) <= d)).length)
  const todo = ds.map((d) => items.filter((w) => ts(w.createdAt) <= d && w.status === 'todo').length)
  const c1 = done
  const c2 = done.map((v, i) => v + wip[i])
  const c3 = c2.map((v, i) => v + todo[i])
  const W = 340, H = 150, L = 26, T = 10
  const iw = W - L - 10, ih = H - T - 20
  const max = Math.max(...c3, 1)
  const X = (i: number) => L + (iw * i) / (N - 1)
  const Y = (v: number) => T + ih * (1 - v / max)
  const pts = (arr: number[]) => arr.map((v, i) => `${X(i)},${Y(v)}`).join(' ')
  const band = (up: number[], low: number[]) => `${pts(up)} ${pts(low).split(' ').reverse().join(' ')}`
  return (
    <div>
      <svg viewBox="0 0 340 150" className="w-full">
        <polygon points={band(c1, ds.map(() => 0))} fill="var(--color-cat-green)" opacity={0.55} />
        <polygon points={band(c2, c1)} fill="var(--color-cat-yellow)" opacity={0.55} />
        <polygon points={band(c3, c2)} fill="var(--color-cat-blue)" opacity={0.4} />
        <polyline points={pts(c3)} fill="none" stroke="var(--color-cat-blue)" strokeWidth={1.5} />
        <text x={L - 4} y={T + 4} textAnchor="end" className="fill-txt-low text-[9px]">{max}</text>
        <text x={L - 4} y={T + ih + 4} textAnchor="end" className="fill-txt-low text-[9px]">0</text>
        <text x={L} y={H - 6} className="fill-txt-low text-[9px]">{md(ds[0])}</text>
        <text x={W - 10} y={H - 6} textAnchor="end" className="fill-txt-low text-[9px]">今天</text>
      </svg>
      <div className="flex gap-3 text-[11px] text-txt-mid">
        <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-sm bg-cat-green" />done</span>
        <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-sm bg-cat-yellow" />in_progress</span>
        <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-sm bg-cat-blue" />todo</span>
      </div>
    </div>
  )
}

/* ---------- ③ 控制图：已完成项周期散点，高于均值橙色 ---------- */
function ControlChart({ items }: { items: WorkItem[] }) {
  const doneItems = items.filter(isDone)
  if (doneItems.length === 0) return <Empty text="当前过滤无已完成工作项" />
  const cycles = doneItems.map((w) => Math.max(0, Math.round((ts(w.updatedAt) - ts(w.createdAt)) / DAY)))
  const mean = cycles.reduce((s, v) => s + v, 0) / cycles.length
  const W = 340, H = 150, L = 26, T = 10
  const iw = W - L - 10, ih = H - T - 20
  const max = Math.max(...cycles, mean, 1)
  const X = (i: number) => L + (iw * i) / Math.max(1, cycles.length - 1)
  const Y = (v: number) => T + ih * (1 - v / max)
  return (
    <svg viewBox="0 0 340 150" className="w-full">
      <line x1={L} x2={W - 10} y1={T + ih} y2={T + ih} stroke="var(--color-line)" />
      <line x1={L} x2={W - 10} y1={Y(mean)} y2={Y(mean)} stroke="var(--color-cat-orange)" strokeWidth={1} strokeDasharray="4 3" />
      <text x={W - 10} y={Y(mean) - 4} textAnchor="end" className="fill-cat-orange text-[9px]">均值 {mean.toFixed(1)} 天</text>
      {cycles.map((c, i) => (
        <circle key={i} cx={X(i)} cy={Y(c)} r={4} opacity={0.85}
          fill={c > mean ? 'var(--color-cat-orange)' : 'var(--color-cat-blue)'}>
          <title>{`${doneItems[i].key} 周期 ${c} 天`}</title>
        </circle>
      ))}
      <text x={L - 4} y={T + 4} textAnchor="end" className="fill-txt-low text-[9px]">{Math.round(max)}d</text>
      <text x={L} y={H - 6} className="fill-txt-low text-[9px]">#1</text>
      <text x={W - 10} y={H - 6} textAnchor="end" className="fill-txt-low text-[9px]">#{cycles.length}</text>
    </svg>
  )
}

/* ---------- ④ 速率柱状：每迭代 done 故事点 ---------- */
function VelocityChart({ list }: { list: { s: Sprint; pts: number }[] }) {
  if (list.length === 0) return <Empty text="当前过滤无迭代" />
  const max = Math.max(...list.map((x) => x.pts), 1)
  return (
    <div className="space-y-2.5 pt-1">
      {list.map(({ s, pts }) => (
        <div key={s.id} className="flex items-center gap-2">
          <span title={s.name} className="w-36 shrink-0 truncate text-xs text-txt-mid">{s.name}</span>
          <Bar value={(pts / max) * 100} tone="brand" className="flex-1" />
          <span className="w-12 shrink-0 text-right text-xs font-bold tabular-nums text-txt-hi">{pts} 点</span>
        </div>
      ))}
    </div>
  )
}

/* ---------- ⑤ 缺陷分布：严重度 × 组件 双维条形 ---------- */
function DefectDist({ defs }: { defs: Defect[] }) {
  const sevs: DefectSeverity[] = ['致命', '严重', '一般', '轻微']
  const sevBar = ['bg-bad', 'bg-cat-orange', 'bg-cat-orange/60', 'bg-cat-orange/35']
  const maxSev = Math.max(...sevs.map((s) => defs.filter((d) => d.severity === s).length), 1)
  const byComp = new Map<string, number>()
  for (const d of defs) if (d.componentId) byComp.set(d.componentId, (byComp.get(d.componentId) ?? 0) + 1)
  const compRows = [...byComp.entries()].sort((a, b) => b[1] - a[1])
  const maxComp = Math.max(...compRows.map(([, n]) => n), 1)
  return (
    <div className="space-y-3 pt-1">
      {sevs.map((s, i) => {
        const n = defs.filter((d) => d.severity === s).length
        return (
          <div key={s} className="flex items-center gap-2">
            <span className="w-8 shrink-0 text-xs text-txt-mid">{s}</span>
            <div className="h-3 flex-1 overflow-hidden rounded-sm bg-ink-700">
              <div className={`h-full rounded-sm ${sevBar[i]}`} style={{ width: `${(n / maxSev) * 100}%` }} />
            </div>
            <span className="w-6 shrink-0 text-right text-xs font-bold tabular-nums text-txt-hi">{n}</span>
          </div>
        )
      })}
      <div className="border-t border-line pt-2 text-[11px] font-semibold text-txt-low">按组件</div>
      {compRows.length === 0 && <div className="text-xs text-txt-low">无挂接组件的缺陷</div>}
      {compRows.map(([cid, n]) => (
        <div key={cid} className="flex items-center gap-2">
          <span className="w-16 shrink-0 truncate text-xs text-txt-mid">{componentById(cid)?.name ?? cid}</span>
          <div className="h-3 flex-1 overflow-hidden rounded-sm bg-ink-700">
            <div className="h-full rounded-sm bg-cat-orange/70" style={{ width: `${(n / maxComp) * 100}%` }} />
          </div>
          <span className="w-6 shrink-0 text-right text-xs font-bold tabular-nums text-txt-hi">{n}</span>
        </div>
      ))}
    </div>
  )
}
/* ---------- ⑥ 资源投入：成员 × 产品 未完成工时堆叠水平条 ---------- */
function ResourceChart({ members, prods, items }: { members: User[]; prods: Product[]; items: WorkItem[] }) {
  if (members.length === 0 || prods.length === 0) return <Empty text="当前过滤无成员/产品" />
  const rows = members.map((u) => ({
    u,
    segs: prods.map((p) => items.filter((w) => w.assigneeId === u.id && w.productId === p.id && !isDone(w)).reduce((s, w) => s + w.estimateHours, 0)),
  }))
  const max = Math.max(...rows.map((r) => r.segs.reduce((s, v) => s + v, 0)), 1)
  return (
    <div className="space-y-2 pt-1">
      <div className="flex gap-3 text-[11px] text-txt-mid">
        {prods.map((p, i) => (
          <span key={p.id} className="flex items-center gap-1"><span className="h-2 w-2 rounded-sm" style={{ background: SEG_COLORS[i % SEG_COLORS.length] }} />{p.name}</span>
        ))}
      </div>
      {rows.map(({ u, segs }) => {
        const total = segs.reduce((s, v) => s + v, 0)
        return (
          <div key={u.id} className="flex items-center gap-2">
            <Avatar userId={u.id} size={18} />
            <span className="w-12 shrink-0 truncate text-xs text-txt-mid">{u.name}</span>
            <div className="flex h-3.5 flex-1 overflow-hidden rounded-sm bg-ink-700">
              {segs.map((h, i) => h > 0 && (
                <div key={i} style={{ width: `${(h / max) * 100}%`, background: SEG_COLORS[i % SEG_COLORS.length] }} title={`${prods[i].name} ${h}h`} />
              ))}
            </div>
            <span className="w-12 shrink-0 text-right text-xs font-bold tabular-nums text-txt-hi">{total}h</span>
          </div>
        )
      })}
    </div>
  )
}

/* ---------- 绩效视图 ---------- */
type SortKey = 'name' | 'doneCount' | 'donePoints' | 'mrCount' | 'onTime' | 'loadHours'
interface PerfRow { u: User; doneCount: number; donePoints: number; mrCount: number; onTime: number | null; loadHours: number }
const PERF_COLS: { key: SortKey; label: string }[] = [
  { key: 'name', label: '成员' },
  { key: 'doneCount', label: '完成工作项' },
  { key: 'donePoints', label: '完成故事点' },
  { key: 'mrCount', label: '交付 MR' },
  { key: 'onTime', label: '按期完成率' },
  { key: 'loadHours', label: '进行中负载(h)' },
]

export default function ReportsPage({ nav }: PageProps) {
  useStore()
  const [productId, setProductId] = useState('')
  const [deptId, setDeptId] = useState('')
  const [sprintSel, setSprintSel] = useState('')
  const [sort, setSort] = useState<{ key: SortKey; asc: boolean }>({ key: 'donePoints', asc: false })

  const inDept = (pid: string) => productById(pid)?.departmentId === deptId
  const fItems = workItems.filter((w) => (!productId || w.productId === productId) && (!deptId || inDept(w.productId)))
  const fSprints = sprints.filter((s) => (!productId || s.productId === productId) && (!deptId || inDept(s.productId)))
  const effSprint = fSprints.find((s) => s.id === sprintSel) ?? fSprints[0]
  const fDefs = fItems.filter((w): w is Defect => w.type === 'defect')
  const fMembers = users.filter((u) => !deptId || u.departmentId === deptId)
  const fProducts = productId ? products.filter((p) => p.id === productId) : deptId ? products.filter((p) => p.departmentId === deptId) : products

  const perfRows: PerfRow[] = fMembers.map((u) => {
    const mine = fItems.filter((w) => w.assigneeId === u.id)
    const done = mine.filter(isDone)
    const withDue = done.filter((w) => w.dueDate)
    const onTime = withDue.length === 0 ? null : withDue.filter((w) => ts(w.updatedAt) <= ts(w.dueDate!)).length / withDue.length
    const mrCount = mergeRequests.filter((m) => {
      if (m.authorId !== u.id) return false
      const pid = repoById(m.repoId)?.productId
      if (productId) return pid === productId
      if (deptId) return pid !== undefined && inDept(pid)
      return true
    }).length
    return { u, doneCount: done.length, donePoints: done.reduce((s, w) => s + w.points, 0), mrCount, onTime, loadHours: mine.filter((w) => !isDone(w)).reduce((s, w) => s + w.estimateHours, 0) }
  })
  const val = (r: PerfRow): string | number => (sort.key === 'name' ? r.u.name : sort.key === 'onTime' ? (r.onTime ?? -1) : r[sort.key])
  const sortedRows = [...perfRows].sort((a, b) => {
    const va = val(a), vb = val(b)
    const c = typeof va === 'string' ? va.localeCompare(String(vb)) : (va as number) - (vb as number)
    return sort.asc ? c : -c
  })
  const toggleSort = (key: SortKey) => setSort((s) => (s.key === key ? { key, asc: !s.asc } : { key, asc: key === 'name' }))

  const exportCsv = () => {
    const lines = [
      PERF_COLS.map((c) => c.label).join(','),
      ...sortedRows.map((r) => [r.u.name, r.doneCount, r.donePoints, r.mrCount, r.onTime === null ? '—' : `${Math.round(r.onTime * 100)}%`, r.loadHours].join(',')),
    ]
    const url = URL.createObjectURL(new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'report.csv'
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div>
      <PageHeader
        title="统计报表"
        desc="六图效能仪表盘 · 全部图表与绩效表共用同一张工作项数据，交叉核对偏差为零"
        actions={
          <>
            <Btn variant="ghost" onClick={() => nav.go('conflicts')}>冲突中心</Btn>
            <Btn variant="default"><Bell size={14} /> 订阅</Btn>
            <Btn variant="primary" onClick={exportCsv}><Download size={14} /> 导出 CSV</Btn>
          </>
        }
      />

      {/* 维度过滤器 */}
      <div className="mb-4 flex flex-wrap items-center gap-3 rounded-card border border-line bg-card px-4 py-2.5">
        <span className="text-xs font-semibold text-txt-low">维度过滤</span>
        <label className="flex items-center gap-1.5 text-xs text-txt-mid">
          产品
          <select value={productId} onChange={(e) => setProductId(e.target.value)} className={selectCls}>
            <option value="">全部</option>
            {products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
        <label className="flex items-center gap-1.5 text-xs text-txt-mid">
          部门
          <select value={deptId} onChange={(e) => { const v = e.target.value; setDeptId(v); if (v && productId && !inDept(productId)) setProductId('') }} className={selectCls}>
            <option value="">全部</option>
            {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </label>
        <span className="ml-auto text-[11px] text-txt-low">工作项 {fItems.length} · 缺陷 {fDefs.length} · 成员 {fMembers.length}</span>
      </div>

      {/* 2×3 六图仪表盘 */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <ChartCard no="①" title="迭代燃尽" note="迭代内剩余故事点（实线实际 / 虚线理想线性）"
          extra={fSprints.length > 0 && (
            <select value={effSprint?.id ?? ''} onChange={(e) => setSprintSel(e.target.value)} className={selectCls}>
              {fSprints.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          )}>
          {effSprint ? <BurndownChart sprint={effSprint} /> : <Empty text="当前过滤无迭代" />}
        </ChartCard>
        <ChartCard no="②" title="累积流图 CFD" note="按 createdAt/updatedAt 推导的 todo/in_progress/done 每日累计">
          <CfdChart items={fItems} />
        </ChartCard>
        <ChartCard no="③" title="控制图" note="已完成工作项 创建→完成 周期天数散点，橙点 = 高于均值">
          <ControlChart items={fItems} />
        </ChartCard>
        <ChartCard no="④" title="迭代速率" note="各迭代 done 工作项故事点合计">
          <VelocityChart list={fSprints.map((s) => ({ s, pts: fItems.filter((w) => w.sprintId === s.id && isDone(w)).reduce((sum, w) => sum + w.points, 0) }))} />
        </ChartCard>
        <ChartCard no="⑤" title="缺陷分布" note="当前过滤缺陷按严重度四档 / 按组件双维统计">
          <DefectDist defs={fDefs} />
        </ChartCard>
        <ChartCard no="⑥" title="资源投入" note="成员各产品未完成工作项 estimateHours 合计（堆叠）">
          <ResourceChart members={fMembers} prods={fProducts} items={fItems} />
        </ChartCard>
      </div>

      {/* 绩效视图 */}
      <Card className="mt-4">
        <CardHeader
          title="绩效视图"
          extra={<span className="text-xs text-txt-low">口径：done=done/closed · 按期率仅统计有 dueDate 的完成项 · 点击列头排序</span>}
        />
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-txt-low">
                {PERF_COLS.map((c) => (
                  <th key={c.key} className="px-4 py-2 font-medium">
                    <button type="button" onClick={() => toggleSort(c.key)} className="cursor-pointer hover:text-txt-hi">
                      {c.label}{sort.key === c.key ? (sort.asc ? ' ↑' : ' ↓') : ''}
                    </button>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {sortedRows.map((r) => (
                <tr key={r.u.id} className="hover:bg-ink-700">
                  <td className="px-4 py-2">
                    <span className="flex items-center gap-2"><Avatar userId={r.u.id} size={22} />
                      <span className="font-medium text-txt-hi">{r.u.name}</span>
                      <span className="text-xs text-txt-low">{r.u.title}</span>
                    </span>
                  </td>
                  <td className="px-4 py-2 font-bold tabular-nums text-txt-hi">{r.doneCount}</td>
                  <td className="px-4 py-2 font-bold tabular-nums text-txt-hi">{r.donePoints}</td>
                  <td className="px-4 py-2 tabular-nums text-txt-mid">{r.mrCount}</td>
                  <td className={`px-4 py-2 font-bold tabular-nums ${r.onTime !== null && r.onTime < 0.5 ? 'text-cat-pink' : 'text-txt-hi'}`}>
                    {r.onTime === null ? '—' : `${Math.round(r.onTime * 100)}%`}
                  </td>
                  <td className={`px-4 py-2 tabular-nums ${r.loadHours > 30 ? 'text-bad' : 'text-txt-mid'}`}>{r.loadHours}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}
