import { useEffect, useMemo, useRef, useState } from 'react'
import { Bell, LogOut, Moon, Search, Settings, Sun } from 'lucide-react'
import { navGroups, type Nav } from './nav'
import type { PageId } from './data/types'
import {
  CURRENT_USER_ID, channels, departmentById, setTheme, useStore, userById,
} from './data/store'
import { Avatar, Pill } from './components/ui'
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

/** 头像用户菜单：从头像点击扩展出的个人信息维护入口 */
function UserMenu({ compact = false }: { compact?: boolean }) {
  useStore()
  const [open, setOpen] = useState(false)
  const me = userById(CURRENT_USER_ID)
  if (!me) return null
  const dept = departmentById(me.departmentId)
  const roleZh = me.platformRole === 'super_admin' ? '超级管理员' : me.platformRole === 'org_admin' ? '组织管理员' : '成员'
  const toggleTheme = () => setTheme(me.themePreference === 'dark' ? 'light' : 'dark')
  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={`flex cursor-pointer items-center gap-2 rounded-md hover:bg-ink-700 ${compact ? 'p-0.5' : 'p-1'}`}
        title="个人信息与设置"
      >
        <Avatar userId={CURRENT_USER_ID} size={compact ? 30 : 26} />
        {!compact && <span className="text-sm text-txt-mid">{me.name}</span>}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-50" onClick={() => setOpen(false)} />
          <div className="absolute right-0 top-full z-50 mt-2 w-72 rounded-card border border-line bg-canvas p-4 shadow-xl">
            <div className="flex items-center gap-3">
              <Avatar userId={CURRENT_USER_ID} size={44} />
              <div className="min-w-0 flex-1">
                <div className="text-sm font-bold text-txt-hi">{me.name}</div>
                <div className="truncate text-xs text-txt-mid">{me.title} · {dept?.name}</div>
              </div>
              <Pill tone={roleZh === '成员' ? 'neutral' : 'brand'}>{roleZh}</Pill>
            </div>
            <div className="mt-3 space-y-1 border-t border-line pt-3 text-xs text-txt-mid">
              <div className="flex justify-between"><span>邮箱</span><span className="font-mono text-txt-hi">{me.email}</span></div>
              <div className="flex justify-between"><span>每日容量</span><span className="tabular-nums text-txt-hi">{me.dailyCapacityHours}h（冲突检测口径）</span></div>
              <div className="flex justify-between"><span>当前主题</span><span className="text-txt-hi">{me.themePreference === 'dark' ? '深色' : '浅色'}</span></div>
            </div>
            <div className="mt-3 space-y-1 border-t border-line pt-3">
              <button type="button" onClick={toggleTheme} className="flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-txt-mid hover:bg-ink-700 hover:text-txt-hi">
                {me.themePreference === 'dark' ? <Sun size={14} /> : <Moon size={14} />} 切换{me.themePreference === 'dark' ? '浅色' : '深色'}主题
              </button>
              <button type="button" onClick={() => setOpen(false)} className="flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-txt-mid hover:bg-ink-700 hover:text-txt-hi">
                <Settings size={14} /> 个人设置（原型演示）
              </button>
              <button type="button" onClick={() => setOpen(false)} className="flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-bad-deep hover:bg-bad-bg">
                <LogOut size={14} /> 退出登录（原型演示）
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

function Sidebar({ page, nav }: { page: PageId; nav: Nav }) {
  const me = userById(CURRENT_USER_ID)
  const childOf: Record<string, PageId> = { repo: 'repos', mr: 'review', pipeline: 'pipelines', topics: 'im' }
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
        {navGroups.map((group) => (
          <div key={group.title} className="mb-4">
            <div className="mb-1 pl-6 text-[11px] font-semibold tracking-widest text-txt-low">{group.title}</div>
            <ul className="space-y-0.5">
              {group.items.map((item) => {
                const active = page === item.id || childOf[page] === item.id
                const count = item.badge?.() ?? 0
                const imUnread = item.id === 'im' ? channels.reduce((s, c) => s + c.unread, 0) : 0
                const badge = item.id === 'im' ? imUnread : count
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
                        <span className={`rounded-full px-1.5 py-px text-[11px] font-bold ${item.id === 'im' || item.id === 'conflicts' ? 'bg-bad text-white' : 'bg-brand-bg text-brand-deep'}`}>{badge}</span>
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
          <div className="truncate text-sm font-medium text-txt-hi">{me?.name}</div>
          <div className="truncate text-[11px] text-txt-low">{me?.title} · 平台研发部</div>
        </div>
        <button
          type="button"
          title="切换深/浅主题"
          onClick={() => setTheme(me?.themePreference === 'dark' ? 'light' : 'dark')}
          className="cursor-pointer rounded-md p-1.5 text-txt-mid hover:bg-ink-700 hover:text-txt-hi"
        >
          {me?.themePreference === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
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
}

function App() {
  useStore()
  const [page, setPage] = useState<PageId>('dashboard')
  const [id, setId] = useState<string | undefined>(undefined)
  const nav = useMemo<Nav>(() => ({ go: (p, i) => { setPage(p); setId(i) } }), [])
  const mainRef = useRef<HTMLElement>(null)
  useEffect(() => { mainRef.current?.scrollTo(0, 0) }, [page, id])

  let content = <DashboardPage nav={nav} />
  switch (page) {
    case 'reports': content = <ReportsPage nav={nav} />; break
    case 'conflicts': content = <ConflictsPage nav={nav} />; break
    case 'goals': content = <GoalsPage nav={nav} id={id} />; break
    case 'requirements': content = <RequirementsPage nav={nav} id={id} />; break
    case 'roadmap': content = <RoadmapPage nav={nav} />; break
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
  }

  return (
    <div className="flex h-full overflow-hidden">
      <Sidebar page={page} nav={nav} />
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <header className="flex h-13 shrink-0 items-center gap-3 border-b border-line bg-page px-6">
          <span className="text-sm font-semibold text-txt-hi">{pageTitles[page]}</span>
          <span className="text-xs text-txt-low">TeamOne · 平台本体研发</span>
          <div className="flex-1" />
          <div className="flex h-8 w-64 items-center gap-2 rounded-input border border-line bg-card px-2.5 text-txt-low">
            <Search size={14} />
            <input placeholder="搜索目标 / 任务 / 缺陷 / 话题…" className="w-full bg-transparent text-sm text-txt-hi outline-none placeholder:text-txt-low/70" />
            <kbd className="rounded border border-line px-1 text-[10px]">⌘K</kbd>
          </div>
          <button type="button" className="relative cursor-pointer rounded-md p-2 text-txt-mid hover:bg-ink-700 hover:text-txt-hi">
            <Bell size={16} />
            <span className="absolute top-1.5 right-1.5 h-1.5 w-1.5 rounded-full bg-bad" />
          </button>
          <div className="h-5 w-px bg-line" />
          <UserMenu />
        </header>
        <main ref={mainRef} className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-6xl px-6 py-6">{content}</div>
        </main>
      </div>
    </div>
  )
}

export default App
