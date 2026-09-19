// ⑥ 缺陷分布（B3 · R-2 单图化）：按组件单一维度水平堆叠条形（ECharts）
// 长度 = 数量、颜色 = 严重度四档固定语义色（与缺陷中心徽章全站唯一：致命红/严重橙/一般蓝/轻微灰）
// 口径 = 当前过滤缺陷全集（defs 直入图内，合计恒等于速览缺陷数）；无组件归「未指定」；不再有第二个维度平摊
// 收口批（D-91 后）：组件名映射真实化——useComponents（GET /api/v1/components）替代 store componentById；
// 真实 uuid 查不到组件时显示「未命名组件」而非 uuid 编码
import { lazy, Suspense, useMemo } from 'react'
import type { EChartsCoreOption } from 'echarts/core'
import { useComponents } from '../../api/queries'
import type { Defect, DefectSeverity } from '../../data/types'
import { Empty, Spinner } from '../../components/ui'
import { axisLbl, tooltipOf, useChartTheme, type ChartTheme } from './chart-shared'

// ⑥h 性能批：Echart 封装（含 echarts 依赖）动态加载，落入独立 echarts chunk；
// Suspense fallback 用与图表同高的占位，chunk 拉取期间布局不跳动
const Echart = lazy(() => import('../../components/Echart'))

/** 严重度四档（与缺陷中心 SEVS 同序同色：致命 bad / 严重 cat-orange / 一般 info / 轻微 txt-low） */
const SEVS: { key: DefectSeverity; colorOf: (ct: ChartTheme) => string }[] = [
  { key: '致命', colorOf: (ct) => ct.bad },
  { key: '严重', colorOf: (ct) => ct.orange },
  { key: '一般', colorOf: (ct) => ct.info },
  { key: '轻微', colorOf: (ct) => ct.txtLow },
]

export interface DefectDistributionProps {
  /** 当前过滤缺陷全集（ReportsPage 已按产品/部门过滤，与速览缺陷数同源） */
  defs: Defect[]
}

/** 按组件 × 严重度水平堆叠条形：组件按总数降序，无组件归「未指定」置底 */
export default function DefectDistribution({ defs }: DefectDistributionProps) {
  const ct = useChartTheme()
  // 收口批：组件名 = 真实组件表（GET /components）；uuid 不在表内（已删组件等）→「未命名组件」
  const { data: componentRows } = useComponents()

  const rows = useMemo(() => {
    const nameOf = (cid: string) => componentRows?.find((c) => c.id === cid)?.name ?? '未命名组件'
    // 组件名 → 四档严重度计数；componentId 缺失归「未指定」，真实 uuid 查不到映射归「未命名组件」
    const acc = new Map<string, { name: string; counts: number[] }>()
    const rowOf = (cid: string) => {
      let r = acc.get(cid)
      if (!r) {
        r = { name: cid ? nameOf(cid) : '未指定', counts: [0, 0, 0, 0] }
        acc.set(cid, r)
      }
      return r
    }
    for (const d of defs) {
      const r = rowOf(d.componentId ?? '')
      const si = SEVS.findIndex((s) => s.key === d.severity)
      if (si >= 0) r.counts[si] += 1
    }
    const list = [...acc.values()]
    // 总数降序；「未指定」始终置底（不为无主缺陷抢占视觉主体）
    const named = list.filter((r) => r.name !== '未指定').sort((a, b) => b.counts.reduce((x, y) => x + y, 0) - a.counts.reduce((x, y) => x + y, 0))
    const unspecified = list.filter((r) => r.name === '未指定')
    return [...named, ...unspecified]
  }, [defs, componentRows])

  if (rows.length === 0) return <Empty text="当前过滤无缺陷" />

  // 行高随组件数自适应（横向条形每行 30px，限高防失控）
  const height = Math.min(380, Math.max(170, 70 + rows.length * 30))

  // 堆叠 series：每个严重度一条 series（y 取组件名、x 为数量），合计 = defs.length
  const option: EChartsCoreOption = {
    tooltip: {
      trigger: 'axis', ...tooltipOf(ct), axisPointer: { type: 'shadow' },
      formatter: (ps: unknown) => {
        const arr = ps as { name?: string; seriesName?: string; value?: number; marker?: string }[]
        const head = arr[0]?.name ?? ''
        const total = arr.reduce((s, p) => s + (p.value || 0), 0)
        const lines = arr
          .filter((p) => (p.value || 0) > 0)
          .map((p) => `${p.marker} ${p.seriesName}：${p.value}`)
        return [`<b>${head}</b>（合计 ${total}）`, ...lines].join('<br/>')
      },
    },
    legend: { data: SEVS.map((s) => s.key), right: 0, top: 0, itemWidth: 12, itemHeight: 8, itemGap: 12, textStyle: { color: ct.txtMid, fontSize: 10 } },
    grid: { left: 8, right: 30, top: 26, bottom: 24, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: ct.line } }, axisLabel: axisLbl(ct) },
    yAxis: {
      type: 'category', inverse: true, // inverse：数组首行（总数最大）显示在最上
      data: rows.map((r) => r.name),
      axisLine: { lineStyle: { color: ct.line } }, axisTick: { show: false },
      axisLabel: { ...axisLbl(ct), color: ct.txtMid, width: 110, overflow: 'truncate' },
    },
    series: SEVS.map((s, si) => ({
      name: s.key,
      type: 'bar',
      stack: 'defects',
      barMaxWidth: 16,
      data: rows.map((r) => r.counts[si]),
      itemStyle: { color: s.colorOf(ct) },
      // 段内标数（0 不标）；轻微档底色浅改用深字，重叠时自动隐藏（小段数字靠 tooltip）
      label: {
        show: true, position: 'inside', fontSize: 10,
        color: s.key === '轻微' ? ct.txtHi : '#fff',
        formatter: (p: { value: number }) => (p.value > 0 ? String(p.value) : ''),
      },
      labelLayout: { hideOverlap: true },
    })),
  }

  return (
    <div>
      <Suspense
        fallback={<div className="flex items-center justify-center" style={{ height }}><Spinner size={16} /></div>}
      >
        <Echart option={option} height={height} className="w-full" />
      </Suspense>
      <div className="mt-1 text-[11px] text-txt-low">
        合计 <strong className="tabular-nums text-txt-hi">{defs.length}</strong> 项（= 速览缺陷数） · 组件 {rows.length - (rows.some((r) => r.name === '未指定') ? 1 : 0)} 个{rows.some((r) => r.name === '未指定') ? ' · 未指定 ' + rows.find((r) => r.name === '未指定')!.counts.reduce((a, b) => a + b, 0) + ' 项' : ''}
      </div>
    </div>
  )
}
