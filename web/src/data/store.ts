// TeamOne v2 原型 · 内存数据仓库 + 模拟实时动作
// 演示场景：TeamOne 研发团队使用 TeamOne 开发 TeamOne（Dogfooding）
// 数据模型依据 docs/v2/03-产品设计文档-v2.md §2；冲突检测依据 §5.1；话题事件表依据 §5.2
import { useSyncExternalStore } from 'react'
import type {
  Artifact, Baseline, Branch, Channel, Commit, ConflictItem,
  Component, Defect, DefectSeverity, DeployEnv, Department, DiffLine, FileNode, GoalStatus,
  MergeRequest, Message, Pipeline, Product, Release, Repo, Requirement, ResourcePermission,
  RoadmapItem, Sprint, StrategicGoal, Task, TestTask, Topic, TopicMessage, TopicTargetType,
  User, WorkItem, WorkItemStatus, WorkTree,
} from './types'

// ---------- 时间工具 ----------
function mins(n: number): string {
  const d = new Date(Date.now() - n * 60000)
  return fmt(d)
}
export function fmt(d: Date): string {
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (sameDay) return hm
  const sameYear = d.getFullYear() === now.getFullYear()
  return sameYear ? `${d.getMonth() + 1}-${d.getDate()} ${hm}` : `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()} ${hm}`
}
export function dateStr(offsetDays: number): string {
  const d = new Date(Date.now() + offsetDays * 86400000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
/** 工作日区间（剔除周末与节假日） */
const HOLIDAYS = new Set(['2026-10-01', '2026-10-02', '2026-10-03'])
export function workdays(start: string, end: string): string[] {
  const out: string[] = []
  const s = new Date(start)
  const e = new Date(end)
  for (let d = new Date(s); d <= e; d.setDate(d.getDate() + 1)) {
    const iso = d.toISOString().slice(0, 10)
    const dow = d.getDay()
    if (dow !== 0 && dow !== 6 && !HOLIDAYS.has(iso)) out.push(iso)
  }
  return out
}
export function daysBetween(a: string, b: string): number {
  return Math.round((new Date(b).getTime() - new Date(a).getTime()) / 86400000)
}

// ---------- 用户与组织 ----------
export const CURRENT_USER_ID = 'u1'

export const users: User[] = [
  { id: 'u1', name: '陈墨', title: '研发负责人', email: 'chenmo@teamone.dev', color: '#7b68ee', online: true, departmentId: 'd1', platformRole: 'org_admin', dailyCapacityHours: 8, themePreference: 'light' },
  { id: 'u2', name: '林晚晴', title: '前端工程师', email: 'wanqing@teamone.dev', color: '#ec4899', online: true, departmentId: 'd1', platformRole: 'member', dailyCapacityHours: 8, themePreference: 'light' },
  { id: 'u3', name: '赵子轩', title: '资深后端工程师', email: 'zixuan@teamone.dev', color: '#16c0a4', online: true, departmentId: 'd1', platformRole: 'member', dailyCapacityHours: 8, themePreference: 'light' },
  { id: 'u4', name: '苏芮', title: '测试工程师', email: 'surui@teamone.dev', color: '#ff9500', online: false, departmentId: 'd1', platformRole: 'member', dailyCapacityHours: 8, themePreference: 'light' },
  { id: 'u5', name: '周天磊', title: 'SRE / 平台工程', email: 'tianlei@teamone.dev', color: '#0091ff', online: true, departmentId: 'd1', platformRole: 'org_admin', dailyCapacityHours: 8, themePreference: 'light' },
  { id: 'u6', name: '韩雪', title: '产品经理', email: 'hanxue@teamone.dev', color: '#f94646', online: true, departmentId: 'd1', platformRole: 'member', dailyCapacityHours: 6, themePreference: 'light' },
  { id: 'u7', name: '吴启航', title: '后端工程师', email: 'qihang@teamone.dev', color: '#22c55e', online: false, departmentId: 'd2', platformRole: 'member', dailyCapacityHours: 8, themePreference: 'light' },
  { id: 'u8', name: '郑安然', title: '桌面端工程师', email: 'anran@teamone.dev', color: '#ffd66b', online: true, departmentId: 'd2', platformRole: 'member', dailyCapacityHours: 8, themePreference: 'light' },
]

export const departments: Department[] = [
  { id: 'd1', name: '平台研发部', leadId: 'u1', memberIds: ['u1', 'u2', 'u3', 'u4', 'u5', 'u6'], productIds: ['p1'], componentIds: ['c1', 'c2'], goalIds: ['g1', 'g2'], createdAt: '2026-01-12' },
  { id: 'd2', name: '客户端部', leadId: 'u8', memberIds: ['u7', 'u8'], productIds: ['p2'], componentIds: ['c3'], goalIds: ['g3'], createdAt: '2026-02-01' },
]

export const products: Product[] = [
  { id: 'p1', key: 'T1', name: 'TeamOne 平台', departmentId: 'd1', goalIds: ['g1', 'g2'], repoIds: ['r1', 'r2', 'r4'], leadId: 'u1', deadline: '2026-09-30', description: '一站式研发协同平台服务端与 Web 端', createdAt: '2026-01-15' },
  { id: 'p2', key: 'DT', name: 'TeamOne 桌面端', departmentId: 'd2', goalIds: ['g3'], repoIds: ['r3'], leadId: 'u8', description: 'Electron 桌面客户端', createdAt: '2026-03-01' },
]

export const components: Component[] = [
  { id: 'c1', name: '流水线引擎', productId: 'p1', repoIds: ['r1'], leadId: 'u3', description: '调度 / 执行器 / 队列' },
  { id: 'c2', name: '协同服务', productId: 'p1', repoIds: ['r1', 'r2'], leadId: 'u7', description: 'Webhook / 推送 / 通知' },
  { id: 'c3', name: '桌面壳', productId: 'p2', repoIds: ['r3'], leadId: 'u8', description: 'Electron 主进程与打包' },
]

// ---------- L1 战略目标 ----------
export const goals: StrategicGoal[] = [
  { id: 'g1', key: 'GOAL-1', name: '发布列车：双周一版稳定交付', departmentId: 'd1', period: '2026-H2', ownerId: 'u1', targetMetric: '版本按期交付率 ≥ 90%', progress: 60, status: 'active', productIds: ['p1'], roadmapItemIds: ['rm1', 'rm3', 'rm6'], deadline: '2026-09-30', topicId: 'top4', createdAt: '2026-07-01' },
  { id: 'g2', key: 'GOAL-2', name: '流水线调度成功率 99.9%', departmentId: 'd1', period: '2026-Q3', ownerId: 'u3', targetMetric: '调度失败率 < 0.1%', progress: 72, status: 'active', productIds: ['p1'], roadmapItemIds: ['rm1', 'rm2'], deadline: '2026-09-30', topicId: 'top5', createdAt: '2026-07-01' },
  { id: 'g3', key: 'GOAL-3', name: '桌面端体验：冷启动 2s 内', departmentId: 'd2', period: '2026-Q3', ownerId: 'u8', targetMetric: '冷启动 P90 ≤ 2s', progress: 88, status: 'at_risk', productIds: ['p2'], roadmapItemIds: ['rm4'], deadline: '2026-09-30', createdAt: '2026-07-01' },
]
export const goalStatusText: Record<GoalStatus, string> = { draft: '草稿', active: '进行中', at_risk: '有风险', achieved: '已达成', archived: '已归档' }

// ---------- L3 RoadMap ----------
export const roadmapItems: RoadmapItem[] = [
  { id: 'rm1', name: '流水线调度高可用', track: '研发引擎', start: '2026-08', end: '2026-09', progress: 72, status: 'in_progress', goalId: 'g2', productId: 'p1', releaseId: 'rel240', sprintIds: ['s2'], issueCount: 6, ownerIds: ['u3'] },
  { id: 'rm2', name: '智能质量门禁（AI 代码审查）', track: '质量与安全', start: '2026-09', end: '2026-11', progress: 6, status: 'planned', goalId: 'g2', productId: 'p1', releaseId: 'rel250', sprintIds: ['s3'], issueCount: 9, ownerIds: ['u3', 'u1'] },
  { id: 'rm3', name: '开放 API 网关', track: '开放生态', start: '2026-09', end: '2026-10', progress: 15, status: 'in_progress', goalId: 'g1', productId: 'p1', releaseId: 'rel250', sprintIds: ['s3'], issueCount: 7, ownerIds: ['u1', 'u7'] },
  { id: 'rm4', name: '桌面端体验优化（冷启动/托盘）', track: '客户端体验', start: '2026-09', end: '2026-09', progress: 88, status: 'in_progress', goalId: 'g3', productId: 'p2', releaseId: 'relDt210', sprintIds: ['s4'], issueCount: 4, ownerIds: ['u8'] },
  { id: 'rm5', name: '效能报表看板 2.0', track: '研发效能', start: '2026-09', end: '2026-09', progress: 55, status: 'in_progress', goalId: 'g1', productId: 'p1', releaseId: 'rel240', sprintIds: ['s2'], issueCount: 4, ownerIds: ['u2'] },
  { id: 'rm6', name: '合规审计 2.0（留痕/取证）', track: '质量与安全', start: '2026-08', end: '2026-09', progress: 90, status: 'in_progress', goalId: 'g1', productId: 'p1', releaseId: 'rel240', sprintIds: ['s1', 's2'], issueCount: 5, ownerIds: ['u4'] },
  { id: 'rm7', name: '同城双活容灾', track: '质量与安全', start: '2026-10', end: '2026-12', progress: 0, status: 'planned', productId: 'p1', sprintIds: [], issueCount: 14, ownerIds: ['u5'] },
]

// ---------- L4 版本（Milestone 并入 Release） ----------
export const releases: Release[] = [
  { id: 'rel230', name: 'v2.3.0', productId: 'p1', planDate: '2026-08-31', codeFreezeDate: '2026-08-27', status: 'released', progress: 100, blocked: false, blockedDefectIds: [], testTaskDoneCount: 10, testTaskTotalCount: 10, artifactIds: ['a6'], envProgress: { e1: 'done', e2: 'done', e3: 'done', e4: 'done' }, baselineId: 'bl2', releaseNotes: '· 审计日志异步导出\n· 登录验证码防爆破\n· 灰度发布 SOP 落地', ownerIds: ['u1', 'u5'], createdAt: '2026-08-10', releasedAt: mins(60 * 24 * 10) },
  { id: 'rel240', name: 'v2.4.0', productId: 'p1', planDate: dateStr(4), codeFreezeDate: dateStr(1), status: 'testing', progress: 62, blocked: true, blockedDefectIds: ['w22', 'w23'], testTaskDoneCount: 8, testTaskTotalCount: 12, artifactIds: ['a1', 'a2', 'a3'], envProgress: { e1: 'done', e2: 'deploying', e3: 'pending', e4: 'pending' }, releaseNotes: '· 执行器调度失败重试与熔断（T1-128）\n· 效能报表看板 2.0（T1-126）\n· Spring Boot 3.3 升级（T1-124）\n· 阻塞：D-88 熔断死锁、D-87 告警缺失', ownerIds: ['u1', 'u5', 'u4'], createdAt: '2026-08-20' },
  { id: 'rel241', name: 'v2.4.1', productId: 'p1', planDate: dateStr(12), codeFreezeDate: dateStr(9), status: 'planned', progress: 5, blocked: false, blockedDefectIds: [], testTaskDoneCount: 0, testTaskTotalCount: 6, artifactIds: [], releaseNotes: '· 修复批次：v2.4.0 顺延缺陷', ownerIds: ['u5'], createdAt: '2026-09-05' },
  { id: 'rel250', name: 'v2.5.0', productId: 'p1', planDate: dateStr(20), codeFreezeDate: dateStr(16), status: 'planned', progress: 0, blocked: false, blockedDefectIds: [], testTaskDoneCount: 0, testTaskTotalCount: 0, artifactIds: [], releaseNotes: '· 流水线 YAML DSL\n· 开放 API 网关首版', ownerIds: ['u1'], createdAt: '2026-09-08' },
  { id: 'relDt210', name: 'DT-v2.1.0', productId: 'p2', planDate: dateStr(20), codeFreezeDate: dateStr(14), status: 'coding', progress: 55, blocked: false, blockedDefectIds: [], testTaskDoneCount: 3, testTaskTotalCount: 9, artifactIds: ['a4'], releaseNotes: '· 冷启动达标 1.8s\n· 托盘图标修复', ownerIds: ['u8'], createdAt: '2026-09-01' },
]

// ---------- L5 迭代 ----------
export const sprints: Sprint[] = [
  { id: 's1', name: 'Sprint 24-2 · v2.3.0', goal: '完成 v2.3.0 交付：审计日志导出、验证码防护', productId: 'p1', releaseId: 'rel230', start: dateStr(-23), end: dateStr(-12), status: 'done', capacityHours: 320, totalPoints: 34, burndown: [34, 34, 30, 26, 22, 18, 14, 12, 10, 6, 0] },
  { id: 's2', name: 'Sprint 24-3 · v2.4.0', goal: '流水线调度高可用 + 效能看板 2.0', productId: 'p1', releaseId: 'rel240', start: dateStr(-9), end: dateStr(4), status: 'active', capacityHours: 400, totalPoints: 46, burndown: [46, 46, 42, 38, 35, 31, 29, 24, 22, 19] },
  { id: 's3', name: 'Sprint 25-1 · v2.5.0', goal: '流水线 YAML DSL + 开放网关', productId: 'p1', releaseId: 'rel250', start: dateStr(5), end: dateStr(16), status: 'planned', capacityHours: 400, totalPoints: 28, burndown: [28] },
  { id: 's4', name: 'Sprint CL-9 · DT 2.1.0', goal: '冷启动达标 + 托盘修复', productId: 'p2', releaseId: 'relDt210', start: dateStr(-9), end: dateStr(5), status: 'active', capacityHours: 160, totalPoints: 18, burndown: [18, 18, 16, 15, 13, 12, 10] },
]

// ---------- L6 工作项 ----------
export const tasks: Task[] = [
  { id: 'w1', key: 'T1-128', title: '执行器调度失败重试与熔断', description: '调度层增加可配置重试与熔断降级。验收：重试可配置、熔断自动切换备用执行器、单测 ≥ 6 场景。', type: 'task', status: 'in_review', priority: 'P0', productId: 'p1', componentId: 'c1', sprintId: 's2', releaseId: 'rel240', roadmapItemId: 'rm1', blockedByIds: [], assigneeId: 'u3', creatorId: 'u1', estimateHours: 16, startDate: dateStr(-6), dueDate: dateStr(1), points: 8, labels: ['调度', '高可用'], topicId: 'top2', requirementId: 'rq1', createdAt: mins(60 * 24 * 6), updatedAt: mins(23), linkedMrIds: ['mr1'] },
  { id: 'w2', key: 'T1-127', title: 'Webhook 投递补充幂等键校验', description: '投递入口强制校验幂等键，重复事件返回原结果。', type: 'task', status: 'in_progress', priority: 'P0', productId: 'p1', componentId: 'c2', sprintId: 's2', releaseId: 'rel240', blockedByIds: [], assigneeId: 'u7', creatorId: 'u4', estimateHours: 12, startDate: dateStr(-8), dueDate: dateStr(2), points: 5, labels: ['Webhook', '可靠性'], requirementId: 'rq3', createdAt: mins(60 * 24 * 4), updatedAt: mins(300), linkedMrIds: ['mr2'] },
  { id: 'w3', key: 'T1-126', title: '效能报表看板 2.0 改版', description: '趋势对比、结果分布环图、多维筛选器。', type: 'task', status: 'in_progress', priority: 'P1', productId: 'p1', sprintId: 's2', releaseId: 'rel240', roadmapItemId: 'rm5', blockedByIds: [], assigneeId: 'u2', creatorId: 'u6', estimateHours: 16, startDate: dateStr(-9), dueDate: dateStr(2), points: 8, labels: ['效能看板', '前端'], requirementId: 'rq2', createdAt: mins(60 * 24 * 5), updatedAt: mins(90), linkedMrIds: ['mr3'] },
  { id: 'w4', key: 'T1-125', title: '审计日志异步导出', description: '审计导出改异步任务 + IM 通知。', type: 'task', status: 'done', priority: 'P1', productId: 'p1', sprintId: 's1', releaseId: 'rel230', roadmapItemId: 'rm6', blockedByIds: [], assigneeId: 'u7', creatorId: 'u6', estimateHours: 12, points: 5, labels: ['审计'], requirementId: 'rq7', createdAt: mins(60 * 24 * 8), updatedAt: mins(60 * 30), linkedMrIds: ['mr5'] },
  { id: 'w5', key: 'T1-124', title: '升级 Spring Boot 3.2 → 3.3', description: 'LTS 跟进，验证 grpc-starter 兼容性。', type: 'task', status: 'done', priority: 'P2', productId: 'p1', sprintId: 's1', releaseId: 'rel230', blockedByIds: [], assigneeId: 'u5', creatorId: 'u5', estimateHours: 8, points: 3, labels: ['依赖升级'], createdAt: mins(60 * 24 * 7), updatedAt: mins(60 * 28), linkedMrIds: ['mr4'] },
  { id: 'w6', key: 'T1-123', title: '登录验证码防爆破', description: '频控 + 图形验证码升级策略。', type: 'task', status: 'closed', priority: 'P0', productId: 'p1', sprintId: 's1', releaseId: 'rel230', blockedByIds: [], assigneeId: 'u3', creatorId: 'u4', estimateHours: 8, points: 3, labels: ['安全'], createdAt: mins(60 * 24 * 9), updatedAt: mins(60 * 40), linkedMrIds: ['mr6'] },
  { id: 'w7', key: 'T1-131', title: '执行器资源权重动态配置', description: '管理后台实时调整调度权重热生效。', type: 'task', status: 'todo', priority: 'P1', productId: 'p1', componentId: 'c1', sprintId: 's2', releaseId: 'rel240', roadmapItemId: 'rm1', blockedByIds: [], assigneeId: 'u7', creatorId: 'u6', estimateHours: 10, startDate: dateStr(-2), dueDate: dateStr(8), points: 5, labels: ['调度'], createdAt: mins(60 * 24 * 2), updatedAt: mins(60 * 24 * 2) },
  { id: 'w8', key: 'T1-130', title: '仓库删除二次确认与回收站', description: '高危操作双人复核 + 7 天回收站。', type: 'task', status: 'todo', priority: 'P0', productId: 'p1', componentId: 'c2', sprintId: 's2', releaseId: 'rel240', blockedByIds: [], assigneeId: 'u3', creatorId: 'u1', estimateHours: 14, startDate: dateStr(-1), dueDate: dateStr(3), points: 8, labels: ['代码托管', '安全'], createdAt: mins(60 * 24 * 2), updatedAt: mins(60 * 24) },
  { id: 'w9', key: 'T1-129', title: '看板实时推送延迟优化', description: '广播风暴改分片投递。', type: 'task', status: 'in_progress', priority: 'P2', productId: 'p1', componentId: 'c2', sprintId: 's2', releaseId: 'rel240', blockedByIds: [], assigneeId: 'u2', creatorId: 'u4', estimateHours: 8, startDate: dateStr(-3), dueDate: dateStr(3), points: 3, labels: ['性能'], createdAt: mins(60 * 24 * 3), updatedAt: mins(60 * 10) },
  { id: 'w10', key: 'T1-121', title: '通知模板配置化', description: '通知模板迁移配置中心，按事件类型自定义。', type: 'task', status: 'in_review', priority: 'P1', productId: 'p1', sprintId: 's2', releaseId: 'rel240', blockedByIds: [], assigneeId: 'u2', creatorId: 'u6', estimateHours: 10, startDate: dateStr(-6), dueDate: dateStr(2), points: 5, labels: ['通知'], createdAt: mins(60 * 24 * 6), updatedAt: mins(60 * 8) },
  { id: 'w11', key: 'T1-132', title: '压测：万级并发流水线调度', description: '调度/执行/通知三链路全链路压测，输出容量报告。', type: 'task', status: 'in_progress', priority: 'P1', productId: 'p1', sprintId: 's2', releaseId: 'rel240', roadmapItemId: 'rm1', blockedByIds: [], assigneeId: 'u4', creatorId: 'u1', estimateHours: 20, startDate: dateStr(-5), dueDate: dateStr(2), points: 3, labels: ['压测'], createdAt: mins(60 * 24 * 2), updatedAt: mins(60 * 2) },
  { id: 'w12', key: 'T1-133', title: '服务端单测覆盖率提升至 75%', description: '补齐 webhook、queue 包分支覆盖。', type: 'task', status: 'todo', priority: 'P2', productId: 'p1', sprintId: 's2', releaseId: 'rel240', blockedByIds: [], assigneeId: 'u7', creatorId: 'u5', estimateHours: 6, startDate: dateStr(0), dueDate: dateStr(4), points: 2, labels: ['质量'], createdAt: mins(60 * 20), updatedAt: mins(60 * 20) },
  { id: 'w13', key: 'T1-139', title: '桌面端调度 SDK 适配', description: '为桌面端封装轻量调度 SDK，复用引擎重试策略。', type: 'task', status: 'in_progress', priority: 'P1', productId: 'p2', componentId: 'c3', sprintId: 's4', releaseId: 'relDt210', roadmapItemId: 'rm4', blockedByIds: [], assigneeId: 'u3', creatorId: 'u8', estimateHours: 12, startDate: dateStr(-2), dueDate: dateStr(2), points: 5, labels: ['桌面端', 'SDK'], createdAt: mins(60 * 24 * 2), updatedAt: mins(60 * 5) },
  { id: 'w14', key: 'T1-135', title: '流水线 YAML DSL 化', description: '流水线编排迁移 YAML DSL，可版本化管理。', type: 'task', status: 'todo', priority: 'P0', productId: 'p1', componentId: 'c1', sprintId: 's3', releaseId: 'rel250', roadmapItemId: 'rm2', blockedByIds: [], assigneeId: 'u3', creatorId: 'u1', estimateHours: 26, startDate: dateStr(5), dueDate: dateStr(14), points: 13, labels: ['流水线'], createdAt: mins(60 * 12), updatedAt: mins(60 * 12) },
  { id: 'w15', key: 'T1-134', title: '开放 API 网关鉴权重构', description: 'OAuth2 + 应用级限流。', type: 'task', status: 'todo', priority: 'P1', productId: 'p1', sprintId: 's3', releaseId: 'rel250', roadmapItemId: 'rm3', blockedByIds: ['w14'], assigneeId: 'u1', creatorId: 'u6', estimateHours: 16, startDate: dateStr(6), dueDate: dateStr(15), points: 8, labels: ['开放平台'], createdAt: mins(60 * 12), updatedAt: mins(60 * 12) },
  { id: 'w16', key: 'DT-13', title: '托盘图标偶发丢失修复', description: 'Windows 托盘注册时序问题。', type: 'task', status: 'todo', priority: 'P2', productId: 'p2', componentId: 'c3', sprintId: 's4', releaseId: 'relDt210', roadmapItemId: 'rm4', blockedByIds: [], assigneeId: 'u8', creatorId: 'u4', estimateHours: 4, startDate: dateStr(1), dueDate: dateStr(3), points: 2, labels: ['桌面端'], createdAt: mins(60 * 24 * 3), updatedAt: mins(60 * 24 * 3) },
  { id: 'w17', key: 'DT-14', title: '安装包体积优化', description: '按需打包 locales 与驱动。', type: 'task', status: 'in_progress', priority: 'P2', productId: 'p2', componentId: 'c3', sprintId: 's4', releaseId: 'relDt210', roadmapItemId: 'rm4', blockedByIds: [], assigneeId: 'u7', creatorId: 'u8', estimateHours: 8, startDate: dateStr(-4), dueDate: dateStr(2), points: 3, labels: ['桌面端', '性能'], createdAt: mins(60 * 24 * 4), updatedAt: mins(60 * 7) },
]

export const testTasks: TestTask[] = [
  { id: 'w18', key: 'TT-9', title: 'v2.4.0 回归测试 · 调度与网关模块', description: '覆盖执行器重试/熔断、Webhook 投递回归。', type: 'testtask', status: 'in_progress', priority: 'P0', productId: 'p1', componentId: 'c1', sprintId: 's2', releaseId: 'rel240', roadmapItemId: 'rm1', blockedByIds: [], assigneeId: 'u4', creatorId: 'u4', estimateHours: 24, startDate: dateStr(-1), dueDate: dateStr(2), points: 5, labels: ['回归'], topicId: 'top8', createdAt: mins(60 * 24 * 2), updatedAt: mins(45), caseCount: 86, passedCount: 71, relatedDefectIds: ['w22', 'w23'], verifierIds: ['u4'] },
  { id: 'w19', key: 'TT-8', title: 'v2.4.0 回归测试 · 看板模块', description: '效能看板 2.0 功能回归。', type: 'testtask', status: 'done', priority: 'P1', productId: 'p1', sprintId: 's2', releaseId: 'rel240', roadmapItemId: 'rm5', blockedByIds: [], assigneeId: 'u4', creatorId: 'u4', estimateHours: 16, startDate: dateStr(-5), dueDate: dateStr(-1), points: 3, labels: ['回归'], createdAt: mins(60 * 24 * 6), updatedAt: mins(60 * 26), caseCount: 42, passedCount: 42, relatedDefectIds: ['w24'], verifierIds: ['u4'] },
  { id: 'w20', key: 'TT-10', title: 'v2.4.1 冒烟用例设计', description: '修复批次冒烟用例 30 条。', type: 'testtask', status: 'todo', priority: 'P2', productId: 'p1', sprintId: 's3', releaseId: 'rel241', blockedByIds: [], assigneeId: 'u4', creatorId: 'u5', estimateHours: 8, startDate: dateStr(6), dueDate: dateStr(8), points: 2, labels: ['冒烟'], createdAt: mins(60 * 24), updatedAt: mins(60 * 24), caseCount: 30, passedCount: 0, relatedDefectIds: [], verifierIds: ['u4'] },
  { id: 'w21', key: 'TT-11', title: 'DT 2.1.0 功能测试', description: '桌面端冷启动/托盘/安装包测试。', type: 'testtask', status: 'in_progress', priority: 'P1', productId: 'p2', sprintId: 's4', releaseId: 'relDt210', roadmapItemId: 'rm4', blockedByIds: [], assigneeId: 'u4', creatorId: 'u8', estimateHours: 12, startDate: dateStr(-2), dueDate: dateStr(5), points: 3, labels: ['功能测试'], createdAt: mins(60 * 24 * 3), updatedAt: mins(60 * 9), caseCount: 36, passedCount: 12, relatedDefectIds: ['w26'], verifierIds: ['u8'] },
]

export const defects: Defect[] = [
  { id: 'w22', key: 'D-88', title: '熔断打开后渠道切换死锁', description: '熔断器 OPEN 后 ExecutorRouter 重入锁未释放，备用执行器无法接管，调度线程挂起。\n复现：压测 5000 TPS 下强制打开熔断。', type: 'defect', status: '修复中', severity: '致命', priority: 'P0', productId: 'p1', componentId: 'c1', sprintId: 's2', releaseId: 'rel240', roadmapItemId: 'rm1', blockedByIds: [], assigneeId: 'u3', creatorId: 'u4', estimateHours: 8, startDate: dateStr(-1), dueDate: dateStr(1), points: 3, labels: ['熔断', '资金级'], topicId: 'top1', createdAt: mins(60 * 26), updatedAt: mins(40), foundInTestTaskId: 'w18', verifyTestTaskId: 'w18', relatedTaskId: 'w1', blockedReleaseId: 'rel240', reportedById: 'u4', reopenedCount: 1 },
  { id: 'w23', key: 'D-87', title: '重试计数超阈值未触发告警', description: '重试 ≥ 3 次未上报 metrics，夜巡无法感知。', type: 'defect', status: '已修复', severity: '严重', priority: 'P1', productId: 'p1', componentId: 'c1', sprintId: 's2', releaseId: 'rel240', roadmapItemId: 'rm1', blockedByIds: [], assigneeId: 'u3', creatorId: 'u4', estimateHours: 4, startDate: dateStr(-2), dueDate: dateStr(2), points: 2, labels: ['可观测'], createdAt: mins(60 * 24 * 2), updatedAt: mins(120), foundInTestTaskId: 'w18', verifyTestTaskId: 'w18', relatedTaskId: 'w1', blockedReleaseId: 'rel240', reportedById: 'u4', reopenedCount: 0 },
  { id: 'w24', key: 'D-86', title: '看板环图 10w 数据点渲染卡顿', description: '全量渲染导致主线程阻塞 1.2s。', type: 'defect', status: '已关闭', severity: '一般', priority: 'P2', productId: 'p1', sprintId: 's2', releaseId: 'rel240', roadmapItemId: 'rm5', blockedByIds: [], assigneeId: 'u2', creatorId: 'u4', estimateHours: 6, startDate: dateStr(-7), dueDate: dateStr(-2), points: 2, labels: ['性能'], topicId: 'top6', createdAt: mins(60 * 24 * 7), updatedAt: mins(60 * 25), foundInTestTaskId: 'w19', verifyTestTaskId: 'w19', relatedTaskId: 'w3', reportedById: 'u4', reopenedCount: 0 },
  { id: 'w25', key: 'D-85', title: 'Webhook 重复投递', description: '消费方网络抖动重发导致同一事件投递两次。', type: 'defect', status: '回归通过', severity: '严重', priority: 'P0', productId: 'p1', componentId: 'c2', sprintId: 's2', releaseId: 'rel240', blockedByIds: [], assigneeId: 'u7', creatorId: 'u6', estimateHours: 6, startDate: dateStr(-8), dueDate: dateStr(-3), points: 3, labels: ['可靠性'], createdAt: mins(60 * 24 * 8), updatedAt: mins(60 * 27), foundInTestTaskId: undefined, verifyTestTaskId: 'w18', relatedTaskId: 'w2', fixedInMrId: 'mr2', reportedById: 'u6', reopenedCount: 0 },
  { id: 'w26', key: 'DT-84', title: '桌面端托盘图标偶发丢失', description: 'Windows 托盘注册时序问题，冷启动偶发。', type: 'defect', status: '新建', severity: '轻微', priority: 'P2', productId: 'p2', componentId: 'c3', sprintId: 's4', releaseId: 'relDt210', roadmapItemId: 'rm4', blockedByIds: [], assigneeId: 'u8', creatorId: 'u4', estimateHours: 2, startDate: dateStr(1), dueDate: dateStr(5), points: 1, labels: ['桌面端'], createdAt: mins(60 * 24), updatedAt: mins(60 * 24), foundInTestTaskId: 'w21', verifyTestTaskId: 'w21', relatedTaskId: 'w16', reportedById: 'u4', reopenedCount: 0 },
]

export const workItems: WorkItem[] = [...tasks, ...testTasks, ...defects]

// ---------- 需求池（连接 目标/版本 与 迭代/任务） ----------
export const requirements: Requirement[] = [
  { id: 'rq1', key: 'REQ-1', title: '执行器失败重试与熔断机制', description: '背景：第三方构建资源超时导致流水线大面积失败。\n价值：调度成功率 99.62% → 99.9%。\n验收标准：重试可配置、熔断自动切换、单测 ≥ 6 场景。', status: 'in_dev', priority: 'P0', productId: 'p1', proposerId: 'u6', ownerId: 'u6', reviewerIds: ['u1', 'u3'], reviews: [
    { userId: 'u6', result: 'approved', comment: '价值明确，排入 v2.4.0。', at: mins(60 * 24 * 5) },
    { userId: 'u1', result: 'approved', comment: '同意，注意熔断阈值先保守。', at: mins(60 * 24 * 5) },
  ], goalId: 'g2', roadmapItemId: 'rm1', releaseId: 'rel240', sprintId: 's2', estimatePoints: 8, topicId: 'top2', createdAt: mins(60 * 24 * 6), updatedAt: mins(23), acceptedAt: mins(60 * 24 * 5) },
  { id: 'rq2', key: 'REQ-2', title: '效能报表看板 2.0', description: '背景：管理方需要研发效能可视化。\n验收标准：趋势对比、结果分布、多维筛选。', status: 'in_dev', priority: 'P1', productId: 'p1', proposerId: 'u6', ownerId: 'u6', reviewerIds: ['u1', 'u6'], reviews: [
    { userId: 'u6', result: 'approved', comment: '设计稿已定稿。', at: mins(60 * 24 * 5) },
    { userId: 'u1', result: 'approved', comment: '通过，排 v2.4.0。', at: mins(60 * 24 * 4) },
  ], goalId: 'g1', roadmapItemId: 'rm5', releaseId: 'rel240', sprintId: 's2', estimatePoints: 8, createdAt: mins(60 * 24 * 7), updatedAt: mins(90), acceptedAt: mins(60 * 24 * 4) },
  { id: 'rq3', key: 'REQ-3', title: 'Webhook 投递幂等与重试', description: '背景：消费方重复收到事件。\n验收标准：幂等键强制、重复返回原结果、投递可追溯。', status: 'in_dev', priority: 'P0', productId: 'p1', proposerId: 'u6', ownerId: 'u6', reviewerIds: ['u1', 'u3'], reviews: [
    { userId: 'u3', result: 'approved', comment: '技术方案可行。', at: mins(60 * 24 * 4) },
    { userId: 'u1', result: 'approved', comment: '通过。', at: mins(60 * 24 * 4) },
  ], goalId: 'g2', roadmapItemId: 'rm1', releaseId: 'rel240', sprintId: 's2', estimatePoints: 5, createdAt: mins(60 * 24 * 5), updatedAt: mins(300), acceptedAt: mins(60 * 24 * 4) },
  { id: 'rq4', key: 'REQ-4', title: '仓库删除二次确认与回收站', description: '背景：删除仓库为高危操作，已有误删工单。\n验收标准：双人复核 + 7 天回收站 + 操作留痕。\n\n粗估 8 点，建议排入 v2.4.1 修复批次。', status: 'pending_review', priority: 'P0', productId: 'p1', proposerId: 'u1', ownerId: 'u6', reviewerIds: ['u6', 'u1'], reviews: [], goalId: 'g1', releaseId: 'rel241', estimatePoints: 8, createdAt: mins(60 * 24 * 2), updatedAt: mins(60 * 24) },
  { id: 'rq5', key: 'REQ-5', title: '开放 API 网关 OAuth2 鉴权', description: '背景：开放生态需要应用级接入。\n验收标准：OAuth2 授权码模式 + 应用级限流 + 审计。', status: 'accepted', priority: 'P1', productId: 'p1', proposerId: 'u6', ownerId: 'u6', reviewerIds: ['u1', 'u5'], reviews: [
    { userId: 'u5', result: 'approved', comment: '网关容量已评估。', at: mins(60 * 24 * 3) },
    { userId: 'u1', result: 'approved', comment: '通过，排 v2.5.0。', at: mins(60 * 24 * 3) },
  ], goalId: 'g1', roadmapItemId: 'rm3', releaseId: 'rel250', sprintId: 's3', estimatePoints: 8, createdAt: mins(60 * 24 * 4), updatedAt: mins(60 * 24 * 3), acceptedAt: mins(60 * 24 * 3) },
  { id: 'rq6', key: 'REQ-6', title: '通知模板配置中心化', description: '背景：IM 通知模板硬编码，变更需发版。\n验收标准：模板可配置、按事件类型区分、支持变量。', status: 'rejected', priority: 'P2', productId: 'p1', proposerId: 'u2', ownerId: 'u6', reviewerIds: ['u6', 'u1'], reviews: [
    { userId: 'u6', result: 'rejected', comment: '当前版本以稳定性优先，建议移入 v2.4.1 再议，附收益数据后重新提交。', at: mins(60 * 24 * 2) },
  ], estimatePoints: 5, createdAt: mins(60 * 24 * 3), updatedAt: mins(60 * 24 * 2) },
  { id: 'rq7', key: 'REQ-7', title: '审计日志异步导出', description: '背景：审计数据量大，同步导出超时。\n验收标准：异步任务 + 完成后 IM 通知下载。', status: 'closed', priority: 'P1', productId: 'p1', proposerId: 'u6', ownerId: 'u6', reviewerIds: ['u1', 'u4'], reviews: [
    { userId: 'u1', result: 'approved', comment: '通过。', at: mins(60 * 24 * 9) },
    { userId: 'u4', result: 'approved', comment: '验收用例已备。', at: mins(60 * 24 * 9) },
  ], goalId: 'g1', roadmapItemId: 'rm6', releaseId: 'rel230', sprintId: 's1', estimatePoints: 5, createdAt: mins(60 * 24 * 10), updatedAt: mins(60 * 30), acceptedAt: mins(60 * 24 * 9), deliveredAt: mins(60 * 24 * 11) },
  { id: 'rq8', key: 'REQ-8', title: '研发周报自动生成（LLM 摘要）', description: '背景：周报人工整理耗时。\n验收标准：定时拉取平台数据自动生成并推送 IM。', status: 'delivered', priority: 'P2', productId: 'p1', proposerId: 'u1', ownerId: 'u5', reviewerIds: ['u1', 'u6'], reviews: [
    { userId: 'u6', result: 'approved', comment: '通过。', at: mins(60 * 24 * 6) },
    { userId: 'u1', result: 'approved', comment: '通过，先内部使用。', at: mins(60 * 24 * 6) },
  ], releaseId: 'rel240', estimatePoints: 3, createdAt: mins(60 * 24 * 7), updatedAt: mins(60 * 3), acceptedAt: mins(60 * 24 * 6), deliveredAt: mins(60 * 3) },
]

// ---------- 仓库 / 分支 / 提交 / 文件树（v1 演进） ----------
export const repos: Repo[] = [
  { id: 'r1', name: 'teamone-server', description: 'TeamOne 服务端 · Git 托管 / 流水线 / 评审引擎', language: 'Java', stars: 128, visibility: 'private', defaultBranch: 'master', leadId: 'u3', updatedAt: mins(23), ciEnabled: true, productId: 'p1', componentId: 'c1' },
  { id: 'r2', name: 'teamone-web', description: 'TeamOne Web 前端（React 19）', language: 'TypeScript', stars: 64, visibility: 'private', defaultBranch: 'main', leadId: 'u2', updatedAt: mins(96), ciEnabled: true, productId: 'p1', componentId: 'c2' },
  { id: 'r3', name: 'teamone-desktop', description: 'TeamOne 桌面客户端（Electron）', language: 'TypeScript', stars: 41, visibility: 'private', defaultBranch: 'main', leadId: 'u8', updatedAt: mins(60 * 26), ciEnabled: true, productId: 'p2', componentId: 'c3' },
  { id: 'r4', name: 'teamone-infra', description: '部署脚本 · 流水线模板 · 研发周报（吃自己的狗粮 🐶）', language: 'Shell', stars: 210, visibility: 'private', defaultBranch: 'main', leadId: 'u1', updatedAt: mins(60 * 3), ciEnabled: true, productId: 'p1' },
]

export const branches: Branch[] = [
  { repoId: 'r1', name: 'master', ahead: 0, behind: 0, lastCommitMsg: 'chore: 发布 v2.3.0', updatedAt: mins(60 * 24 * 10), protected: true, authorId: 'u5' },
  { repoId: 'r1', name: 'feat/executor-retry', ahead: 6, behind: 1, lastCommitMsg: 'feat: 执行器调度失败重试与熔断', updatedAt: mins(23), protected: false, authorId: 'u3' },
  { repoId: 'r1', name: 'fix/webhook-idempotent', ahead: 3, behind: 2, lastCommitMsg: 'fix: Webhook 投递补充幂等键', updatedAt: mins(310), protected: false, authorId: 'u7' },
  { repoId: 'r1', name: 'release/v2.4.0', ahead: 2, behind: 0, lastCommitMsg: 'chore: bump 2.4.0-rc.3', updatedAt: mins(58), protected: true, authorId: 'u5' },
  { repoId: 'r2', name: 'main', ahead: 0, behind: 0, lastCommitMsg: 'Merge !37 审计日志导出页面', updatedAt: mins(60 * 5), protected: true, authorId: 'u2' },
  { repoId: 'r2', name: 'feat/dashboard-redesign', ahead: 9, behind: 0, lastCommitMsg: 'style: 图表配色对齐设计稿', updatedAt: mins(96), protected: false, authorId: 'u2' },
  { repoId: 'r3', name: 'main', ahead: 0, behind: 0, lastCommitMsg: 'perf: 冷启动延迟加载模块', updatedAt: mins(60 * 26), protected: true, authorId: 'u8' },
  { repoId: 'r4', name: 'main', ahead: 1, behind: 0, lastCommitMsg: 'ci: 周报流水线接入 LLM 摘要', updatedAt: mins(60 * 3), protected: true, authorId: 'u1' },
]

export const commits: Commit[] = [
  { id: 'a3f92c1', repoId: 'r1', message: 'feat: 执行器调度失败重试与熔断', authorId: 'u3', date: mins(23), branch: 'feat/executor-retry', additions: 214, deletions: 32 },
  { id: '7d01be4', repoId: 'r1', message: 'test: 重试策略单测覆盖 6 场景', authorId: 'u3', date: mins(51), branch: 'feat/executor-retry', additions: 186, deletions: 0 },
  { id: 'c88aa2f', repoId: 'r1', message: 'chore: bump 2.4.0-rc.3', authorId: 'u5', date: mins(58), branch: 'release/v2.4.0', additions: 3, deletions: 3 },
  { id: 'e1d7f03', repoId: 'r1', message: 'fix: Webhook 投递补充幂等键', authorId: 'u7', date: mins(310), branch: 'fix/webhook-idempotent', additions: 42, deletions: 11 },
  { id: 'b24c6d8', repoId: 'r1', message: 'refactor: 执行器调度策略抽取接口', authorId: 'u7', date: mins(60 * 9), branch: 'master', additions: 128, deletions: 97 },
  { id: 'f0e35a9', repoId: 'r1', message: 'perf: 任务队列批量落库', authorId: 'u3', date: mins(60 * 26), branch: 'master', additions: 76, deletions: 40 },
  { id: '9a77b21', repoId: 'r2', message: 'style: 图表配色对齐设计稿', authorId: 'u2', date: mins(96), branch: 'feat/dashboard-redesign', additions: 145, deletions: 88 },
  { id: '4c2dd90', repoId: 'r2', message: 'feat: 效能看板筛选器组件', authorId: 'u2', date: mins(60 * 4), branch: 'feat/dashboard-redesign', additions: 320, deletions: 12 },
  { id: '6f1e8ba', repoId: 'r3', message: 'perf: 冷启动延迟加载模块', authorId: 'u8', date: mins(60 * 26), branch: 'main', additions: 87, deletions: 23 },
  { id: 'd95f4c7', repoId: 'r4', message: 'ci: 周报流水线接入 LLM 摘要', authorId: 'u1', date: mins(60 * 3), branch: 'main', additions: 64, deletions: 5 },
]

export const fileTrees: Record<string, FileNode[]> = {
  r1: [
    { name: 'src/main/java/com/teamone', kind: 'dir', children: [
      { name: 'scheduler', kind: 'dir', children: [
        { name: 'TaskDispatcher.java', kind: 'file', lastCommitMsg: 'feat: 执行器调度失败重试与熔断', updatedAt: mins(23) },
        { name: 'DispatcherRetryConfig.java', kind: 'file', lastCommitMsg: 'feat: 执行器调度失败重试与熔断', updatedAt: mins(23) },
        { name: 'ExecutorRouter.java', kind: 'file', lastCommitMsg: 'refactor: 执行器调度策略抽取接口', updatedAt: mins(60 * 9) },
      ] },
      { name: 'webhook', kind: 'dir', children: [{ name: 'WebhookService.java', kind: 'file', lastCommitMsg: 'fix: Webhook 投递补充幂等键', updatedAt: mins(310) }] },
      { name: 'queue', kind: 'dir', children: [{ name: 'TaskQueue.java', kind: 'file', lastCommitMsg: 'perf: 任务队列批量落库', updatedAt: mins(60 * 26) }] },
    ] },
    { name: 'src/test/java', kind: 'dir', children: [{ name: 'DispatcherRetryPolicyTest.java', kind: 'file', lastCommitMsg: 'test: 重试策略单测覆盖 6 场景', updatedAt: mins(51) }] },
    { name: 'pom.xml', kind: 'file', lastCommitMsg: 'chore: bump 2.4.0-rc.3', updatedAt: mins(58) },
    { name: 'Jenkinsfile', kind: 'file', lastCommitMsg: 'ci: 流水线增加覆盖率门禁', updatedAt: mins(60 * 30) },
    { name: 'README.md', kind: 'file', lastCommitMsg: 'docs: 部署文档更新', updatedAt: mins(60 * 24 * 9) },
  ],
  r2: [
    { name: 'src', kind: 'dir', children: [
      { name: 'pages', kind: 'dir', children: [
        { name: 'Dashboard.tsx', kind: 'file', lastCommitMsg: 'style: 图表配色对齐设计稿', updatedAt: mins(96) },
        { name: 'FilterBar.tsx', kind: 'file', lastCommitMsg: 'feat: 效能看板筛选器组件', updatedAt: mins(60 * 4) },
      ] },
      { name: 'api', kind: 'dir', children: [{ name: 'report.ts', kind: 'file', updatedAt: mins(60 * 4) }] },
    ] },
    { name: 'package.json', kind: 'file', lastCommitMsg: 'chore: react 19.2', updatedAt: mins(60 * 50) },
  ],
  r3: [
    { name: 'src', kind: 'dir', children: [
      { name: 'main', kind: 'dir', children: [{ name: 'main.ts', kind: 'file', lastCommitMsg: 'perf: 冷启动延迟加载模块', updatedAt: mins(60 * 26) }] },
      { name: 'renderer', kind: 'dir', children: [{ name: 'App.tsx', kind: 'file', updatedAt: mins(60 * 30) }] },
    ] },
    { name: 'package.json', kind: 'file', updatedAt: mins(60 * 40) },
  ],
  r4: [
    { name: 'scripts', kind: 'dir', children: [{ name: 'weekly.ts', kind: 'file', lastCommitMsg: 'ci: 周报流水线接入 LLM 摘要', updatedAt: mins(60 * 3) }] },
    { name: 'pipelines', kind: 'dir', children: [{ name: 'server.yaml', kind: 'file', updatedAt: mins(60 * 20) }] },
    { name: 'README.md', kind: 'file', updatedAt: mins(60 * 70) },
  ],
}

// ---------- WorkTree / 基线 ----------
export const workTrees: WorkTree[] = [
  { id: 'wt1', repoId: 'r1', name: 'feat/executor-retry', branch: 'feat/executor-retry', localPath: '~/work/teamone/server-wt-executor', ownerId: 'u3', relatedTaskId: 'w1', basedOn: { kind: 'baseline' as const, id: 'bl2' }, ahead: 6, behind: 1, dirtyFileCount: 2, lastCommitAt: mins(23), status: 'active' as const },
  { id: 'wt2', repoId: 'r1', name: 'fix/webhook-idempotent', branch: 'fix/webhook-idempotent', localPath: '~/work/teamone/server-wt-webhook', ownerId: 'u7', relatedTaskId: 'w2', basedOn: { kind: 'baseline' as const, id: 'bl2' }, ahead: 3, behind: 2, dirtyFileCount: 0, lastCommitAt: mins(310), status: 'active' as const },
  { id: 'wt3', repoId: 'r1', name: 'release/v2.4.0', branch: 'release/v2.4.0', localPath: '~/work/teamone/server-wt-release', ownerId: 'u5', basedOn: { kind: 'branch' as const, id: 'master' }, ahead: 2, behind: 0, dirtyFileCount: 0, lastCommitAt: mins(58), status: 'active' as const },
  { id: 'wt4', repoId: 'r3', name: 'perf/desktop-sdk', branch: 'perf/desktop-sdk', localPath: '~/work/teamone/desktop-wt-sdk', ownerId: 'u3', relatedTaskId: 'w13', basedOn: { kind: 'baseline' as const, id: 'bl4' }, ahead: 4, behind: 0, dirtyFileCount: 1, lastCommitAt: mins(60 * 5), status: 'active' as const },
  { id: 'wt5', repoId: 'r2', name: 'feat/dashboard-redesign', branch: 'feat/dashboard-redesign', localPath: '~/work/teamone/web-wt-dashboard', ownerId: 'u2', relatedTaskId: 'w3', basedOn: { kind: 'branch' as const, id: 'main' }, ahead: 9, behind: 0, dirtyFileCount: 3, lastCommitAt: mins(96), status: 'active' as const },
  { id: 'wt6', repoId: 'r1', name: 'chore/boot-3.3', branch: 'chore/boot-3.3', localPath: '~/work/teamone/server-wt-boot', ownerId: 'u5', relatedTaskId: 'w5', basedOn: { kind: 'baseline' as const, id: 'bl2' }, ahead: 0, behind: 0, dirtyFileCount: 0, lastCommitAt: mins(60 * 28), status: 'merged' as const },
]
export type WorkTreeItem = WorkTree

export const baselines: Baseline[] = [
  { id: 'bl1', repoId: 'r1', name: 'BL-R2.2-产品基线', type: 'product', tagRef: 'v2.2.1', artifactVersion: '2.2.1', commitShort: 'f00ab12', createdAt: '2026-07-02', status: 'superseded', approverIds: ['u1', 'u5'], includesCommits: ['f00ab12'], mrIds: [], conflictResolved: [], protectedBranches: ['master'], supersededById: 'bl2' },
  { id: 'bl2', repoId: 'r1', name: 'BL-R2.3-功能基线', type: 'functional', tagRef: 'v2.3.0-rc', artifactVersion: '2.3.0', commitShort: 'b24c6d8', createdAt: mins(60 * 24 * 10), status: 'approved', approverIds: ['u1', 'u5', 'u3'], includesCommits: ['b24c6d8', 'f0e35a9'], mrIds: ['mr5', 'mr6'], conflictResolved: [{ filePath: 'scheduler/ExecutorRouter.java', solution: '以调度策略接口版本为准，手工合并重试常量并补回归', confirmedById: 'u7', reviewedById: 'u3', resolvedAt: mins(60 * 24 * 10) }], protectedBranches: ['master', 'release/*'] },
  { id: 'bl3', repoId: 'r1', name: 'BL-R2.4-分配基线', type: 'allocated', tagRef: 'v2.4.0-rc.3', artifactVersion: '2.4.0-rc.3', commitShort: 'a3f92c1', createdAt: mins(60 * 24), status: 'in_review', approverIds: ['u1'], includesCommits: ['a3f92c1', '7d01be4', 'c88aa2f'], mrIds: ['mr1', 'mr4'], conflictResolved: [], protectedBranches: ['release/v2.4.0'] },
  { id: 'bl4', repoId: 'r3', name: 'BL-DT2.0-产品基线', type: 'product', tagRef: 'dt-v2.0.0', artifactVersion: '2.0.0', commitShort: '11ce9d0', createdAt: '2026-08-15', status: 'approved', approverIds: ['u8', 'u1'], includesCommits: ['11ce9d0'], mrIds: [], conflictResolved: [], protectedBranches: ['main'] },
]
export const baselineTypeText = { functional: '功能基线', allocated: '分配基线', product: '产品基线' } as const

// ---------- MR（含 v2 单测检测 / 冲突 / 基线关联） ----------
const dispatcherDiff: DiffLine[] = [
  { type: 'ctx', oldNo: 12, newNo: 12, text: 'package com.teamone.scheduler;' },
  { type: 'ctx', oldNo: 14, newNo: 14, text: 'public class TaskDispatcher {' },
  { type: 'del', oldNo: 15, newNo: undefined, text: '  public DispatchResult call(Executor ex, TaskRequest req) {' },
  { type: 'del', oldNo: 16, newNo: undefined, text: '    return ex.execute(req);' },
  { type: 'add', oldNo: undefined, newNo: 15, text: '  private final RetryExecutor retryExecutor;' },
  { type: 'add', oldNo: undefined, newNo: 16, text: '  private final CircuitBreakerRegistry breakerRegistry;' },
  { type: 'add', oldNo: undefined, newNo: 18, text: '  public DispatchResult call(Executor ex, TaskRequest req) {' },
  { type: 'add', oldNo: undefined, newNo: 19, text: '    RetryPolicy policy = policies.get(ex.getName());' },
  { type: 'add', oldNo: undefined, newNo: 20, text: '    CircuitBreaker breaker = breakerRegistry.of(ex.getName());' },
  { type: 'add', oldNo: undefined, newNo: 21, text: '    return breaker.execute(() ->' },
  { type: 'add', oldNo: undefined, newNo: 22, text: '        retryExecutor.execute(policy, () -> ex.execute(req)));' },
  { type: 'ctx', oldNo: 18, newNo: 24, text: '}' },
]
const configDiff: DiffLine[] = [
  { type: 'ctx', oldNo: 1, newNo: 1, text: '@ConfigurationProperties(prefix = "dispatcher.retry")' },
  { type: 'del', oldNo: 3, newNo: undefined, text: '  private int maxAttempts = 1;' },
  { type: 'add', oldNo: undefined, newNo: 3, text: '  private int maxAttempts = 3;' },
  { type: 'add', oldNo: undefined, newNo: 4, text: '  private Backoff backoff = Backoff.EXPONENTIAL;' },
  { type: 'add', oldNo: undefined, newNo: 5, text: '  private double failureRateThreshold = 0.5;' },
]
const testDiff: DiffLine[] = [
  { type: 'add', oldNo: undefined, newNo: 1, text: 'class DispatcherRetryPolicyTest {' },
  { type: 'add', oldNo: undefined, newNo: 2, text: '  @Test void 应在三次内重试成功() { ... }' },
  { type: 'add', oldNo: undefined, newNo: 3, text: '  @Test void 熔断打开时切换备用执行器() { ... }' },
  { type: 'add', oldNo: undefined, newNo: 4, text: '  @Test void 重试耗尽后进入降级() { ... }' },
  { type: 'add', oldNo: undefined, newNo: 5, text: '  // 6 场景全部覆盖' },
  { type: 'add', oldNo: undefined, newNo: 6, text: '}' },
]
const webhookDiff: DiffLine[] = [
  { type: 'ctx', oldNo: 30, newNo: 30, text: 'public DeliverResult deliver(DeliverRequest req) {' },
  { type: 'add', oldNo: undefined, newNo: 31, text: '  requireIdempotencyKey(req);' },
  { type: 'add', oldNo: undefined, newNo: 32, text: '  DeliverResult exists = idempotentStore.get(req.getKey());' },
  { type: 'add', oldNo: undefined, newNo: 33, text: '  if (exists != null) return exists;' },
  { type: 'ctx', oldNo: 31, newNo: 34, text: '  ...' },
]
const dashboardDiff: DiffLine[] = [
  { type: 'ctx', oldNo: 1, newNo: 1, text: 'export function Dashboard() {' },
  { type: 'del', oldNo: 2, newNo: undefined, text: '  const [range, setRange] = useState(\'7d\')' },
  { type: 'add', oldNo: undefined, newNo: 2, text: '  const filters = useReportFilters()' },
  { type: 'add', oldNo: undefined, newNo: 3, text: '  const { data, loading } = useBuildTrends(filters)' },
  { type: 'add', oldNo: undefined, newNo: 6, text: '      <FilterBar value={filters} />' },
  { type: 'add', oldNo: undefined, newNo: 7, text: '      <TrendCompare data={data} loading={loading} />' },
  { type: 'ctx', oldNo: 5, newNo: 9, text: '  )' },
]

export const mergeRequests: MergeRequest[] = [
  {
    id: 'mr1', number: 42, title: 'feat: 执行器调度失败重试与熔断', repoId: 'r1',
    description: '为流水线执行器调用增加统一重试与熔断：重试按执行器可配置；基于错误率的熔断器打开后由 ExecutorRouter 切换备用执行器；单测 6 场景全覆盖。\n\nCloses T1-128',
    sourceBranch: 'feat/executor-retry', targetBranch: 'master', authorId: 'u3',
    reviewers: [{ userId: 'u1', state: 'pending' }, { userId: 'u5', state: 'approved' }],
    status: 'open', conflicts: false, createdAt: mins(120),
    checks: [{ name: '流水线 #312', status: 'passed' }, { name: '安全扫描', status: 'passed' }],
    diffs: [
      { path: 'src/main/java/com/teamone/scheduler/TaskDispatcher.java', status: 'modified', additions: 12, deletions: 3, lines: dispatcherDiff },
      { path: 'src/main/java/com/teamone/scheduler/DispatcherRetryConfig.java', status: 'modified', additions: 3, deletions: 1, lines: configDiff },
      { path: 'src/test/java/com/teamone/scheduler/DispatcherRetryPolicyTest.java', status: 'added', additions: 186, deletions: 0, lines: testDiff },
    ],
    comments: [
      { id: 'mc1', authorId: 'u5', text: '建议熔断打开时打 WARN 日志并上报指标，方便夜巡。', createdAt: mins(90) },
      { id: 'mc2', authorId: 'u3', text: '已加，metrics 名称为 dispatcher.breaker.state。', createdAt: mins(60) },
    ],
    linkedWorkItemKey: 'T1-128', additions: 400, deletions: 32,
    unitTestCheck: { hasTests: true, testFiles: ['src/test/java/com/teamone/scheduler/DispatcherRetryPolicyTest.java'], passed: true, coverageTotal: 76.2, coverageDelta: 88, gatePassed: true },
    conflictFiles: [], basedOnBaselineId: 'bl2', rebaseRequired: false,
  },
  {
    id: 'mr2', number: 41, title: 'fix: Webhook 投递补充幂等键校验', repoId: 'r1',
    description: '投递入口强制校验 idempotencyKey，重复事件直接返回原结果。\n\nCloses T1-127',
    sourceBranch: 'fix/webhook-idempotent', targetBranch: 'master', authorId: 'u7',
    reviewers: [{ userId: 'u3', state: 'changes_requested' }, { userId: 'u1', state: 'pending' }],
    status: 'open', conflicts: false, createdAt: mins(300),
    checks: [{ name: '流水线 #311', status: 'failed' }, { name: '安全扫描', status: 'passed' }],
    diffs: [{ path: 'src/main/java/com/teamone/webhook/WebhookService.java', status: 'modified', additions: 14, deletions: 2, lines: webhookDiff }],
    comments: [{ id: 'mc3', authorId: 'u3', text: '幂等记录过期时间要覆盖消费方重试周期，24h 不够，请改 48h。', createdAt: mins(240) }],
    linkedWorkItemKey: 'T1-127', additions: 42, deletions: 11,
    unitTestCheck: { hasTests: false, testFiles: [], passed: false, coverageTotal: 71.8, coverageDelta: 0, gatePassed: false },
    conflictFiles: [], basedOnBaselineId: 'bl2', rebaseRequired: true,
  },
  {
    id: 'mr3', number: 40, title: 'feat: 效能报表看板 2.0 改版', repoId: 'r2',
    description: '效能看板重构：趋势对比、分布环图、筛选器。\n\nCloses T1-126',
    sourceBranch: 'feat/dashboard-redesign', targetBranch: 'main', authorId: 'u2',
    reviewers: [{ userId: 'u1', state: 'pending' }, { userId: 'u6', state: 'pending' }],
    status: 'draft', conflicts: false, createdAt: mins(60 * 5),
    checks: [{ name: '流水线 #89', status: 'running' }],
    diffs: [{ path: 'src/pages/Dashboard.tsx', status: 'modified', additions: 145, deletions: 88, lines: dashboardDiff }],
    comments: [{ id: 'mc4', authorId: 'u6', text: '环图图例建议放右侧，跟设计稿 v3 对齐。', createdAt: mins(60 * 2) }],
    linkedWorkItemKey: 'T1-126', additions: 465, deletions: 100,
    unitTestCheck: { hasTests: true, testFiles: ['src/pages/Dashboard.test.tsx'], passed: true, coverageTotal: 62, coverageDelta: 71, gatePassed: false },
    conflictFiles: [], rebaseRequired: false,
  },
  {
    id: 'mr4', number: 39, title: 'chore: 升级 Spring Boot 3.2 → 3.3', repoId: 'r1',
    description: 'LTS 跟进，全量回归通过。\n\nCloses T1-124',
    sourceBranch: 'chore/boot-3.3', targetBranch: 'master', authorId: 'u5',
    reviewers: [{ userId: 'u3', state: 'approved' }, { userId: 'u1', state: 'approved' }],
    status: 'open', conflicts: false, createdAt: mins(60 * 30),
    checks: [{ name: '流水线 #310', status: 'passed' }, { name: '覆盖率门禁 75.9%', status: 'passed' }],
    diffs: [{ path: 'pom.xml', status: 'modified', additions: 3, deletions: 3, lines: [
      { type: 'ctx', oldNo: 10, newNo: 10, text: '<parent>' },
      { type: 'del', oldNo: 11, newNo: undefined, text: '  <version>3.2.7</version>' },
      { type: 'add', oldNo: undefined, newNo: 11, text: '  <version>3.3.2</version>' },
      { type: 'ctx', oldNo: 12, newNo: 12, text: '</parent>' },
    ] }],
    comments: [], linkedWorkItemKey: 'T1-124', additions: 12, deletions: 9,
    unitTestCheck: { hasTests: false, testFiles: [], passed: true, coverageTotal: 75.9, coverageDelta: 100, gatePassed: true, exempt: { reason: '纯依赖升级，无业务逻辑变更', approvedById: 'u1' } },
    conflictFiles: [], basedOnBaselineId: 'bl2', rebaseRequired: false,
  },
  {
    id: 'mr5', number: 37, title: 'feat: 审计日志异步导出', repoId: 'r1',
    description: '审计导出改异步任务。\n\nCloses T1-125',
    sourceBranch: 'feat/audit-async-export', targetBranch: 'master', authorId: 'u7',
    reviewers: [{ userId: 'u3', state: 'approved' }, { userId: 'u1', state: 'approved' }],
    status: 'merged', conflicts: false, createdAt: mins(60 * 24 * 3),
    checks: [{ name: '流水线 #305', status: 'passed' }],
    diffs: [], comments: [], linkedWorkItemKey: 'T1-125', additions: 286, deletions: 64,
    unitTestCheck: { hasTests: true, testFiles: ['AuditExportTaskTest.java'], passed: true, coverageTotal: 74, coverageDelta: 82, gatePassed: true },
    conflictFiles: [],
  },
  {
    id: 'mr6', number: 36, title: 'fix: 登录验证码防爆破', repoId: 'r1',
    description: '验证码接口频控。\n\nCloses T1-123',
    sourceBranch: 'fix/captcha-throttle', targetBranch: 'master', authorId: 'u3',
    reviewers: [{ userId: 'u1', state: 'approved' }, { userId: 'u4', state: 'approved' }],
    status: 'merged', conflicts: false, createdAt: mins(60 * 24 * 5),
    checks: [{ name: '流水线 #298', status: 'passed' }],
    diffs: [], comments: [], linkedWorkItemKey: 'T1-123', additions: 96, deletions: 18,
    unitTestCheck: { hasTests: true, testFiles: ['CaptchaThrottleTest.java'], passed: true, coverageTotal: 73.5, coverageDelta: 90, gatePassed: true },
    conflictFiles: [],
  },
]

// ---------- CI/CD / 制品 / 环境 ----------
function log(info: string[]): string[] { return info }
export const pipelines: Pipeline[] = [
  {
    id: 'p1', repoId: 'r1', title: '#312 · feat: 执行器调度失败重试与熔断', branch: 'feat/executor-retry',
    commitShort: 'a3f92c1', commitMsg: 'feat: 执行器调度失败重试与熔断', trigger: 'push', triggerUserId: 'u3',
    status: 'passed', startedAt: mins(20), durationSec: 284, unitTestJobId: 'p1j2',
    stages: [
      { name: '构建', jobs: [{ id: 'p1j1', name: 'Maven 构建', status: 'passed', durationSec: 96, log: log(['[INFO] Building teamone-server 2.4.0-SNAPSHOT', '[INFO] BUILD SUCCESS']) }] },
      { name: '测试', jobs: [
        { id: 'p1j2', name: '单元测试', status: 'passed', durationSec: 122, log: log(['[INFO] Running DispatcherRetryPolicyTest', '[INFO] Tests run: 1284, Failures: 0', '[INFO] 新增重试策略用例 6/6 通过']) },
        { id: 'p1j3', name: '覆盖率门禁', status: 'passed', durationSec: 18, log: log(['[INFO] JaCoCo 总覆盖率: 76.2%', '[INFO] patch 覆盖率: 88%', '[INFO] ✔ 双阈值达标 (≥60% / ≥80%)']) },
      ] },
      { name: '质量扫描', jobs: [
        { id: 'p1j4', name: 'SonarQube', status: 'passed', durationSec: 34, log: log(['[INFO] Quality gate: PASSED']) },
        { id: 'p1j5', name: '安全扫描', status: 'passed', durationSec: 26, log: log(['[INFO] 高危依赖: 0', '[INFO] 秘钥泄漏检测: 通过']) },
      ] },
      { name: '打包制品', jobs: [
        { id: 'p1j6', name: '构建 Jar', status: 'passed', durationSec: 42, log: log(['[INFO] teamone-server-2.4.0-rc.3.jar (86.4 MB)']) },
        { id: 'p1j7', name: '构建镜像', status: 'passed', durationSec: 38, log: log(['[INFO] registry.teamone.dev/teamone-server:2.4.0-rc.3']) },
      ] },
      { name: '部署测试环境', jobs: [
        { id: 'p1j8', name: '部署 staging', status: 'passed', durationSec: 27, log: log(['[INFO] Rollout complete: 6/6 pods ready']) },
        { id: 'p1j9', name: '冒烟测试', status: 'passed', durationSec: 15, log: log(['[INFO] 冒烟用例 24/24 通过']) },
      ] },
    ],
  },
  {
    id: 'p2', repoId: 'r1', title: '#311 · fix: Webhook 投递补充幂等键校验', branch: 'fix/webhook-idempotent',
    commitShort: 'e1d7f03', commitMsg: 'fix: Webhook 投递补充幂等键', trigger: 'mr', triggerUserId: 'u7',
    status: 'failed', startedAt: mins(300), durationSec: 187, unitTestJobId: 'p2j2',
    stages: [
      { name: '构建', jobs: [{ id: 'p2j1', name: 'Maven 构建', status: 'passed', durationSec: 91, log: log(['[INFO] BUILD SUCCESS']) }] },
      { name: '测试', jobs: [{ id: 'p2j2', name: '单元测试', status: 'passed', durationSec: 118, log: log(['[INFO] Tests run: 1271, Failures: 0', '[WARN] 变更代码未关联测试文件（启发式 A 未命中）']) }] },
      { name: '质量扫描', jobs: [
        { id: 'p2j4', name: 'SonarQube', status: 'passed', durationSec: 33, log: log(['[INFO] Quality gate: PASSED']) },
        { id: 'p2j5', name: '安全扫描', status: 'failed', durationSec: 29, log: log(['[ERROR] 高危漏洞: commons-io 2.11 (CVE-2024-47554)', '[ERROR] 质量红线拦截，流水线终止']) },
      ] },
      { name: '打包制品', jobs: [{ id: 'p2j6', name: '构建 Jar', status: 'skipped', log: [] }] },
      { name: '部署测试环境', jobs: [{ id: 'p2j8', name: '部署 staging', status: 'skipped', log: [] }] },
    ],
  },
  {
    id: 'p3', repoId: 'r2', title: '#89 · feat: 效能报表看板 2.0 改版', branch: 'feat/dashboard-redesign',
    commitShort: '9a77b21', commitMsg: 'style: 图表配色对齐设计稿', trigger: 'push', triggerUserId: 'u2',
    status: 'running', startedAt: mins(6), durationSec: 0, unitTestJobId: 'p3j2',
    stages: [
      { name: '构建', jobs: [{ id: 'p3j1', name: 'pnpm 构建', status: 'passed', durationSec: 64, log: log(['[INFO] vite build ✓']) }] },
      { name: '测试', jobs: [
        { id: 'p3j2', name: '单元测试', status: 'running', log: ['[INFO] vitest run …', '[INFO] 运行中 132/210 用例'] },
        { id: 'p3j3', name: 'E2E 冒烟', status: 'pending', log: [] },
      ] },
      { name: '部署测试环境', jobs: [{ id: 'p3j5', name: '部署 preview', status: 'pending', log: [] }] },
    ],
  },
  {
    id: 'p4', repoId: 'r1', title: '#310 · chore: 升级 Spring Boot 3.3', branch: 'chore/boot-3.3',
    commitShort: '5b8e1d2', commitMsg: 'chore: bump boot 3.3.2', trigger: 'mr', triggerUserId: 'u5',
    status: 'passed', startedAt: mins(60 * 28), durationSec: 302, unitTestJobId: 'p4j2',
    stages: [
      { name: '构建', jobs: [{ id: 'p4j1', name: 'Maven 构建', status: 'passed', durationSec: 99, log: log(['[INFO] BUILD SUCCESS (boot 3.3.2)']) }] },
      { name: '测试', jobs: [{ id: 'p4j2', name: '单元测试', status: 'passed', durationSec: 126, log: log(['[INFO] Tests run: 1271, Failures: 0']) }] },
      { name: '打包制品', jobs: [{ id: 'p4j6', name: '构建 Jar', status: 'passed', durationSec: 44, log: log(['[INFO] teamone-server-3.3.2-check.jar']) }] },
    ],
  },
  {
    id: 'p5', repoId: 'r4', title: '#57 · ci: 研发周报自动生成', branch: 'main',
    commitShort: 'd95f4c7', commitMsg: 'ci: 周报流水线接入 LLM 摘要', trigger: 'schedule', triggerUserId: 'u1',
    status: 'passed', startedAt: mins(60 * 3), durationSec: 88,
    stages: [
      { name: '数据采集', jobs: [{ id: 'p5j1', name: '拉取平台数据', status: 'passed', durationSec: 21, log: log(['[INFO] 汇总 4 仓库 / 38 MR / 116 提交']) }] },
      { name: '报告生成', jobs: [{ id: 'p5j2', name: 'LLM 摘要 + 推送 IM', status: 'passed', durationSec: 46, log: log(['[INFO] 已推送至 #产品研发部']) }] },
    ],
  },
  {
    id: 'p6', repoId: 'r3', title: '#44 · perf: 桌面端冷启动优化', branch: 'main',
    commitShort: '6f1e8ba', commitMsg: 'perf: 冷启动延迟加载模块', trigger: 'push', triggerUserId: 'u8',
    status: 'passed', startedAt: mins(60 * 25), durationSec: 211,
    stages: [
      { name: '构建', jobs: [{ id: 'p6j1', name: 'Electron 构建', status: 'passed', durationSec: 120, log: log(['[INFO] TeamOne-Setup-2.3.1.exe (96.3MB)']) }] },
      { name: '测试', jobs: [{ id: 'p6j2', name: '单测 + 冒烟', status: 'passed', durationSec: 63, log: log(['[INFO] 冷启动实测 1.8s ✔']) }] },
      { name: '打包制品', jobs: [{ id: 'p6j3', name: '产出安装包', status: 'passed', durationSec: 12, log: log(['[INFO] TeamOne-Setup-2.3.1.exe']) }] },
    ],
  },
]

export const artifacts: Artifact[] = [
  { id: 'a1', name: 'teamone-server', version: '2.4.0-rc.3', type: 'jar', size: '86.4 MB', checksum: 'sha256:9f2a…c41d', builtAt: mins(15), pipelineId: 'p1', repoId: 'r1' },
  { id: 'a2', name: 'teamone-server', version: '2.4.0-rc.3', type: 'docker', size: '212 MB', checksum: 'sha256:1c88…77aa', builtAt: mins(14), pipelineId: 'p1', repoId: 'r1' },
  { id: 'a3', name: 'teamone-web', version: '2.3.6', type: 'npm', size: '4.2 MB', checksum: 'sha256:6d31…0f9e', builtAt: mins(60 * 5), pipelineId: 'p3', repoId: 'r2' },
  { id: 'a4', name: 'teamone-desktop', version: '2.3.1', type: 'zip', size: '96.3 MB', checksum: 'sha256:b0aa…33d2', builtAt: mins(60 * 24), pipelineId: 'p6', repoId: 'r3' },
  { id: 'a5', name: 'teamone-server', version: '3.3.2-check', type: 'jar', size: '85.7 MB', checksum: 'sha256:77e0…9c1b', builtAt: mins(60 * 28), pipelineId: 'p4', repoId: 'r1' },
  { id: 'a6', name: 'teamone-server', version: '2.3.0', type: 'jar', size: '84.1 MB', checksum: 'sha256:44ac…e870', builtAt: mins(60 * 24 * 10), pipelineId: 'p4', repoId: 'r1' },
]

export const deployEnvs: DeployEnv[] = [
  { id: 'e1', name: '开发', url: 'dev.teamone.dev', currentVersion: '2.4.0-rc.3', lastDeployAt: mins(12), status: 'healthy', deployedById: 'u5' },
  { id: 'e2', name: '测试', url: 'test.teamone.dev', currentVersion: '2.4.0-rc.2', lastDeployAt: mins(60 * 20), status: 'healthy', deployedById: 'u5' },
  { id: 'e3', name: '预发', url: 'staging.teamone.dev', currentVersion: '2.3.0', lastDeployAt: mins(60 * 24 * 5), status: 'healthy', deployedById: 'u5' },
  { id: 'e4', name: '生产', url: 'app.teamone.dev', currentVersion: '2.3.0', lastDeployAt: mins(60 * 24 * 10), status: 'healthy', deployedById: 'u1' },
]

// ---------- 部门×角色×资源 ACL ----------
export const resourcePermissions: ResourcePermission[] = [
  { id: 'rp1', departmentId: 'd1', resourceType: 'product', resourceId: 'p1', roleId: 'dept_lead', subjectType: 'role', subjectId: 'dept_lead', actions: ['view', 'edit', 'approve', 'release', 'manage'], grantedById: 'u1', createdAt: '2026-01-15' },
  { id: 'rp2', departmentId: 'd1', resourceType: 'release', resourceId: 'rel240', roleId: 'member', subjectType: 'user', subjectId: 'u4', actions: ['view', 'approve', 'release'], grantedById: 'u1', createdAt: '2026-08-20' },
  { id: 'rp3', departmentId: 'd1', resourceType: 'component', resourceId: 'c2', roleId: 'member', subjectType: 'user', subjectId: 'u2', actions: ['view', 'edit'], grantedById: 'u1', createdAt: '2026-06-01' },
  { id: 'rp4', departmentId: 'd1', resourceType: 'repo', resourceId: 'r1', roleId: 'guest', subjectType: 'user', subjectId: 'u8', actions: ['view'], grantedById: 'u1', createdAt: '2026-09-01' },
  { id: 'rp5', departmentId: 'd2', resourceType: 'component', resourceId: 'c3', roleId: 'guest', subjectType: 'role', subjectId: 'guest', actions: ['view'], grantedById: 'u8', createdAt: '2026-09-01' },
  { id: 'rp6', departmentId: 'd1', resourceType: 'release', resourceId: 'rel240', roleId: 'component_lead', subjectType: 'user', subjectId: 'u3', actions: ['view', 'approve'], grantedById: 'u1', createdAt: '2026-08-25' },
]
export const roleText: Record<string, string> = { dept_lead: '部门负责人', component_lead: '组件负责人', member: '成员', guest: '访客(外包)' }
export const resourceTypeText: Record<string, string> = { product: '产品', component: '组件', repo: '仓库', pipeline: '流水线', release: '版本', goal: '战略目标', roadmap: 'RoadMap', sprint: '迭代', baseline: '基线' }
export const actionText: Record<string, string> = { view: '查看', edit: '编辑', approve: '审批', release: '发布', manage: '管理' }

// ---------- 话题 ----------
let msgSeq = 100
function tmsg(authorId: string, text: string, agoMin: number): TopicMessage {
  return { id: `tm${msgSeq++}`, authorId, text, createdAt: mins(agoMin) }
}

export const topics: Topic[] = [
  { id: 'top1', title: '[缺陷] D-88 熔断打开后渠道切换死锁', targetType: 'defect', targetId: 'w22', relatedTargetIds: ['rel240', 'w18'], creatorId: 'u4', autoCreated: true, autoCreateReason: 'severity_high', participantIds: ['u4', 'u3', 'u1', 'u5'], unsubscribedIds: [], status: 'active', messages: [
    tmsg('u4', '压测 5000 TPS 强制打开熔断后，备用渠道没有接管，调度线程全部挂起，这是致命级，@赵子轩 今天必须给出定位。', 60 * 25),
    tmsg('u3', '已定位：熔断 OPEN 后 ExecutorRouter 重入锁未释放。修复方案：锁粒度改为 per-executor。', 60 * 22),
    tmsg('u5', '修复前 v2.4.0 保持发布锁定，我不批 release。', 60 * 20),
    tmsg('u1', '同意。修复后先让 @苏芮 回归 TT-9 全量用例再解锁。', 60 * 6),
  ], lastActiveAt: mins(60 * 6), createdAt: mins(60 * 26) },
  { id: 'top2', title: '[任务] T1-128 执行器调度失败重试与熔断', targetType: 'task', targetId: 'w1', relatedTargetIds: ['mr1', 'rel240'], creatorId: 'u1', autoCreated: true, autoCreateReason: 'object_created', participantIds: ['u3', 'u1', 'u5'], unsubscribedIds: [], status: 'active', messages: [
    tmsg('u1', '任务已创建，熔断阈值先保守，50% 错误率触发。', 60 * 24 * 6),
    tmsg('u3', '实现见 !42，6 场景单测全绿，请评审。', 118),
  ], lastActiveAt: mins(118), createdAt: mins(60 * 24 * 6) },
  { id: 'top3', title: '[版本] v2.4.0 提测与回归进展', targetType: 'release', targetId: 'rel240', relatedTargetIds: ['w18', 'w22'], creatorId: 'u5', autoCreated: true, autoCreateReason: 'release_testing', participantIds: ['u4', 'u1', 'u5', 'u3', 'u7'], unsubscribedIds: [], status: 'active', messages: [
    tmsg('u5', 'v2.4.0 进入 testing，回归 TT-8 已全通过，TT-9 进行中 71/86。', 60 * 24),
    tmsg('u4', '发现致命缺陷 D-88，版本已阻塞，发布按钮已锁定。', 60 * 25),
    tmsg('u1', 'D-88/D-87 关闭前不讨论发布窗口。', 60 * 5),
  ], lastActiveAt: mins(60 * 5), createdAt: mins(60 * 24) },
  { id: 'top4', title: '[目标] GOAL-1 发布列车：双周一版稳定交付', targetType: 'goal', targetId: 'g1', relatedTargetIds: ['rel240', 'rel250'], creatorId: 'u1', autoCreated: true, autoCreateReason: 'object_created', participantIds: ['u1', 'u6', 'u3'], unsubscribedIds: [], status: 'active', messages: [
    tmsg('u1', 'H2 目标：按期交付率 ≥ 90%。当前 v2.4.0 有阻塞风险，各位盯紧 deadline。', 60 * 24 * 30),
    tmsg('u6', '产品侧验收清单已就绪。', 60 * 24 * 12),
  ], lastActiveAt: mins(60 * 24 * 12), createdAt: mins(60 * 24 * 30) },
  { id: 'top5', title: '[目标] GOAL-2 流水线调度成功率 99.9%', targetType: 'goal', targetId: 'g2', relatedTargetIds: ['rm1'], creatorId: 'u3', autoCreated: true, autoCreateReason: 'object_created', participantIds: ['u3', 'u1', 'u5'], unsubscribedIds: [], status: 'active', messages: [
    tmsg('u3', '成功率当前 99.62%，重试与熔断上线后预计达标。', 60 * 24 * 10),
  ], lastActiveAt: mins(60 * 24 * 10), createdAt: mins(60 * 24 * 20) },
  { id: 'top6', title: '熔断阈值策略讨论（RoadMap：调度高可用）', targetType: 'roadmap', targetId: 'rm1', relatedTargetIds: [], creatorId: 'u1', autoCreated: false, participantIds: ['u1', 'u3', 'u5'], unsubscribedIds: [], status: 'active', messages: [
    tmsg('u1', '阈值 50% 是否太激进？观察一周数据再定。', 60 * 24 * 3),
    tmsg('u3', '压测数据支持 50%，附带半开恢复 30s。', 60 * 24 * 2),
    tmsg('u5', '同意，先灰度一个执行器组。', 60 * 24),
  ], lastActiveAt: mins(60 * 24), createdAt: mins(60 * 24 * 3) },
  { id: 'top7', title: '[缺陷] D-86 看板环图 10w 数据点渲染卡顿', targetType: 'defect', targetId: 'w24', relatedTargetIds: ['w19'], creatorId: 'u4', autoCreated: true, autoCreateReason: 'object_created', participantIds: ['u4', 'u2', 'u6'], unsubscribedIds: [], status: 'archived', archivedReason: 'object_closed', archivedAt: mins(60 * 25), pinnedConclusion: '采用虚拟化采样方案，10w 点渲染 45ms 达标，已随 TT-8 回归关闭。', messages: [
    tmsg('u4', '环图在 10w 点时主线程阻塞 1.2s。', 60 * 24 * 7),
    tmsg('u2', '改为采样聚合 + 按需全量，渲染 45ms。', 60 * 24 * 2),
    tmsg('u4', '回归通过，关闭。', 60 * 26),
  ], lastActiveAt: mins(60 * 26), createdAt: mins(60 * 24 * 7) },
  { id: 'top8', title: '[迭代] Sprint 24-2 · v2.3.0 冲刺总结', targetType: 'sprint', targetId: 's1', relatedTargetIds: ['rel230'], creatorId: 'u1', autoCreated: true, autoCreateReason: 'object_created', participantIds: ['u1', 'u2', 'u3', 'u4', 'u5', 'u7'], unsubscribedIds: [], status: 'archived', archivedReason: 'sprint_ended', archivedAt: mins(60 * 24 * 12), pinnedConclusion: '迭代 34 点全部交付，v2.3.0 按期发布；遗留项：覆盖率提升任务顺延至 24-3。', messages: [
    tmsg('u1', '迭代总结：34/34 点交付，无阻塞。', 60 * 24 * 12),
  ], lastActiveAt: mins(60 * 24 * 12), createdAt: mins(60 * 24 * 23) },
  { id: 'top9', title: '[测试任务] TT-9 v2.4.0 回归测试 · 调度与网关模块', targetType: 'testtask', targetId: 'w18', relatedTargetIds: ['w22', 'w23'], creatorId: 'u4', autoCreated: true, autoCreateReason: 'object_created', participantIds: ['u4', 'u3', 'u1', 'u5'], unsubscribedIds: [], status: 'active', messages: [
    tmsg('u4', '回归执行中：71/86 通过，剩余用例依赖 D-88 修复。', 60 * 24),
    tmsg('u3', 'D-88 修复今天提测，会通知你回归。', 60 * 3),
  ], lastActiveAt: mins(60 * 3), createdAt: mins(60 * 24 * 2) },
]

// ---------- IM ----------
export const channels: Channel[] = [
  { id: 'c1', name: '产品研发部', description: '全员频道', kind: 'channel', memberIds: ['u1', 'u2', 'u3', 'u4', 'u5', 'u6', 'u7', 'u8'], unread: 2 },
  { id: 'c2', name: '平台研发', description: '项目频道 · v2.4.0 冲刺', kind: 'channel', memberIds: ['u1', 'u2', 'u3', 'u4', 'u5', 'u7'], unread: 1 },
  { id: 'c3', name: '发布通告', description: 'CI/CD 与发布事件自动推送（只读）', kind: 'channel', memberIds: ['u1', 'u2', 'u3', 'u4', 'u5', 'u6', 'u7', 'u8'], unread: 3 },
  { id: 'c4', name: '客户端部', description: 'Web / 桌面端', kind: 'channel', memberIds: ['u1', 'u2', 'u7', 'u8'], unread: 0 },
  { id: 'd1', name: '林晚晴', description: '', kind: 'dm', memberIds: ['u1', 'u2'], unread: 1 },
  { id: 'd2', name: '韩雪', description: '', kind: 'dm', memberIds: ['u1', 'u6'], unread: 0 },
]
let imSeq = 200
function mkMsg(channelId: string, authorId: string, text: string, agoMin: number, attachments?: Message['attachments']): Message {
  return { id: `m${imSeq++}`, channelId, authorId, text, createdAt: mins(agoMin), attachments }
}
export const messages: Message[] = [
  mkMsg('c1', 'u6', '各位早，v2.4.0 计划 9-14 发布，当前被 D-88 阻塞，请相关同学聚焦缺陷修复 🙏', 60 * 8),
  mkMsg('c1', 'u5', '昨晚研发周报已自动生成：本周 38 个 MR、116 次提交；注意 9-19 是 v2.4.1 冻结日，距 v2.4.0 发布只有 5 天，调度资源时留意。', 60 * 3, [{ type: 'pipeline', refId: 'p5', label: '流水线 #57 · 研发周报' }]),
  mkMsg('c2', 'u4', 'TT-9 回归进行中：71/86 通过。发现致命缺陷 D-88（熔断死锁），v2.4.0 已被阻塞 🚨', 60 * 25, [{ type: 'defect', refId: 'w22', label: 'D-88 熔断打开后渠道切换死锁' }]),
  mkMsg('c2', 'u3', 'D-88 根因定位：熔断 OPEN 后 ExecutorRouter 重入锁未释放。修复方案已发到话题，今晚提测。', 60 * 22),
  mkMsg('c2', 'u5', '修复合入前 v2.4.0 发布保持锁定。', 60 * 20),
  mkMsg('c3', 'system', '发布门禁：v2.4.0 存在未关闭致命/严重缺陷（D-88、D-87），发布按钮已锁定', 60 * 24, [{ type: 'release', refId: 'rel240', label: 'v2.4.0' }]),
  mkMsg('c3', 'system', '流水线 #312 运行成功 · teamone-server 2.4.0-rc.3 制品已产出（Jar + Docker）', 14, [{ type: 'pipeline', refId: 'p1', label: '流水线 #312' }]),
  mkMsg('c3', 'system', '基线 BL-R2.4-分配基线 已提交审批（当前 1/2 批准）', 60 * 24, [{ type: 'mr', refId: 'mr1', label: '!42' }]),
  mkMsg('c4', 'u8', '桌面端冷启动 1.8s 达标 🚀，DT 2.1.0 还剩托盘修复。', 60 * 25),
  mkMsg('c4', 'u2', '环图虚拟化采样方案文档我发到话题了。', 60 * 24),
  mkMsg('d1', 'u2', '墨哥，看板 2.0 交互稿 v3 更新了，review 下 !40 顺便确认视觉 👀', 55),
  mkMsg('d2', 'u6', 'v2.4.0 发布通告草稿好了，等阻塞解除就能发。', 60 * 6),
]

// ---------- 动态 ----------
// D-91 收口：store.activities/addActivity 原型动态流已删除——工作台「最近动态」改由
// DashboardPage 直连真实数据源（GET /notifications + GET /work-items 合并排序）。

// ============================================================
// 订阅
// ============================================================
let version = 0
const listeners = new Set<() => void>()
function bump() { version++; listeners.forEach((l) => l()) }
function subscribeStore(l: () => void) { listeners.add(l); return () => { listeners.delete(l) } }
export function useStore(): number { return useSyncExternalStore(subscribeStore, () => version) }
export { bump }

// ---------- 通用 getters ----------
export function userById(id: string): User | undefined { return users.find((u) => u.id === id) }
export function departmentById(id: string): Department | undefined { return departments.find((d) => d.id === id) }
export function productById(id: string): Product | undefined { return products.find((p) => p.id === id) }
export function componentById(id: string): Component | undefined { return components.find((c) => c.id === id) }
export function goalById(id: string): StrategicGoal | undefined { return goals.find((g) => g.id === id) }
export function releaseById(id: string): Release | undefined { return releases.find((r) => r.id === id) }
export function sprintById(id: string): Sprint | undefined { return sprints.find((s) => s.id === id) }
export function repoById(id: string): Repo | undefined { return repos.find((r) => r.id === id) }
export function mrById(id: string): MergeRequest | undefined { return mergeRequests.find((m) => m.id === id) }
export function pipelineById(id: string): Pipeline | undefined { return pipelines.find((p) => p.id === id) }
export function topicById(id: string): Topic | undefined { return topics.find((t) => t.id === id) }
export function baselineById(id: string): Baseline | undefined { return baselines.find((b) => b.id === id) }
export function workTreeById(id: string): WorkTreeItem | undefined { return workTrees.find((w) => w.id === id) }
export function workItemById(id: string): WorkItem | undefined { return workItems.find((w) => w.id === id) }
export function workItemByKey(key: string): WorkItem | undefined { return workItems.find((w) => w.key === key) }
export function requirementById(id: string): Requirement | undefined { return requirements.find((r) => r.id === id) }
export function channelById(id: string): Channel | undefined { return channels.find((c) => c.id === id) }
export function messagesOf(channelId: string): Message[] { return messages.filter((m) => m.channelId === channelId) }
export function topicsOfTarget(targetType: TopicTargetType, targetId: string): Topic[] {
  return topics.filter((t) => t.targetType === targetType && t.targetId === targetId)
}

/** 对象展示名（话题标题 / 跳转卡片用） */
export function targetLabel(targetType: TopicTargetType, targetId: string): string {
  const map: Record<TopicTargetType, string> = {
    goal: '目标', requirement: '需求', roadmap: 'RoadMap', sprint: '迭代', task: '任务', testtask: '测试任务',
    defect: '缺陷', release: '版本', repo: '仓库', worktree: '工作树', baseline: '基线', mr: 'MR',
  }
  const it = workItems.find((w) => w.id === targetId) ?? requirements.find((r) => r.id === targetId)
  if (it && 'key' in it) return `${map[targetType]} ${it.key}`
  const known: Record<string, string | undefined> = {
    g1: 'GOAL-1', g2: 'GOAL-2', g3: 'GOAL-3', rel230: 'v2.3.0', rel240: 'v2.4.0', rel241: 'v2.4.1', rel250: 'v2.5.0', relDt210: 'DT-v2.1.0',
    s1: 'Sprint 24-2', s2: 'Sprint 24-3', s3: 'Sprint 25-1', s4: 'Sprint CL-9',
    rm1: '调度高可用', rm2: '智能质量门禁', rm3: '开放 API 网关', rm4: '桌面体验', rm5: '效能看板 2.0', rm6: '合规审计 2.0', rm7: '同城双活',
    r1: 'teamone-server', r2: 'teamone-web', r3: 'teamone-desktop', r4: 'teamone-infra',
    mr1: '!42', mr2: '!41', mr3: '!40', mr4: '!39', bl2: 'BL-R2.3', bl3: 'BL-R2.4', bl4: 'BL-DT2.0',
    wt1: 'feat/executor-retry', wt4: 'perf/desktop-sdk',
    rq1: 'REQ-1', rq2: 'REQ-2', rq3: 'REQ-3', rq4: 'REQ-4', rq5: 'REQ-5', rq6: 'REQ-6', rq7: 'REQ-7', rq8: 'REQ-8',
  }
  const k = known[targetId]
  return k ? `${map[targetType]} ${k}` : map[targetType]
}

/** 跳转页（话题/对象卡片用） */
export function targetPage(targetType: TopicTargetType, targetId: string): { page: string; id?: string } {
  switch (targetType) {
    case 'goal': return { page: 'goals', id: targetId }
    case 'requirement': return { page: 'requirements', id: targetId }
    case 'roadmap': return { page: 'roadmap' }
    case 'sprint': return { page: 'tasks', id: targetId }
    case 'task': case 'testtask': return { page: 'tasks', id: targetId }
    case 'defect': return { page: 'defects', id: targetId }
    case 'release': return { page: 'delivery', id: targetId }
    case 'repo': return { page: 'repo', id: targetId }
    case 'worktree': return { page: 'repo' }
    case 'baseline': return { page: 'repo' }
    case 'mr': return { page: 'mr', id: targetId }
  }
}

// ============================================================
// 冲突检测 computeConflicts（§5.1 CF-1~CF-6）
// ============================================================
export function computeConflicts(): ConflictItem[] {
  const items: ConflictItem[] = []
  let seq = 1
  const add = (c: Omit<ConflictItem, 'id' | 'detectedAt'>) => {
    items.push({ ...c, id: `cf${seq++}`, detectedAt: fmt(new Date()) })
  }
  const open = workItems.filter((w) => w.status !== 'done' && w.status !== 'closed' && w.startDate && w.dueDate)

  // 步骤 1：工时摊平
  const load = new Map<string, Map<string, number>>()
  for (const t of open) {
    const span = workdays(t.startDate!, t.dueDate!)
    if (span.length === 0) continue
    const per = t.estimateHours / span.length
    const m = load.get(t.assigneeId) ?? new Map<string, number>()
    for (const d of span) m.set(d, (m.get(d) ?? 0) + per)
    load.set(t.assigneeId, m)
  }

  // CF-1 人员超载 / CF-4 跨项目争用
  for (const [uid, byDay] of load) {
    const u = userById(uid)
    if (!u) continue
    for (const [d, h] of byDay) {
      const cap = u.dailyCapacityHours
      if (h > cap) {
        const prods = new Set(open.filter((t) => t.assigneeId === uid && t.startDate! <= d && t.dueDate! >= d).map((t) => t.productId))
        if (prods.size >= 2) {
          add({ type: '跨项目争用', severity: 'red', subjectType: 'user', subjectId: uid, detail: `${u.name} ${d.slice(5)} 跨产品负载 ${h.toFixed(1)}h > 容量 ${cap}h（${[...prods].map((p) => productById(p)?.key ?? p).join(' + ')}）`, relatedTaskIds: open.filter((t) => t.assigneeId === uid && t.startDate! <= d && t.dueDate! >= d).map((t) => t.id) })
        } else {
          add({ type: '人员超载', severity: 'red', subjectType: 'user', subjectId: uid, detail: `${u.name} ${d.slice(5)} 负载 ${h.toFixed(1)}h > 容量 ${cap}h`, relatedTaskIds: open.filter((t) => t.assigneeId === uid && t.startDate! <= d && t.dueDate! >= d).map((t) => t.id) })
        }
      } else if (h >= cap * 0.85) {
        add({ type: '人员超载', severity: 'yellow', subjectType: 'user', subjectId: uid, detail: `${u.name} ${d.slice(5)} 负载 ${h.toFixed(1)}h / ${cap}h（≥85%）`, relatedTaskIds: [] })
      }
    }
  }

  // CF-2 时间区间重叠（同人两个未完成任务分属不同迭代，或均为进行中）
  for (const u of users) {
    const ts = open.filter((t) => t.assigneeId === u.id).sort((a, b) => (a.startDate! < b.startDate! ? -1 : 1))
    for (let i = 0; i < ts.length - 1; i++) {
      for (let j = i + 1; j < ts.length; j++) {
        const a = ts[i], b = ts[j]
        const overlap = a.startDate! <= b.dueDate! && b.startDate! <= a.dueDate!
        const differentSprint = a.sprintId !== b.sprintId
        const bothActive = a.status === 'in_progress' && b.status === 'in_progress'
        if (overlap && (differentSprint || bothActive) && a.id !== b.id) {
          add({ type: '时间区间重叠', severity: 'red', subjectType: 'user', subjectId: u.id, detail: `${u.name}：「${a.key}」与「${b.key}」时间区间重叠（${differentSprint ? '跨迭代' : '同时进行中'}）`, relatedTaskIds: [a.id, b.id] })
        }
      }
    }
  }

  // CF-3 里程碑挤压（同产品相邻发布：r2 冻结距 r1 发布 < 7 天）
  const byProduct = new Map<string, Release[]>()
  for (const r of releases) {
    const arr = byProduct.get(r.productId) ?? []
    arr.push(r)
    byProduct.set(r.productId, arr)
  }
  for (const [, arr] of byProduct) {
    const sorted = [...arr].sort((a, b) => a.planDate.localeCompare(b.planDate))
    for (let i = 0; i < sorted.length - 1; i++) {
      const r1 = sorted[i], r2 = sorted[i + 1]
      const gap = daysBetween(r1.planDate, r2.codeFreezeDate)
      if (gap < 7) {
        add({ type: '里程碑挤压', severity: 'yellow', subjectType: 'release', subjectId: r2.id, detail: `${r2.name} 冻结（${r2.codeFreezeDate.slice(5)}）距 ${r1.name} 发布（${r1.planDate.slice(5)}）仅 ${Math.max(gap, 0)} 天（< 7 天）`, relatedTaskIds: [] })
      }
    }
  }

  // CF-5 依赖倒挂
  for (const t of open) {
    for (const bId of t.blockedByIds) {
      const b = workItemById(bId)
      if (!b) continue
      if ((t.dueDate && b.dueDate && t.dueDate < b.dueDate) || (t.sprintId && b.sprintId && sprintEnd(t.sprintId) < sprintEnd(b.sprintId))) {
        add({ type: '依赖倒挂', severity: 'red', subjectType: 'task', subjectId: t.id, detail: `「${t.key}」截止晚于其依赖「${b.key}」的截止`, relatedTaskIds: [t.id, b.id] })
      }
    }
  }

  // CF-6 Deadline 越级
  for (const t of open) {
    const se = t.sprintId ? sprintEnd(t.sprintId) : undefined
    const rp = t.releaseId ? releaseById(t.releaseId)?.planDate : undefined
    if (t.dueDate && ((se && t.dueDate > se) || (rp && t.dueDate > rp))) {
      const which = se && t.dueDate > se ? `迭代截止 ${se}` : `版本发布日 ${rp}`
      add({ type: 'Deadline越级', severity: 'red', subjectType: 'task', subjectId: t.id, detail: `「${t.key}」截止 ${t.dueDate} 晚于${which}`, relatedTaskIds: [t.id] })
    }
  }

  items.sort((a, b) => (a.severity === b.severity ? a.type.localeCompare(b.type) : a.severity === 'red' ? -1 : 1))
  return items
}

function sprintEnd(sprintId: string): string {
  return sprintById(sprintId)?.end ?? '9999-12-31'
}

// ============================================================
// 动作（含话题事件表 §5.2 联动）
// ============================================================
let idSeq = 1000
const nextId = (p: string) => `${p}${idSeq++}`
function addSystemMsg(channelId: string, text: string, attachments?: Message['attachments']) {
  messages.push({ id: nextId('m'), channelId, authorId: 'system', text, createdAt: fmt(new Date()), attachments })
}

export const workItemStatusText: Record<WorkItemStatus, string> = {
  todo: '待处理', in_progress: '进行中', in_review: '待验收', blocked: '受阻', done: '已完成', closed: '已关闭',
}
export const defectStatusFlow: Defect['status'][] = ['新建', '修复中', '已修复', '回归通过', '已关闭']
export const severityTone: Record<DefectSeverity, 'bad' | 'warn' | 'info' | 'neutral'> = { 致命: 'bad', 严重: 'warn', 一般: 'info', 轻微: 'neutral' }

/** 干系人规则表：返回话题应拉入的成员（去重） */
export function stakeholdersFor(targetType: TopicTargetType, targetId: string): string[] {
  const set = new Set<string>()
  const push = (id?: string) => id && set.add(id)
  const it = workItems.find((w) => w.id === targetId)
  if (targetType === 'requirement') {
    const rq = requirements.find((x) => x.id === targetId)
    if (rq) {
      push(rq.proposerId); push(rq.ownerId); rq.reviewerIds.forEach(push)
      push(productById(rq.productId)?.leadId)
      if (rq.goalId) push(goalById(rq.goalId)?.ownerId)
    }
  } else if (targetType === 'defect' && it) {
    const d = it as Defect
    push(d.reportedById); push(d.assigneeId)
    const foundIn = d.foundInTestTaskId ? workItemById(d.foundInTestTaskId) : undefined
    if (foundIn) { push(foundIn.assigneeId); (foundIn as TestTask).verifierIds.forEach(push) }
    if (d.blockedReleaseId) releaseById(d.blockedReleaseId)?.ownerIds.forEach(push)
    if (d.componentId) push(componentById(d.componentId)?.leadId)
    push(departmentById(productById(d.productId)?.departmentId ?? '')?.leadId)
  } else if ((targetType === 'task' || targetType === 'testtask') && it) {
    push(it.assigneeId); push(it.creatorId)
    if (it.componentId) push(componentById(it.componentId)?.leadId)
    if (it.releaseId) releaseById(it.releaseId)?.ownerIds.forEach(push)
    if (it.roadmapItemId) roadmapItems.find((r) => r.id === it.roadmapItemId)?.ownerIds.forEach(push)
  } else if (targetType === 'sprint') {
    const sp = sprintById(targetId)
    if (sp) { workItems.filter((w) => w.sprintId === sp.id).forEach((w) => push(w.assigneeId)); push(productById(sp.productId)?.leadId) }
  } else if (targetType === 'release') {
    const r = releaseById(targetId)
    if (r) { r.ownerIds.forEach(push); workItems.filter((w) => w.releaseId === r.id && w.type === 'testtask').forEach((w) => push(w.assigneeId)); push(productById(r.productId)?.leadId) }
  } else if (targetType === 'goal') {
    const g = goalById(targetId)
    if (g) { push(g.ownerId); g.productIds.forEach((pid) => push(productById(pid)?.leadId)); push(departmentById(g.departmentId)?.leadId) }
  } else if (targetType === 'roadmap') {
    const rm = roadmapItems.find((x) => x.id === targetId)
    if (rm) { rm.ownerIds.forEach(push); if (rm.goalId) push(goalById(rm.goalId)?.ownerId); if (rm.productId) push(productById(rm.productId)?.leadId) }
  } else if (targetType === 'mr') {
    const mr = mrById(targetId)
    if (mr) { push(mr.authorId); mr.reviewers.forEach((r) => push(r.userId)); push(repoById(mr.repoId)?.leadId) }
  } else if (targetType === 'repo' || targetType === 'baseline' || targetType === 'worktree') {
    const repoId = targetType === 'repo' ? targetId : undefined
    if (repoId) push(repoById(repoId)?.leadId)
    push('u1')
  }
  set.delete(CURRENT_USER_ID)
  return [...set]
}

const targetTypeZh: Record<TopicTargetType, string> = {
  goal: '目标', requirement: '需求', roadmap: 'RoadMap', sprint: '迭代', task: '任务', testtask: '测试任务', defect: '缺陷',
  release: '版本', repo: '仓库', worktree: '工作树', baseline: '基线', mr: 'MR',
}

/** 按对象自动建话题（事件表 §5.2） */
export function autoCreateTopic(targetType: TopicTargetType, targetId: string, reason: string): Topic {
  const it = workItems.find((w) => w.id === targetId)
  const title = it ? `[${targetTypeZh[targetType]}] ${it.key} ${it.title}` : `[${targetTypeZh[targetType]}] ${targetLabel(targetType, targetId)}`
  const participants = stakeholdersFor(targetType, targetId)
  const topic: Topic = {
    id: nextId('top'), title, targetType, targetId, relatedTargetIds: [], creatorId: it?.creatorId ?? CURRENT_USER_ID,
    autoCreated: true, autoCreateReason: reason, participantIds: participants, unsubscribedIds: [],
    status: 'active', messages: [], lastActiveAt: fmt(new Date()), createdAt: fmt(new Date()),
  }
  topics.unshift(topic)
  return topic
}

/** 手动/自动建话题统一入口 */
export function createTopic(input: { targetType: TopicTargetType; targetId: string; title?: string; autoCreated?: boolean; reason?: string; firstMessage?: string }): Topic {
  const topic = autoCreateTopic(input.targetType, input.targetId, input.reason ?? 'object_created')
  if (!input.autoCreated) { topic.autoCreated = false; topic.autoCreateReason = undefined }
  if (input.title) topic.title = input.title
  if (input.firstMessage) topic.messages.push({ id: nextId('tm'), authorId: CURRENT_USER_ID, text: input.firstMessage, createdAt: fmt(new Date()) })
  bump()
  return topic
}

export function addTopicMessage(topicId: string, text: string) {
  const t = topicById(topicId)
  if (!t || !text.trim()) return
  if (t.status === 'archived') { t.status = 'active'; t.archivedReason = undefined; t.archivedAt = undefined } // 归档话题评论即重开（简化演示）
  t.messages.push({ id: nextId('tm'), authorId: CURRENT_USER_ID, text, createdAt: fmt(new Date()) })
  t.lastActiveAt = fmt(new Date())
  bump()
}

/** 发送频道/私聊消息（支持前端离线或演示双向互动） */
export function addChannelMessage(channelId: string, authorId: string, text: string, attachments?: Message['attachments']) {
  if (!text.trim()) return
  const msg: Message = {
    id: nextId('m'),
    channelId,
    authorId,
    text: text.trim(),
    createdAt: fmt(new Date()),
    attachments,
  }
  messages.push(msg)
  const ch = channelById(channelId)
  if (ch && authorId !== CURRENT_USER_ID) {
    ch.unread = (ch.unread ?? 0) + 1
  }
  bump()
  return msg
}

/** 标记频道/私聊已读 */
export function markChannelRead(channelId: string) {
  const ch = channelById(channelId)
  if (ch && ch.unread) {
    ch.unread = 0
    bump()
  }
}

export function reopenTopic(topicId: string) {
  const t = topicById(topicId)
  if (!t) return
  t.status = 'active'; t.archivedReason = undefined; t.archivedAt = undefined
  bump()
}

function archiveTopicInternal(topicId: string | undefined, reason: Topic['archivedReason'], conclusion?: string) {
  if (!topicId) return
  const t = topicById(topicId)
  if (!t || t.status !== 'active') return
  t.status = 'archived'
  t.archivedReason = reason
  t.archivedAt = fmt(new Date())
  if (conclusion) t.pinnedConclusion = conclusion
}

/** 更新工作项状态：联动话题生命周期 + 版本阻塞重算（§5.2 事件表） */
export function setWorkItemStatus(id: string, status: string) {
  const it = workItemById(id)
  if (!it) return
  it.status = status as WorkItemStatus
  it.updatedAt = fmt(new Date())
  // 需求联动：首个开发任务启动 → 需求进入「开发中」
  if (it.type === 'task' && status === 'in_progress' && it.requirementId) {
    const rq = requirementById(it.requirementId)
    if (rq && rq.status === 'accepted') { rq.status = 'in_dev'; rq.updatedAt = fmt(new Date()) }
  }
  if (it.type === 'defect') {
    const d = it as Defect
    recalcReleaseBlocked(d.blockedReleaseId)
    if (status === '已关闭') {
      archiveTopicInternal(d.topicId, 'object_closed', `缺陷 ${d.key} 已关闭。`)
    }
    if (status === '重新打开' && d.topicId) {
      const t = topicById(d.topicId)
      if (t) { t.status = 'active'; t.archivedReason = undefined; t.archivedAt = undefined }
    }
  }
  if ((it.type === 'task' || it.type === 'testtask') && status === 'closed') {
    archiveTopicInternal(it.topicId, 'object_closed')
  }
  bump()
}

function recalcReleaseBlocked(releaseId?: string) {
  if (!releaseId) return
  const rel = releaseById(releaseId)
  if (!rel) return
  const blockers = defects.filter((d) => d.blockedReleaseId === releaseId && (d.severity === '致命' || d.severity === '严重') && d.status !== '已关闭' && d.status !== '回归通过')
  rel.blocked = blockers.length > 0
  rel.blockedDefectIds = blockers.map((d) => d.id)
}

export function completeSprint(sprintId: string) {
  const sp = sprintById(sprintId)
  if (!sp || sp.status !== 'active') return
  sp.status = 'done'
  const topic = topics.find((t) => t.targetType === 'sprint' && t.targetId === sprintId)
  const openCount = workItems.filter((w) => w.sprintId === sprintId && w.status !== 'done' && w.status !== 'closed').length
  archiveTopicInternal(topic?.id, 'sprint_ended', `迭代结束，未关闭工作项 ${openCount} 件顺延。`)
  bump()
}

export function publishRelease(releaseId: string): { ok: boolean; blockers: Defect[] } {
  const rel = releaseById(releaseId)
  if (!rel) return { ok: false, blockers: [] }
  const blockers = rel.blockedDefectIds.map((id) => workItemById(id) as Defect).filter(Boolean)
  if (rel.blocked) return { ok: false, blockers }
  rel.status = 'released'
  rel.releasedAt = fmt(new Date())
  const topic = topics.find((t) => t.targetType === 'release' && t.targetId === releaseId)
  archiveTopicInternal(topic?.id, 'release_released', `${rel.name} 正式发布！发布说明已同步知识库。`)
  addSystemMsg('c3', `🎉 ${rel.name} 正式发布！`, [{ type: 'release', refId: releaseId, label: rel.name }])
  bump()
  return { ok: true, blockers: [] }
}

// ---- Issue 风格兼容动作（工作项） ----
export function addWorkItemComment(_workItemId: string, _text: string) {
  // v2 原型：评论统一收敛到话题（对象页「讨论」Tab 引导到话题）
  bump()
}

export function createWorkItem(input: { type: 'task' | 'testtask' | 'defect'; title: string; description?: string; priority?: WorkItemBaseP; assigneeId: string; sprintId?: string; releaseId?: string; severity?: DefectSeverity; componentId?: string; dueDate?: string; estimateHours?: number }): WorkItem {
  const prefix = input.type === 'defect' ? 'D' : input.type === 'testtask' ? 'TT' : 'T1'
  const n = 140 + workItems.filter((w) => w.key.startsWith(prefix)).length
  const base = {
    id: nextId('w'), key: `${prefix}-${n}`, title: input.title, description: input.description ?? '',
    status: (input.type === 'defect' ? '新建' : 'todo') as never, priority: (input.priority ?? 'P1') as never,
    productId: 'p1', componentId: input.componentId, sprintId: input.sprintId, releaseId: input.releaseId,
    blockedByIds: [], assigneeId: input.assigneeId, creatorId: CURRENT_USER_ID,
    estimateHours: input.estimateHours ?? 8, points: 3, labels: [],
    createdAt: fmt(new Date()), updatedAt: fmt(new Date()),
  }
  let item: WorkItem
  if (input.type === 'defect') {
    item = { ...base, type: 'defect', severity: input.severity ?? '一般', status: '新建' as never, reportedById: CURRENT_USER_ID, reopenedCount: 0 } as Defect
    defects.unshift(item as Defect)
  } else if (input.type === 'testtask') {
    item = { ...base, type: 'testtask', caseCount: 0, passedCount: 0, relatedDefectIds: [], verifierIds: [input.assigneeId] } as TestTask
    testTasks.unshift(item as TestTask)
  } else {
    item = { ...base, type: 'task', linkedMrIds: [] } as Task
    tasks.unshift(item as Task)
  }
  workItems.push(item)
  // 事件表：对象创建 → 自动建话题（缺陷致命/严重为强制）
  const reason = input.type === 'defect' && (input.severity === '致命' || input.severity === '严重') ? 'severity_high' : 'object_created'
  const topic = autoCreateTopic(input.type, item.id, reason)
  item.topicId = topic.id
  bump()
  return item
}
type WorkItemBaseP = 'P0' | 'P1' | 'P2' | 'P3'

// ---- MR ----
export function reviewMr(mrId: string, state: 'approved' | 'changes_requested') {
  const mr = mrById(mrId)
  if (!mr) return
  const r = mr.reviewers.find((x) => x.userId === CURRENT_USER_ID)
  if (r) r.state = state
  mr.comments.push({ id: nextId('c'), authorId: CURRENT_USER_ID, text: state === 'approved' ? '已批准 ✔ 含单测检测与基线核查。' : '请修改后再评审。', createdAt: fmt(new Date()) })
  bump()
}
export function addMrComment(mrId: string, text: string) {
  const mr = mrById(mrId)
  if (!mr || !text.trim()) return
  mr.comments.push({ id: nextId('c'), authorId: CURRENT_USER_ID, text, createdAt: fmt(new Date()) })
  bump()
}
export function exemptMrUnitTest(mrId: string, reason: string) {
  const mr = mrById(mrId)
  if (!mr || !reason.trim()) return
  mr.unitTestCheck = { ...mr.unitTestCheck, gatePassed: true, exempt: { reason, approvedById: CURRENT_USER_ID } }
  bump()
}
export function resolveMrConflict(mrId: string, filePath: string, solution: string) {
  const mr = mrById(mrId)
  if (!mr) return
  mr.conflictFiles = mr.conflictFiles.filter((f) => f !== filePath)
  ;(mr.conflictResolutions ??= []).push({ filePath, solution, confirmedById: CURRENT_USER_ID, reviewedById: mr.reviewers[0]?.userId ?? 'u1', resolvedAt: fmt(new Date()) })
  if (mr.conflictFiles.length === 0) mr.conflicts = false
  bump()
}
export function markRebased(mrId: string) {
  const mr = mrById(mrId)
  if (!mr) return
  mr.rebaseRequired = false
  bump()
}
export function mergeMr(mrId: string): { ok: boolean; reasons: string[] } {
  const mr = mrById(mrId)
  if (!mr || mr.status !== 'open') return { ok: false, reasons: ['MR 状态不可合并'] }
  const reasons: string[] = []
  if (mr.reviewers.some((r) => r.state !== 'approved')) reasons.push('评审未全部批准')
  if (mr.checks.some((c) => c.status === 'failed')) reasons.push('存在失败检查项')
  if (!mr.unitTestCheck.gatePassed) reasons.push('单测门禁未通过')
  if (mr.rebaseRequired) reasons.push('落后基线需先 rebase')
  if (mr.conflictFiles.length > 0) reasons.push('存在未解决冲突文件')
  if (reasons.length > 0) return { ok: false, reasons }
  mr.status = 'merged'
  addSystemMsg('c2', `!${mr.number} ${mr.title} 已合入 ${mr.targetBranch}`, [{ type: 'mr', refId: mrId, label: `!${mr.number}` }])
  const wi = mr.linkedWorkItemKey ? workItemByKey(mr.linkedWorkItemKey) : undefined
  if (wi && wi.type === 'task' && wi.status === 'in_review') {
    wi.status = 'done'
    wi.updatedAt = fmt(new Date())
  }
  bump()
  return { ok: true, reasons: [] }
}

// ---- 基线 ----
export function approveBaseline(baselineId: string) {
  const b = baselineById(baselineId)
  if (!b || b.status !== 'in_review') return
  if (!b.approverIds.includes(CURRENT_USER_ID)) b.approverIds.push(CURRENT_USER_ID)
  if (b.approverIds.length >= 2) {
    b.status = 'approved'
    // 旧的同仓库同类型 approved 基线 → superseded
    for (const other of baselines) {
      if (other.id !== b.id && other.repoId === b.repoId && other.type === b.type && other.status === 'approved') {
        other.status = 'superseded'
        other.supersededById = b.id
      }
    }
  }
  bump()
}
export function submitBaseline(baselineId: string) {
  const b = baselineById(baselineId)
  if (!b || b.status !== 'draft') return
  b.status = 'in_review'
  bump()
}

// ---- 需求（评审流程：草稿 → 待评审 → 已受理 → 开发中 → 已交付 → 已验收；驳回可重提） ----
export const reqFlowText: Record<string, string> = {
  draft: '草稿', pending_review: '待评审', accepted: '已受理', in_dev: '开发中',
  delivered: '已交付', closed: '已验收', rejected: '已拒绝',
}

export function createRequirement(input: {
  title: string; description?: string; priority?: 'P0' | 'P1' | 'P2' | 'P3'
  productId?: string; ownerId?: string; reviewerIds: string[]
  goalId?: string; releaseId?: string; estimatePoints?: number
  docContent?: string; docFileName?: string; docFileType?: string
  attachments?: { id?: string; name: string; size: number | string; url?: string; uploadedAt: string }[]
}): Requirement {
  const n = 9 + requirements.length
  const rq: Requirement = {
    id: nextId('rq'), key: `REQ-${n}`, title: input.title, description: input.description ?? '',
    status: 'draft', priority: input.priority ?? 'P2', productId: input.productId ?? 'p1',
    proposerId: CURRENT_USER_ID, ownerId: input.ownerId ?? 'u6', reviewerIds: input.reviewerIds,
    reviews: [], goalId: input.goalId, releaseId: input.releaseId, estimatePoints: input.estimatePoints,
    docContent: input.docContent, docFileName: input.docFileName, docFileType: input.docFileType,
    attachments: input.attachments ?? [],
    createdAt: fmt(new Date()), updatedAt: fmt(new Date()),
  }
  requirements.unshift(rq)
  bump()
  return rq
}

/**
 * 更新需求（补全/修改 PRD 正文、附件文档或基本字段）
 * @param id 需求唯一标识
 * @param patch 需更新的字段子集
 */
export function updateRequirement(id: string, patch: Partial<Requirement>): void {
  const r = requirements.find((x) => x.id === id)
  if (!r) return
  Object.assign(r, patch, { updatedAt: fmt(new Date()) })
  bump()
}

/**
 * 创建战略目标（L1 Goal Setting）：自增生成 GOAL-x Key 并通知协同
 * @param input 目标初始参数（名称、周期、负责人、衡量口径、关联产品等）
 * @returns 新建的战略目标实体
 */
export function createGoal(input: {
  name: string
  period: string
  ownerId: string
  targetMetric?: string
  productIds: string[]
  deadline?: string
  roadmapItemIds?: string[]
  departmentId?: string
  status?: GoalStatus
}): StrategicGoal {
  const n = goals.length + 1
  const goal: StrategicGoal = {
    id: nextId('g'),
    key: `GOAL-${n}`,
    name: input.name.trim(),
    departmentId: input.departmentId ?? 'd1',
    period: input.period,
    ownerId: input.ownerId,
    targetMetric: input.targetMetric,
    progress: 0,
    status: input.status ?? 'active',
    productIds: input.productIds.length > 0 ? input.productIds : ['p1'],
    roadmapItemIds: input.roadmapItemIds ?? [],
    deadline: input.deadline,
    createdAt: fmt(new Date()),
  }
  goals.unshift(goal)
  const topic = autoCreateTopic('goal', goal.id, 'object_created')
  goal.topicId = topic.id
  bump()
  return goal
}

/**
 * 更新战略目标配置
 * @param id 目标唯一标识
 * @param patch 目标修改属性补丁
 */
export function updateGoal(id: string, patch: Partial<StrategicGoal>): void {
  const g = goalById(id)
  if (!g) return
  Object.assign(g, patch)
  bump()
}

/** 提交评审：自动生成评审话题并按干系人规则拉人 */
export function submitRequirement(id: string) {
  const rq = requirementById(id)
  if (!rq || (rq.status !== 'draft' && rq.status !== 'rejected')) return
  rq.status = 'pending_review'
  rq.updatedAt = fmt(new Date())
  if (!rq.topicId) {
    const topic = autoCreateTopic('requirement', rq.id, 'object_created')
    rq.topicId = topic.id
  }
  bump()
}

/** 评审动作：全部评审人通过 → 已受理（自动建话题/挂话题）；任一驳回 → 已拒绝 */
export function reviewRequirement(id: string, result: 'approved' | 'rejected', comment: string) {
  const rq = requirementById(id)
  if (!rq || rq.status !== 'pending_review') return
  rq.reviews.push({ userId: CURRENT_USER_ID, result, comment, at: fmt(new Date()) })
  rq.updatedAt = fmt(new Date())
  if (result === 'rejected') {
    rq.status = 'rejected'
    bump()
    return
  }
  const pending = rq.reviewerIds.filter((u) => !rq.reviews.some((r) => r.userId === u && r.result === 'approved'))
  if (pending.length === 0) {
    rq.status = 'accepted'
    rq.acceptedAt = fmt(new Date())
    if (!rq.topicId) {
      const topic = autoCreateTopic('requirement', rq.id, 'object_created')
      rq.topicId = topic.id
    }
  }
  bump()
}

/** 排期：受理后挂 RoadMap 条目 / 版本 / 迭代 */
export function scheduleRequirement(id: string, patch: { roadmapItemId?: string; releaseId?: string; sprintId?: string }) {
  const rq = requirementById(id)
  if (!rq || rq.status !== 'accepted') return
  if (patch.roadmapItemId !== undefined) rq.roadmapItemId = patch.roadmapItemId
  if (patch.releaseId !== undefined) rq.releaseId = patch.releaseId
  if (patch.sprintId !== undefined) rq.sprintId = patch.sprintId
  rq.updatedAt = fmt(new Date())
  bump()
}

export function setRequirementStatus(id: string, status: Requirement['status']) {
  const rq = requirementById(id)
  if (!rq) return
  rq.status = status
  rq.updatedAt = fmt(new Date())
  if (status === 'accepted') rq.acceptedAt = fmt(new Date())
  if (status === 'delivered') rq.deliveredAt = fmt(new Date())
  bump()
}

/** 任务挂接到需求（需求 → 拆解任务 追踪链） */
export function linkTaskToRequirement(taskId: string, requirementId: string) {
  const t = workItemById(taskId)
  const rq = requirementById(requirementId)
  if (!t || t.type !== 'task' || !rq) return
  t.requirementId = requirementId
  if (!rq.releaseId && t.releaseId) rq.releaseId = t.releaseId
  if (!rq.sprintId && t.sprintId) rq.sprintId = t.sprintId
  bump()
}

// ---- 流水线模拟（v1 保留） ----
export function runPipeline(pipelineId: string) {
  const p = pipelineById(pipelineId)
  if (!p || p.status === 'running') return
  p.status = 'running'
  p.startedAt = fmt(new Date())
  p.durationSec = 0
  for (const s of p.stages) for (const j of s.jobs) { j.status = 'pending'; j.durationSec = undefined }
  bump()
  const all = p.stages.flatMap((s) => s.jobs)
  let i = 0
  let total = 0
  const step = () => {
    if (i > 0) {
      const prev = all[i - 1]
      const dur = 8 + Math.floor(Math.random() * 30)
      prev.status = 'passed'
      prev.durationSec = dur
      total += dur
    }
    if (i >= all.length) {
      p.status = 'passed'
      p.durationSec = total
      const num = p.title.split('·')[0].trim().slice(1)
      addSystemMsg('c3', `流水线 #${num} 手动触发运行成功 · ${p.branch}`, [{ type: 'pipeline', refId: p.id, label: p.title.split('·')[0].trim() }])
      bump()
      return
    }
    all[i].status = 'running'
    bump()
    i++
    setTimeout(step, 650 + Math.random() * 500)
  }
  setTimeout(step, 300)
}

// ---- 部署（v1 保留） ----
export function deployToEnv(envId: string, version: string, releaseId?: string) {
  const env = deployEnvs.find((e) => e.id === envId)
  if (!env || env.status === 'deploying') return
  env.status = 'deploying'
  bump()
  setTimeout(() => {
    env.currentVersion = version
    env.lastDeployAt = fmt(new Date())
    env.status = 'healthy'
    env.deployedById = CURRENT_USER_ID
    if (releaseId) {
      const rel = releaseById(releaseId)
      if (rel?.envProgress) rel.envProgress[envId] = 'done'
    }
    addSystemMsg('c3', `「${env.name}环境」已完成部署 ${version}`, releaseId ? [{ type: 'release', refId: releaseId, label: version }] : undefined)
    bump()
  }, 1800)
}

// ---- IM（v1 保留 + 话题联动） ----
export function openChannel(channelId: string) {
  const c = channelById(channelId)
  if (c && c.unread > 0) { c.unread = 0; bump() }
}
export function sendImMessage(channelId: string, text: string) {
  if (!text.trim()) return
  messages.push({ id: nextId('m'), channelId, authorId: CURRENT_USER_ID, text, createdAt: fmt(new Date()) })
  bump()
  const ch = channelById(channelId)
  if (!ch || ch.kind !== 'channel') return
  const candidates = ch.memberIds.filter((m) => m !== CURRENT_USER_ID)
  const replier = candidates[Math.floor(Math.random() * candidates.length)]
  const rules: { match: RegExp; replies: string[] }[] = [
    { match: /发布|部署|上线|release/i, replies: ['收到，今晚发布窗口我来跟进 👌', 'v2.4.0 还被 D-88 阻塞着，先修缺陷。'] },
    { match: /MR|评审|review|合并/i, replies: ['我看下这个 MR，半小时内给结论。', '注意单测门禁，改动要有对应测试。'] },
    { match: /缺陷|bug|故障|D-8/i, replies: ['D-88 今晚修复，明天提测。', '已同步到缺陷话题里了。'] },
    { match: /话题|讨论/i, replies: ['已拉你进对应话题。'] },
  ]
  const fallback = ['收到 👌', '好，我同步一下进度。', '稍等，我看下代码。', '对，这块我补一下测试用例。']
  const rule = rules.find((r) => r.match.test(text))
  const reply = rule ? rule.replies[Math.floor(Math.random() * rule.replies.length)] : fallback[Math.floor(Math.random() * fallback.length)]
  setTimeout(() => {
    messages.push({ id: nextId('m'), channelId, authorId: replier, text: reply, createdAt: fmt(new Date()) })
    bump()
  }, 1300 + Math.random() * 1200)
}

// ---- 主题 ----
export function setTheme(theme: 'light' | 'dark') {
  const me = userById(CURRENT_USER_ID)
  if (me) me.themePreference = theme
  document.documentElement.dataset.theme = theme
  bump()
}
