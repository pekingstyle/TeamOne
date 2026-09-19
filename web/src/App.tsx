import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import {
  AlertTriangle, Bell, CheckCheck, FileText, LogOut, MessagesSquare, Moon,
  Package, Settings, Sun,
} from 'lucide-react'
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query'
import { ADMIN_ONLY_PAGES, navGroups, type Nav } from './nav'
import type { PageId } from './data/types'
import {
  CURRENT_USER_ID, setTheme, useStore, userById,
} from './data/store'
import { Avatar, Pill } from './components/ui'
import { AuthProvider, LoginView, useAuth } from './api/AuthContext'
import {
  currentViewedConv, notificationsApi, useNotifications, useSidebarBadges,
  useUnreadPush, type RemoteMergeRequest, type RemoteNotification,
} from './api/queries'
import { teamOneWs } from './api/ws'
import DashboardPage from './features/dashboard/DashboardPage'
import GoalsPage from './features/goals/GoalsPage'
import RequirementsPage from './features/requirements/RequirementsPage'
import RoadmapPage from './features/roadmap/RoadmapPage'
import TasksPage from './features/tasks/TasksPage'
import DefectsPage from './features/defects/DefectsPage'
import DeliveryPage from './features/delivery/DeliveryPage'
import ImPage from './features/im/ImPage'
import TeamPage from './features/team/TeamPage'
import ConflictsPage from './features/conflicts/ConflictsPage'
import ReportsPage from './features/reports/ReportsPage'
import ReposPage from './features/repos/ReposPage'
import RepoDetailPage from './features/repos/RepoDetailPage'
import ReviewPage from './features/review/ReviewPage'
import MrDetailPage from './features/review/MrDetailPage'
import PipelinesPage from './features/cicd/PipelinesPage'
import PipelineDetailPage from './features/cicd/PipelineDetailPage'
import SettingsPage from './features/settings/SettingsPage'
import PersonalSettingsDialog, { resolveTheme } from './features/settings/PersonalSettingsDialog'

/**
 * 当前用户展示投影（⑥h 任务①）：真实登录用户（useAuth().user）优先，
 * store 原型用户（u1 陈墨）仅作未登录兜底——修复侧栏资料卡 / 用户菜单 / 顶栏头像
 * 恒显「陈墨」的假用户 bug。个人设置保存昵称后 AuthContext.refreshUser 更新 user，
 * 这里随之即时更新（无需重新登录）。纯计算不做 memo：每次 render 重读，store 原地改同样可见。
 */
function useDisplayUser() {
  const { user } = useAuth()
  const fb = userById(CURRENT_USER_ID)
  if (user) {
    return {
      id: user.id,
      name: user.displayName || user.username,
      title: user.title || `@${user.username}`,
      // 远端投影无部门/邮箱字段：明细行改展示账号名；容量缺省展示「—」
      account: `@${user.username}`,
      capacityHours: user.dailyCapacityHours,
      roleZh: user.platformRole === 'OWNER' ? '超级管理员' : user.platformRole === 'ADMIN' ? '管理员' : '成员',
    }
  }
  return {
    id: CURRENT_USER_ID,
    name: fb?.name ?? '',
    title: fb?.title ?? '',
    account: fb?.email ?? '',
    capacityHours: fb?.dailyCapacityHours,
    roleZh: fb?.platformRole === 'super_admin' ? '超级管理员' : fb?.platformRole === 'org_admin' ? '组织管理员' : '成员',
  }
}

/**
 * 当前生效主题订阅（setTheme 维护 documentElement[data-theme]；「跟随系统」解析后同样落在这里）。
 * useSyncExternalStore 订阅属性变化：渲染期不直接读 DOM（purity），切换主题时图标即时翻转。
 */
const themeListeners = new Set<() => void>()
const themeObserver = new MutationObserver(() => themeListeners.forEach((l) => l()))
function subscribeTheme(onChange: () => void): () => void {
  themeListeners.add(onChange)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  return () => { themeListeners.delete(onChange) }
}
function useDarkTheme(): boolean {
  return useSyncExternalStore(subscribeTheme, () => document.documentElement.dataset.theme === 'dark')
}

/** 头像用户菜单：从头像点击扩展出的个人信息维护入口 */
function UserMenu({ compact = false }: { compact?: boolean }) {
  useStore()
  const { logout } = useAuth()
  const [open, setOpen] = useState(false)
  // R-11：个人设置真弹窗（昵称/主题/改密），替换原「原型演示」死按钮
  const [settingsOpen, setSettingsOpen] = useState(false)
  // ⑥h 任务①：资料卡读真实登录用户，不再恒显 store 原型用户「陈墨」
  const me = useDisplayUser()
  const dark = useDarkTheme()
  if (!me.name) return null
  const toggleTheme = () => setTheme(dark ? 'light' : 'dark')
  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={`flex cursor-pointer items-center gap-2 rounded-md hover:bg-ink-700 ${compact ? 'p-0.5' : 'p-1'}`}
        title="个人信息与设置"
      >
        <Avatar userId={me.id} size={compact ? 30 : 26} />
        {!compact && <span className="text-sm text-txt-mid">{me.name}</span>}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-50" onClick={() => setOpen(false)} />
          <div className="absolute right-0 top-full z-50 mt-2 w-72 rounded-card border border-line bg-canvas p-4 shadow-xl">
            <div className="flex items-center gap-3">
              <Avatar userId={me.id} size={44} />
              <div className="min-w-0 flex-1">
                <div className="text-sm font-bold text-txt-hi">{me.name}</div>
                <div className="truncate text-xs text-txt-mid">{me.title}</div>
              </div>
              <Pill tone={me.roleZh === '成员' ? 'neutral' : 'brand'}>{me.roleZh}</Pill>
            </div>
            <div className="mt-3 space-y-1 border-t border-line pt-3 text-xs text-txt-mid">
              <div className="flex justify-between"><span>账号</span><span className="font-mono text-txt-hi">{me.account}</span></div>
              <div className="flex justify-between"><span>每日容量</span><span className="tabular-nums text-txt-hi">{me.capacityHours != null ? `${me.capacityHours}h（冲突检测口径）` : '—'}</span></div>
              <div className="flex justify-between"><span>当前主题</span><span className="text-txt-hi">{dark ? '深色' : '浅色'}</span></div>
            </div>
            <div className="mt-3 space-y-1 border-t border-line pt-3">
              <button type="button" onClick={toggleTheme} className="flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-txt-mid hover:bg-ink-700 hover:text-txt-hi">
                {dark ? <Sun size={14} /> : <Moon size={14} />} 切换{dark ? '浅色' : '深色'}主题
              </button>
              {/* R-11：打开个人设置弹窗（昵称/主题偏好/修改密码，真实 API） */}
              <button
                type="button"
                onClick={() => { setOpen(false); setSettingsOpen(true) }}
                className="flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-txt-mid hover:bg-ink-700 hover:text-txt-hi"
              >
                <Settings size={14} /> 个人设置
              </button>
              <button
                type="button"
                onClick={() => { setOpen(false); void logout() }}
                className="flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-bad-deep hover:bg-bad-bg"
              >
                <LogOut size={14} /> 退出登录
              </button>
            </div>
          </div>
        </>
      )}
      <PersonalSettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </div>
  )
}

// ==================== T-4 通知中心：铃铛面板 + 紧急弹窗（产品补全） ====================

/** 通知 kind → 展示文案 / 图标 / 跳转页（payload 带 id 字段则下钻，否则停留列表页） */
const NOTIF_VIEW: Record<string, { icon: typeof Bell; title: string; page?: PageId; idField?: string }> = {
  'requirement.review': { icon: FileText, title: '评审邀请', page: 'requirements', idField: 'requirementId' },
  'release.gate': { icon: Package, title: '门禁解除', page: 'delivery' },
  'conflict.red': { icon: AlertTriangle, title: '红色冲突预警', page: 'conflicts' },
  'im.mention': { icon: MessagesSquare, title: 'IM @提醒', page: 'im', idField: 'conversationId' },
  unread: { icon: MessagesSquare, title: '未读消息', page: 'im' },
}

function notifTitle(kind: string): string {
  return NOTIF_VIEW[kind]?.title ?? '通知'
}

// ---------------- ⑥h 任务④：动态/活动 mr 目标死路收口 ----------------

/** store 原型 MR id 形态（活动流遗留的 mr1~mr6）：真实评审主键为服务端 id，不会命中此形态 */
const MOCK_MR_ID = /^mr\d+$/

/**
 * 在真实 MR 缓存（useMergeRequests 的 ['mrs'] 系列查询）中按 id 或 !mrNumber 匹配：
 * 仅对原型形态 id 做前置拦截——返回命中的真实 MR id（QA 复审 MUST-FIX：命中后必须以
 * 真实 id 跳转，否则详情页拿原型 id 请求 /mrs/mr3 仍是死路）；未命中返回 null。
 * 真实 id（含新建 MR 尚未回填列表缓存的场景）不经此函数，由详情页 404 空态兜底。
 */
function realMrId(queryClient: QueryClient, id: string): string | null {
  const lists = queryClient.getQueriesData<{ items: RemoteMergeRequest[] }>({ queryKey: ['mrs'] })
  for (const [, data] of lists) {
    const hit = (data?.items ?? []).find((m) => m.id === id || String(m.number) === id.replace(/^mr/, ''))
    if (hit) return hit.id
  }
  return null
}

/** 通知摘要行（payload 事实投影；缺字段安全兜底） */
function notifDetail(n: RemoteNotification): string {
  const p = (n.payload ?? {}) as Record<string, unknown>
  if (n.kind === 'requirement.review') return `${String(p.key ?? '')} ${String(p.title ?? '')}`.trim() || '你有新的评审邀请'
  if (n.kind === 'release.gate') return '发布门禁已解除，可以继续推进'
  if (n.kind === 'conflict.red') return `你有 ${String(p.redCount ?? 1)} 条红色冲突待处理`
  // V16 R-10e：IM @提醒（payload 由后端 ImFastFanout.buildNotifyPayload 统一产出）
  if (n.kind === 'im.mention') {
    const who = String(p.senderName ?? '有人')
    const preview = String(p.preview ?? '').trim()
    return `${who} 在会话中提到了你${preview ? `：${preview}` : ''}`
  }
  return '点击查看详情'
}

/** 相对时间（非法输入 → '—'，不产出 NaN） */
function relTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return '—'
  const s = Math.floor((Date.now() - d.getTime()) / 1000)
  if (s < 60) return '刚刚'
  if (s < 3600) return `${Math.floor(s / 60)}分钟前`
  if (s < 86400) return `${Math.floor(s / 3600)}小时前`
  return `${Math.floor(s / 86400)}天前`
}

interface UrgentToast {
  id: number
  title: string
  body: string
  until: number
}

/**
 * 紧急提醒统一入口（T-4③）：
 * - 页面在前台 → 应用内 toast 4s；
 * - 页面在后台（document.hidden）→ 浏览器原生 Notification（已授权时），被拒/不可用 → 应用内 toast 常驻 8s。
 */
function fireUrgent(title: string, body: string, pushToast: (t: Omit<UrgentToast, 'id' | 'until'>, durationMs: number) => void): void {
  // perf：同 kind 5s 节流——消息刷屏/冲突批量重算时避免 toast 与原生通知风暴（错过的信息在通知中心可追）
  const key = title
  const now = Date.now()
  const last = urgentThrottle.get(key) ?? 0
  if (now - last < 5000) return
  urgentThrottle.set(key, now)
  if (document.hidden) {
    if ('Notification' in window && Notification.permission === 'granted') {
      try {
        const native = new Notification('TeamOne', { body: `${title} · ${body}` })
        native.onclick = () => { window.focus(); native.close() }
        return
      } catch { /* 构造失败（如移动端限制）→ 走降级 */ }
    }
    pushToast({ title, body }, 8000)
    return
  }
  pushToast({ title, body }, 4000)
}

/** 紧急提醒节流表（title → 上次触发时间戳；模块级，重挂载不清零） */
const urgentThrottle = new Map<string, number>()

/**
 * useUrgentAlerts：订阅 user:{uid}（与 useUnreadPush 同频道，服务端重复订阅幂等）：
 * - conflict.detected 且 redCount>0 → 紧急提醒 + 失效冲突/通知查询；
 * - unread（IM 新消息扇出）且会话非当前查看 → 紧急提醒。
 */
function useUrgentAlerts(userId: string | undefined, pushToast: (t: Omit<UrgentToast, 'id' | 'until'>, durationMs: number) => void): void {
  const queryClient = useQueryClient()
  useEffect(() => {
    if (!userId) return
    let disposed = false
    const offs: Array<() => void> = []
    void teamOneWs
      .connect()
      .then(() => {
        if (disposed) return
        offs.push(teamOneWs.onEvent('conflict.detected', (p) => {
          const redCount = Number(p.redCount ?? 0)
          if (redCount <= 0) return
          void queryClient.invalidateQueries({ queryKey: ['conflicts'] })
          void queryClient.invalidateQueries({ queryKey: ['notifications'] })
          fireUrgent('冲突预警', `你有 ${redCount} 条红色冲突待处理`, pushToast)
        }))
        offs.push(teamOneWs.onEvent('unread', (p) => {
          const convId = String(p.conversationId ?? '')
          if (!convId || convId === currentViewedConv()) return // 当前查看中的会话不弹
          fireUrgent('新消息', '你有会话收到新消息，请前往即时沟通查看', pushToast)
        }))
        // V16 R-10e：IM @提醒 notify 帧（user:{uid}）→ 铃铛角标失效 + 紧急提醒（AC：3s 内触达）
        offs.push(teamOneWs.onEvent('notify', (p) => {
          void queryClient.invalidateQueries({ queryKey: ['notifications'] })
          const who = String(p.senderName ?? '有人')
          const preview = String(p.preview ?? '').trim()
          fireUrgent('新提醒', `${who} 在会话中提到了你${preview ? `：${preview}` : ''}`, pushToast)
        }))
        return teamOneWs.sub(`user:${userId}`)
      })
      .catch(() => { /* WS 不可达时静默（查询失效兜底仍在） */ })
    return () => {
      disposed = true
      offs.forEach((off) => off())
    }
  }, [userId, queryClient, pushToast])
}

/** 通知中心铃铛：角标=未读数；下拉面板（360px）列出未读通知，支持单条已读跳转与全部已读 */
function NotificationBell({ nav }: { nav: Nav }) {
  const queryClient = useQueryClient()
  const { data } = useNotifications()
  const [open, setOpen] = useState(false)
  const [marking, setMarking] = useState(false)
  const unreadCount = data?.unreadCount ?? 0
  const items = useMemo(() => data?.items ?? [], [data])

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['notifications'] })

  const markAll = async () => {
    setMarking(true)
    try {
      await notificationsApi.markAllRead()
      await queryClient.invalidateQueries({ queryKey: ['notifications'] })
    } finally {
      setMarking(false)
    }
  }

  const openItem = (n: RemoteNotification) => {
    void notificationsApi.markRead([n.id]).then(refresh).catch(() => { /* 已读失败不打断跳转 */ })
    setOpen(false)
    const v = NOTIF_VIEW[n.kind]
    if (v?.page) {
      const relId = v.idField ? String((n.payload ?? {})[v.idField] ?? '') : ''
      nav.go(v.page, relId || undefined)
    }
  }

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="relative cursor-pointer rounded-md p-2 text-txt-mid hover:bg-ink-700 hover:text-txt-hi"
        title="通知中心"
      >
        <Bell size={16} />
        {unreadCount > 0 && (
          <span className="absolute top-0.5 right-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-bad px-1 text-[10px] font-bold leading-none text-white">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-50" onClick={() => setOpen(false)} />
          <div className="absolute right-0 top-full z-50 mt-2 w-[360px] overflow-hidden rounded-card border border-line bg-canvas shadow-xl">
            <div className="flex items-center justify-between border-b border-line px-4 py-2.5">
              <span className="text-sm font-bold text-txt-hi">通知中心</span>
              <span className="text-xs text-txt-low">未读 {unreadCount}</span>
            </div>
            <ul className="max-h-96 overflow-y-auto">
              {items.length === 0 && (
                <li className="px-4 py-10 text-center text-xs text-txt-low">暂无未读通知</li>
              )}
              {items.map((n) => {
                const Icon = NOTIF_VIEW[n.kind]?.icon ?? Bell
                return (
                  <li key={n.id}>
                    <button
                      type="button"
                      onClick={() => openItem(n)}
                      className="flex w-full cursor-pointer items-start gap-2.5 px-4 py-2.5 text-left transition-colors hover:bg-ink-700"
                    >
                      <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand-bg text-brand-deep">
                        <Icon size={14} />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-medium text-txt-hi">{notifTitle(n.kind)}</span>
                        <span className="block truncate text-xs text-txt-mid">{notifDetail(n)}</span>
                      </span>
                      <span className="shrink-0 pt-0.5 text-[11px] text-txt-low">{relTime(n.createdAt)}</span>
                    </button>
                  </li>
                )
              })}
            </ul>
            <div className="border-t border-line px-4 py-2">
              <button
                type="button"
                disabled={marking || unreadCount === 0}
                onClick={() => void markAll()}
                className="flex w-full cursor-pointer items-center justify-center gap-1.5 rounded-md py-1.5 text-xs font-semibold text-brand transition-colors hover:bg-brand-bg disabled:cursor-not-allowed disabled:opacity-40"
              >
                <CheckCheck size={13} /> 全部已读
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

/** 应用内右上角 toast 栈（紧急提醒的应用内形态；到时自动消失，可手动关闭） */
function ToastStack({ toasts, dismiss }: { toasts: UrgentToast[]; dismiss: (id: number) => void }) {
  if (toasts.length === 0) return null
  return (
    <div className="pointer-events-none fixed top-16 right-6 z-[70] flex w-80 flex-col gap-2">
      {toasts.map((t) => (
        <div
          key={t.id}
          className={`pointer-events-auto flex items-start gap-2.5 rounded-card border bg-card p-3 shadow-xl ${
            t.title === '冲突预警' ? 'border-bad/50' : 'border-line'
          }`}
          role="status"
        >
          <span className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full ${
            t.title === '冲突预警' ? 'bg-bad-bg text-bad-deep' : 'bg-brand-bg text-brand-deep'
          }`}>
            {t.title === '冲突预警' ? <AlertTriangle size={14} /> : t.title === '新消息' ? <MessagesSquare size={14} /> : <Bell size={14} />}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-bold text-txt-hi">{t.title}</span>
            <span className="block text-xs leading-5 text-txt-mid">{t.body}</span>
          </span>
          <button
            type="button"
            onClick={() => dismiss(t.id)}
            className="cursor-pointer rounded p-0.5 text-txt-low hover:text-txt-hi"
            aria-label="关闭提醒"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  )
}

function Sidebar({ page, nav }: { page: PageId; nav: Nav }) {
  const { user } = useAuth()
  // ⑥h 任务①：底部资料卡读真实登录用户（useDisplayUser），store 原型用户仅未登录兜底
  const me = useDisplayUser()
  const dark = useDarkTheme()
  const childOf: Record<string, PageId> = { repo: 'repos', mr: 'review', pipeline: 'pipelines', topics: 'im' }
  // 角标真实化：全部来自真实 API（useSidebarBadges 内部 staleTime 30s；im 复用 conversations 缓存）
  const badges = useSidebarBadges()
  const badgeOf: Partial<Record<PageId, number>> = {
    conflicts: badges.conflicts,
    defects: badges.defects,
    requirements: badges.requirements,
    im: badges.im,
    review: badges.review,
  }
  // R-12：系统设置仅管理员可见——按登录态角色过滤（后端 403 为纵深防御，此处管入口）
  const isAdmin = user?.platformRole === 'OWNER' || user?.platformRole === 'ADMIN'
  const visibleGroups = navGroups.map((g) => ({
    ...g,
    items: g.items.filter((i) => !ADMIN_ONLY_PAGES.includes(i.id) || isAdmin),
  }))
  return (
    <aside className="sticky top-0 flex h-screen w-56 shrink-0 flex-col border-r border-line bg-sidebar">
      <div className="flex items-center gap-2.5 px-5 pt-5 pb-4">
        <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-gradient-to-br from-brand to-cat-teal text-sm font-black text-white">T1</div>
        <div>
          <div className="text-[15px] font-bold tracking-wide text-txt-hi">TeamOne</div>
          <div className="text-[11px] text-txt-low">一站式研发协同平台</div>
        </div>
      </div>

      {/* 签名元素：Git 主线轨道（overflow-x-hidden 修复横向溢出） */}
      <nav className="relative flex-1 overflow-y-auto overflow-x-hidden px-4 pt-2 pb-4">
        <span className="pointer-events-none absolute top-3 bottom-6 left-[19px] w-px bg-line-hi/60" aria-hidden />
        {visibleGroups.map((group) => (
          <div key={group.title} className="mb-4">
            <div className="mb-1 pl-6 text-[11px] font-semibold tracking-widest text-txt-low">{group.title}</div>
            <ul className="space-y-0.5">
              {group.items.map((item) => {
                const active = page === item.id || childOf[page] === item.id
                const badge = badgeOf[item.id] ?? 0
                return (
                  <li key={item.id} className="relative">
                    <span
                      aria-hidden
                      className={`absolute top-1/2 left-[4px] z-10 h-2 w-2 -translate-y-1/2 rounded-full border-2 transition-colors ${
                        active ? 'border-brand bg-brand shadow-[0_0_0_3px_var(--color-brand-bg)]' : 'border-line-hi bg-page'
                      }`}
                    />
                    <button
                      type="button"
                      onClick={() => nav.go(item.id)}
                      className={`flex w-full cursor-pointer items-center gap-2.5 rounded-input py-[7px] pl-7 pr-2.5 text-left text-sm transition-colors ${
                        active ? 'bg-brand-bg font-semibold text-brand-deep' : 'text-txt-mid hover:bg-ink-700 hover:text-txt-hi'
                      }`}
                    >
                      <item.icon size={16} className="shrink-0" />
                      <span className="min-w-0 flex-1">{item.label}</span>
                      {badge > 0 && (
                        <span className="rounded-full bg-bad px-1.5 py-px text-[11px] font-bold text-white">{badge}</span>
                      )}
                    </button>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </nav>

      <div className="flex items-center gap-2.5 border-t border-line px-4 py-3.5">
        <UserMenu compact />
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium text-txt-hi">{me.name}</div>
          <div className="truncate text-[11px] text-txt-low">{me.title}</div>
        </div>
        <button
          type="button"
          title="切换深/浅主题"
          onClick={() => setTheme(dark ? 'light' : 'dark')}
          className="cursor-pointer rounded-md p-1.5 text-txt-mid hover:bg-ink-700 hover:text-txt-hi"
        >
          {dark ? <Sun size={15} /> : <Moon size={15} />}
        </button>
      </div>
    </aside>
  )
}

const pageTitles: Record<PageId, string> = {
  dashboard: '工作台', reports: '统计报表', conflicts: '冲突中心',
  goals: '战略目标', requirements: '需求管理', roadmap: '产品 RoadMap', tasks: '迭代与任务', defects: '缺陷中心', delivery: '版本与发布',
  topics: '即时沟通 · 话题', im: '即时沟通', team: '团队与权限',
  repos: '代码仓库', repo: '代码仓库', review: '代码评审', mr: '合并请求', pipelines: 'CI/CD 流水线', pipeline: '流水线详情',
  settings: '系统设置',
}

function App() {
  useStore()
  const { user } = useAuth()
  const queryClientGlobal = useQueryClient()
  // P1-3 未读实时推送：登录后订阅 user:{uid}，unread 帧 → invalidate conversations（徽标实时）
  useUnreadPush(user?.id)

  // IM 实时性批：WS 断线重连补偿——断线窗口内错失的 message/unread/notify 帧不可恢复，
  // 重订阅完成后失效全部查询重拉（water-tight），用户无需刷新页面即可回到最新状态
  useEffect(() => {
    if (!user) return
    return teamOneWs.onReconnect(() => {
      void queryClientGlobal.invalidateQueries()
    })
  }, [user, queryClientGlobal])

  // R-11：登录/刷新后主题偏好应用（后端值优先；system 解析为系统当前配色）
  const themePref = user?.themePreference ?? null
  useEffect(() => {
    if (themePref) setTheme(resolveTheme(themePref))
  }, [themePref])

  // ---- T-4 紧急弹窗：toast 栈 + user:{uid} 帧处理（conflict.detected / unread） ----
  const [toasts, setToasts] = useState<UrgentToast[]>([])
  const toastSeq = useRef(0)
  const pushToast = useCallback((t: Omit<UrgentToast, 'id' | 'until'>, durationMs: number) => {
    const id = ++toastSeq.current
    const until = Date.now() + durationMs
    setToasts((prev) => [...prev.slice(-3), { id, until, ...t }])
    setTimeout(() => setToasts((prev) => prev.filter((x) => x.id !== id)), durationMs)
  }, [])
  const dismissToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((x) => x.id !== id))
  }, [])
  useUrgentAlerts(user?.id, pushToast)

  // 浏览器通知权限：挂载时请求一次（用户拒绝/允许后浏览器按源记忆，不会重复骚扰）
  useEffect(() => {
    if ('Notification' in window && Notification.permission === 'default') {
      void Notification.requestPermission()
    }
  }, [])

  const [page, setPage] = useState<PageId>('dashboard')
  const [id, setId] = useState<string | undefined>(undefined)
  const queryClient = useQueryClient()
  const nav = useMemo<Nav>(() => ({
    go: (p, i) => {
      // ⑥h 任务④：mr 目标收口——store 活动流原型 id（mr1~mr6）在真实评审中不存在，
      // 直接跳转会落「未找到该评审」死路。先查真实 MR 缓存，命中（按 id 或 !number）改用
      // 真实 id 跳转（QA 复审 MUST-FIX）；未命中 toast 提示且不跳转。非原型形态 id 不受影响。
      if (p === 'mr' && i && MOCK_MR_ID.test(i)) {
        const realId = realMrId(queryClient, i)
        if (!realId) {
          pushToast({ title: '评审不存在', body: '该动态对应的评审已不存在' }, 4000)
          return
        }
        i = realId
      }
      setPage(p); setId(i)
    },
  }), [queryClient, pushToast])
  const mainRef = useRef<HTMLElement>(null)
  useEffect(() => { mainRef.current?.scrollTo(0, 0) }, [page, id])

  let content = <DashboardPage nav={nav} />
  switch (page) {
    case 'reports': content = <ReportsPage nav={nav} />; break
    case 'conflicts': content = <ConflictsPage nav={nav} />; break
    case 'goals': content = <GoalsPage nav={nav} id={id} />; break
    case 'requirements': content = <RequirementsPage nav={nav} id={id} />; break
    case 'roadmap': content = <RoadmapPage nav={nav} id={id} />; break
    case 'tasks': content = <TasksPage nav={nav} id={id} />; break
    case 'defects': content = <DefectsPage nav={nav} id={id} />; break
    case 'delivery': content = <DeliveryPage nav={nav} id={id} />; break
    case 'topics': content = <ImPage nav={nav} id={id} />; break
    case 'im': content = <ImPage nav={nav} id={id} />; break
    case 'team': content = <TeamPage nav={nav} />; break
    case 'repos': content = <ReposPage nav={nav} />; break
    case 'repo': content = <RepoDetailPage nav={nav} id={id} />; break
    case 'review': content = <ReviewPage nav={nav} />; break
    case 'mr': content = <MrDetailPage nav={nav} id={id} />; break
    case 'pipelines': content = <PipelinesPage nav={nav} />; break
    case 'pipeline': content = <PipelineDetailPage nav={nav} id={id} />; break
    case 'settings': content = <SettingsPage nav={nav} />; break
  }

  return (
    // h-screen 而非 h-full：不依赖 #root 高度链（祖先高度断裂时 h-full 会被内容撑开，
    // 导致整页文档级滚动 + overflow-hidden 祖先使侧栏 sticky 失效——侧栏浮空/页头滚没的根因）
    <div className="flex h-screen overflow-hidden">
      <Sidebar page={page} nav={nav} />
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <header className="flex h-13 shrink-0 items-center gap-3 border-b border-line bg-page px-6">
          <span className="text-sm font-semibold text-txt-hi">{pageTitles[page]}</span>
          <span className="text-xs text-txt-low">TeamOne · 平台本体研发</span>
          <div className="flex-1" />
          {/* M3 搜索：顶栏搜索框延后（P2-7 nginx 收口后统一接 U4 代码/工作项检索），保留布局占位 */}
          <div className="hidden h-8 w-64 items-center gap-2 rounded-input border border-line bg-card px-2.5 text-txt-low" aria-hidden />
          <NotificationBell nav={nav} />
          <div className="h-5 w-px bg-line" />
          <UserMenu />
        </header>
        <main ref={mainRef} className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-6xl px-6 py-6">{content}</div>
        </main>
      </div>
      <ToastStack toasts={toasts} dismiss={dismissToast} />
    </div>
  )
}

// ---------------- W4 鉴权壳层：未登录只渲染 LoginView，其余 13 页不挂载 ----------------
const queryClient = new QueryClient({
  defaultOptions: {
    // perf：全局 refetchOnWindowFocus=false——窗口每次聚焦会让全部活跃查询（5 角标+会话+消息+通知+缺陷…）
    // 齐发重拉，是最大的无谓负载源；时效敏感的 notifications 查询单独开启 focus 刷新
    queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 15_000 },
  },
})

function AuthGate() {
  const { user, initializing } = useAuth()
  if (initializing) {
    return (
      <div className="flex h-full items-center justify-center bg-page">
        <div className="flex items-center gap-2 text-sm text-txt-low">
          <span className="h-2 w-2 animate-pulse rounded-full bg-brand" />恢复登录态…
        </div>
      </div>
    )
  }
  if (!user) return <LoginView />
  return <App />
}

export default function AppRoot() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <AuthGate />
      </AuthProvider>
    </QueryClientProvider>
  )
}
