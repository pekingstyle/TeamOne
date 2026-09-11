// TeamOne 原型 · 共享 UI 基础组件
import type { ReactNode } from 'react'
import { userById } from '../data/store'
import type { RunStatus } from '../data/types'

/** 圆形文字头像 */
export function Avatar({ userId, size = 28 }: { userId: string; size?: number }) {
  const u = userById(userId)
  if (!u) return <span className="inline-flex items-center justify-center rounded-full bg-ink-700 text-txt-mid" style={{ width: size, height: size, fontSize: size * 0.42 }}>?</span>
  return (
    <span
      title={`${u.name} · ${u.title}`}
      className="relative inline-flex shrink-0 items-center justify-center rounded-full font-semibold text-white select-none"
      style={{ width: size, height: size, fontSize: size * 0.42, background: u.color }}
    >
      {u.name.slice(-1)}
      {u.online && (
        <span className="absolute -right-0 -bottom-0 rounded-full border-2 border-canvas bg-ok" style={{ width: size * 0.28, height: size * 0.28 }} />
      )}
    </span>
  )
}

/** 状态胶囊（Fancy 风格：浅色底 + 深色字 + 全圆角） */
export function Pill({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'brand' | 'ok' | 'warn' | 'bad' | 'info' | 'purple' | 'orange' | 'pink' | 'teal' }) {
  const tones: Record<string, string> = {
    neutral: 'bg-ink-700 text-txt-mid',
    brand: 'bg-brand-bg text-brand-deep',
    purple: 'bg-brand-bg text-cat-purple',
    ok: 'bg-ok-bg text-ok-deep',
    warn: 'bg-warn-bg text-warn-deep',
    bad: 'bg-bad-bg text-bad-deep',
    info: 'bg-info-bg text-info',
    orange: 'bg-warn-bg text-cat-orange',
    pink: 'bg-bad-bg text-cat-pink',
    teal: 'bg-info-bg text-cat-teal',
  }
  return <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs leading-4 font-semibold ${tones[tone]}`}>{children}</span>
}

/** 进度环（目标/版本聚合进度） */
export function ProgressRing({ value, size = 52, stroke = 6, color = 'var(--color-brand)' }: { value: number; size?: number; stroke?: number; color?: string }) {
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const off = c * (1 - Math.min(100, Math.max(0, value)) / 100)
  return (
    <svg width={size} height={size} className="shrink-0 -rotate-90">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--color-ink-700)" strokeWidth={stroke} />
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeDasharray={c} strokeDashoffset={off} strokeLinecap="round" className="transition-all duration-500" />
      <text x={size / 2} y={size / 2} textAnchor="middle" dominantBaseline="central" className="rotate-90 fill-txt-hi text-[13px] font-bold" style={{ transformOrigin: 'center' }}>{Math.round(value)}%</text>
    </svg>
  )
}

/** 负载热力格（绿/黄/红 三档） */
export function HeatCell({ ratio, title }: { ratio: number; title?: string }) {
  const tone = ratio > 1 ? 'bg-bad' : ratio >= 0.85 ? 'bg-warn' : ratio > 0 ? 'bg-ok' : 'bg-ink-700'
  return <span title={title} className={`block h-5 w-full rounded-sm ${tone} ${ratio > 1 ? 'opacity-90' : 'opacity-70'}`} />
}

/** 通用徽章 */
export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'brand' | 'ok' | 'warn' | 'bad' | 'vio' }) {
  const tones: Record<string, string> = {
    neutral: 'bg-ink-700 text-txt-mid',
    brand: 'bg-brand/12 text-brand',
    ok: 'bg-ok/12 text-ok',
    warn: 'bg-warn/12 text-warn',
    bad: 'bg-bad/12 text-bad',
    vio: 'bg-vio/12 text-vio',
  }
  return <span className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs leading-4 font-medium ${tones[tone]}`}>{children}</span>
}

/** 状态点（流水线/检查项通用） */
export function StatusDot({ status, size = 8 }: { status: RunStatus; size?: number }) {
  const map: Record<RunStatus, string> = {
    passed: 'bg-ok',
    failed: 'bg-bad',
    running: 'bg-brand animate-pulse',
    pending: 'bg-txt-low/40',
    skipped: 'bg-txt-low/30',
    canceled: 'bg-txt-low/40',
  }
  return <span className={`inline-block shrink-0 rounded-full ${map[status]}`} style={{ width: size, height: size }} />
}

export const runStatusText: Record<RunStatus, string> = {
  passed: '成功', failed: '失败', running: '运行中', pending: '等待', skipped: '跳过', canceled: '已取消',
}

/** 面板卡片 */
export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`rounded-card border border-line bg-ink-850 ${className}`}>{children}</div>
}

export function CardHeader({ title, extra }: { title: ReactNode; extra?: ReactNode }) {
  return (
    <div className="flex items-center justify-between border-b border-line px-4 py-3">
      <h3 className="text-sm font-semibold text-txt-hi">{title}</h3>
      {extra}
    </div>
  )
}

/** 主按钮 / 次按钮 */
export function Btn({ children, onClick, variant = 'default', disabled, className = '' }: {
  children: ReactNode
  onClick?: () => void
  variant?: 'primary' | 'default' | 'ghost' | 'danger'
  disabled?: boolean
  className?: string
}) {
  const styles: Record<string, string> = {
    primary: 'bg-brand text-white hover:bg-brand-deep font-semibold',
    default: 'bg-canvas text-txt-hi hover:bg-ink-700 border border-line',
    ghost: 'text-txt-mid hover:text-txt-hi hover:bg-ink-700',
    danger: 'bg-bad-bg text-bad-deep hover:bg-bad/20 border border-bad/30',
  }
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex cursor-pointer items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors disabled:cursor-not-allowed disabled:opacity-45 ${styles[variant]} ${className}`}
    >
      {children}
    </button>
  )
}

/** 页头 */
export function PageHeader({ title, desc, actions }: { title: ReactNode; desc?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-bold tracking-wide text-txt-hi">{title}</h1>
        {desc && <p className="mt-1 text-sm text-txt-mid">{desc}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}

/** 进度条 */
export function Bar({ value, tone = 'brand', className = '' }: { value: number; tone?: 'brand' | 'ok' | 'warn' | 'bad'; className?: string }) {
  const colors: Record<string, string> = { brand: 'bg-brand', ok: 'bg-ok', warn: 'bg-warn', bad: 'bg-bad' }
  return (
    <div className={`h-1.5 overflow-hidden rounded-full bg-ink-700 ${className}`}>
      <div className={`h-full rounded-full transition-all duration-500 ${colors[tone]}`} style={{ width: `${Math.min(100, Math.max(0, value))}%` }} />
    </div>
  )
}

/** 空状态 */
export function Empty({ text }: { text: string }) {
  return <div className="flex flex-col items-center justify-center gap-2 py-16 text-txt-low"><span className="text-3xl">🗂️</span><span className="text-sm">{text}</span></div>
}

/** 优先级徽章 */
export function PriorityBadge({ p }: { p: 'P0' | 'P1' | 'P2' | 'P3' }) {
  const tone = p === 'P0' ? 'bad' : p === 'P1' ? 'warn' : 'neutral'
  return <Badge tone={tone as 'bad' | 'warn' | 'neutral'}>{p}</Badge>
}
