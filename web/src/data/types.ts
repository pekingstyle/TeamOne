// TeamOne v2 原型 · 全局数据类型定义
// 依据 docs/v2/03-产品设计文档-v2.md §2 ER 模型实现
// 建模决策：①版本/迭代为工作项的横切属性 ②Goal×Product 多对多 ③上层进度只读聚合 ④v1 Milestone 并入 Release

// ==================== 组织与用户 ====================

export interface User {
  id: string
  name: string
  title: string
  email: string
  color: string // 头像底色
  online: boolean
  departmentId: string
  platformRole: 'super_admin' | 'org_admin' | 'member'
  dailyCapacityHours: number // CF-1 负载计算容量（默认 8）
  themePreference: 'light' | 'dark'
}

export interface Department {
  id: string
  name: string
  parentId?: string
  leadId: string
  memberIds: string[]
  productIds: string[]
  componentIds: string[]
  goalIds: string[]
  createdAt: string
}

// ==================== L1 战略目标 ====================

export type GoalStatus = 'draft' | 'active' | 'at_risk' | 'achieved' | 'archived'

export interface StrategicGoal {
  id: string
  key: string // GOAL-1
  name: string
  departmentId: string
  period: string // 2026-Q3 / 2026-H2
  ownerId: string
  targetMetric?: string
  progress: number // 只读聚合
  status: GoalStatus
  productIds: string[]
  roadmapItemIds: string[]
  deadline?: string
  topicId?: string
  createdAt: string
}

// ==================== L2 产品与组件 ====================

export interface Product {
  id: string
  key: string // 工作项编号前缀
  name: string
  departmentId: string
  goalIds: string[]
  repoIds: string[]
  leadId: string
  deadline?: string
  description?: string
  createdAt: string
}

export interface Component {
  id: string
  name: string
  productId: string
  repoIds: string[]
  leadId: string
  description?: string
}

// ==================== L3 RoadMap 条目 ====================

export interface RoadmapItem {
  id: string
  name: string
  track: string
  start: string // YYYY-MM
  end: string
  progress: number // 只读聚合
  status: 'planned' | 'in_progress' | 'shipped'
  goalId?: string
  productId?: string
  releaseId?: string
  sprintIds: string[]
  issueCount: number
  ownerIds: string[]
}

// ==================== L4 版本（v1 Milestone+Release 合并） ====================

export type ReleaseStatus = 'planned' | 'coding' | 'testing' | 'released'

export interface Release {
  id: string
  name: string
  productId: string
  planDate: string // 发布日 Deadline
  codeFreezeDate: string // 代码冻结日（CF-3 输入）
  status: ReleaseStatus
  progress: number // 只读聚合
  blocked: boolean // 存在未关闭致命/严重缺陷时 true，发布锁定
  blockedDefectIds: string[]
  testTaskDoneCount: number
  testTaskTotalCount: number
  artifactIds: string[]
  envProgress?: Record<string, 'pending' | 'deploying' | 'done' | 'failed'>
  baselineId?: string
  releaseNotes: string
  ownerIds: string[]
  createdAt: string
  releasedAt?: string
}

// ==================== L5 迭代 ====================

export interface Sprint {
  id: string
  name: string
  goal: string
  productId: string
  releaseId?: string
  start: string
  end: string
  status: 'planned' | 'active' | 'done'
  capacityHours: number
  totalPoints: number
  burndown: number[]
}

// ==================== 需求（L5.5：连接目标与执行） ====================

export type ReqStatus =
  | 'draft' | 'pending_review' | 'accepted' | 'in_dev' | 'delivered' | 'closed' | 'rejected'
export const reqStatusText: Record<ReqStatus, string> = {
  draft: '草稿', pending_review: '待评审', accepted: '已受理', in_dev: '开发中',
  delivered: '已交付', closed: '已验收', rejected: '已拒绝',
}

export interface ReqReview {
  userId: string
  result: 'approved' | 'rejected'
  comment: string
  at: string
}

export interface Requirement {
  id: string
  key: string // REQ-x
  title: string
  description: string // 背景 / 价值 / 验收标准
  status: ReqStatus
  priority: WorkPriority
  productId: string
  proposerId: string // 提出人
  ownerId: string // 需求负责人（PM）
  reviewerIds: string[]
  reviews: ReqReview[]
  goalId?: string
  roadmapItemId?: string
  releaseId?: string // 评审通过后排入的交付版本
  sprintId?: string // 进入的迭代
  estimatePoints?: number
  topicId?: string
  createdAt: string
  updatedAt: string
  /** 后端乐观锁版本（dogfooding：PRD 正文 PUT 需带 If-Match） */
  version?: number
  acceptedAt?: string
  deliveredAt?: string
  /** Markdown 需求详细正文内容（支持富文本/表格/代码块规范） */
  docContent?: string
  /** 上传的原始文档文件名（如 PRD-需求规格.md / 架构设计.docx） */
  docFileName?: string
  /** 文档类型标识（markdown / word / text 等） */
  docFileType?: string
  /** 关联的需求文档附件清单（文件名、大小、下载地址） */
  attachments?: { id?: string; name: string; size: number | string; url?: string; uploadedAt: string }[]
}

// ==================== L6 工作项（任务/测试任务/缺陷） ====================

export type WorkItemType = 'task' | 'testtask' | 'defect'
export type WorkItemStatus = 'todo' | 'in_progress' | 'in_review' | 'blocked' | 'done' | 'closed'
export type WorkPriority = 'P0' | 'P1' | 'P2' | 'P3'

export interface WorkItemBase {
  id: string
  key: string
  title: string
  description: string
  type: WorkItemType
  status: WorkItemStatus
  priority: WorkPriority
  productId: string
  componentId?: string
  sprintId?: string
  releaseId?: string
  roadmapItemId?: string
  parentWorkItemId?: string
  blockedByIds: string[]
  assigneeId: string
  creatorId: string
  estimateHours: number
  startDate?: string
  dueDate?: string
  points: number
  labels: string[]
  topicId?: string
  requirementId?: string // v2.2：所属需求（需求 → 拆解任务 追踪链）
  createdAt: string
  updatedAt: string
}

export interface Task extends WorkItemBase {
  type: 'task'
  linkedMrIds?: string[]
}

export interface TestTask extends WorkItemBase {
  type: 'testtask'
  releaseId: string // RL-1：测试任务必挂版本
  caseCount: number
  passedCount: number
  relatedDefectIds: string[]
  verifierIds: string[]
}

export type DefectSeverity = '致命' | '严重' | '一般' | '轻微'
export type DefectStatus = '新建' | '修复中' | '已修复' | '回归通过' | '已关闭' | '重新打开'

export interface Defect extends Omit<WorkItemBase, 'status' | 'type'> {
  type: 'defect'
  severity: DefectSeverity
  status: DefectStatus
  foundInTestTaskId?: string
  verifyTestTaskId?: string
  relatedTaskId?: string
  fixedInMrId?: string
  blockedReleaseId?: string
  reportedById: string
  reopenedCount: number
  deferredReleaseId?: string
}

export type WorkItem = Task | TestTask | Defect

// ==================== 话题（对象化讨论） ====================

export type TopicTargetType =
  | 'goal' | 'requirement' | 'roadmap' | 'sprint' | 'task' | 'testtask' | 'defect'
  | 'release' | 'repo' | 'worktree' | 'baseline' | 'mr'

export interface TopicMessage {
  id: string
  authorId: string
  text: string
  refTargetIds?: string[]
  fromImChannelId?: string
  createdAt: string
}

export interface Topic {
  id: string
  title: string
  targetType: TopicTargetType
  targetId: string
  relatedTargetIds: string[]
  creatorId: string
  autoCreated: boolean
  autoCreateReason?: string // object_created | severity_high | release_testing | mr_escalated
  participantIds: string[]
  unsubscribedIds: string[]
  status: 'active' | 'archived' | 'locked'
  archivedReason?: string // object_closed | sprint_ended | release_released | baseline_frozen
  archivedAt?: string
  pinnedConclusion?: string
  messages: TopicMessage[]
  lastActiveAt: string
  createdAt: string
}

// ==================== 部门×角色×资源权限 ====================

export type ResourceType =
  | 'product' | 'component' | 'repo' | 'pipeline' | 'release'
  | 'goal' | 'roadmap' | 'sprint' | 'baseline'
export type ResourceAction = 'view' | 'edit' | 'approve' | 'release' | 'manage'

export interface ResourcePermission {
  id: string
  departmentId?: string
  resourceType: ResourceType
  resourceId: string
  roleId: string // dept_lead / component_lead / member / guest
  subjectType: 'user' | 'dept' | 'role'
  subjectId: string
  actions: ResourceAction[]
  grantedById: string
  createdAt: string
}

// ==================== Git 过程域：WorkTree / 基线 / MR ====================

export interface WorkTree {
  id: string
  repoId: string
  name: string
  branch: string
  localPath: string
  ownerId: string
  relatedTaskId?: string
  basedOn: { kind: 'baseline' | 'branch'; id: string }
  ahead: number
  behind: number
  dirtyFileCount: number
  lastCommitAt: string
  status: 'active' | 'merged' | 'stale'
}

export type BaselineType = 'functional' | 'allocated' | 'product' // 功能/分配/产品基线

export interface ConflictResolution {
  filePath: string
  solution: string
  confirmedById: string
  reviewedById: string
  resolvedAt: string
}

export interface Baseline {
  id: string
  repoId: string
  name: string
  type: BaselineType
  tagRef: string
  artifactVersion?: string
  requirementSnapshotId?: string
  commitShort: string
  createdAt: string
  status: 'draft' | 'in_review' | 'approved' | 'superseded'
  approverIds: string[]
  includesCommits: string[]
  mrIds: string[]
  conflictResolved: ConflictResolution[]
  protectedBranches: string[]
  supersededById?: string
}

export interface UnitTestCheck {
  hasTests: boolean
  testFiles: string[]
  passed: boolean
  coverageTotal: number
  coverageDelta: number
  gatePassed: boolean
  /** 门禁回传备注（后端实名 reportUrl，含 coverage=jacoco/simulated 注记；来源徽标用，可缺省） */
  reportUrl?: string
  exempt?: { reason: string; approvedById: string }
}

// ---------- 代码托管（v1 保留） ----------

export interface Repo {
  id: string
  name: string
  description: string
  language: string
  stars: number
  visibility: 'private' | 'public'
  defaultBranch: string
  leadId: string
  updatedAt: string
  ciEnabled: boolean
  componentId?: string
  productId?: string
}

export interface Branch {
  repoId: string
  name: string
  ahead: number
  behind: number
  lastCommitMsg: string
  updatedAt: string
  protected: boolean
  authorId: string
}

export interface Commit {
  id: string
  repoId: string
  message: string
  authorId: string
  date: string
  branch: string
  additions: number
  deletions: number
}

export interface FileNode {
  name: string
  kind: 'dir' | 'file'
  children?: FileNode[]
  lastCommitMsg?: string
  updatedAt?: string
}

export type DiffLineType = 'add' | 'del' | 'ctx'

export interface DiffLine {
  type: DiffLineType
  oldNo?: number
  newNo?: number
  text: string
}

export interface FileDiff {
  path: string
  status: 'modified' | 'added' | 'removed'
  additions: number
  deletions: number
  lines: DiffLine[]
}

export interface CheckRun {
  name: string
  status: 'pending' | 'running' | 'passed' | 'failed'
}

export type MrStatus = 'open' | 'merged' | 'closed' | 'draft'

export interface MergeRequest {
  id: string
  number: number
  title: string
  description: string
  repoId: string
  sourceBranch: string
  targetBranch: string
  authorId: string
  reviewers: { userId: string; state: 'pending' | 'approved' | 'changes_requested' }[]
  status: MrStatus
  checks: CheckRun[]
  conflicts: boolean
  createdAt: string
  diffs: FileDiff[]
  comments: Comment[]
  linkedWorkItemKey?: string
  additions: number
  deletions: number
  unitTestCheck: UnitTestCheck // v2：单测检测
  conflictFiles: string[] // v2：冲突文件清单
  conflictResolutions?: ConflictResolution[]
  basedOnBaselineId?: string
  rebaseRequired?: boolean
}

export interface Comment {
  id: string
  authorId: string
  text: string
  createdAt: string
}

// ==================== CI/CD（v1 保留） ====================

export type RunStatus = 'pending' | 'running' | 'passed' | 'failed' | 'canceled' | 'skipped'

export interface Job {
  id: string
  name: string
  status: RunStatus
  durationSec?: number
  log: string[]
}

export interface Stage {
  name: string
  jobs: Job[]
}

export type PipelineTrigger = 'push' | 'manual' | 'schedule' | 'mr'

export interface Pipeline {
  id: string
  repoId: string
  title: string
  branch: string
  commitShort: string
  commitMsg: string
  trigger: PipelineTrigger
  triggerUserId: string
  status: RunStatus
  startedAt: string
  durationSec: number
  stages: Stage[]
  unitTestJobId?: string // 单测检测途径 B
}

export interface Artifact {
  id: string
  name: string
  version: string
  type: 'jar' | 'docker' | 'npm' | 'zip'
  size: string
  checksum: string
  builtAt: string
  pipelineId: string
  repoId: string
}

export type EnvName = '开发' | '测试' | '预发' | '生产'

export interface DeployEnv {
  id: string
  name: EnvName
  url: string
  currentVersion: string
  lastDeployAt: string
  status: 'healthy' | 'deploying' | 'failed'
  deployedById: string
}

// ==================== IM（v1 保留） ====================

export interface Channel {
  id: string
  name: string
  description: string
  kind: 'channel' | 'dm'
  memberIds: string[]
  unread: number
}

export type MsgAttachment = {
  type: 'mr' | 'workitem' | 'pipeline' | 'release' | 'topic' | 'defect'
  refId: string
  label: string
}

export interface Message {
  id: string
  channelId: string
  authorId: string
  text: string
  createdAt: string
  attachments?: MsgAttachment[]
  pending?: boolean
}

// ==================== 冲突检测 ====================

export type ConflictType =
  | '人员超载' | '时间区间重叠' | '里程碑挤压' | '跨项目争用' | '依赖倒挂' | 'Deadline越级'

export interface ConflictItem {
  id: string
  type: ConflictType
  severity: 'red' | 'yellow'
  subjectType: 'user' | 'task' | 'sprint' | 'release' | 'product'
  subjectId: string
  detail: string
  relatedTaskIds: string[]
  detectedAt: string
}

// ==================== 页面路由 ====================

export type PageId =
  | 'dashboard' | 'reports' | 'conflicts'
  | 'goals' | 'requirements' | 'roadmap' | 'tasks' | 'defects' | 'delivery'
  | 'topics' | 'im' | 'team'
  | 'repos' | 'repo' | 'review' | 'mr' | 'pipelines' | 'pipeline'
  | 'settings'
