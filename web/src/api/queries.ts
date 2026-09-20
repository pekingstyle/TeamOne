// W4 前端批 · 三页共享 API 查询/变更（字段 2026-09-11 curl 实测）
// - 缺陷：GET/POST /api/v1/work-items、POST /{idOrKey}/transition
// - 版本：GET /api/v1/releases、POST /api/v1/releases/{key}/publish（422 T1-PRD-4230 门禁）
// - 会话：GET /api/v1/conversations?type=topic&archived=false（当前后端未部署该 REST，页面按降级处理）
// - 门禁频道：WS gate:{releaseId} → defect.blocked_changed → 失联时页面手动刷新兜底
// M2-INC-1 W1 追加：useMessages before 反向游标 / useCommits（V-6 关联提交）/ useRoadmapTimeline（RoadMap 只读）
// M2-INC-1 W2 追加：useConflicts/useHeatmap/conflictsApi.recompute（冲突中心真实 API）+
//                   useSprints/useSprintWorkItems（TasksPage 迭代联动，GET /sprints + sprintId 过滤）
import { useEffect } from 'react'
import { useInfiniteQuery, useQuery, useQueryClient, type InfiniteData, type QueryClient } from '@tanstack/react-query'
import type { Defect, DefectSeverity, WorkPriority } from '../data/types'
import { api, ApiError, type RemoteUser } from './client'
import { teamOneWs } from './ws'

// ---------------- 缺陷（work-items type=defect） ----------------

/** GET /api/v1/work-items 原始条目（非空字段实测；labels 是 JSON 字符串） */
export interface RemoteWorkItem {
  id: string
  key: string
  type: string
  title: string
  description?: string
  status: string
  severity?: string
  priority?: string
  productId: string
  goalId?: string
  assigneeId: string
  reporterId: string
  version: number
  labels: string
  /** 所属/交付版本（Views.of(WorkItem) 投影 work_item.release_id；与阻塞版本 blockedReleaseId 语义区分） */
  releaseId?: string
  blockedReleaseId?: string
  /** Views.of(WorkItem) 投影：所属组件 uuid（缺陷分布/抽屉组件名映射用） */
  componentId?: string
  /** Views.of(WorkItem) 投影：发现于测试任务（后端字段名 foundInId → 前端 foundInTestTaskId） */
  foundInId?: string
  /** Views.of(WorkItem) 投影：关联任务（后端字段名 relatedId → 前端 relatedTaskId） */
  relatedId?: string
  /** 列表投影：type=requirement 行的拆解任务统计（children 按 parent_id，type∈task/test_task/defect）；无子行 0/0 */
  taskCount?: number
  taskDoneCount?: number
  createdAt: string
  updatedAt: string
  path: string
}

interface PagePayload<T> { items: T[]; page: number; size: number; total: number }

/** 后端条目 → 前端 Defect 展示形（+version 乐观锁；缺失字段安全兜底，纯展示函数仍走 store.ts） */
export type DefectRow = Defect & { version: number; releaseId?: string }
export function remoteToDefect(raw: RemoteWorkItem): DefectRow {
  let labels: string[] = []
  try { labels = JSON.parse(raw.labels ?? '[]') as string[] } catch { /* labels 非法时按空处理 */ }
  return {
    id: raw.id,
    key: raw.key,
    title: raw.title,
    description: raw.description ?? '',
    type: 'defect',
    status: raw.status as Defect['status'],
    severity: (raw.severity ?? '一般') as DefectSeverity,
    priority: (raw.priority ?? 'P2') as WorkPriority,
    productId: raw.productId,
    assigneeId: raw.assigneeId,
    creatorId: raw.reporterId,
    blockedByIds: [],
    estimateHours: 0,
    points: 0,
    labels,
    blockedReleaseId: raw.blockedReleaseId,
    // 所属/交付版本（work_item.release_id，与阻塞版本 blockedReleaseId 区分；行版本列优先展示）
    releaseId: raw.releaseId,
    // 收口批（D-91 后字段映射真实化）：Views.of(WorkItem) 已投影 componentId/foundInId/relatedId
    componentId: raw.componentId,
    foundInTestTaskId: raw.foundInId,
    relatedTaskId: raw.relatedId,
    reportedById: raw.reporterId,
    reopenedCount: 0,
    createdAt: fmtIso(raw.createdAt),
    updatedAt: fmtIso(raw.updatedAt),
    version: raw.version,
  }
}

/** ISO 时间 → 页面展示（YYYY-MM-DD HH:mm），daysSince 兼容 */
function fmtIso(iso: string): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function useDefects() {
  return useQuery({
    queryKey: ['defects'],
    queryFn: async () => {
      const page = await api<PagePayload<RemoteWorkItem>>('/api/v1/work-items?type=defect&page=1&size=100')
      return page.items.map(remoteToDefect)
    },
  })
}

export const defectsApi = {
  /** POST /api/v1/work-items（201 返回实体；productId/goalId 必填其一，实测 400 文案）。
   *  R-8 表单补全：componentId=所属组件；复现步骤/发现环境无独立列——由调用方合入 description
   *  （【发现环境】前缀 + 【复现步骤】分节，缺陷抽屉按 description 原样展示）；labels 可选。 */
  create(input: {
    title: string
    severity: DefectSeverity
    priority: WorkPriority
    assigneeId: string
    productId: string
    blockedReleaseId?: string
    description?: string
    componentId?: string
    labels?: string[]
    sprintId?: string
    releaseId?: string
    dueDate?: string
  }): Promise<RemoteWorkItem> {
    return api<RemoteWorkItem>('/api/v1/work-items', {
      method: 'POST',
      body: {
        type: 'defect',
        title: input.title,
        severity: input.severity,
        priority: input.priority,
        assigneeId: input.assigneeId,
        productId: input.productId,
        blockedReleaseId: input.blockedReleaseId || undefined,
        description: input.description || undefined,
        componentId: input.componentId || undefined,
        labels: input.labels && input.labels.length > 0 ? input.labels : undefined,
        sprintId: input.sprintId || undefined,
        releaseId: input.releaseId || undefined,
        dueDate: input.dueDate || undefined,
      },
    })
  },
  /** POST /{idOrKey}/transition {to}（非法流转 422 T1-PRD-4201，实测） */
  transition(idOrKey: string, to: string): Promise<RemoteWorkItem> {
    return api<RemoteWorkItem>(`/api/v1/work-items/${idOrKey}/transition`, { method: 'POST', body: { to } })
  },
}

/** GET /api/v1/components 条目（Views.of(Component)；R-8 缺陷表单「所属组件」下拉数据源） */
export interface RemoteComponent {
  id: string
  key: string
  name: string
  productId: string
  ownerId?: string
}

export function useComponents() {
  return useQuery({
    queryKey: ['components'],
    staleTime: 5 * 60_000,
    queryFn: () => api<RemoteComponent[]>('/api/v1/components'),
  })
}

/** 通用工作项 API（任务、测试任务、缺陷通用状态流转） */
export const workItemsApi = {
  transition: defectsApi.transition,
  /** PUT /work-items/{idOrKey} 局部更新（带 version 则走 If-Match 乐观锁，409=已被他人更新；dogfooding：需求 PRD 正文并入 description） */
  update(idOrKey: string, patch: { description?: string; title?: string; priority?: string }, version?: number): Promise<RemoteSprintWorkItem> {
    return api<RemoteSprintWorkItem>(`/api/v1/work-items/${encodeURIComponent(idOrKey)}`, {
      method: 'PUT',
      body: patch,
      headers: version != null ? { 'If-Match': String(version) } : undefined,
    })
  },
  /** POST /work-items 通用创建（任务等非缺陷类型；dogfooding 切换：新建任务走真实 API）。
   *  PM-11：estimateHours 透传后端 WorkItemService.CreateSpec.estimateHours（BigDecimal，小时） */
  create(input: {
    type: string
    title: string
    description?: string
    priority?: string
    assigneeId?: string
    productId?: string
    goalId?: string
    sprintId?: string
    releaseId?: string
    roadmapItemId?: string
    dueDate?: string
    estimateHours?: number
  }): Promise<RemoteSprintWorkItem> {
    return api<RemoteSprintWorkItem>('/api/v1/work-items', {
      method: 'POST',
      body: {
        type: input.type,
        title: input.title,
        description: input.description || undefined,
        priority: input.priority || undefined,
        assigneeId: input.assigneeId || undefined,
        productId: input.productId || undefined,
        goalId: input.goalId || undefined,
        sprintId: input.sprintId || undefined,
        releaseId: input.releaseId || undefined,
        roadmapItemId: input.roadmapItemId || undefined,
        dueDate: input.dueDate || undefined,
        estimateHours: input.estimateHours || undefined,
      },
    })
  },
}

/** GET /work-items 全类型工作项清单（报表/跨迭代聚合；dogfooding 切换：ReportsPage/TasksPage 真实数据源） */
export function useWorkItems(type?: string) {
  return useQuery({
    queryKey: ['work-items', 'all', type ?? 'all'],
    queryFn: () => {
      const p = new URLSearchParams({ page: '1', size: '200' })
      if (type) p.set('type', type)
      return api<PagePayload<RemoteSprintWorkItem>>(`/api/v1/work-items?${p.toString()}`).then((r) => r.items)
    },
  })
}

/** GET /work-items/{idOrKey} 单条工作项（跨迭代深链详情；dogfooding 切换） */
export function useWorkItem(idOrKey: string | undefined) {
  return useQuery({
    queryKey: ['work-items', 'detail', idOrKey],
    enabled: !!idOrKey,
    queryFn: () => api<RemoteSprintWorkItem>(`/api/v1/work-items/${encodeURIComponent(idOrKey!)}`),
  })
}

// ---------------- 版本（releases） ----------------

/** GET /api/v1/releases 条目实测字段（无 progress/testTaskCount/releaseNotes 等原型字段） */
export interface RemoteRelease {
  id: string
  key: string
  name: string
  productId: string
  roadmapItemId?: string
  /** planned | coding | code_freeze | blocked | released */
  status: string
  blocked: boolean
  blockedDefectIds: string[]
  blockedDefectKeys: string[]
  planDate?: string
  envProgress: Record<string, string>
  releasedAt?: string
  createdAt: string
  updatedAt: string
}

export function useReleases() {
  return useQuery({
    queryKey: ['releases'],
    queryFn: () => api<RemoteRelease[]>('/api/v1/releases'),
  })
}

export const releasesApi = {
  /** POST /releases/{key}/publish：422 T1-PRD-4230 时 details=阻塞缺陷清单（"KEY 严重度 负责人"）；
   *  422 T1-PRD-4231 时 details=未完结工作项分组明细（R-9 发布一致性，"任务×2（T-103、T-104）"） */
  publish(key: string): Promise<RemoteRelease> {
    return api<RemoteRelease>(`/api/v1/releases/${encodeURIComponent(key)}/publish`, { method: 'POST' })
  },
}

// ---------------- 版本交付联动（R-9 · B2：构建→测试→部署真实展示 + 部署登记，eng 侧供数） ----------------

/** GET /releases/{id}/pipelines 条目（R-9 契约字段；stages 结构与 usePipelines 的 RemoteStage 同源） */
export interface ReleasePipelineItem {
  id: string
  repoName: string
  branch: string
  commitSha: string
  status: string
  stages: RemoteStage[]
  createdAt: string
}

/** 某版本的流水线运行清单（createdAt 倒序，最新 20 条；空数组=该版本尚未关联流水线） */
export function useReleasePipelines(releaseId: string | undefined) {
  return useQuery({
    queryKey: ['releases', releaseId, 'pipelines'],
    enabled: !!releaseId,
    queryFn: () =>
      api<{ items: ReleasePipelineItem[] }>(`/api/v1/releases/${encodeURIComponent(releaseId!)}/pipelines`)
        .then((p) => p.items),
  })
}

/** GET /releases/{id}/deployments 条目（R-9 契约字段；env ∈ dev/staging/prod） */
export interface ReleaseDeploymentItem {
  env: string
  status: 'running' | 'success' | 'failed' | 'rolled_back' | string
  artifactVersion?: string
  deployedAt: string
  note?: string
}

/** 某版本的部署登记清单（deployed_at 倒序） */
export function useReleaseDeployments(releaseId: string | undefined) {
  return useQuery({
    queryKey: ['releases', releaseId, 'deployments'],
    enabled: !!releaseId,
    queryFn: () =>
      api<{ items: ReleaseDeploymentItem[] }>(`/api/v1/releases/${encodeURIComponent(releaseId!)}/deployments`)
        .then((p) => p.items),
  })
}

/** 部署登记（POST /releases/{id}/deployments，platform:manage 管理员同族；真实执行联动属 M4/M5） */
export const deploymentsApi = {
  register(releaseId: string, input: {
    env: string
    artifactVersion?: string
    note?: string
    pipelineRunId?: string
  }): Promise<ReleaseDeploymentItem> {
    return api(`/api/v1/releases/${encodeURIComponent(releaseId)}/deployments`, { method: 'POST', body: input })
  },
}

// ---------------- 会话（conversations，collab REST 已补齐，契约 2026-09-12 实测） ----------------

/** GET /api/v1/conversations 条目（实测字段，M2-INC-2 支持 unreadCount） */
export interface RemoteConversation {
  id: string
  type: string
  name: string
  targetType: string
  targetId: string
  autoCreated: boolean
  archived: boolean
  archivedReason?: string
  lastMessageId?: number
  lastMessageAt?: string
  memberCount: number
  unreadCount?: number
  peerUserId?: string
  peerAvatar?: string
}

interface ConvPage { items: RemoteConversation[] }

/** GET /api/v1/conversations/{id} 详情（含 members[]） */
export interface RemoteConversationDetail extends RemoteConversation {
  members: string[]
}

export interface RemoteAttachment {
  fileId: string
  name?: string
  originalName?: string
  size?: number
  mime?: string
}

/** GET /api/v1/conversations/{id}/messages 条目（实测字段，M2-INC-2 撤回/附件） */
export interface RemoteMessage {
  msgId: number
  conversationId: string
  senderId: string
  kind: string
  body: string | null
  attachments?: RemoteAttachment[]
  refType?: string
  refId?: string
  clientMsgId?: string
  createdAt: string
  withdrawn?: boolean
}

export interface MessagePage { items: RemoteMessage[]; hasMore: boolean }

export function useConversations(typeOrArchived?: string | boolean, archived = false) {
  let type: string | undefined
  let isArchived = archived
  if (typeof typeOrArchived === 'boolean') {
    isArchived = typeOrArchived
  } else if (typeof typeOrArchived === 'string') {
    type = typeOrArchived
  }
  return useQuery({
    queryKey: ['conversations', type ?? 'all', isArchived],
    queryFn: () => {
      const params = new URLSearchParams()
      if (type) params.set('type', type)
      params.set('archived', String(isArchived))
      return api<ConvPage>(`/api/v1/conversations?${params.toString()}`).then((p) => p.items)
    },
    select: (rows) => rows.slice().sort((a, b) => (b.lastMessageAt ?? '').localeCompare(a.lastMessageAt ?? '')),
    // IM 实时性批：WS 帧 + 重连补偿为主，窗口聚焦兜底（staleTime 默认 0，聚焦即拉）
    refetchOnWindowFocus: true,
  })
}

export const conversationsApi = {
  createDm(peerId: string): Promise<RemoteConversation> {
    return api<RemoteConversation>('/api/v1/conversations', {
      method: 'POST',
      body: { type: 'dm', peerId },
    })
  },
  createGroup(name: string, memberIds: string[]): Promise<RemoteConversation> {
    return api<RemoteConversation>('/api/v1/conversations', {
      method: 'POST',
      body: { type: 'group', name, memberIds },
    })
  },
  addMembers(id: string, userIds: string[]): Promise<{ added: string[] }> {
    return api<{ added: string[] }>(`/api/v1/conversations/${id}/members`, {
      method: 'POST',
      body: { userIds },
    })
  },
  removeMember(id: string, uid: string): Promise<{ removed: string }> {
    return api<{ removed: string }>(`/api/v1/conversations/${id}/members/${uid}`, {
      method: 'DELETE',
    })
  },
  withdraw(id: string, msgId: number): Promise<{ msgId: number; withdrawnAt: string; conversationId: string }> {
    return api<{ msgId: number; withdrawnAt: string; conversationId: string }>(
      `/api/v1/conversations/${id}/messages/${msgId}/withdraw`,
      { method: 'POST' },
    )
  },
  /**
   * HTTP 已读端点（R-10a 补偿通道，与 WS read 同走后端 advanceRead）：
   * lastReadMessageId 缺省=读到最新（R-10b 哨兵废除）；返回服务端权威 unread。
   */
  markRead(id: string, lastReadMessageId?: number): Promise<{ conversationId: string; lastReadMessageId: number; unread: number }> {
    return api<{ conversationId: string; lastReadMessageId: number; unread: number }>(
      `/api/v1/conversations/${id}/read`,
      { method: 'POST', body: lastReadMessageId != null ? { lastReadMessageId } : {} },
    )
  },
  /**
   * 查询单条消息的已读/未读成员明细（Direction 4 Phase 4 · IM 读回执能力）。
   * @param convId 会话 UUID
   * @param msgId 消息自增 ID
   * @returns 已读回执 DTO（含 sender、readers、unreaders 与统计字段）
   */
  getMessageReaders(convId: string, msgId: number): Promise<RemoteMessageReaders> {
    return api<RemoteMessageReaders>(`/api/v1/conversations/${convId}/messages/${msgId}/readers`)
  },
}

/**
 * 已读回执成员简报（与后端 MessageReadersDto.MemberBrief 对齐）
 */
export interface RemoteMemberBrief {
  /** 用户唯一标识 UUID */
  userId: string
  /** 登录账号名 */
  username: string
  /** 显示昵称/真实姓名 */
  displayName: string
  /** 推进游标覆盖该消息的时间戳（未读成员为 null） */
  readAt: string | null
}

/**
 * 消息已读回执响应 DTO（与后端 MessageReadersDto 对齐）
 */
export interface RemoteMessageReaders {
  /** 会话 UUID */
  conversationId: string
  /** 目标消息自增 ID */
  messageId: number
  /** 消息发送者用户 ID */
  senderId: string
  /** 会话全部成员数（含发送者） */
  totalMembers: number
  /** 接收方总人数（排除发送者） */
  totalRecipients: number
  /** 接收方已读人数 */
  readCount: number
  /** 接收方未读人数 */
  unreadCount: number
  /** 接收方是否全员读毕 */
  allRead: boolean
  /** 消息发送者资料 */
  sender: RemoteMemberBrief
  /** 已读成员列表（按 readAt 升序） */
  readers: RemoteMemberBrief[]
  /** 未读成员列表（按 displayName 字典序） */
  unreaders: RemoteMemberBrief[]
}

/**
 * React Query Hook：查询单条消息的已读/未读成员明细（Direction 4 Phase 4）。
 * 仅在 convId 和 msgId 均有值时启用查询。
 * @param convId 会话 UUID
 * @param msgId 消息自增 ID（undefined 表示不查询）
 */
export function useMessageReaders(convId: string | undefined, msgId: number | undefined) {
  return useQuery({
    queryKey: ['messageReaders', convId, msgId],
    enabled: !!convId && msgId != null && msgId > 0,
    queryFn: () => api<RemoteMessageReaders>(`/api/v1/conversations/${convId}/messages/${msgId}/readers`),
  })
}


export const filesApi = {
  presign(fileName: string, size: number, mime: string): Promise<{
    fileId: string
    uploadUrl: string
    bucket: string
    objectKey: string
  }> {
    return api('/api/v1/files/presign', {
      method: 'POST',
      body: { fileName, size, mime },
    })
  },
  complete(fileId: string): Promise<{
    id: string
    uploaderId: string
    bucket: string
    objectKey: string
    size: number
    mime: string
    originalName: string
    status: string
    createdAt: string
  }> {
    return api(`/api/v1/files/${fileId}/complete`, {
      method: 'POST',
    })
  },
  downloadUrl(fileId: string): Promise<{
    downloadUrl: string
    fileName: string
    mime: string
    size: number
    status: string
  }> {
    return api(`/api/v1/files/${fileId}/download-url`)
  },
  async upload(file: File): Promise<RemoteAttachment> {
    const pre = await filesApi.presign(file.name, file.size, file.type || 'application/octet-stream')
    const putRes = await fetch(pre.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type || 'application/octet-stream' },
      body: file,
    })
    if (!putRes.ok) {
      throw new Error(`文件上传失败: HTTP ${putRes.status}`)
    }
    const ready = await filesApi.complete(pre.fileId)
    return {
      fileId: ready.id,
      name: ready.originalName,
      originalName: ready.originalName,
      size: ready.size,
      mime: ready.mime,
    }
  },
}

/** 历史消息：before 反向游标（M2-INC-1 W1 清账，05 §3.3 after= 语义扩展 before=）。
 *  首页 = 尾窗最新 50 条（before=latest，后端 DESC 返回、此处反转页内 ASC）；
 *  「加载更多历史」走 fetchPreviousPage 取更早一页并前插；hasPreviousPage=false 即到会话开头。
 *  WS 离线补偿的 after= 升序语义保持不变（后端兼容双游标）。 */
export function useMessages(convId: string | undefined) {
  return useInfiniteQuery<MessagePage, Error, InfiniteData<MessagePage, string>, (string | undefined)[], string>({
    queryKey: ['messages', convId],
    enabled: !!convId,
    initialPageParam: 'latest',
    queryFn: ({ pageParam }) => {
      if (!convId) throw new Error('convId is required') // enabled 已守卫，此处为 TS 收窄
      return api<MessagePage>(`/api/v1/conversations/${convId}/messages?before=${pageParam}&limit=50`)
        .then((p) => ({ ...p, items: [...p.items].reverse() })) // 后端 DESC → 页内旧→新
    },
    getNextPageParam: (lastPage) => (lastPage.hasMore ? String(lastPage.items.at(-1)!.msgId) : undefined),
    getPreviousPageParam: (firstPage) => {
      if (!firstPage.hasMore || firstPage.items.length === 0) return undefined
      return String(firstPage.items[0].msgId) // 页内 ASC 后首条 = 本页最旧，取它之前的更早一页
    },
    // IM 实时性批：切回标签页即拉最新一页历史（与 WS 帧互补，防断线窗口漏帧后停留旧内容）
    refetchOnWindowFocus: true,
  })
}

export function useConversationDetail(convId: string | undefined) {
  return useQuery({
    queryKey: ['conversation', convId],
    enabled: !!convId,
  queryFn: () => api<RemoteConversationDetail>(`/api/v1/conversations/${convId}`),
  })
}

// ---------------- WS 门禁频道（gate:{releaseId}） ----------------
export interface BlockedChangedPayload {
  releaseId: string
  blocked: boolean
  count: number
  productId?: string
  productOwnerId?: string
}

/**
 * useGateChannel：订阅 gate:{releaseId}（product edit 权限，实测 OWNER 可订）；
 * defect.blocked_changed 到达即失效 defects/releases 查询（实时刷新阻塞标记）。
 * @param releaseIds 要盯的版本 id 集合（阻塞中 + 未发布）
 */
export function useGateChannel(releaseIds: string[], onBlockedChanged?: (p: BlockedChangedPayload) => void): void {
  const queryClient = useQueryClient()
  const key = releaseIds.slice().sort().join(',')
  useEffect(() => {
    if (releaseIds.length === 0) return
    let disposed = false
    const offs: Array<() => void> = []
    void teamOneWs
      .connect()
      .then(() => {
        if (disposed) return
        offs.push(teamOneWs.onEvent('defect.blocked_changed', (payload) => {
          const p = payload as unknown as BlockedChangedPayload
          void queryClient.invalidateQueries({ queryKey: ['defects'] })
          void queryClient.invalidateQueries({ queryKey: ['releases'] })
          onBlockedChanged?.(p)
        }))
        return Promise.allSettled(releaseIds.map((id) => teamOneWs.sub(`gate:${id}`)))
      })
      .catch(() => { /* WS 不可达时静默：页面留有手动刷新与查询失效兜底 */ })
    // 仅在 releaseIds 集合（key）或 queryClient 变化时重建订阅
    return () => {
      disposed = true
      offs.forEach((off) => off())
      releaseIds.forEach((id) => teamOneWs.unsub(`gate:${id}`))
    }
  }, [key, queryClient])
}

// ---------------- 关联提交（V-6 前端接线，M2-INC-1 W1 / M2-C） ----------------

/** GET /api/v1/commits?workItemKey= 条目（eng.commit_work_item 投影，created_at DESC） */
export interface RemoteCommit {
  repo: string
  sha: string
  authorName?: string
  authorEmail?: string
  committedAt?: string
  subject?: string
}

interface CommitsPayload { workItemKey: string; count: number; items: RemoteCommit[] }

/** 工作项关联提交清单（push hook 解析 refs #KEY 留痕；无提交返回空清单，不视为错误） */
export function useCommits(workItemKey: string | undefined) {
  return useQuery({
    queryKey: ['commits', workItemKey],
    enabled: !!workItemKey,
    queryFn: () =>
      api<CommitsPayload>(`/api/v1/commits?workItemKey=${encodeURIComponent(workItemKey!)}`),
  })
}

// ---------------- RoadMap 时间轴（M2-INC-1 W1 只读接通，05 §3.3 /roadmap/timeline） ----------------

/** 条目关联版本引用（时间窗：planDate/codeFreezeDate；roadmapItemId=版本→条目反查指针，R-7/B5 起投影） */
export interface RoadmapReleaseRef {
  id: string
  key: string
  name: string
  status: string
  roadmapItemId?: string
  planDate?: string
  codeFreezeDate?: string
}

/** 时间轴条目行（进度=查询侧 COUNT FILTER 聚合投影 workItemDone/total） */
export interface RoadmapItemRow {
  id: string
  name: string
  productId?: string
  goalId?: string
  releaseId?: string
  startDate?: string
  dueDate?: string
  workItemDone: number
  total: number
  releaseIds: string[]
  release?: RoadmapReleaseRef
}

/** goal 视图行：目标泳道（roadmapItems = 挂该目标的条目） */
export interface TimelineGoalRow {
  id: string
  name: string
  ownerId?: string
  workItemDone: number
  total: number
  roadmapItems: RoadmapItemRow[]
}

/** release 视图行：版本泳道（R-7/B5 主视角；条目=正向挂接 ∪ roadmapItemId 反查，服务端已去重） */
export interface TimelineReleaseRow extends RoadmapReleaseRef {
  productId?: string
  roadmapItems: RoadmapItemRow[]
}

export type TimelinePayload =
  | { view: 'goal'; items: TimelineGoalRow[] }
  | { view: 'release'; items: TimelineReleaseRow[] }

/** RoadMap 双视角时间轴（W1 基础版：数据全来自查询侧聚合，状态/进度不落冗余列） */
export function useRoadmapTimeline(view: 'goal' | 'release') {
  return useQuery({
    queryKey: ['roadmap', 'timeline', view],
    queryFn: () => api<TimelinePayload>(`/api/v1/roadmap/timeline?view=${view}`),
  })
}

/** GET /api/v1/roadmap-items 条目（Views.of(RoadmapItem)；dogfooding 切换：工作项关联链路真实数据源） */
export interface RemoteRoadmapItem {
  id: string
  name: string
  description?: string
  productId?: string
  goalId?: string
  startDate?: string
  dueDate?: string
}

export function useRoadmapItems() {
  return useQuery({
    queryKey: ['roadmap', 'items'],
    staleTime: 60_000,
    queryFn: () => api<RemoteRoadmapItem[]>('/api/v1/roadmap-items'),
  })
}

// ---------------- 冲突中心（M2-INC-1 W2 真实 API，05 §3.3 GET /conflicts） ----------------

/** 冲突时间窗口（批算器伴随投影，可缺省；ISO yyyy-MM-dd，单日窗口 start===end） */
export interface ConflictWindow {
  start: string
  end: string
}

/** 参与冲突的事件（工作项投影，可缺省；后端拿不到 title 时整个键省略，故 title 可选） */
export interface ConflictEvent {
  key: string
  title?: string
  start?: string
  due?: string
}

/** 冲突快照条目（payload=批算器写入明细；fp 为红色新增判定指纹，页面无需消费） */
export interface RemoteConflict {
  kind: 'CF-1' | 'CF-2' | 'CF-3' | 'CF-4' | 'CF-5' | 'CF-6'
  payload: {
    severity: 'red' | 'yellow'
    subjectType: 'user' | 'task' | 'release'
    subjectId: string
    userId?: string
    detail: string
    relatedTaskIds: string[]
    fp: string
    /** 冲突时间窗口（缺省安全：旧快照无此字段则页面不渲染该行；ISO yyyy-MM-dd，单日窗口 start===end） */
    window?: ConflictWindow
    /** 参与冲突的事件列表（缺省安全：旧快照无此字段则页面不渲染） */
    events?: ConflictEvent[]
  }
  detectedAt: string
  resolvedAt?: string
}

interface ConflictPage { items: RemoteConflict[] }

/** 冲突快照查询（kind 可选过滤；服务端 red 优先 + kind 字典序） */
export function useConflicts(kind?: string) {
  return useQuery({
    queryKey: ['conflicts', kind ?? 'all'],
    queryFn: () =>
      api<ConflictPage>(`/api/v1/conflicts${kind ? `?kind=${encodeURIComponent(kind)}` : ''}`)
        .then((p) => p.items),
  })
}

/** 热力图原料矩阵（50 人×90 天 ≤2s；服务端只回小时原料，无判决字段——红线②） */
export interface HeatmapPayload {
  from: string
  users: { userId: string }[]
  days: string[]
  cells: { userId: string; date: string; hours: string }[]
}

export function useHeatmap(days = 90) {
  return useQuery({
    queryKey: ['conflicts', 'heatmap', days],
    queryFn: () => api<HeatmapPayload>(`/api/v1/conflicts/heatmap?days=${days}`),
  })
}

export const conflictsApi = {
  /** POST /conflicts/recompute（admin：平台级 platform:manage；非 admin 403 T1-PLT-4030） */
  recompute(): Promise<{ total: number; red: number; yellow: number; redNew: number; notifiedUsers: number }> {
    return api('/api/v1/conflicts/recompute', { method: 'POST' })
  },
}

/** CF-3 里程碑挤压快照（UT-31：RoadMap「发布挤压」警示带数据源；GET /conflicts?kind=CF-3，仅需登录态，与 useConflicts 共享缓存） */
export function useCf3Conflicts() {
  return useConflicts('CF-3')
}

// ---------------- 迭代（M2-INC-1 W2 TasksPage 迭代联动：GET /sprints + sprintId 过滤） ----------------

/** GET /api/v1/sprints 条目（Views.of(Sprint) 实测投影；completedAt 非空即已完成） */
export interface RemoteSprint {
  id: string
  name: string
  productId: string
  releaseId?: string
  capacityHours?: number
  startDate?: string
  dueDate?: string
  completedAt?: string
}

export function useSprints() {
  return useQuery({
    queryKey: ['sprints'],
    queryFn: () => api<RemoteSprint[]>('/api/v1/sprints'),
  })
}

/** GET /work-items?sprintId= 原始条目扩展字段（W2 起后端补 sprintId 过滤 + 日期/工时投影） */
export interface RemoteSprintWorkItem extends RemoteWorkItem {
  sprintId?: string
  releaseId?: string
  roadmapItemId?: string
  requirementId?: string
  startDate?: string
  dueDate?: string
  storyPoints?: number
  estimateHours?: number
  priority?: string
}

/** 迭代看板数据（sprintId 过滤；size=200 覆盖单迭代量级） */
export function useSprintWorkItems(sprintId: string | undefined) {
  return useQuery({
    queryKey: ['work-items', 'sprint', sprintId],
    enabled: !!sprintId,
    queryFn: () =>
      api<PagePayload<RemoteSprintWorkItem>>(
        `/api/v1/work-items?sprintId=${encodeURIComponent(sprintId!)}&page=1&size=200`,
      ).then((p) => p.items),
  })
}

// ---------------- 代码仓储域（M2-INC-3 U1~U3：自研 Git 仓储与文件/提交浏览） ----------------

export interface RemoteRepo {
  id: string
  name: string
  repoPath: string
  defaultBranch: string
  description?: string
  productId?: string
  componentId?: string
  visibility: string
  ciEnabled: boolean
  createdAt: string
  updatedAt: string
  branchCount?: number
  commitCount?: number
  stars?: number
}

export interface RemoteBranch {
  name: string
  commitSha: string
  committedAt?: string
  commitSubject?: string
}

export interface RemoteTag {
  name: string
  commitSha: string
  committedAt?: string
  message?: string
}

export interface RemoteCommit {
  sha: string
  authorName?: string
  authorEmail?: string
  committedAt?: string
  subject?: string
  body?: string
  /** 该提交所在分支（分支治理批补充；缺省安全，前端回退当前浏览分支） */
  branch?: string
}

export interface RemoteTreeItem {
  mode: string
  type: 'blob' | 'tree'
  sha: string
  size?: number | null
  name: string
  path: string
}

export interface RemoteBlob {
  path: string
  size: number
  content?: string
  isBinary: boolean
}

export function useRepos() {
  return useQuery({
    queryKey: ['repos'],
    queryFn: () => api<{ items: RemoteRepo[]; total: number }>('/api/v1/repos'),
  })
}

export function useRepo(idOrName: string | undefined) {
  return useQuery({
    queryKey: ['repo', idOrName],
    enabled: !!idOrName,
    queryFn: () => api<RemoteRepo>(`/api/v1/repos/${encodeURIComponent(idOrName!)}`),
  })
}

export function useRepoBranches(idOrName: string | undefined) {
  return useQuery({
    queryKey: ['repo', idOrName, 'branches'],
    enabled: !!idOrName,
    queryFn: () => api<RemoteBranch[]>(`/api/v1/repos/${encodeURIComponent(idOrName!)}/branches`),
  })
}

export function useRepoTags(idOrName: string | undefined) {
  return useQuery({
    queryKey: ['repo', idOrName, 'tags'],
    enabled: !!idOrName,
    queryFn: () => api<RemoteTag[]>(`/api/v1/repos/${encodeURIComponent(idOrName!)}/tags`),
  })
}

export function useRepoCommits(idOrName: string | undefined, ref?: string, page = 1, size = 20) {
  return useQuery({
    queryKey: ['repo', idOrName, 'commits', ref, page, size],
    enabled: !!idOrName,
    queryFn: () => {
      const p = new URLSearchParams()
      if (ref) p.set('ref', ref)
      p.set('page', String(page))
      p.set('size', String(size))
      return api<{ items: RemoteCommit[]; page: number; size: number; total: number; ref: string }>(
        `/api/v1/repos/${encodeURIComponent(idOrName!)}/commits?${p.toString()}`,
      )
    },
  })
}

export function useRepoTree(idOrName: string | undefined, ref?: string, path?: string) {
  return useQuery({
    queryKey: ['repo', idOrName, 'tree', ref, path],
    enabled: !!idOrName,
    queryFn: () => {
      const p = new URLSearchParams()
      if (ref) p.set('ref', ref)
      if (path) p.set('path', path)
      return api<{ items: RemoteTreeItem[]; ref: string; path: string }>(
        `/api/v1/repos/${encodeURIComponent(idOrName!)}/tree?${p.toString()}`,
      )
    },
  })
}

export function useRepoBlob(idOrName: string | undefined, ref?: string, path?: string) {
  return useQuery({
    queryKey: ['repo', idOrName, 'blob', ref, path],
    enabled: !!idOrName && !!path,
    queryFn: () => {
      const p = new URLSearchParams()
      if (ref) p.set('ref', ref)
      p.set('path', path!)
      return api<RemoteBlob>(
        `/api/v1/repos/${encodeURIComponent(idOrName!)}/blob?${p.toString()}`,
      )
    },
  })
}

// ---------------- 分支策略（branch-rules）与 cherry-pick（分支治理批） ----------------

/**
 * 分支策略规则行（GET/PUT /api/v1/repos/{idOrName}/branch-rules 契约字段，全部缺省安全）
 */
export interface RemoteBranchRule {
  /** 分支类型（如 feature / fix / poc / release / hotfix；同时是 DELETE 的路径标识） */
  branchType: string
  /** 分支名称模式（如 feature/*；仓库有规则时新建分支名必须命中某条模式，否则 422） */
  namePattern: string
  /** 起源分支（从哪个分支切出） */
  baseBranch: string
  /** 合入目标分支 */
  mergeTarget: string
  /** 是否允许直接 push（false=必须走 MR） */
  allowDirectPush: boolean
  /** 合并后是否自动删除分支 */
  autoDeleteAfterMerge: boolean
  /** 规则说明（人话） */
  description?: string
}

/** 分支策略模型（GET 只返回规则列表，model 由前端按规则形状推断） */
export type BranchModel = 'gitflow' | 'github-flow' | 'custom'

/** PUT 保存载荷：model + 全量规则（整仓替换） */
export interface BranchRulesPayload {
  model: BranchModel
  rules: RemoteBranchRule[]
}

/** GET /repos/{idOrName}/branch-rules（未配置策略时返回空数组，不视为错误） */
export function useRepoBranchRules(idOrName: string | undefined) {
  return useQuery({
    queryKey: ['repo', idOrName, 'branch-rules'],
    enabled: !!idOrName,
    queryFn: () => api<RemoteBranchRule[]>(`/api/v1/repos/${encodeURIComponent(idOrName!)}/branch-rules`),
  })
}

/** 分支策略 API 客户端对象（分支治理批） */
export const branchRulesApi = {
  /** 列表（与 useRepoBranchRules 同源，供命令式调用） */
  list(idOrName: string): Promise<RemoteBranchRule[]> {
    return api<RemoteBranchRule[]>(`/api/v1/repos/${encodeURIComponent(idOrName)}/branch-rules`)
  },
  /** PUT 整仓保存（model + 全量规则）→ 返回保存后的规则列表 */
  saveAll(idOrName: string, payload: BranchRulesPayload): Promise<RemoteBranchRule[]> {
    return api<RemoteBranchRule[]>(`/api/v1/repos/${encodeURIComponent(idOrName)}/branch-rules`, {
      method: 'PUT',
      body: payload,
    })
  },
  /** DELETE 删除单条规则（204 无返回体；branchType 为路径标识） */
  async remove(idOrName: string, branchType: string): Promise<void> {
    await api(`/api/v1/repos/${encodeURIComponent(idOrName)}/branch-rules/${encodeURIComponent(branchType)}`, {
      method: 'DELETE',
    })
  },
}

// ---------------- 仓库成员权限（ACL · docs/v2/13 §2 角色矩阵 / §5 授权流程） ----------------

/** 仓库四级角色（契约小写；前端比较一律 toLowerCase 兜底大小写漂移） */
export type RepoMemberRole = 'owner' | 'maintainer' | 'developer' | 'reporter'

/** 授权来源：DIRECT=直接授予；INHERITED=建仓系统授予（建仓人 Owner，不可改角色不可删）；GROUP=组成员命中 */
export type RepoMemberSource = 'DIRECT' | 'INHERITED' | 'GROUP'

/** GET /repos/{idOrName}/members 行投影（契约字段，可缺省安全兜底） */
export interface RepoMemberItem {
  userId: string
  username: string
  displayName?: string
  role: RepoMemberRole
  source: RepoMemberSource
  /** 授予人姓名（后端已 humanize；缺省前端兜底「-」） */
  grantedByName?: string
  grantedAt?: string
}

/** GET /repos/{idOrName}/members（所有人可查看；空列表=仅平台 OWNER/ADMIN 兜底管理） */
export function useRepoMembers(idOrName: string | undefined) {
  return useQuery({
    queryKey: ['repo', idOrName, 'members'],
    enabled: !!idOrName,
    queryFn: async () => {
      const page = await api<{ items: RepoMemberItem[] }>(
        `/api/v1/repos/${encodeURIComponent(idOrName!)}/members`,
      )
      return page.items ?? []
    },
  })
}

/** 仓库成员 API 客户端对象（ACL 成员面板批）。写操作仅仓库 Owner 角色 ∨ 平台 OWNER/ADMIN（后端 403 兜底） */
export const repoMembersApi = {
  /** 列表（与 useRepoMembers 同源，供命令式调用） */
  list(idOrName: string): Promise<RepoMemberItem[]> {
    return api<{ items: RepoMemberItem[] }>(`/api/v1/repos/${encodeURIComponent(idOrName)}/members`).then(
      (p) => p.items ?? [],
    )
  },
  /** PUT upsert 成员（添加成员与改角色同入口）→ 返回单个成员 DTO（QA 复审 MUST-FIX：后端返回单对象非列表）；409=最后 Owner 保护，403=无权管理 */
  upsert(idOrName: string, userId: string, role: RepoMemberRole): Promise<RepoMemberItem> {
    return api<RepoMemberItem>(`/api/v1/repos/${encodeURIComponent(idOrName)}/members`, {
      method: 'PUT',
      body: { userId, role },
    })
  },
  /** DELETE 移除成员（204 无返回体）；409=最后 Owner 保护，400=服务端说明（读 err.message） */
  async remove(idOrName: string, userId: string): Promise<void> {
    await api(`/api/v1/repos/${encodeURIComponent(idOrName)}/members/${encodeURIComponent(userId)}`, {
      method: 'DELETE',
    })
  },
}

// ---------------- 我的能力位（ACL · docs/v2/13 §4.5：前端按钮显隐数据源） ----------------

/** GET /repos/{idOrName}/me/permissions 响应（契约字段；capabilities 如 view/pull/push/create-branch/...） */
export interface RemoteRepoMyPermissions {
  /** 当前生效仓库角色（后端 DTO 实名字段 role，QA 复审 MUST-FIX 对齐）；null=无仓库角色（仅 visibility 兜底可读） */
  role: RepoMemberRole | null
  /** 能力位清单（逐动作走五步链，与后端放行同源） */
  capabilities: string[]
  /** 平台 OWNER/ADMIN 短路标记（仅短路时出现，可缺省） */
  platformAdmin?: boolean
}

/**
 * 我的仓库能力位（GET /repos/{idOrName}/me/permissions，staleTime 30s）。
 * 消费方约定（docs/v2/13 §5.2 ②）：无能力的按钮不渲染（非置灰）；
 * 缺省安全——data 未就绪（加载中/端点未部署 404）时按「有能力」渲染，后端 403 兜底不变。
 */
export function useRepoMyPermissions(idOrName: string | undefined) {
  return useQuery({
    queryKey: ['repo', idOrName, 'my-permissions'],
    enabled: !!idOrName,
    staleTime: 30_000,
    retry: false,
    queryFn: () =>
      api<RemoteRepoMyPermissions>(
        `/api/v1/repos/${encodeURIComponent(idOrName!)}/me/permissions`,
      ),
  })
}

/** cherry-pick 结果（摘取后在目标分支生成的新提交） */
export interface RemoteCherryPickResult {
  /** 目标分支上的新提交 SHA（与源提交不同） */
  commitSha: string
  /** 目标分支名 */
  branch: string
}

/** cherry-pick API（产品回测场景：把 main 上的修复摘到 release 分支回归；422/409=冲突等业务错误，读 err.message 原样展示） */
export const cherryPickApi = {
  pick(idOrName: string, commitSha: string, targetBranch: string): Promise<RemoteCherryPickResult> {
    return api<RemoteCherryPickResult>(`/api/v1/repos/${encodeURIComponent(idOrName)}/cherry-pick`, {
      method: 'POST',
      body: { commitSha, targetBranch },
    })
  },
}

// ---------------- MR 评审与对比（U5~U7） ----------------

export interface RemoteDiffLine {
  oldNo?: number
  newNo?: number
  type: 'add' | 'del' | 'ctx'
  text: string
}

export interface RemoteFileDiff {
  path: string
  status: 'modified' | 'added' | 'removed'
  additions: number
  deletions: number
  lines: RemoteDiffLine[]
}

export interface RemoteDiffResult {
  totalAdditions: number
  totalDeletions: number
  files: RemoteFileDiff[]
}

export interface RemoteMergeCheck {
  canMerge: boolean
  rebaseRequired: boolean
  conflictFiles: string[]
}

export interface RemoteCompareResult {
  baseSha?: string
  targetSha: string
  sourceSha: string
  commits: RemoteCommit[]
  diff: RemoteDiffResult
  mergeCheck: RemoteMergeCheck
}

export interface RemoteMergeRequest {
  id: string
  number: number
  title: string
  description?: string
  repoId: string
  repoName?: string
  sourceBranch: string
  targetBranch: string
  authorId: string
  reviewers: { userId: string; state: 'pending' | 'approved' | 'changes_requested' }[]
  status: 'open' | 'merged' | 'closed' | 'draft'
  checks: { name: string; status: 'passed' | 'failed' | 'running' | 'pending' }[]
  conflicts: boolean
  conflictFiles: string[]
  conflictResolutions?: { filePath: string; solution: string; confirmedById?: string; reviewedById?: string; resolvedAt: string }[]
  unitTestCheck: {
    hasTests: boolean
    testFiles: string[]
    passed: boolean
    coverageTotal: number
    coverageDelta: number
    gatePassed: boolean
    /** 门禁回传备注——后端实名落在 reportUrl（"/pipelines/{id} （…;coverage=jacoco）"，
     *  ⑥l 集成实测契约对齐）；前端据此渲染覆盖率来源徽标：jacoco→「真实」/ simulated→「模拟」，缺省不渲染 */
    reportUrl?: string
    exempt?: { reason: string; approvedById: string; approvedAt?: string }
  }
  rebaseRequired: boolean
  linkedWorkItemKey?: string
  basedOnBaselineId?: string
  /** 合并门禁覆盖率阈值（B2 · 可缺省 → 前端按 60/80 兜底） */
  gateConfig?: { totalCoverage: number; patchCoverage: number }
  additions: number
  deletions: number
  diffs?: RemoteFileDiff[]
  comments: { id: string; authorId: string; text: string; createdAt: string }[]
  mergeCommitSha?: string
  mergedById?: string
  mergedAt?: string
  closedAt?: string
  createdAt: string
  updatedAt: string
}

export function useMergeRequests(repoId?: string, status?: string) {
  return useQuery({
    queryKey: ['mrs', repoId, status],
    queryFn: () => {
      const p = new URLSearchParams()
      if (repoId) p.set('repoId', repoId)
      if (status) p.set('status', status)
      return api<{ items: RemoteMergeRequest[]; total: number }>(`/api/v1/mrs?${p.toString()}`)
    },
  })
}

export function useMergeRequest(id: string | undefined) {
  return useQuery({
    queryKey: ['mr', id],
    enabled: !!id,
    // B2 去 mock：404（未找到该评审）直接进入错误态不重试；页面不再回退 store 假数据
    retry: (failureCount, error) => !(error instanceof ApiError && error.status === 404) && failureCount < 2,
    queryFn: () => api<RemoteMergeRequest>(`/api/v1/mrs/${encodeURIComponent(id!)}`),
  })
}

export function useMrDiff(id: string | undefined) {
  return useQuery({
    queryKey: ['mr', id, 'diff'],
    enabled: !!id,
    queryFn: () => api<RemoteDiffResult>(`/api/v1/mrs/${encodeURIComponent(id!)}/diff`),
  })
}

export function useRepoCompare(idOrName: string | undefined, target?: string, source?: string) {
  return useQuery({
    queryKey: ['repo', idOrName, 'compare', target, source],
    enabled: !!idOrName && !!target && !!source,
    queryFn: () => {
      const p = new URLSearchParams()
      p.set('target', target!)
      p.set('source', source!)
      return api<RemoteCompareResult>(`/api/v1/repos/${encodeURIComponent(idOrName!)}/compare?${p.toString()}`)
    },
  })
}

export const mrsApi = {
  create(data: {
    repoId: string
    title: string
    description?: string
    sourceBranch: string
    targetBranch: string
    reviewerIds?: string[]
    linkedWorkItemKey?: string
    basedOnBaselineId?: string
  }): Promise<RemoteMergeRequest> {
    return api<RemoteMergeRequest>('/api/v1/mrs', { method: 'POST', body: data })
  },
  review(id: string, state: 'approved' | 'changes_requested', comment?: string): Promise<RemoteMergeRequest> {
    return api<RemoteMergeRequest>(`/api/v1/mrs/${encodeURIComponent(id)}/review`, { method: 'POST', body: { state, comment } })
  },
  exemptUnitTest(id: string, reason: string): Promise<RemoteMergeRequest> {
    return api<RemoteMergeRequest>(`/api/v1/mrs/${encodeURIComponent(id)}/checks/unit-test/exempt`, { method: 'POST', body: { reason } })
  },
  resolveConflict(id: string, filePath: string, solution: string): Promise<RemoteMergeRequest> {
    return api<RemoteMergeRequest>(`/api/v1/mrs/${encodeURIComponent(id)}/conflicts/resolve`, { method: 'POST', body: { filePath, solution } })
  },
  rebase(id: string): Promise<RemoteMergeRequest> {
    return api<RemoteMergeRequest>(`/api/v1/mrs/${encodeURIComponent(id)}/rebase`, { method: 'POST' })
  },
  merge(id: string): Promise<{ ok: boolean; mergeCommitSha: string; message: string }> {
    return api<{ ok: boolean; mergeCommitSha: string; message: string }>(`/api/v1/mrs/${encodeURIComponent(id)}/merge`, { method: 'POST' })
  },
  addComment(id: string, text: string): Promise<RemoteMergeRequest> {
    return api<RemoteMergeRequest>(`/api/v1/mrs/${encodeURIComponent(id)}/comments`, { method: 'POST', body: { text } })
  },
  /** 关闭评审（⑥h 评审生命周期补全）：draft|open → closed；权限=作者本人或管理员；非法迁移 4xx */
  close(id: string): Promise<RemoteMergeRequest> {
    return api<RemoteMergeRequest>(`/api/v1/mrs/${encodeURIComponent(id)}/close`, { method: 'POST' })
  },
  /** 重新打开评审（⑥h 评审生命周期补全）：closed → open；merged 不可重开（4xx） */
  reopen(id: string): Promise<RemoteMergeRequest> {
    return api<RemoteMergeRequest>(`/api/v1/mrs/${encodeURIComponent(id)}/reopen`, { method: 'POST' })
  },
  /** 触发 MR 涉及变更文件的后台异步 Blame 预热（S-1'） */
  preloadBlame(id: string): Promise<{ ok: boolean; queuedFiles: number; message: string }> {
    return api<{ ok: boolean; queuedFiles: number; message: string }>(`/api/v1/mrs/${encodeURIComponent(id)}/blame/preload`, { method: 'POST' })
  },
  /** 获取 MR 关联的单个文件的 Blame 溯源信息 */
  getBlame(id: string, filePath: string): Promise<RemoteBlameResult> {
    return api<RemoteBlameResult>(`/api/v1/mrs/${encodeURIComponent(id)}/blame?path=${encodeURIComponent(filePath)}`)
  },
}

// ---------------- 基线与分支保护（V-15） ----------------

/**
 * 远程基线数据接口（映射 eng.baseline 表与 BaselineResponse）
 */
export interface RemoteBaseline {
  /** 基线唯一标识 UUID */
  id: string
  /** 所属仓库 ID */
  repoId: string
  /** 所属仓库名称 */
  repoName: string
  /** 基线名称（如 "V1.0.0-GA 正式发布基线"） */
  name: string
  /** 基线类型：functional(功能基线) / allocated(分配基线) / product(产品基线) */
  type: 'functional' | 'allocated' | 'product'
  /** 对应的 Git 标签（Tag）名 */
  tagRef: string
  /** 基线固化锁定的 Commit 完整 40 位 SHA */
  commitSha?: string
  /** 关联产物包版本号 */
  artifactVersion?: string
  /** 关联需求基线快照或条目 ID */
  requirementSnapshotId?: string
  /** 基线流转状态：draft(草稿) / in_review(评审中) / approved(已定版冻结) / superseded(已废止) */
  status: 'draft' | 'in_review' | 'approved' | 'superseded'
  /** 后继替代基线的 UUID（当状态为 superseded 时有效） */
  supersededById?: string
  /** 审批同意本基线的用户 ID 列表（双人会签达到 2 人自动定版打 Tag） */
  approverIds: string[]
  /** 创建人用户 ID */
  createdBy?: string
  /** 创建时间（ISO-8601） */
  createdAt: string
  /** 批准定版冻结时间（ISO-8601） */
  approvedAt?: string
}

/**
 * 远程分支保护策略数据接口（映射 eng.branch_protection 表）
 */
export interface RemoteBranchProtection {
  /** 规则唯一标识 UUID */
  id: string
  /** 所属仓库 ID */
  repoId: string
  /** 目标保护分支通配表达式（如 "main", "release/*"） */
  branchPattern: string
  /** 是否强制要求通过 MR 合并（禁止直接 push） */
  requireMr: boolean
  /** MR 合并前所需达到的最少独立批准人数 */
  minApprovals: number
  /** 是否强制要求单元测试与 CI 门禁通过（或豁免） */
  requireUnitTest: boolean
  /** 是否禁止强推（git push --force） */
  blockForcePush: boolean
  /** 规则创建时间（ISO-8601） */
  createdAt: string
  /** 规则最后修改时间（ISO-8601） */
  updatedAt: string
}

/**
 * React Query Hook: 获取指定仓库的所有基线列表
 * @param idOrName 仓库 ID 或短名称
 */
export function useRepoBaselines(idOrName: string | undefined) {
  return useQuery({
    queryKey: ['repo', idOrName, 'baselines'],
    enabled: !!idOrName,
    queryFn: () => api<RemoteBaseline[]>(`/api/v1/repos/${encodeURIComponent(idOrName!)}/baselines`),
  })
}

/**
 * React Query Hook: 获取指定仓库配置的所有分支保护规则
 * @param idOrName 仓库 ID 或短名称
 */
export function useRepoProtections(idOrName: string | undefined) {
  return useQuery({
    queryKey: ['repo', idOrName, 'protections'],
    enabled: !!idOrName,
    queryFn: () => api<RemoteBranchProtection[]>(`/api/v1/repos/${encodeURIComponent(idOrName!)}/protections`),
  })
}

/**
 * 基线管理 API 客户端调用对象
 */
export const baselinesApi = {
  /** 在指定仓库下新建基线（草稿状态） */
  create(idOrName: string, data: {
    name: string
    type: string
    tagRef: string
    targetRefOrSha?: string
    artifactVersion?: string
    requirementSnapshotId?: string
    description?: string
  }): Promise<RemoteBaseline> {
    return api<RemoteBaseline>(`/api/v1/repos/${encodeURIComponent(idOrName)}/baselines`, { method: 'POST', body: data })
  },
  /** 提交草稿基线进入评审审批流 */
  submit(id: string): Promise<RemoteBaseline> {
    return api<RemoteBaseline>(`/api/v1/baselines/${encodeURIComponent(id)}/submit`, { method: 'POST' })
  },
  /** 审批通过基线（双人会签达到 2 人自动生成 Annotated Tag 并不可变冻结） */
  approve(id: string): Promise<RemoteBaseline> {
    return api<RemoteBaseline>(`/api/v1/baselines/${encodeURIComponent(id)}/approve`, { method: 'POST' })
  },
  /** 废止旧基线并创建新基线替代 */
  supersede(id: string, newReq: {
    name: string
    type: string
    tagRef: string
    targetRefOrSha?: string
    artifactVersion?: string
    requirementSnapshotId?: string
    description?: string
  }): Promise<RemoteBaseline> {
    return api<RemoteBaseline>(`/api/v1/baselines/${encodeURIComponent(id)}/supersede`, { method: 'POST', body: newReq })
  },
}

/**
 * 分支保护规则 API 客户端调用对象
 */
export const branchProtectionsApi = {
  /** 保存或更新指定仓库的分支保护策略 */
  save(idOrName: string, data: {
    branchPattern: string
    requireMr?: boolean
    minApprovals?: number
    requireUnitTest?: boolean
    blockForcePush?: boolean
  }): Promise<RemoteBranchProtection> {
    return api<RemoteBranchProtection>(`/api/v1/repos/${encodeURIComponent(idOrName)}/protections`, { method: 'POST', body: data })
  },
  /** 删除指定的分支保护策略 */
  delete(idOrName: string, protectionId: string): Promise<{ ok: boolean; message: string }> {
    return api<{ ok: boolean; message: string }>(`/api/v1/repos/${encodeURIComponent(idOrName)}/protections/${encodeURIComponent(protectionId)}`, { method: 'DELETE' })
  },
}

// ---------------- CI/CD 流水线与自研工作树（V-16） ----------------

/**
 * 流水线内部单个作业（Job）明细
 */
export interface RemoteJob {
  /** 作业唯一标识 */
  id: string
  /** 作业显示名称（如 "Maven Compile & Package"） */
  name: string
  /** 作业执行状态 */
  status: 'passed' | 'failed' | 'running' | 'pending' | 'skipped' | 'canceled'
  /** 作业耗时秒数 */
  durationSec?: number
  /** 作业输出控制台日志行列表 */
  logs: string[]
}

/**
 * 流水线内部阶段（Stage）明细
 */
export interface RemoteStage {
  /** 阶段显示名称（如 "构建阶段 (Build)", "测试门禁 (Test Gate)"） */
  name: string
  /** 阶段聚合状态（由内部 jobs 状态推导） */
  status: 'passed' | 'failed' | 'running' | 'pending' | 'skipped' | 'canceled'
  /** 阶段耗时秒数 */
  durationSec?: number
  /** 阶段摘要文本（stages jsonb 回传；test 阶段可含 "覆盖率 total 78.3% / patch 85.0%"，
   *  ⑥l-B 前端正则提取后在 test 作业行尾显示覆盖率小徽标；缺省安全） */
  summary?: string
  /** 阶段内部包含的作业列表 */
  jobs: RemoteJob[]
}

/**
 * 流水线真实执行作业明细（⑥k-B 契约：GET /api/v1/pipelines/{id} 在既有字段外加 jobs[]，全部缺省安全）
 */
export interface RemotePipelineJob {
  /** 作业唯一标识 */
  id: string
  /** 所属阶段：build=构建 / test=测试（真实执行 Build→Test 链式，作业逐个 running） */
  stage: 'build' | 'test' | string
  /** 作业显示名称 */
  name: string
  /** 作业状态：pending/running/success/failed/skipped（真实内核用 success，展示前经 normRunStatus 归一化） */
  status: 'pending' | 'running' | 'success' | 'failed' | 'skipped' | string
  /** 进程退出码（未结束/未运行为 null） */
  exitCode?: number | null
  /** 作业开始时间（ISO-8601，未开始为 null） */
  startedAt?: string | null
  /** 作业结束时间（ISO-8601，未结束为 null） */
  finishedAt?: string | null
  /** 尾部日志文本（可 null；模拟运行或未开始时为空） */
  logTail?: string | null
  /** 失败原因（超时/工具缺失/环境故障；日志为空时兜底展示——QA 复审 MUST-FIX） */
  errorMsg?: string | null
}

/**
 * run/job 状态归一化：真实执行内核 run.status/job.status 语义为 pending/running/success/failed，
 * 前端 RunStatus 历史口径为 passed——统一在数据入口把 success 折叠为 passed（其余原样透传）。
 */
export function normRunStatus(s: string | undefined): 'passed' | 'failed' | 'running' | 'pending' | 'skipped' | 'canceled' {
  return s === 'success' ? 'passed' : ((s ?? 'pending') as 'passed' | 'failed' | 'running' | 'pending' | 'skipped' | 'canceled')
}

/**
 * 远程流水线运行记录（映射 eng.pipeline_run 表与 PipelineRunResponse）
 */
export interface RemotePipelineRun {
  /** 流水线运行唯一标识 UUID */
  id: string
  /** 所属仓库 ID */
  repoId: string
  /** 所属仓库名称 */
  repoName: string
  /** 流水线标题（如 "#101 · main 自动化构建与测试"） */
  title: string
  /** 触发分支名称 */
  branch: string
  /** 构建针对的完整 40 位 Commit SHA */
  commitSha: string
  /** 简短 7 位 Commit SHA */
  commitShort?: string
  /** 流水线整体运行状态 */
  status: 'passed' | 'failed' | 'running' | 'pending' | 'skipped' | 'canceled'
  /** 触发来源：push(代码推送) / manual(手动触发) / schedule(定时构建) / mr(合并请求) */
  trigger: 'push' | 'manual' | 'schedule' | 'mr'
  /** 触发人用户 ID */
  triggerUserId: string
  /** 关联的合并请求（MR）ID，有值时执行完毕将自动反哺 MR 单测门禁 */
  mrId?: string
  /** 开始执行时间（ISO-8601） */
  startedAt?: string
  /** 执行完成时间（ISO-8601） */
  finishedAt?: string
  /** 累计执行总时长（秒） */
  durationSec: number
  /** 各阶段及作业明细 */
  stages: RemoteStage[]
  /** 构建体系标识（⑥k-B 契约：列表项在既有字段外加 buildSystem；缺省安全，旧数据无此字段按 unknown 展示） */
  buildSystem?: 'maven' | 'npm' | 'unknown' | string
  /** 真实执行作业明细（详情契约新增；模拟时代旧 run 无此字段 → 页面显示「模拟运行（无作业明细）」） */
  jobs?: RemotePipelineJob[]
}

/**
 * 客户端工作树（Worktree）上报与拓扑视图模型（映射 eng.worktree_report 表）
 */
export interface RemoteWorktreeReport {
  /** 工作树记录唯一标识 UUID */
  id: string
  /** 所属仓库 ID */
  repoId: string
  /** 客户端本地绝对路径 */
  localPath: string
  /** 工作树检出的分支名称 */
  branchName: string
  /** 分离检出时的基准分支或 commit */
  baseRef: string
  /** 工作树持有者（拥有人）用户 ID */
  ownerUserId: string
  /** 客户端未提交或未追踪的脏文件变更数量 */
  dirtyFileCount: number
  /** 本地分支领先远程主线 Commit 数量 */
  aheadCount: number
  /** 本地分支落后远程主线 Commit 数量 */
  behindCount: number
  /** 工作树活跃状态：active(活跃检出) / merged(对应分支已合并) / stale(陈旧闲置) */
  status: 'active' | 'merged' | 'stale'
  /** 本地最新提交的 Commit SHA */
  lastCommitSha?: string
  /** 本地最新提交的时间（ISO-8601） */
  lastCommitAt?: string
  /** 客户端最后心跳/上报活跃时间（ISO-8601） */
  lastActiveAt: string
  /** 创建时间（ISO-8601） */
  createdAt?: string
  /** 更新时间（ISO-8601） */
  updatedAt?: string
}

/**
 * React Query Hook: 查询 CI 流水线运行列表
 * @param repoId 可选仓库 ID 过滤
 * @param branch 可选分支过滤
 * @param status 可选状态过滤
 */
export function usePipelines(repoId?: string, branch?: string, status?: string) {
  return useQuery({
    queryKey: ['pipelines', repoId, branch, status],
    queryFn: () => {
      const p = new URLSearchParams()
      if (repoId) p.set('repoId', repoId)
      if (branch) p.set('branch', branch)
      if (status) p.set('status', status)
      return api<{ items: RemotePipelineRun[]; page: number; size: number; total: number }>(`/api/v1/pipelines?${p.toString()}`)
    },
    // 运行中行状态自动刷新（⑥k-B）：页面存在 running/pending 的 run 时 3s 轮询，全部落定后自动关闭
    refetchInterval: (query) => {
      const items = query.state.data?.items
      if (!items) return false
      return items.some((r) => r.status === 'running' || r.status === 'pending') ? 3000 : false
    },
  })
}

/**
 * React Query Hook: 查询单条流水线运行详情与日志
 * @param id 流水线运行记录 UUID
 */
export function usePipeline(id: string | undefined) {
  return useQuery({
    queryKey: ['pipeline', id],
    enabled: !!id,
    queryFn: () => api<RemotePipelineRun>(`/api/v1/pipelines/${encodeURIComponent(id!)}`),
    // running 作业的详情 3s 轮询刷新（⑥k-B）：run 或任一作业仍处于 running/pending 时开启，落定即关
    refetchInterval: (query) => {
      const d = query.state.data
      if (!d) return false
      const active =
        d.status === 'running' || d.status === 'pending' ||
        (d.jobs ?? []).some((j) => j.status === 'running' || j.status === 'pending')
      return active ? 3000 : false
    },
  })
}

/**
 * CI 流水线操作 API 客户端对象
 */
export const pipelinesApi = {
  /** 为指定仓库手动或按条件触发一次流水线 */
  trigger(repoIdOrName: string, data: {
    branch?: string
    commitSha?: string
    trigger?: string
    mrId?: string
  }): Promise<RemotePipelineRun> {
    return api<RemotePipelineRun>(`/api/v1/repos/${encodeURIComponent(repoIdOrName)}/pipelines/trigger`, {
      method: 'POST',
      body: data,
    })
  },
  /** 重新运行（Rerun）指定的流水线 */
  rerun(id: string): Promise<RemotePipelineRun> {
    return api<RemotePipelineRun>(`/api/v1/pipelines/${encodeURIComponent(id)}/rerun`, {
      method: 'POST',
    })
  },
}

/**
 * React Query Hook: 查询指定仓库客户端上报的所有工作树（Worktree）列表
 * @param repoIdOrName 仓库 ID 或短名称
 */
export function useRepoWorktrees(repoIdOrName: string | undefined) {
  return useQuery({
    queryKey: ['repo', repoIdOrName, 'worktrees'],
    enabled: !!repoIdOrName,
    queryFn: () => api<RemoteWorktreeReport[]>(`/api/v1/repos/${encodeURIComponent(repoIdOrName!)}/worktrees`),
  })
}

/**
 * 客户端工作树状态上报 API 客户端对象
 */
export const worktreesApi = {
  /** 客户端（Electron/IDE）向服务端上报本地工作树状态快照 */
  report(repoIdOrName: string, data: {
    localPath: string
    branchName: string
    baseRef?: string
    dirtyFileCount?: number
    aheadCount?: number
    behindCount?: number
    lastCommitSha?: string
  }): Promise<RemoteWorktreeReport> {
    return api<RemoteWorktreeReport>(`/api/v1/repos/${encodeURIComponent(repoIdOrName)}/worktrees/report`, {
      method: 'POST',
      body: data,
    })
  },
}

// ---------------- Git 逐文件 Blame 与预热（S-1'） ----------------

/**
 * 单行代码历史 Blame 归属元数据
 */
export interface RemoteBlameLine {
  /** 行号（从 1 开始） */
  lineNo: number
  /** 该行变更对应的完整 40 位 Commit SHA */
  commitSha: string
  /** 7 位短 SHA */
  commitShort: string
  /** 作者显示名称 */
  authorName: string
  /** 作者邮箱 */
  authorEmail: string
  /** 提交时间（ISO-8601） */
  committedAt: string
  /** 提交信息标题摘要 */
  summary: string
  /** 源码行内容 */
  lineContent: string
}

/**
 * 文件级 Blame 计算结果与性能指标
 */
export interface RemoteBlameResult {
  /** 仓库路径标识 */
  repoKey: string
  /** 计算针对的目标 Commit SHA */
  commitSha: string
  /** 相对文件路径 */
  filePath: string
  /** 按行排序的 Blame 详情列表 */
  lines: RemoteBlameLine[]
  /** 文件总行数 */
  totalLines: number
  /** 计算耗时毫秒数 */
  durationMs: number
  /** 是否命中两级缓存（L1 内存 / L2 Valkey） */
  fromCache: boolean
}

/**
 * React Query Hook: 查询仓库指定文件的 Blame 溯源快照
 * @param repoIdOrName 仓库 ID 或短名称
 * @param ref 分支名称或 Commit SHA
 * @param path 相对文件路径
 */
export function useRepoBlame(repoIdOrName: string | undefined, ref?: string, path?: string) {
  return useQuery({
    queryKey: ['repo', repoIdOrName, 'blame', ref, path],
    enabled: !!repoIdOrName && !!path,
    queryFn: () => {
      const p = new URLSearchParams()
      if (ref) p.set('ref', ref)
      p.set('path', path!)
      return api<RemoteBlameResult>(`/api/v1/repos/${encodeURIComponent(repoIdOrName!)}/blame?${p.toString()}`)
    },
  })
}

/**
 * 仓库通用操作 API 客户端对象（补充 Blame 查调）
 */
export const reposApi = {
  /** 新建仓库（⑥h 建仓批）：POST /repos → 服务端 git init --bare + 落库 + GitFlow 规则/默认分支保护即席生效；
   *  400=名称非法（字母数字-_，1~64），409=同名仓库已存在（读 err.message toast） */
  create(data: { name: string; description?: string; defaultBranch?: string }): Promise<RemoteRepo> {
    return api<RemoteRepo>('/api/v1/repos', { method: 'POST', body: data })
  },
  /** 新建分支（B2 · UT-32）：POST /repos/{idOrName}/branches；409=同名分支已存在；startRef 可省略（默认分支） */
  createBranch(idOrName: string, name: string, startRef?: string): Promise<{ name: string }> {
    return api<{ name: string }>(`/api/v1/repos/${encodeURIComponent(idOrName)}/branches`, {
      method: 'POST',
      body: startRef ? { name, startRef } : { name },
    })
  },
  /** 删除分支（B2 · UT-32）：DELETE /repos/{idOrName}/branches/{name} → 204 无返回体；409=受保护分支不可删。
   *  分支名不编码：名字常含斜杠（feature/x），后端 {*name} 按裸斜杠捕获剩余路径，%2F 反而会被 Tomcat 拒绝。 */
  async deleteBranch(idOrName: string, name: string): Promise<void> {
    await api(`/api/v1/repos/${encodeURIComponent(idOrName)}/branches/${name}`, { method: 'DELETE' })
  },
  /** 查询指定文件的 Blame 溯源快照 */
  getBlame(idOrName: string, path: string, ref?: string): Promise<RemoteBlameResult> {
    const p = new URLSearchParams({ path })
    if (ref) p.set('ref', ref)
    return api<RemoteBlameResult>(`/api/v1/repos/${encodeURIComponent(idOrName)}/blame?${p.toString()}`)
  },
  /** 全文检索代码行（U4） */
  search(idOrName: string, query: string, ref?: string, path?: string, limit?: number): Promise<RemoteSearchResult> {
    const p = new URLSearchParams({ q: query })
    if (ref) p.set('ref', ref)
    if (path) p.set('path', path)
    if (limit) p.set('limit', String(limit))
    return api<RemoteSearchResult>(`/api/v1/repos/${encodeURIComponent(idOrName)}/search?${p.toString()}`)
  },
}

// ============================================================================
// 12. 自研 Git 代码全文检索（U4 / V-21）
// ============================================================================

/**
 * 代码搜索匹配条目详情
 */
export interface RemoteSearchMatch {
  /** 匹配所在文件的相对路径 */
  filePath: string
  /** 匹配行号（从 1 开始计） */
  lineNo: number
  /** 匹配行源码内容 */
  lineContent: string
}

/**
 * 仓库代码检索结果
 */
export interface RemoteSearchResult {
  /** 搜索关键字 */
  query: string
  /** 搜索目标分支或引用 */
  ref: string
  /** 匹配行总数 */
  totalMatches: number
  /** 检索耗时（毫秒） */
  durationMs: number
  /** 是否达到上限被截断 */
  truncated: boolean
  /** 检索匹配条目列表 */
  matches: RemoteSearchMatch[]
}

/**
 * React Query Hook: 全文检索指定仓库、分支/引用的代码文件内容（自研 Git U4）
 * @param repoIdOrName 仓库 ID 或短名称
 * @param query 检索关键字
 * @param ref 目标分支或 Commit SHA（缺省 HEAD）
 * @param path 可选文件路径匹配模式
 * @param limit 最大匹配返回数（缺省 50）
 */
export function useRepoCodeSearch(
  repoIdOrName: string | undefined,
  query: string,
  ref?: string,
  path?: string,
  limit?: number
) {
  return useQuery({
    queryKey: ['repo', repoIdOrName, 'search', query, ref, path, limit],
    enabled: !!repoIdOrName && !!query && query.trim().length > 0,
    queryFn: () => {
      const p = new URLSearchParams()
      p.set('q', query.trim())
      if (ref) p.set('ref', ref)
      if (path) p.set('path', path)
      if (limit) p.set('limit', String(limit))
      return api<RemoteSearchResult>(`/api/v1/repos/${encodeURIComponent(repoIdOrName!)}/search?${p.toString()}`)
    },
  })
}

// ============================================================================
// 13. 研发效能与度量大盘（V-19）
// ============================================================================

/** 迭代速率统计条目 */
export interface RemoteSprintVelocity {
  sprintId: string
  sprintName: string
  completedPoints: number
  totalPoints: number
}

/** 效能综合报表响应 */
export interface RemoteEfficiencyReport {
  totalItems: number
  completedItems: number
  completionRate: number
  avgCycleTimeDays: number
  defectCount: number
  defectResolutionRate: number
  totalStoryPoints: number
  completedStoryPoints: number
  defectSeverityDistribution: Record<string, number>
  sprintVelocities: RemoteSprintVelocity[]
}

/** 燃尽图数据点 */
export interface RemoteBurndownPoint {
  dayIndex: number
  date: string
  idealPoints: number
  actualPoints: number
}

/** 燃尽图报表响应 */
export interface RemoteBurndownReport {
  sprintId: string
  sprintName: string
  startDate: string
  dueDate: string
  totalPoints: number
  timeline: RemoteBurndownPoint[]
}

/** 累积流图数据点 */
export interface RemoteCfdPoint {
  date: string
  todo: number
  inProgress: number
  done: number
}

/** 累积流图报表响应 */
export interface RemoteCfdReport {
  series: RemoteCfdPoint[]
}

/**
 * React Query Hook: 查询效能综合报表（交付数、完成率、周期、缺陷分布与速率）
 */
export function useEfficiencyReport(productId?: string, sprintId?: string) {
  return useQuery({
    queryKey: ['reports', 'efficiency', productId, sprintId],
    queryFn: () => {
      const p = new URLSearchParams()
      if (productId) p.set('productId', productId)
      if (sprintId) p.set('sprintId', sprintId)
      const qs = p.toString()
      return api<RemoteEfficiencyReport>(`/api/v1/reports/efficiency${qs ? `?${qs}` : ''}`)
    },
  })
}

/**
 * React Query Hook: 查询指定迭代的燃尽图时序数据
 */
export function useSprintBurndown(sprintId?: string) {
  return useQuery({
    queryKey: ['reports', 'burndown', sprintId],
    enabled: !!sprintId,
    queryFn: () => {
      const p = new URLSearchParams()
      if (sprintId) p.set('sprintId', sprintId)
      return api<RemoteBurndownReport>(`/api/v1/reports/burndown?${p.toString()}`)
    },
  })
}

/**
 * React Query Hook: 查询累积流图 (CFD) 时序堆叠数据
 */
export function useCfdReport(productId?: string, sprintId?: string, days: number = 30) {
  return useQuery({
    queryKey: ['reports', 'cfd', productId, sprintId, days],
    queryFn: () => {
      const p = new URLSearchParams()
      if (productId) p.set('productId', productId)
      if (sprintId) p.set('sprintId', sprintId)
      p.set('days', String(days))
      return api<RemoteCfdReport>(`/api/v1/reports/cfd?${p.toString()}`)
    },
  })
}

export const reportsApi = {
  /** 获取综合效能指标 */
  getEfficiency: (productId?: string, sprintId?: string) => {
    const p = new URLSearchParams()
    if (productId) p.set('productId', productId)
    if (sprintId) p.set('sprintId', sprintId)
    const qs = p.toString()
    return api<RemoteEfficiencyReport>(`/api/v1/reports/efficiency${qs ? `?${qs}` : ''}`)
  },
  /** 获取燃尽图 */
  getBurndown: (sprintId: string) =>
    api<RemoteBurndownReport>(`/api/v1/reports/burndown?sprintId=${encodeURIComponent(sprintId)}`),
  /** 获取累积流图 */
  getCfd: (productId?: string, sprintId?: string, days = 30) => {
    const p = new URLSearchParams({ days: String(days) })
    if (productId) p.set('productId', productId)
    if (sprintId) p.set('sprintId', sprintId)
    return api<RemoteCfdReport>(`/api/v1/reports/cfd?${p.toString()}`)
  },
}

// ---------------- Goal 完成率总览（B2 · UT-30 · GET /api/v1/goals/overview） ----------------

/**
 * Goal 下钻 RoadMap 条目（B3 · R-1 / 裁决 D8）：
 * - done/total：task/test_task/defect 三类型口径（与 rollup 恒一致，requirement 不进完成率）；
 * - doneHours/inProgressHours/todoHours：estimate_hours 三桶工时（桑基节点 tooltip 用）；
 * - 类型计数三桶（桑基末端度量）：taskCount 含 test_task（并入任务桶，tooltip 说明），
 *   requirement/defect 各自成桶；各 *Done 为该桶已完成数（类型感知完结集）；
 * - releaseId：条目正向挂接版本（下钻 条目→版本→迭代 链用，可空）。
 */
export interface GoalOverviewItem {
  id: string
  name: string
  done: number
  total: number
  doneHours: number
  inProgressHours: number
  todoHours: number
  releaseId?: string
  taskCount: number
  taskDone: number
  requirementCount: number
  requirementDone: number
  defectCount: number
  defectDone: number
}

/** Goal 完成率行（completionRate=查询侧聚合百分比；items 为其 RoadMap 条目下钻） */
export interface GoalOverviewRow {
  goalId: string
  name: string
  total: number
  done: number
  completionRate: number
  items: GoalOverviewItem[]
}

export interface GoalOverviewPayload {
  goals: GoalOverviewRow[]
}

/** Goal 完成率总览（统计报表宏观视角；staleTime 30s 防轮询风暴） */
export function useGoalOverview() {
  return useQuery({
    queryKey: ['goals', 'overview'],
    staleTime: 30_000,
    queryFn: () => api<GoalOverviewPayload>('/api/v1/goals/overview'),
  })
}

// ==================== P0 企业治理底座：用户管理后台 API ====================

/** 平台系统用户视图（与后端 AdminUserDto.UserSummary 对齐） */
export interface RemoteUserSummary {
  id: string
  username: string
  displayName: string
  title: string | null
  email: string | null
  platformRole: 'OWNER' | 'ADMIN' | 'MEMBER'
  departmentId: string | null
  dailyCapacityHours: number
  status: 'ACTIVE' | 'DISABLED'
}

/** 创建用户请求载荷 */
export interface CreateUserPayload {
  username: string
  password: string
  displayName: string
  title?: string
  email?: string
  platformRole?: 'OWNER' | 'ADMIN' | 'MEMBER'
  departmentId?: string
  dailyCapacityHours?: number
}

/** 更新用户请求载荷 */
export interface UpdateUserPayload {
  displayName?: string
  title?: string
  email?: string
  platformRole?: 'OWNER' | 'ADMIN' | 'MEMBER'
  departmentId?: string
  dailyCapacityHours?: number
}

/** 用户管理后台客户端 API */
export const adminUsersApi = {
  list(query?: string, status?: string, role?: string): Promise<{ content: RemoteUserSummary[]; totalElements: number }> {
    const p = new URLSearchParams()
    if (query) p.set('query', query)
    if (status) p.set('status', status)
    if (role) p.set('role', role)
    const qs = p.toString()
    return api<{ content: RemoteUserSummary[]; totalElements: number }>(`/api/v1/admin/users${qs ? `?${qs}` : ''}`)
  },
  get(id: string): Promise<RemoteUserSummary> {
    return api<RemoteUserSummary>(`/api/v1/admin/users/${id}`)
  },
  create(payload: CreateUserPayload): Promise<RemoteUserSummary> {
    return api<RemoteUserSummary>('/api/v1/admin/users', { method: 'POST', body: payload })
  },
  update(id: string, payload: UpdateUserPayload): Promise<RemoteUserSummary> {
    return api<RemoteUserSummary>(`/api/v1/admin/users/${id}`, { method: 'PUT', body: payload })
  },
  updateStatus(id: string, status: 'ACTIVE' | 'DISABLED'): Promise<RemoteUserSummary> {
    return api<RemoteUserSummary>(`/api/v1/admin/users/${id}/status`, { method: 'PUT', body: { status } })
  },
  resetPassword(id: string, newPassword: string): Promise<{ ok: boolean; message: string }> {
    return api<{ ok: boolean; message: string }>(`/api/v1/admin/users/${id}/password`, {
      method: 'PUT',
      body: { newPassword },
    })
  },
}

/** React Query Hook：查询平台用户列表 */
export function useAdminUsers(query?: string, status?: string, role?: string) {
  return useQuery({
    queryKey: ['adminUsers', query, status, role],
    queryFn: () => adminUsersApi.list(query, status, role),
  })
}

// ==================== M2-W4 体验修复批（P1-1/P1-3/P2-2/P2-4） ====================

// ---------------- 工作台聚合（GET /api/v1/me/summary，05 §3.3 platform 段） ----------------

/** 工作台聚合响应（2026-09-14 契约；进度/计数一律后端查询侧聚合） */
export interface MeSummary {
  greetingName: string
  activeSprint: { name: string; progress: number; allocatedHours: number; capacityHours: number; dueDate?: string } | null
  goals: { id: string; key: string; name: string; progress: number; status: string }[]
  myRedConflicts: number
  releaseGate: { id: string; key: string; name: string; status: string; blocked: boolean; blockingCount: number; planDate?: string } | null
  myTodos: { id: string; key: string; title: string; type: string; dueDate?: string; priority?: string; status: string }[]
  myTopics: { id: string; title: string; kind: string; lastActivity?: string }[]
}

export function useMeSummary() {
  return useQuery({
    queryKey: ['me', 'summary'],
    queryFn: () => api<MeSummary>('/api/v1/me/summary'),
  })
}

// ---------------- 战略目标（GET/POST /api/v1/goals + GET /goals/{id}/rollup） ----------------

/** GET /api/v1/goals 条目（Views.of(StrategicGoal) 实测投影；无 key/status 列） */
export interface RemoteGoal {
  id: string
  name: string
  description?: string
  ownerId?: string
  parentId?: string
  createdAt: string
  updatedAt: string
}

/**
 * GET /goals/{id}/rollup 行（rollup = 查询侧 COUNT FILTER，红线①）；R-5/D1 补产品标签投影。
 * R-5 AC③ 直连口径：id 可为 null——哨兵行「直挂目标的工作项」（goal_id 直挂、无 RoadMap
 * 条目归属，仅在计数 > 0 时输出，排在条目行后），前端渲染为「直挂目标」分组、不提供下钻。
 */
export interface GoalRollupRow {
  id?: string | null
  name: string
  productId?: string | null
  productName?: string | null
  workItemDone: number
  total: number
  releaseIds: string[]
}

export interface GoalRollup {
  goalId: string
  roadmapItems: GoalRollupRow[]
}

export const goalsApi = {
  list(): Promise<RemoteGoal[]> {
    return api<RemoteGoal[]>('/api/v1/goals')
  },
  rollup(id: string): Promise<GoalRollup> {
    return api<GoalRollup>(`/api/v1/goals/${encodeURIComponent(id)}/rollup`)
  },
  /** POST /api/v1/goals（name 必填；ownerId 接受 uuid/username，缺省=操作者） */
  create(input: { name: string; description?: string; ownerId?: string }): Promise<RemoteGoal> {
    return api<RemoteGoal>('/api/v1/goals', { method: 'POST', body: input })
  },
}

export function useGoals() {
  return useQuery({
    queryKey: ['goals'],
    queryFn: () => goalsApi.list(),
  })
}

export function useGoalRollup(id: string | undefined) {
  return useQuery({
    queryKey: ['goals', id, 'rollup'],
    enabled: !!id,
    queryFn: () => goalsApi.rollup(id!),
  })
}

// ---------------- 产品（GET /api/v1/products；R-5 分层过滤 / 需求创建挂产品 / 缺陷表单共用） ----------------

/** GET /api/v1/products 条目（Views.of(Product) 实测投影） */
export interface RemoteProduct {
  id: string
  key: string
  name: string
  description?: string
  goalId?: string
  ownerId?: string
}

export function useProducts() {
  return useQuery({
    queryKey: ['products'],
    staleTime: 5 * 60_000,
    queryFn: () => api<RemoteProduct[]>('/api/v1/products'),
  })
}

// ---------------- 需求评审流真实 API（R-6/D2，B4 批） ----------------

/** 需求列表（GET /work-items?type=requirement）：需求页真实数据源（store 仅原型兜底） */
export function useRequirements() {
  return useQuery({
    queryKey: ['requirements', 'list'],
    queryFn: () =>
      api<PagePayload<RemoteWorkItem>>('/api/v1/work-items?type=requirement&page=1&size=100'),
  })
}

/**
 * GET /work-items 条目 → 前端 Requirement 展示形（字段级映射）：
 * 评审记录/评审人不落 store——评审区数据一律经 useRequirementReviewRounds 真实拉取。
 */
export function remoteToRequirement(raw: RemoteWorkItem): import('../data/types').Requirement {
  const t = raw as RemoteWorkItem & { goalId?: string; sprintId?: string; releaseId?: string }
  // PRD 正文按保存时的分节标记从 description 解析（QA 复审 MUST-FIX：不解析会恒显「暂未填写」，
  // 且空编辑器直接保存会清掉 description 里的 PRD 段）
  const desc = raw.description ?? ''
  const marker = '## PRD 详细说明'
  const mi = desc.indexOf(marker)
  const docContent = mi >= 0 ? desc.slice(mi + marker.length).replace(/^\s*\n/, '').trim() : undefined
  const baseDescription = mi >= 0 ? desc.slice(0, mi).trim() : desc
  return {
    id: raw.id,
    key: raw.key,
    title: raw.title,
    description: baseDescription,
    docContent,
    status: raw.status as import('../data/types').ReqStatus,
    priority: (raw.priority ?? 'P2') as import('../data/types').WorkPriority,
    productId: raw.productId,
    proposerId: raw.reporterId,
    ownerId: raw.assigneeId,
    reviewerIds: [],
    reviews: [],
    goalId: t.goalId,
    sprintId: t.sprintId,
    releaseId: t.releaseId,
    version: raw.version,
    createdAt: fmtIso(raw.createdAt),
    updatedAt: fmtIso(raw.updatedAt),
  }
}

/** 需求评审流客户端（创建/提交评审/逐人评审） */
export const requirementsApi = {
  /** POST /work-items（type=requirement；productId 或 goalId 必填其一作 path 根） */
  create(input: {
    title: string
    description?: string
    priority?: string
    productId?: string
    goalId?: string
    storyPoints?: number
  }): Promise<RemoteWorkItem> {
    return api<RemoteWorkItem>('/api/v1/work-items', {
      method: 'POST',
      body: { type: 'requirement', ...input },
    })
  },
  /** POST /{idOrKey}/submit：draft→pending_review，round+1 并按 reviewerIds 逐人建行（会签） */
  submit(idOrKey: string, reviewerIds: string[]): Promise<RemoteWorkItem> {
    return api<RemoteWorkItem>(`/api/v1/work-items/${encodeURIComponent(idOrKey)}/submit`, {
      method: 'POST',
      body: { reviewerIds },
    })
  },
  /** POST /{idOrKey}/review：本轮评审人逐人批；任一 rejected→draft、全员 approved→accepted */
  review(idOrKey: string, result: 'approved' | 'rejected', comment?: string): Promise<RemoteWorkItem> {
    return api<RemoteWorkItem>(`/api/v1/work-items/${encodeURIComponent(idOrKey)}/review`, {
      method: 'POST',
      body: { result, comment },
    })
  },
  /** POST /{idOrKey}/schedule：accepted 态绑定 release/sprint/storyPoints（状态保持 accepted） */
  schedule(idOrKey: string, patch: { releaseId?: string; sprintId?: string; storyPoints?: number }): Promise<RemoteWorkItem> {
    return api<RemoteWorkItem>(`/api/v1/work-items/${encodeURIComponent(idOrKey)}/schedule`, {
      method: 'POST',
      body: patch,
    })
  },
  /** POST /{idOrKey}/review-rounds/{round}/minutes：挂纪要（fileId 两步制 ready）+可选 summary */
  uploadMinutes(idOrKey: string, round: number, fileId: string, summary?: string): Promise<RemoteRequirementRound> {
    return api<RemoteRequirementRound>(
      `/api/v1/work-items/${encodeURIComponent(idOrKey)}/review-rounds/${round}/minutes`,
      { method: 'POST', body: { fileId, summary: summary || undefined } },
    )
  },
  /** PUT /{idOrKey}/review-rounds/{round}/minutes：补 summary / 换纪要（fileId 缺省=不变） */
  updateMinutes(idOrKey: string, round: number, patch: { fileId?: string; summary?: string }): Promise<RemoteRequirementRound> {
    return api<RemoteRequirementRound>(
      `/api/v1/work-items/${encodeURIComponent(idOrKey)}/review-rounds/${round}/minutes`,
      { method: 'PUT', body: patch },
    )
  },
}

/** GET /work-items/{idOrKey}/review-rounds 行（服务端聚合：round 内逐人记录 + 纪要/结论） */
export interface RemoteRequirementRound {
  round: number
  /** 会签结论：rejected=任一驳回（回 draft 可重提）/ approved=全员通过（受理）/ pending=评审中 */
  conclusion: 'pending' | 'approved' | 'rejected'
  concludedAt?: string
  minutesFileId?: string
  summary?: string
  createdAt?: string
  reviews: RemoteRequirementReview[]
}

/** 评审轮次（含纪要）：需求抽屉「评审流程」区唯一数据源（无 store 兜底） */
export function useRequirementReviewRounds(idOrKey: string | undefined) {
  return useQuery({
    queryKey: ['requirementRounds', idOrKey],
    enabled: !!idOrKey,
    retry: false, // 原型需求 key 未入库时 404 属预期 → 显示显式空态
    queryFn: () => api<RemoteRequirementRound[]>(`/api/v1/work-items/${encodeURIComponent(idOrKey!)}/review-rounds`),
  })
}

// ---------------- 需求评审历史（GET /api/v1/work-items/{idOrKey}/reviews） ----------------

/** 评审历史条目（Views.of(RequirementReview) 实测投影；result: pending|approved|rejected） */
export interface RemoteRequirementReview {
  id: string
  requirementId: string
  round: number
  reviewerId: string
  result: 'pending' | 'approved' | 'rejected'
  comment?: string
  decidedAt?: string
  createdAt: string
}

/** 最近驳回意见（P2-4）：数据源真实 API；需求行展开区显示 */
export function useRequirementReviews(idOrKey: string | undefined) {
  return useQuery({
    queryKey: ['requirementReviews', idOrKey],
    enabled: !!idOrKey,
    retry: false, // 原型需求 key 未入库时 404 属预期，静默回退 store 评审数据
    queryFn: () => api<RemoteRequirementReview[]>(`/api/v1/work-items/${encodeURIComponent(idOrKey!)}/reviews`),
  })
}

// ---------------- WS 未读实时推送（P1-3 前端接线；R-10c/d 修补与角标同步） ----------------

/**
 * 会话未读局部修补 + 侧栏总角标同步（R-10c/d 单一入口）：
 * ① setQueriesData 前缀 ['conversations'] 命中全部 queryKey 变体（all/topic/dm/group × active/archived），
 *    一次性改齐该会话 unreadCount（旧实现只 patch 单一 ['conversations','all',false] 变体 → 标签页漏清）；
 * ② 总角标缓存 ['im','unread-summary']（独立 queryKey，非 ['conversations'] 前缀，互不误伤）
 *    按差值增减，再失效重取兜底（回源值恒为真相，WS 帧局部值只负责零等待）。
 */
export function patchConversationUnread(queryClient: QueryClient, convId: string, unread: number): void {
  let prev: number | undefined
  queryClient.setQueriesData<RemoteConversation[]>({ queryKey: ['conversations'] }, (rows) => {
    if (!Array.isArray(rows)) return rows // 防御：非数组缓存原样放行（前缀匹配兜底）
    const hit = rows.find((c) => c.id === convId)
    if (hit) prev = hit.unreadCount ?? 0
    return rows.map((c) => (c.id === convId ? { ...c, unreadCount: unread } : c))
  })
  if (prev !== undefined) {
    queryClient.setQueryData<number>(['im', 'unread-summary'], (total) =>
      Math.max(0, (total ?? 0) + (unread - prev!)),
    )
  }
  void queryClient.invalidateQueries({ queryKey: ['im', 'unread-summary'] })
}

/**
 * 侧栏 IM 总未读角标（R-10d）：后端聚合 GET /conversations/unread-summary（SQL SUM 一层，
 * 与 GET /conversations 求和恒等）；queryKey 独立（['im','unread-summary']）防与页面级
 * conversations 数组缓存互踩，staleTime 15s——实时增减由 patchConversationUnread 兜住。
 */
export function useUnreadSummary(): number {
  const { data } = useQuery({
    queryKey: ['im', 'unread-summary'],
    staleTime: 15_000,
    queryFn: () => api<{ total: number }>('/api/v1/conversations/unread-summary').then((r) => Number(r.total ?? 0)),
  })
  return data ?? 0
}

/**
 * useUnreadPush：登录后订阅 user:{uid} 频道（仅本人可订，服务端 COL 鉴权）；
 * 收到 unread 帧（后端 message.created 扇出，payload 自带 conversationId+unreadCount）
 * 与 read 帧（R-10 多端一致：本人在另一端已读）→ patchConversationUnread 局部更新
 * （零等待 + 总角标同步）；payload 缺字段时回退 invalidate 兜底。
 * 挂载点：App（AuthProvider 内层）。WS 不可达时静默（页面仍有查询失效兜底）。
 */
export function useUnreadPush(userId: string | undefined) {
  const queryClient = useQueryClient()
  useEffect(() => {
    if (!userId) return
    let disposed = false
    const offs: Array<() => void> = []
    void teamOneWs
      .connect()
      .then(() => {
        if (disposed) return
        offs.push(teamOneWs.onEvent('unread', (payload: { conversationId?: string; unreadCount?: number }) => {
          if (payload?.conversationId != null && typeof payload.unreadCount === 'number') {
            patchConversationUnread(queryClient, payload.conversationId, payload.unreadCount)
          } else {
            void queryClient.invalidateQueries({ queryKey: ['conversations'] })
          }
        }))
        // read 帧（另一端已读）：本端会话角标与侧栏总角标同步归零（多端一致，QA 矩阵项）
        offs.push(teamOneWs.onEvent('read', (payload: { conversationId?: string; unread?: number }) => {
          if (payload?.conversationId != null && typeof payload.unread === 'number') {
            patchConversationUnread(queryClient, payload.conversationId, payload.unread)
          }
        }))
        return teamOneWs.sub(`user:${userId}`)
      })
      .catch(() => { /* WS 不可达时静默 */ })
    return () => {
      disposed = true
      offs.forEach((off) => off?.())
    }
  }, [userId, queryClient])
}

// ---------------- 侧栏真实角标（M2-INC2 后角标真实化：全部替代 nav.ts store 演示数据） ----------------

/**
 * 角标专用查询：与页面 hook 数据形状不同（计数 vs 数组），一律独立 queryKey 防缓存互踩；
 * staleTime 30s 防轮询风暴（红线③），0 值由 Sidebar 不渲染。
 */

/** 冲突中心角标：GET /conflicts 中 severity=red 且 resolved_at 为空（红色未消解）条数 */
export function useConflictsBadge(): number {
  const { data } = useQuery({
    queryKey: ['conflicts', 'badge', 'red-unresolved'],
    staleTime: 30_000,
    queryFn: () =>
      api<ConflictPage>('/api/v1/conflicts')
        .then((p) => p.items.filter((c) => c.payload?.severity === 'red' && !c.resolvedAt).length),
  })
  return data ?? 0
}

/** 缺陷中心角标：GET /work-items?type=defect 中 status∉{已关闭,回归通过} 条数 */
export function useDefectsBadge(): number {
  const { data } = useQuery({
    queryKey: ['defects', 'badge', 'active'],
    staleTime: 30_000,
    queryFn: () =>
      api<PagePayload<RemoteWorkItem>>('/api/v1/work-items?type=defect&page=1&size=100')
        .then((p) => p.items.filter((d) => d.status !== '已关闭' && d.status !== '回归通过').length),
  })
  return data ?? 0
}

/** 需求管理角标：GET /work-items?type=requirement&status=pending_review 服务端过滤 total */
export function useRequirementsBadge(): number {
  const { data } = useQuery({
    queryKey: ['requirements', 'badge', 'pending_review'],
    staleTime: 30_000,
    queryFn: () =>
      api<PagePayload<RemoteWorkItem>>('/api/v1/work-items?type=requirement&status=pending_review&page=1&size=1')
        .then((p) => Number(p.total ?? 0)),
  })
  return data ?? 0
}

/** 代码评审角标：GET /mrs?status=open 的 total（接口不可用/报错 → 0 即隐藏，红线允许） */
export function useReviewBadge(): number {
  const { data } = useQuery({
    queryKey: ['mrs', 'badge', 'open'],
    staleTime: 30_000,
    queryFn: () =>
      api<{ items: RemoteMergeRequest[]; total: number }>('/api/v1/mrs?status=open')
        .then((p) => Number(p.total ?? p.items.length)),
  })
  return data ?? 0
}

/** 侧栏角标聚合（im 走后端聚合 unread-summary，R-10d；其余各查询 staleTime 30s） */
export interface SidebarBadges {
  conflicts: number
  defects: number
  requirements: number
  im: number
  review: number
}

export function useSidebarBadges(): SidebarBadges {
  const conflicts = useConflictsBadge()
  const defects = useDefectsBadge()
  const requirements = useRequirementsBadge()
  const review = useReviewBadge()
  // R-10d：IM 总角标改走后端聚合端点（不再前端 reduce 会话清单——旧口径漏归档会话且依赖列表缓存新鲜度）
  const im = useUnreadSummary()
  return {
    conflicts,
    defects,
    requirements,
    review,
    im,
  }
}

// ---------------- 通知中心（T-4 产品补全；后端 NotificationController GET/PUT 已备，只读现有表） ----------------

/** GET /api/v1/notifications 条目（collab.notification 投影；payload 为事件投影 jsonb） */
export interface RemoteNotification {
  id: string
  kind: string
  payload: Record<string, unknown>
  readAt?: string | null
  createdAt: string
}

export interface NotificationPage {
  unreadCount: number
  items: RemoteNotification[]
}

/** 未读通知查询（铃铛角标 + 面板清单同源；staleTime 30s 防风暴） */
export function useNotifications() {
  return useQuery({
    queryKey: ['notifications', 'unread'],
    staleTime: 30_000,
    // perf：通知是唯一时效敏感的 focus 刷新（全局已关）——轻量单查询，切回窗口即收新提醒
    refetchOnWindowFocus: true,
    queryFn: () => api<NotificationPage>('/api/v1/notifications?unread=true&limit=50'),
  })
}

export const notificationsApi = {
  /** PUT /notifications/read {ids}：单条已读（只前进不回退，仅限本人行） */
  markRead(ids: string[]): Promise<{ updated: number }> {
    return api('/api/v1/notifications/read', { method: 'PUT', body: { ids } })
  },
  /** PUT /notifications/read {all:true}：全部已读 */
  markAllRead(): Promise<{ updated: number }> {
    return api('/api/v1/notifications/read', { method: 'PUT', body: { all: true } })
  },
}

/** 最近动态（工作台 D-91 收口）：unread=false 取含已读在内的最近 N 条（后端 createdAt desc）。
 *  与 useNotifications（未读铃铛口径）分开：动态流要展示已读项，不复用未读查询避免互相污染缓存。 */
export function useRecentNotifications(limit = 5) {
  return useQuery({
    queryKey: ['notifications', 'recent', limit],
    staleTime: 30_000,
    refetchOnWindowFocus: true,
    queryFn: () => api<NotificationPage>(`/api/v1/notifications?unread=false&limit=${limit}`),
  })
}

// ---------------- 紧急弹窗辅助：当前查看中会话登记（T-4③「会话非当前查看」判定） ----------------

let viewedConvId: string | undefined
/** ImPage 挂载/切换会话时上报当前查看中的会话 ID；卸载时上报 undefined */
export function reportViewedConv(convId: string | undefined): void {
  viewedConvId = convId
}
/** 当前查看中的会话 ID（未打开即时沟通页时为 undefined） */
export function currentViewedConv(): string | undefined {
  return viewedConvId
}

// ---------------- P2-2 表单字段级错误解析 ----------------

/** PLT_4000 message 可能带字段前缀（如「name 必填」「title 不能为空」）→ 提取字段名 */
const KNOWN_FIELDS = [
  'title', 'description', 'severity', 'priority', 'assigneeId', 'productId', 'goalId',
  'blockedReleaseId', 'name', 'ownerId', 'key', 'period', 'deadline', 'status',
] as const

/**
 * 从后端错误信封解析「字段名 → 原因」（P2-2）：
 * message 形如「name 必填」或「name：长度超限」时返回 { field:'name', message:'必填' }；
 * 无法识别字段时返回 { field:null, message }，由表单顶部统一展示（toast 另行保留）。
 */
export function parseFieldError(err: unknown): { field: string | null; message: string } {
  const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : String(err)
  const m = message.match(/^([A-Za-z][A-Za-z0-9]*)\s*[：:]?\s*(.+)$/)
  if (m && (KNOWN_FIELDS as readonly string[]).includes(m[1])) {
    return { field: m[1], message: m[2] }
  }
  return { field: null, message }
}

// ==================== P0 企业治理底座：个人访问令牌 (PAT) API ====================

/** 令牌概要（脱敏视图） */
export interface RemotePatSummary {
  id: string
  name: string
  tokenPrefix: string
  scopes: string
  expiresAt: string | null
  lastUsedAt: string | null
  createdAt: string
  expired: boolean
}

/** 创建令牌响应（仅此一次返回明文完整令牌） */
export interface CreatePatResponse {
  id: string
  name: string
  rawToken: string
  tokenPrefix: string
  scopes: string
  expiresAt: string | null
  createdAt: string
}

export const tokensApi = {
  list(): Promise<RemotePatSummary[]> {
    return api<RemotePatSummary[]>('/api/v1/tokens')
  },
  create(name: string, scopes?: string, expiresDays?: number): Promise<CreatePatResponse> {
    return api<CreatePatResponse>('/api/v1/tokens', {
      method: 'POST',
      body: { name, scopes, expiresDays },
    })
  },
  revoke(id: string): Promise<{ ok: boolean; message: string }> {
    return api<{ ok: boolean; message: string }>(`/api/v1/tokens/${id}`, { method: 'DELETE' })
  },
}

/** React Query Hook：查询当前用户所有 PAT 令牌 */
export function useMyTokens() {
  return useQuery({
    queryKey: ['myTokens'],
    queryFn: () => tokensApi.list(),
  })
}

// ==================== P0 企业治理底座：操作审计日志 API ====================

export interface RemoteAuditLogEntry {
  id: string
  actorId: string | null
  actorName: string
  action: string
  resourceType: string
  resourceId: string
  detail: Record<string, unknown>
  createdAt: string
}

export const auditLogsApi = {
  list(page = 0, size = 20): Promise<{ content: RemoteAuditLogEntry[]; totalElements: number; totalPages: number }> {
    return api<{ content: RemoteAuditLogEntry[]; totalElements: number; totalPages: number }>(
      `/api/v1/audit-logs?page=${page}&size=${size}`,
    )
  },
}

/** React Query Hook：查询操作审计日志流 */
export function useAuditLogs(page = 0, size = 20) {
  return useQuery({
    queryKey: ['auditLogs', page, size],
    queryFn: () => auditLogsApi.list(page, size),
  })
}

// ==================== P0 数据迁移与数据质量门禁 API ====================

export interface RemoteRowError {
  rowNumber: number
  columnName: string
  message: string
  rawValue: string
}

export interface RemoteParsedRow {
  rowNumber: number
  type: string
  title: string
  priority: string
  status: string
  assigneeUsername: string
  assigneeId: string | null
  sprintName: string
  sprintId: string | null
  storyPoints: number | null
  estimateHours: number | null
  severity: string | null
  description: string
  valid: boolean
  errorMessages: string[]
}

export interface RemoteValidationReport {
  filename: string
  totalRows: number
  validRows: number
  errorRows: number
  canImport: boolean
  errors: RemoteRowError[]
  previewRows: RemoteParsedRow[]
}

export interface RemoteImportResult {
  importedCount: number
  skippedCount: number
  createdKeys: string[]
}

export const migrationApi = {
  getTemplateUrl(productId: string): string {
    return `/api/v1/products/${productId}/migration/template`
  },
  validate(productId: string, content: string, filename?: string): Promise<RemoteValidationReport> {
    return api<RemoteValidationReport>(`/api/v1/products/${productId}/migration/validate`, {
      method: 'POST',
      body: { content, filename },
    })
  },
  import(productId: string, rows: RemoteParsedRow[], skipErrors: boolean): Promise<RemoteImportResult> {
    return api<RemoteImportResult>(`/api/v1/products/${productId}/migration/import`, {
      method: 'POST',
      body: { rows, skipErrors },
    })
  },
}




// ==================== B6 系统设置（R-12）与个人设置（R-11） ====================

// ---------------- 系统设置（GET/PUT /api/v1/settings/{group} + POST /credentials/{key}/test） ----------------

/** 设置分组（与后端 SettingService.GROUPS 白名单一致；llm 后端可用，前端置灰「规划中」） */
export type SettingGroup = 'project' | 'repo' | 'server' | 'credential' | 'llm'

/** 分组中文标签（tab 文案唯一口径） */
export const SETTING_GROUP_LABELS: Record<SettingGroup, string> = {
  project: '项目', repo: '仓库', server: '服务器', credential: '凭据', llm: '大模型',
}

/** GET /settings/{group} 条目：秘密项只回掩码（前4位****）+ configured 布尔，明文永不回显 */
export interface SettingItemView {
  key: string
  value?: string | null
  masked?: string | null
  secret: boolean
  configured: boolean
  version: number
  updatedAt?: string | null
  updatedBy?: string | null
}

export interface SettingGroupView {
  group: SettingGroup
  items: SettingItemView[]
}

/** PUT /settings/{group} 条目：value 缺省=保留已存值（秘密项已配置时留空即不改） */
export interface SettingItemUpsert {
  key: string
  value?: string | null
  secret?: boolean
  updateVersion: number
}

/** POST /settings/credentials/{key}/test 响应（仓库组真实探测；其余组 supported=false） */
export interface CredentialTestResult {
  supported: boolean
  ok: boolean
  message: string
  elapsedMs?: number
}

/** 系统设置客户端 API（仅管理员：403 由 api 层错误信封透出） */
export const settingsApi = {
  get(group: SettingGroup): Promise<SettingGroupView> {
    return api<SettingGroupView>(`/api/v1/settings/${group}`)
  },
  /** 行级乐观锁保存：409（T1-PLT-4091）时 ApiError.currentVersion 为服务端最新版本 */
  save(group: SettingGroup, items: SettingItemUpsert[]): Promise<SettingGroupView> {
    return api<SettingGroupView>(`/api/v1/settings/${group}`, { method: 'PUT', body: items })
  },
  /** 凭据连通性测试（本批仅仓库组走 git ls-remote 探测） */
  testCredential(key: string): Promise<CredentialTestResult> {
    return api<CredentialTestResult>(`/api/v1/settings/credentials/${encodeURIComponent(key)}/test`, {
      method: 'POST',
      body: {},
    })
  },
}

/** React Query Hook：按组拉取设置（保存成功后由调用方 invalidate） */
export function useSettings(group: SettingGroup) {
  return useQuery({
    queryKey: ['settings', group],
    queryFn: () => settingsApi.get(group),
  })
}

// ---------------- 个人设置（PUT /api/v1/me + POST /api/v1/me/password） ----------------

/** PUT /api/v1/me 载荷：字段可选，null/缺省 = 不修改（仅本人字段，角色/用户名不在列） */
export interface MeUpdatePayload {
  displayName?: string
  themePreference?: 'light' | 'dark' | 'system'
  dailyCapacityHours?: number
}

/** PUT /api/v1/me 响应（与 /auth/me 同形投影） */
export type MeProfile = RemoteUser & { themePreference?: 'light' | 'dark' | 'system' | null }

/** 个人设置客户端 API（R-11） */
export const meApi = {
  update(payload: MeUpdatePayload): Promise<MeProfile> {
    return api<MeProfile>('/api/v1/me', { method: 'PUT', body: payload })
  },
  /** 改密：旧密错误 400（T1-PLT-4000「旧密码不正确」）；成功后其他设备刷新令牌被作废 */
  changePassword(payload: { oldPassword: string; newPassword: string }): Promise<{ ok: boolean; message: string }> {
    return api<{ ok: boolean; message: string }>('/api/v1/me/password', {
      method: 'POST',
      body: payload,
    })
  },
}

// ---------------- 权限判定矩阵（admin · ⑥m 真实化：行 = 真实用户 × 列 = 真实仓库，服务端五步链判定） ----------------

/** 矩阵单元格：role 为小写线格式（owner/maintainer/developer/reporter），空串 = 无角色（仅剩 visibility 兜底） */
export interface PermMatrixCell {
  role: 'owner' | 'maintainer' | 'developer' | 'reporter' | ''
  capCount: number
  platformAdmin: boolean
}

/** 矩阵行：一个真实平台用户及其在每仓的有效角色（memberships 以 repoId 为键） */
export interface PermMatrixUser {
  id: string
  username: string
  displayName: string
  platformRole: 'OWNER' | 'ADMIN' | 'MEMBER'
  memberships: Record<string, PermMatrixCell>
}

/** 矩阵列：一个真实仓库 */
export interface PermMatrixRepo {
  id: string
  name: string
  visibility: string
}

export interface PermMatrix {
  repos: PermMatrixRepo[]
  users: PermMatrixUser[]
}

/** 权限判定矩阵（GET /api/v1/admin/permission-matrix · platform:manage；前端只展示不判定）。
 * enabled 由调用方按平台角色门控（非 OWNER/ADMIN 必 403，不发无效请求）。 */
export function usePermMatrix(enabled = true) {
  return useQuery({
    queryKey: ['perm-matrix'],
    queryFn: () => api<PermMatrix>('/api/v1/admin/permission-matrix'),
    staleTime: 30_000,
    enabled,
  })
}
