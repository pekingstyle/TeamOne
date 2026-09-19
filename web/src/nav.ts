// TeamOne v2 原型 · 导航配置与页面 props 约定（依据设计文档 §4.1 四分组）
// v2.1：话题讨论并入「即时沟通」（频道/话题/私聊三类），'topics' 页面标识保留作跳转别名
import type { ComponentType } from 'react'
import {
  AlertTriangle, BarChart3, CalendarRange, CircleDot, FileText, FolderGit2, GitPullRequest,
  LayoutDashboard, ListChecks, MessagesSquare, Package, Settings, Target, Users, Workflow,
} from 'lucide-react'
import type { PageId } from './data/types'

/** 页面间跳转句柄 */
export interface Nav {
  go: (page: PageId, id?: string) => void
}

/** 所有功能页面的统一 props */
export interface PageProps {
  nav: Nav
  id?: string
}

export interface NavItem {
  id: PageId
  label: string
  icon: ComponentType<{ size?: number | string; className?: string }>
}

// v2.2：角标真实化——nav.ts 不再携带 badge（旧字段读 store 演示数据，与实际不符）；
// 角标统一由 App.tsx Sidebar 经 useSidebarBadges()（TanStack Query，真实 API，staleTime 30s）计算。
export const navGroups: { title: string; items: NavItem[] }[] = [
  {
    title: '概览',
    items: [
      { id: 'dashboard', label: '工作台', icon: LayoutDashboard },
      { id: 'reports', label: '统计报表', icon: BarChart3 },
      { id: 'conflicts', label: '冲突中心', icon: AlertTriangle },
    ],
  },
  {
    title: '产品研发',
    items: [
      { id: 'goals', label: '战略目标', icon: Target },
      { id: 'requirements', label: '需求管理', icon: FileText },
      { id: 'roadmap', label: 'RoadMap', icon: CalendarRange },
      { id: 'tasks', label: '迭代与任务', icon: ListChecks },
      { id: 'defects', label: '缺陷中心', icon: CircleDot },
      { id: 'delivery', label: '版本与发布', icon: Package },
    ],
  },
  {
    title: '团队协同',
    items: [
      { id: 'im', label: '即时沟通', icon: MessagesSquare },
      { id: 'team', label: '团队与权限', icon: Users },
    ],
  },
  {
    title: '工程底座',
    items: [
      { id: 'repos', label: '代码仓库', icon: FolderGit2 },
      { id: 'review', label: '代码评审', icon: GitPullRequest },
      { id: 'pipelines', label: 'CI/CD 流水线', icon: Workflow },
      // R-12 系统设置：仅管理员可见（App.tsx Sidebar 按 AuthContext 角色过滤隐藏入口）
      { id: 'settings', label: '系统设置', icon: Settings },
    ],
  },
]

/** 仅管理员可见的页面（R-12：settings:read/edit 权限码仅 OWNER/ADMIN 放行，入口同步隐藏） */
export const ADMIN_ONLY_PAGES: PageId[] = ['settings']
