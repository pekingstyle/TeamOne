// ECharts 轻量封装（报表可视化批）：按需注册 tree-shaking + ResizeObserver 自适应
// 用法：<Echart option={opt} height={420} className="w-full" />
// 红线：业务侧只允许 import 本文件；任何地方直接 import 'echarts' 全量包都会击穿 tree-shaking。
// ⑥h 性能批：本模块经 React.lazy 动态加载（见 features/reports 各图表组件）——echarts 依赖
// 随本模块整体落入独立异步 chunk，首屏不再打包；cssColor 已迁至 features/reports/chart-shared.ts。
import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, SankeyChart, ScatterChart } from 'echarts/charts'
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'

// 按需注册：仅加载报表页用到的图型/组件
// （桑基 / 折线·面积 / 柱状 / 散点 + 直角坐标系 / 提示框 / 图例 / 标线 + Canvas 渲染器）
echarts.use([
  LineChart, SankeyChart, ScatterChart, BarChart,
  GridComponent, TooltipComponent, LegendComponent, MarkLineComponent,
  CanvasRenderer,
])

interface EchartProps {
  /** ECharts option（echarts/core 宽松类型，图型细节不做编译期校验） */
  option: EChartsCoreOption
  /** 画布高度（px），默认 300 */
  height?: number
  /** 附加类名（宽度自适应一般传 w-full） */
  className?: string
  /** 图表事件（可选，如 { click: fn }；handler 随引用变化自动换绑） */
  onEvents?: Record<string, (params: unknown) => void>
}

/** ECharts React 封装：init → setOption(notMerge) → ResizeObserver 自适应 → dispose */
export default function Echart({ option, height = 300, className = '', onEvents }: EchartProps) {
  const boxRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<ReturnType<typeof echarts.init> | null>(null)

  // 挂载期：初始化实例 + 容器尺寸监听（卡片宽度 / 窗口缩放自动 resize）；卸载时销毁防泄漏
  useEffect(() => {
    const box = boxRef.current
    if (!box) return
    const chart = echarts.init(box)
    chartRef.current = chart
    const ro = new ResizeObserver(() => chart.resize())
    ro.observe(box)
    return () => {
      ro.disconnect()
      chart.dispose()
      chartRef.current = null
    }
  }, [])

  // option 变化：notMerge 全量替换，避免旧 series 残留（如远程数据 ↔ 本地兜底的口径切换）
  useEffect(() => {
    chartRef.current?.setOption(option, { notMerge: true })
  }, [option])

  // 事件换绑：先整体 off 再逐个 on，避免旧 handler 残留重复触发
  useEffect(() => {
    const chart = chartRef.current
    if (!chart || !onEvents) return
    for (const [name, handler] of Object.entries(onEvents)) {
      chart.off(name)
      chart.on(name, handler as never)
    }
    return () => {
      for (const name of Object.keys(onEvents)) chart.off(name)
    }
  }, [onEvents])

  return <div ref={boxRef} className={className} style={{ height }} />
}
