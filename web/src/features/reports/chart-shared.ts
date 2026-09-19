// 报表图表共享基建（B3 · 组件拆分）：主题色包 / tooltip / 坐标轴样式 / 小工具
// 色值全部来自 CSS 变量（明/暗主题自适应）；业务侧 ECharts 一律经由 components/Echart.tsx
// ⑥h 性能批：cssColor 从 Echart.tsx 迁入本文件——切断 chart-shared → Echart 的静态依赖链，
// 否则 Echart（连带 echarts 全家桶）会因本模块的静态 import 被拖回首屏 chunk，lazy 分包失效。
import { useEffect, useState } from 'react'

/**
 * 读取主题 CSS 变量解析后的实际色值。
 * ECharts 走 Canvas 绘制，不识别 var(--x)，必须换成 computed 后的颜色；
 * 变量定义在 :root / [data-theme="dark"]，随当前主题返回对应色值。
 */
export function cssColor(varName: string, fallback = '#9ca0a8'): string {
  return getComputedStyle(document.documentElement).getPropertyValue(varName).trim() || fallback
}

/** ②~⑦ 小图统一高度（同一行卡片高度对齐，避免参差） */
export const CHART_H = 230

/** 周期计算毫秒日 */
export const DAY = 86400000

/** 工作项完成口径（store 本地兜底视图；done/closed 视为完成） */
export const isDone = (w: { status: string }) => w.status === 'done' || w.status === 'closed'

/** 图表主题色包（getComputedStyle 解析 CSS 变量实际色值；Canvas 不认 var(--x)） */
export function chartTheme() {
  const c = (n: string, f: string) => cssColor(n, f)
  return {
    txtHi: c('--color-txt-hi', '#1e1f24'),
    txtMid: c('--color-txt-mid', '#646464'),
    txtLow: c('--color-txt-low', '#9ca0a8'),
    line: c('--color-line', '#e8e8e8'),
    lineHi: c('--color-line-hi', '#d4d4d4'),
    ink700: c('--color-ink-700', '#f1f3f6'),
    card: c('--color-ink-800', '#ffffff'),
    blue: c('--color-cat-blue', '#0091ff'),
    green: c('--color-cat-green', '#22c55e'),
    yellow: c('--color-cat-yellow', '#ffd66b'),
    orange: c('--color-cat-orange', '#ff9500'),
    teal: c('--color-cat-teal', '#16c0a4'),
    purple: c('--color-cat-purple', '#7b68ee'),
    pink: c('--color-cat-pink', '#ec4899'),
    // R-2 缺陷严重度语义色（与缺陷中心 SEV_HEX 全站唯一语义对齐）
    bad: c('--color-bad', '#f94646'),
    info: c('--color-info', '#0091ff'),
  }
}
export type ChartTheme = ReturnType<typeof chartTheme>

/** tooltip 通用底色（贴卡片观感：卡片底色 + 边框色 + 正文色） */
export const tooltipOf = (ct: ChartTheme) => ({
  backgroundColor: ct.card,
  borderColor: ct.lineHi,
  borderWidth: 1,
  textStyle: { color: ct.txtHi, fontSize: 11 },
  extraCssText: 'box-shadow:0 4px 12px rgba(13,21,48,.08);border-radius:8px;',
})

/** 坐标轴文字统一样式 */
export const axisLbl = (ct: ChartTheme) => ({ color: ct.txtLow, fontSize: 10 })

/** ECharts tooltip formatter 参数最小切面（业务侧只依赖自定义 tip 字段，避免引 echarts 内部类型） */
export interface TipParam {
  name: string
  data?: { tip?: string }
}

/** 图表主题 Hook：初值 + 明/暗主题切换（data-theme 属性变化）时重取 CSS 变量色值，驱动 ECharts 换肤 */
export function useChartTheme(): ChartTheme {
  const [ct, setCt] = useState<ChartTheme>(chartTheme)
  useEffect(() => {
    const ob = new MutationObserver(() => setCt(chartTheme()))
    ob.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    return () => ob.disconnect()
  }, [])
  return ct
}

/** 解析 store 时间（"M-D HH:MM" | "YYYY-M-D HH:MM" | "YYYY-MM-DD"）→ 自然日时间戳 */
export function ts(s: string): number {
  const p = s.split(' ')[0].split('-').map(Number)
  return p.length === 3 ? new Date(p[0], p[1] - 1, p[2]).getTime() : new Date(new Date().getFullYear(), p[0] - 1, p[1]).getTime()
}

/** 时间戳 → "M-D" 短标签 */
export const md = (t: number) => { const d = new Date(t); return `${d.getMonth() + 1}-${d.getDate()}` }
