// TeamOne v2 原型 · 导航配置与页面 props 约定（依据设计文档 §4.1 四分组）
// v2.1：话题讨论并入「即时沟通」（频道/话题/私聊三类），'topics' 页面标识保留作跳转别名
import type { ComponentType } from 'react'
import {
  AlertTriangle, BarChart3, CalendarRange, CircleDot, FileText, FolderGit2, GitPullRequest,
  LayoutDashboard, ListChecks, MessagesSquare, Package, Target, Users, Workflow,
} from 'lucide-react'
import type { PageId } from './data/types'
import { computeConflicts, defects, mergeRequests, requirements } from './data/store'

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
  badge?: () => number
}

export const navGroups: { title: string; items: NavItem[] }[] = [
  {
    title: '概览',
    items: [
      { id: 'dashboard', label: '工作台', icon: LayoutDashboard },
      { id: 'reports', label: '统计报表', icon: BarChart3 },
      { id: 'conflicts', label: '冲突中心', icon: AlertTriangle, badge: () => computeConflicts().filter((c) => c.severity === 'red').length },
    ],
  },
  {
    title: '产品研发',
    items: [
      { id: 'goals', label: '战略目标', icon: Target },
      { id: 'requirements', label: '需求管理', icon: FileText, badge: () => requirements.filter((r) => r.status === 'pending_review').length },
      { id: 'roadmap', label: 'RoadMap', icon: CalendarRange },
      { id: 'tasks', label: '迭代与任务', icon: ListChecks },
      { id: 'defects', label: '缺陷中心', icon: CircleDot, badge: () => defects.filter((d) => d.status !== '已关闭' && d.status !== '回归通过').length },
      { id: 'delivery', label: '版本与发布', icon: Package },
    ],
  },
  {
    title: '团队协同',
    items: [
      { id: 'im', label: '即时沟通', icon: MessagesSquare, badge: () => 0 },
      { id: 'team', label: '团队与权限', icon: Users },
    ],
  },
  {
    title: '工程底座',
    items: [
      { id: 'repos', label: '代码仓库', icon: FolderGit2 },
      { id: 'review', label: '代码评审', icon: GitPullRequest, badge: () => mergeRequests.filter((m) => m.status === 'open').length },
      { id: 'pipelines', label: 'CI/CD 流水线', icon: Workflow },
    ],
  },
]
