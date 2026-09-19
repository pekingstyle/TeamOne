// 即时沟通（M2-INC-2 完整化 · 全真实数据）：
//  - 会话列表：GET /api/v1/conversations 支持全部/话题/私信/群组过滤与 unreadCount 未读角标
//  - 创建会话：支持发起私信（POST /conversations type=dm 防裂）与新建群聊（type=group）
//  - 游标与未读：打开会话发送 WS read 帧推进游标，消红点
//  - 历史消息：GET /api/v1/conversations/{id}/messages?before=&limit= 反向游标加载更多
//  - 发送消息：WS msg 本地乐观插入 → ack 回填 msgId（上屏零等待）
//  - 实时性（IM 延迟诊断批）：后端发送事务 afterCommit 直推 L3 帧；前端收帧/发送后
//    一律 setQueriesData 局部更新会话清单（lastMessageAt 排序推进），不再整表 invalidate 重拉
//  - 消息撤回：本人 24h 内可撤回（POST /withdraw），监听 WS message.withdrawn 实时更新
//  - 文件附件：两步制上传（POST /files/presign → PUT 直传 MinIO → POST /complete），展示附件卡片并提供预签名下载
import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertCircle,
  AtSign,
  ChevronDown,
  ChevronRight,
  Download,
  FileText,
  Hash,
  Lock,
  MessageSquare,
  Paperclip,
  Plus,
  RefreshCw,
  RotateCcw,
  Send,
  User as UserIcon,
  Users,
  X,
} from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import type { PageProps } from '../../nav'
import {
  conversationsApi,
  filesApi,
  patchConversationUnread,
  reportViewedConv,
  useConversationDetail,
  useConversations,
  useMessageReaders,
  useMessages,
  type RemoteConversation,
} from '../../api/queries'
import type { MessagePage, RemoteAttachment, RemoteMessage } from '../../api/queries'
import { teamOneWs } from '../../api/ws'
import { useUserBriefs, toBrief } from '../../api/users'
import { RemoteAvatar, remoteName } from '../../api/RemoteAvatar'
import { fmt } from '../../data/store'
import { useAuth } from '../../api/AuthContext'
import { Btn, Empty, Pill, Spinner } from '../../components/ui'

const inputCls =
  'w-full rounded-input border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none transition-colors placeholder:text-txt-low/70 focus:border-brand'
const ARCHIVE_REASON: Record<string, string> = {
  target_closed: '对象已关闭',
  sprint_ended: '迭代结束',
  release_released: '发布完成',
  manual: '手动归档',
}

/** 安全时间渲染（T-2 加固 + R-10f 年份合法性兜底）：空/非法输入 → '—'，杜绝 Invalid Date。
 *  解析结果早于 2024-01-01 或晚于 now+1d 视为脏数据（如 epoch 秒/毫秒错位产生的 2001 类年份）
 *  → 显示 '—'；合法值仍走 store.fmt（当天 HH:mm / 同年 M-D HH:mm / 跨年 YYYY-M-D HH:mm）。 */
function fmtSafe(v?: string | null): string {
  if (!v) return '—'
  const d = new Date(v)
  const t = d.getTime()
  if (isNaN(t)) return '—'
  if (t < Date.UTC(2024, 0, 1) || t > Date.now() + 86_400_000) return '—'
  return fmt(d)
}

/** 消息行模型：REST 历史（status=sent）与 WS 实时/乐观（pending/failed）统一 */
interface MsgRow {
  key: string // React key：msgId 优先，乐观态用 clientMsgId
  msgId?: number
  clientMsgId?: string
  senderId: string
  kind: string
  body: string | null
  attachments?: RemoteAttachment[]
  createdAt: string
  status: 'sent' | 'pending' | 'failed'
  errText?: string
  withdrawn?: boolean
}

function fromRemote(m: RemoteMessage): MsgRow {
  return {
    key: `m${m.msgId}`,
    msgId: m.msgId,
    clientMsgId: m.clientMsgId,
    senderId: m.senderId,
    kind: m.kind,
    body: m.body,
    attachments: m.attachments,
    createdAt: m.createdAt,
    status: 'sent',
    withdrawn: m.withdrawn,
  }
}

export default function ImPage({ nav, id }: PageProps) {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const myId = user?.id ?? ''
  // dogfooding：成员选择/私信建会话仅需登录态（briefs）——原 GET /users 需管理权限，普通成员 403 后选择器恒空
  const { data: userRows = [] } = useUserBriefs()
  const users = toBrief(userRows)

  // ---- 选项卡与会话列表 ----
  const [convTab, setConvTab] = useState<'all' | 'topic' | 'dm' | 'group'>('all')
  const [showArchived, setShowArchived] = useState(false)
  const { data: allActiveConvs = [], isFetching: convFetching, refetch: refetchConvs } = useConversations(false)
  const { data: archivedConvs = [] } = useConversations(true)

  // dogfooding 切换：仅真实会话（GET /conversations），移除本地 store 演示兜底
  const activeConvs = useMemo(() => {
    if (convTab === 'all') return allActiveConvs
    return allActiveConvs.filter((c) => c.type === convTab)
  }, [allActiveConvs, convTab])

  const [sel, setSel] = useState<string | undefined>(() => id ?? undefined)
  useEffect(() => {
    if (id) setSel(id)
  }, [id])

  // T-4③ 辅助：向全局上报当前查看中的会话（紧急弹窗据此判定「会话非当前查看」），卸载时清除
  useEffect(() => {
    reportViewedConv(sel)
    return () => reportViewedConv(undefined)
  }, [sel])

  // T-3「刷新才可见」修复：打开/切回会话即强制重拉该会话历史——
  // 非激活会话的离线消息只经 unread 帧失效会话清单，messages 缓存（staleTime 15s）可能滞后，
  // 不强刷则切回时短暂展示旧消息，直到手动刷新。仅依赖 sel/store 会话标志，避免随会话清单刷新而重拉。
  useEffect(() => {
    if (!sel) return
    void queryClient.invalidateQueries({ queryKey: ['messages', sel] })
  }, [sel, queryClient])

  // ---- 弹窗：发起私信 / 新建群聊 ----
  const [showDmModal, setShowDmModal] = useState(false)
  const [showGroupModal, setShowGroupModal] = useState(false)
  const [groupName, setGroupName] = useState('')
  const [groupMembers, setGroupMembers] = useState<string[]>([])
  const [creatingConv, setCreatingConv] = useState(false)

  // ---- WS 实时消息与撤回状态 ----
  const [liveMsgs, setLiveMsgs] = useState<Record<string, MsgRow[]>>({})
  const [withdrawnMsgIds, setWithdrawnMsgIds] = useState<Set<number>>(() => new Set())

  // ---- 会话清单局部更新（IM 延迟诊断批）：零网络推进 lastMessageAt 保排序 ----
  // 发送 ack 后/收到 message.created 后调用；未读角标由 unread 帧的 setQueriesData
  // 局部维护（queries.ts useUnreadPush），两者都不再触发会话清单全量重拉。
  // 查询失效仍留作挂载/窗口聚焦的最终兜底（staleTime 默认 0）。
  const touchConversation = (convId: string) => {
    const now = new Date().toISOString()
    queryClient.setQueriesData<RemoteConversation[]>({ queryKey: ['conversations'] }, (rows) =>
      rows?.map((c) => (c.id === convId ? { ...c, lastMessageAt: now } : c)),
    )
  }

  // ---- 已读推进（R-10a/b）：WS read 帧（带 err/超时回调）失败 → HTTP 已读端点兜底 ----
  // ack/HTTP 响应里的 unread 为服务端权威值；patchConversationUnread 前缀修补全部会话缓存
  // 变体并同步侧栏总角标。双通道皆败则静默（查询失效兜底最终一致）。
  const markReadNow = (convId: string, lastReadMessageId?: number) => {
    void teamOneWs
      .markRead(convId, lastReadMessageId)
      .then((ack) => patchConversationUnread(queryClient, convId, Number(ack.unread ?? 0)))
      .catch(() => {
        void conversationsApi
          .markRead(convId, lastReadMessageId)
          .then((res) => patchConversationUnread(queryClient, convId, Number(res.unread ?? 0)))
          .catch(() => { /* WS+HTTP 双败：留待会话清单失效兜底 */ })
      })
  }


  useEffect(() => {
    const offMsg = teamOneWs.onEvent('message.created', (payload) => {
      const conversationId = String(payload.conversationId ?? '')
      const msgId = Number(payload.msgId ?? 0)
      if (!conversationId || !msgId) return
      setLiveMsgs((byConv) => {
        const list = byConv[conversationId] ?? []
        if (list.some((m) => m.msgId === msgId)) return byConv
        return {
          ...byConv,
          [conversationId]: [
            ...list,
            {
              key: `m${msgId}`,
              msgId,
              clientMsgId: String(payload.clientMsgId ?? ''),
              senderId: String(payload.senderId ?? ''),
              kind: String(payload.kind ?? 'text'),
              body: payload.body != null ? String(payload.body) : null,
              attachments: (payload.attachments as RemoteAttachment[]) ?? [],
              createdAt: String(payload.createdAt ?? new Date().toISOString()),
              status: 'sent',
            },
          ],
        }
      })
      // 若是当前激活会话，立即推游标；未读角标由 unread 帧的 setQueriesData 维护（useUnreadPush），
      // 这里只做 lastMessageAt 局部排序推进——不再 invalidate 会话清单全量重拉（IM 延迟优化批）
      if (conversationId === sel) {
        markReadNow(conversationId, msgId)
      }
      touchConversation(conversationId)
    })

    const offWithdrawn = teamOneWs.onEvent('message.withdrawn', (payload) => {
      const conversationId = String(payload.conversationId ?? '')
      const msgId = Number(payload.msgId ?? 0)
      if (!msgId) return
      setWithdrawnMsgIds((prev) => new Set([...prev, msgId]))
      if (conversationId) {
        setLiveMsgs((byConv) => {
          const list = byConv[conversationId]
          if (!list) return byConv
          return {
            ...byConv,
            [conversationId]: list.map((m) =>
              m.msgId === msgId ? { ...m, withdrawn: true, body: null, attachments: [] } : m,
            ),
          }
        })
      }
    })

    return () => {
      offMsg()
      offWithdrawn()
    }
  }, [sel, queryClient])

  // ---- 历史消息（before 反向游标） ----
  const history = useMessages(sel)
  const historyRows = useMemo<MsgRow[]>(
    () => (history.data?.pages ?? []).flatMap((p: MessagePage) => p.items.map(fromRemote)),
    [history.data],
  )
  const loadMore = () => {
    if (!sel || !history.hasPreviousPage || history.isFetchingPreviousPage) return
    void history.fetchPreviousPage()
  }

  // ---- 打开会话：WS sub conv:{id} & 游标推进消未读 ----
  const prevChRef = useRef<string | undefined>(undefined)
  useEffect(() => {
    const convId = sel
    if (!convId) return
    const ch = `conv:${convId}`
    void teamOneWs
      .connect()
      .then(() => teamOneWs.sub(ch))
      .catch(() => {
        /* 静默处理，网络错误后续提示 */
      })

    // 推进已读游标（R-10b：废除 999999999 哨兵——lastMessageId 缺省时省略游标，
    // 服务端语义=读到最新取 conversation.last_message_id；R-10a：err 回调 + HTTP 兜底）
    const currConv = allActiveConvs.find((c) => c.id === convId)
    if (currConv && (currConv.unreadCount ?? 0) > 0) {
      markReadNow(convId, currConv.lastMessageId)
    }
    return () => {
      if (prevChRef.current && prevChRef.current !== ch) teamOneWs.unsub(prevChRef.current)
      prevChRef.current = ch
    }
  }, [sel, allActiveConvs, queryClient])

  // ---- 会话详情（members 右栏） ----
  const { data: convDetail } = useConversationDetail(sel)
  const selConv = useMemo(
    () =>
      allActiveConvs.find((c) => c.id === sel) ??
      archivedConvs.find((c) => c.id === sel) ??
      convDetail,
    [allActiveConvs, archivedConvs, convDetail, sel],
  )

  // 解析私聊对端用户的 ID、姓名与头像
  const resolveDmPeer = (c: RemoteConversation) => {
    let peerId = c.peerUserId
    if (!peerId && convDetail?.id === c.id && convDetail.members) {
      peerId = convDetail.members.find((u) => u !== myId)
    }
    const name = (peerId ? remoteName(users, peerId) : '') || c.name || '私聊会话'
    return { peerId, name }
  }

  // ---- 合并视图：历史 + 实时（msgId 去重），乐观/失败置底 ----
  const rows = useMemo<MsgRow[]>(() => {
    const convId = sel
    if (!convId) return []
    const live = liveMsgs[convId] ?? []
    const seen = new Set<number>()
    const merged: MsgRow[] = []
    for (const m of historyRows) {
      if (m.msgId != null) {
        if (seen.has(m.msgId)) continue
        seen.add(m.msgId)
      }
      merged.push(m)
    }
    for (const m of live) {
      if (m.msgId != null && (seen.has(m.msgId) || merged.some((x) => x.clientMsgId && x.clientMsgId === m.clientMsgId)))
        continue
      merged.push(m)
    }
    return merged
  }, [sel, historyRows, liveMsgs])

  const boxRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const box = boxRef.current
    if (box) box.scrollTop = box.scrollHeight
  }, [sel, rows.length, history.isFetching])

  // ---- @ 提及功能（群聊与话题支持） ----
  const [mentionOpen, setMentionOpen] = useState(false)
  const [mentionQuery, setMentionQuery] = useState('')
  const [mentionIndex, setMentionIndex] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)

  // 当前会话干系人/成员列表
  const currentMembers = useMemo<string[]>(() => {
    if (convDetail?.members) return convDetail.members
    if (selConv?.peerUserId) return [myId, selConv.peerUserId]
    return []
  }, [convDetail, selConv, myId])

  // @ 候选人清单
  const mentionCandidates = useMemo(() => {
    const list: { id: string; name: string; role: string }[] = [
      { id: 'all', name: '所有人', role: '全体成员' },
    ]
    const seen = new Set<string>(['all', myId])
    for (const uid of currentMembers) {
      if (seen.has(uid)) continue
      seen.add(uid)
      const name = remoteName(users, uid) || uid
      const role = users.get(uid)?.title || '干系人'
      list.push({ id: uid, name, role })
    }
    return list
  }, [currentMembers, users, myId])

  const filteredCandidates = useMemo(() => {
    if (!mentionOpen) return []
    const q = mentionQuery.toLowerCase()
    return mentionCandidates.filter(
      (c) => c.name.toLowerCase().includes(q) || c.role.toLowerCase().includes(q),
    )
  }, [mentionCandidates, mentionOpen, mentionQuery])

  // 插入 @ 提及成员
  const insertMention = (candidateName: string) => {
    const cursor = inputRef.current?.selectionStart ?? text.length
    const before = text.slice(0, cursor)
    const after = text.slice(cursor)
    const atIdx = before.lastIndexOf('@')
    if (atIdx !== -1) {
      const newText = before.slice(0, atIdx) + `@${candidateName} ` + after
      setText(newText)
      setMentionOpen(false)
      setTimeout(() => {
        if (inputRef.current) {
          const newPos = atIdx + candidateName.length + 2
          inputRef.current.focus()
          inputRef.current.setSelectionRange(newPos, newPos)
        }
      }, 0)
    }
  }

  // 监听输入触发 @
  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value
    setText(val)
    if (selConv && selConv.type !== 'dm') {
      const cursor = e.target.selectionStart ?? val.length
      const before = val.slice(0, cursor)
      const match = before.match(/@([^@\s]*)$/)
      if (match) {
        setMentionOpen(true)
        setMentionQuery(match[1])
        setMentionIndex(0)
      } else {
        setMentionOpen(false)
      }
    } else {
      setMentionOpen(false)
    }
  }

  // ---- 发送文本消息 ----
  const [text, setText] = useState('')
  const send = async (retryOf?: MsgRow) => {
    const convId = sel
    if (!convId) return
    const body = (retryOf ? retryOf.body ?? '' : text).trim()
    if (!body) return
    setMentionOpen(false)

    // 远程 WS 实时发送
    const clientMsgId = retryOf?.clientMsgId ?? crypto.randomUUID()
    // V16 R-10e：@提醒名单随发送帧上送——从正文中回查 @姓名 映射为成员 id
    // （'所有人' 展开为除本人外的全部成员），后端再过滤为「会话成员 − 发送者」
    const mentionedIds = (() => {
      const ids: string[] = []
      for (const c of mentionCandidates) {
        if (c.id !== 'all' && body.includes(`@${c.name}`)) ids.push(c.id)
      }
      if (/@所有人/.test(body)) {
        for (const uid of currentMembers) {
          if (uid !== myId) ids.push(uid)
        }
      }
      return [...new Set(ids)]
    })()
    if (!retryOf) setText('')
    setLiveMsgs((byConv) => {
      const list = byConv[convId] ?? []
      if (retryOf) {
        return {
          ...byConv,
          [convId]: list.map((m) =>
            m.clientMsgId === clientMsgId ? { ...m, status: 'pending' as const, errText: undefined } : m,
          ),
        }
      }
      return {
        ...byConv,
        [convId]: [
          ...list,
          {
            key: clientMsgId,
            clientMsgId,
            senderId: myId,
            kind: 'text',
            body,
            createdAt: new Date().toISOString(),
            status: 'pending' as const,
          },
        ],
      }
    })
    try {
      const msgId = Number(await teamOneWs.sendMsg(convId, body, clientMsgId, 'text', undefined, mentionedIds))
      setLiveMsgs((byConv) => ({
        ...byConv,
        [convId]: (byConv[convId] ?? []).map((m) =>
          m.clientMsgId === clientMsgId ? { ...m, msgId, status: 'sent' as const } : m,
        ),
      }))
      // IM 延迟优化批：ack 即上屏（上方乐观行就地转正）+ 清单局部排序推进，
      // 不再 invalidate 全量重拉（原实现一条消息触发一次会话列表全表请求）
      touchConversation(convId)
    } catch (e) {
      const code = (e as { code?: string }).code ?? ''
      const message = (e as { message?: string }).message ?? '发送失败'
      setLiveMsgs((byConv) => ({
        ...byConv,
        [convId]: (byConv[convId] ?? []).map((m) =>
          m.clientMsgId === clientMsgId
            ? { ...m, status: 'failed' as const, errText: `${code} ${message}` }
            : m,
        ),
      }))
    }
  }

  // ---- 撤回消息 ----
  const handleWithdraw = async (msgId: number) => {
    if (!sel) return
    try {
      await conversationsApi.withdraw(sel, msgId)
      setWithdrawnMsgIds((prev) => new Set([...prev, msgId]))
      void queryClient.invalidateQueries({ queryKey: ['messages', sel] })
    } catch (err) {
      alert('撤回失败: ' + (err as Error).message)
    }
  }

  // ---- 已读回执（Direction 4 Phase 4） ----
  /** 当前展开已读回执明细的消息 ID（null 表示未展开） */
  const [readReceiptMsgId, setReadReceiptMsgId] = useState<number | null>(null)
  /** 查询已读回执明细（仅在 readReceiptMsgId 有值时启用） */
  const { data: readReceipt, isFetching: readReceiptFetching } = useMessageReaders(
    sel,
    readReceiptMsgId ?? undefined,
  )

  // ---- 附件上传与下载 ----
  const [uploading, setUploading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !sel) return
    e.target.value = ''
    setUploading(true)
    try {
      const att = await filesApi.upload(file)
      const clientMsgId = crypto.randomUUID()
      await teamOneWs.sendMsg(sel, file.name, clientMsgId, 'file', [att])
      touchConversation(sel) // 清单局部排序推进；附件消息经 WS message.created 帧回显上屏
      void queryClient.invalidateQueries({ queryKey: ['messages', sel] })
    } catch (err) {
      alert('文件上传失败: ' + (err as Error).message)
    } finally {
      setUploading(false)
    }
  }

  const handleDownload = async (fileId: string) => {
    try {
      const info = await filesApi.downloadUrl(fileId)
      window.open(info.downloadUrl, '_blank')
    } catch (err) {
      alert('获取下载链接失败: ' + (err as Error).message)
    }
  }

  // ---- 消息行渲染 ----
  const renderMsg = (m: MsgRow) => {
    const isWithdrawn = m.withdrawn || (m.msgId ? withdrawnMsgIds.has(m.msgId) : false)
    if (isWithdrawn) {
      const mine = m.senderId === myId
      return (
        <div key={m.key} className="mx-auto max-w-[88%] text-center py-1">
          <div className="inline-block rounded-lg bg-ink-700/60 px-3 py-1 text-xs text-txt-low italic">
            {mine ? '你' : remoteName(users, m.senderId)} 撤回了一条消息
          </div>
        </div>
      )
    }

    if (m.kind === 'system') {
      return (
        <div key={m.key} className="mx-auto max-w-[88%] text-center">
          <div className="rounded-lg bg-ink-700 px-3 py-2 text-xs leading-5 text-txt-mid">{m.body}</div>
        </div>
      )
    }

    const mine = m.senderId === myId
    return (
      <div key={m.key} className="group flex gap-2.5">
        {mine ? (
          <span className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-brand text-xs font-bold text-white">
            我
          </span>
        ) : (
          <RemoteAvatar userId={m.senderId} users={users} size={32} />
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-baseline gap-2">
            <span className="text-sm font-semibold text-txt-hi">
              {mine ? '我' : remoteName(users, m.senderId)}
            </span>
            <span className="text-[11px] tabular-nums text-txt-low">{fmtSafe(m.createdAt)}</span>
            {m.status === 'pending' && <span className="text-[11px] text-txt-low">发送中…</span>}
            {m.status === 'failed' && (
              <>
                <span className="flex items-center gap-1 text-[11px] font-semibold text-bad-deep">
                  <AlertCircle size={11} />
                  {m.errText}
                </span>
                <button
                  type="button"
                  onClick={() => void send(m)}
                  className="flex cursor-pointer items-center gap-0.5 rounded px-1 text-[11px] text-bad-deep hover:bg-bad-bg"
                >
                  <RotateCcw size={11} /> 重发
                </button>
              </>
            )}
            {mine && m.status === 'sent' && m.msgId && (
              <button
                type="button"
                onClick={() => void handleWithdraw(m.msgId!)}
                className="opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer rounded px-1 text-[11px] text-txt-low hover:text-bad"
                title="24小时内可撤回"
              >
                撤回
              </button>
            )}
            {/* 已读回执按钮（仅已发送消息 & 非私信，群聊/话题查看已读明细） */}
            {m.status === 'sent' && m.msgId && selConv && selConv.type !== 'dm' && (
              <button
                type="button"
                onClick={() => setReadReceiptMsgId(readReceiptMsgId === m.msgId ? null : m.msgId!)}
                className={`opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer rounded px-1.5 text-[11px] ${
                  readReceiptMsgId === m.msgId ? 'text-brand font-semibold opacity-100' : 'text-txt-low hover:text-brand'
                }`}
                title="查看已读/未读成员"
              >
                已读
              </button>
            )}
          </div>

          {/* 文本内容（支持高亮 @ 提及） */}
          {m.body && (
            <div className="mt-0.5 text-sm leading-6 whitespace-pre-wrap text-txt-mid">
              {m.body.split(/(@[^\s@]+)/g).map((part, idx) => {
                if (part.startsWith('@')) {
                  const isAll = part === '@所有人'
                  const isMe =
                    part === '@我' ||
                    (user?.username && part === `@${user.username}`) ||
                    (user?.displayName && part === `@${user.displayName}`)
                  return (
                    <span
                      key={idx}
                      className={`inline-flex items-center px-1.5 py-0.2 rounded text-xs font-semibold mx-0.5 ${
                        isAll || isMe
                          ? 'bg-orange-500/15 text-orange-600 dark:text-orange-400'
                          : 'bg-brand/10 text-brand font-medium'
                      }`}
                    >
                      {part}
                    </span>
                  )
                }
                return part
              })}
            </div>
          )}

          {/* 附件卡片 */}
          {m.attachments && m.attachments.length > 0 && (
            <div className="mt-1.5 space-y-1.5">
              {m.attachments.map((att) => (
                <div
                  key={att.fileId}
                  className="flex items-center gap-2.5 rounded-lg border border-line bg-card px-3 py-2 text-xs max-w-sm"
                >
                  <FileText size={18} className="text-brand shrink-0" />
                  <div className="min-w-0 flex-1">
                    <div className="truncate font-medium text-txt-hi">{att.name || att.originalName || '附件'}</div>
                    {att.size != null && (
                      <div className="text-[10px] text-txt-low">{(att.size / 1024).toFixed(1)} KB</div>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => void handleDownload(att.fileId)}
                    className="flex items-center gap-1 rounded bg-ink-700 px-2 py-1 text-xs font-semibold text-brand hover:bg-brand-bg cursor-pointer"
                  >
                    <Download size={12} /> 下载
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* 已读回执明细面板（Direction 4 Phase 4） */}
          {readReceiptMsgId === m.msgId && (
            <div className="mt-2 rounded-lg border border-line bg-card p-3 max-w-md">
              {readReceiptFetching ? (
                <div className="text-xs text-txt-low animate-pulse">加载已读信息…</div>
              ) : readReceipt ? (
                <div className="space-y-2">
                  {/* 统计摘要 */}
                  <div className="flex items-center gap-3 text-xs">
                    <span className="font-semibold text-txt-hi">
                      已读 {readReceipt.readCount} / 未读 {readReceipt.unreadCount}
                    </span>
                    {readReceipt.allRead && (
                      <span className="rounded-full bg-ok-bg px-2 py-0.5 text-[10px] font-semibold text-ok-deep">
                        全员已读 ✓
                      </span>
                    )}
                    <span className="text-txt-low">共 {readReceipt.totalRecipients} 人</span>
                  </div>
                  {/* 已读列表 */}
                  {readReceipt.readers.length > 0 && (
                    <div>
                      <div className="text-[10px] font-semibold text-ok-deep mb-1">
                        已读（{readReceipt.readers.length}）
                      </div>
                      <div className="flex flex-wrap gap-1">
                        {readReceipt.readers.map((r) => (
                          <span
                            key={r.userId}
                            className="inline-flex items-center gap-1 rounded-full bg-ok-bg/60 px-2 py-0.5 text-[10px] text-ok-deep"
                            title={r.readAt ? `阅读于 ${fmtSafe(r.readAt)}` : ''}
                          >
                            {r.displayName}
                            {r.readAt && (
                              <span className="text-[9px] opacity-70">{fmtSafe(r.readAt)}</span>
                            )}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                  {/* 未读列表 */}
                  {readReceipt.unreaders.length > 0 && (
                    <div>
                      <div className="text-[10px] font-semibold text-txt-low mb-1">
                        未读（{readReceipt.unreaders.length}）
                      </div>
                      <div className="flex flex-wrap gap-1">
                        {readReceipt.unreaders.map((r) => (
                          <span
                            key={r.userId}
                            className="inline-flex rounded-full bg-ink-700/80 px-2 py-0.5 text-[10px] text-txt-low"
                          >
                            {r.displayName}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              ) : (
                <div className="text-xs text-txt-low">暂无已读数据</div>
              )}
            </div>
          )}
        </div>
      </div>
    )
  }

  const archived = selConv?.archived ?? false

  // ---- 渲染会话图标 ----
  const renderConvIcon = (type: string) => {
    switch (type) {
      case 'dm':
        return <UserIcon size={14} className="shrink-0 text-blue-500" />
      case 'group':
        return <Users size={14} className="shrink-0 text-green-500" />
      default:
        return <Hash size={14} className="shrink-0 text-brand" />
    }
  }

  // ---- 发起私信处理 ----
  const handleStartDm = async (peerId: string) => {
    setCreatingConv(true)
    try {
      const conv = await conversationsApi.createDm(peerId)
      await queryClient.invalidateQueries({ queryKey: ['conversations'] })
      setSel(conv.id)
      setShowDmModal(false)
    } catch (err) {
      alert('发起私聊失败: ' + (err as Error).message)
    } finally {
      setCreatingConv(false)
    }
  }

  // ---- 新建群聊处理 ----
  const handleCreateGroup = async () => {
    const name = groupName.trim()
    if (!name) {
      alert('请输入群聊名称')
      return
    }
    setCreatingConv(true)
    try {
      const conv = await conversationsApi.createGroup(name, groupMembers)
      await queryClient.invalidateQueries({ queryKey: ['conversations'] })
      setSel(conv.id)
      setShowGroupModal(false)
      setGroupName('')
      setGroupMembers([])
    } catch (err) {
      alert('创建群聊失败: ' + (err as Error).message)
    } finally {
      setCreatingConv(false)
    }
  }

  return (
    <div>
      <div className="flex h-[74vh] min-h-[540px] overflow-hidden rounded-card border border-line bg-canvas">
        {/* 左栏：会话列表 + 分类 Tabs + 归档折叠 */}
        <aside className="flex w-64 shrink-0 flex-col overflow-y-auto border-r border-line bg-card">
          {/* 顶栏控制区 */}
          <div className="flex items-center justify-between px-3 pt-3 pb-2 border-b border-line">
            <span className="text-xs font-bold text-txt-hi flex items-center gap-1.5">
              <MessageSquare size={14} className="text-brand" /> 即时沟通
            </span>
            <div className="flex items-center gap-1">
              <button
                type="button"
                title="发起私聊"
                onClick={() => setShowDmModal(true)}
                className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"
              >
                <UserIcon size={13} />
              </button>
              <button
                type="button"
                title="新建群聊"
                onClick={() => setShowGroupModal(true)}
                className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"
              >
                <Plus size={13} />
              </button>
              <button
                type="button"
                title="刷新会话"
                onClick={() => void refetchConvs()}
                className="cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"
              >
                <RefreshCw size={12} className={convFetching ? 'animate-spin' : undefined} />
              </button>
            </div>
          </div>

          {/* 会话类型过滤 Tabs */}
          <div className="grid grid-cols-4 gap-1 p-2 text-center text-xs">
            {(
              [
                ['all', '全部'],
                ['topic', '话题'],
                ['dm', '私信'],
                ['group', '群聊'],
              ] as const
            ).map(([t, label]) => (
              <button
                key={t}
                type="button"
                onClick={() => setConvTab(t)}
                className={`cursor-pointer rounded py-1 transition-colors ${
                  convTab === t ? 'bg-brand text-white font-bold' : 'text-txt-mid hover:bg-ink-700'
                }`}
              >
                {label}
              </button>
            ))}
          </div>

          {/* 活跃会话列表 */}
          <ul className="flex-1 space-y-0.5 px-2 overflow-y-auto">
            {activeConvs.map((c) => {
              const isDm = c.type === 'dm'
              const { peerId, name: dmName } = isDm ? resolveDmPeer(c) : { peerId: undefined, name: c.name }
              const title = isDm ? dmName : (c.name || `会话 ${c.id.slice(0, 4)}`)
              return (
                <li key={c.id}>
                  <button
                    type="button"
                    onClick={() => setSel(c.id)}
                    className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm transition-colors ${
                      sel === c.id
                        ? 'bg-brand-bg font-semibold text-brand-deep'
                        : 'text-txt-mid hover:bg-ink-700 hover:text-txt-hi'
                    }`}
                  >
                    {isDm && peerId ? (
                      <RemoteAvatar userId={peerId} users={users} size={20} />
                    ) : (
                      renderConvIcon(c.type)
                    )}
                    <span className="min-w-0 flex-1 truncate">
                      {title}
                    </span>
                    {c.unreadCount != null && c.unreadCount > 0 && (
                      <span className="rounded-full bg-red-500 px-1.5 py-0.2 text-[10px] font-bold text-white leading-4">
                        {c.unreadCount}
                      </span>
                    )}
                  </button>
                </li>
              )
            })}
            {activeConvs.length === 0 && (
              <li className="px-2.5 py-4 text-center text-[11px] leading-4 text-txt-low">
                暂无此分类会话
              </li>
            )}
          </ul>

          {/* 已归档话题折叠 */}
          <div className="border-t border-line p-2">
            <button
              type="button"
              onClick={() => setShowArchived((v) => !v)}
              className="flex w-full cursor-pointer items-center gap-1 rounded px-2 py-1 text-[11px] font-semibold text-txt-low hover:text-txt-mid"
            >
              {showArchived ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
              已归档 · {archivedConvs.length}
            </button>
            {showArchived && (
              <ul className="mt-1 space-y-0.5 max-h-36 overflow-y-auto">
                {archivedConvs.map((c) => (
                  <li key={c.id}>
                    <button
                      type="button"
                      onClick={() => setSel(c.id)}
                      className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-sm text-txt-low transition-colors hover:bg-ink-700 ${
                        sel === c.id ? 'bg-brand-bg font-semibold text-brand-deep' : ''
                      }`}
                    >
                      <Lock size={12} className="shrink-0" />
                      <span className="min-w-0 flex-1 truncate line-through decoration-txt-low/50">
                        {c.name || `会话 ${c.id.slice(0, 4)}`}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </aside>

        {/* 中栏：会话时间线 */}
        <section className="flex min-w-0 flex-1 flex-col">
          {sel && selConv ? (
            <>
              <div className="flex items-center gap-3 border-b border-line px-4 py-2.5 bg-card/40">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 text-sm font-bold text-txt-hi">
                    {selConv.type === 'dm' ? (
                      (() => {
                        const { peerId, name } = resolveDmPeer(selConv)
                        return (
                          <>
                            {peerId && <RemoteAvatar userId={peerId} users={users} size={24} />}
                            <span>{name}</span>
                            <Pill tone="info">私聊</Pill>
                            <span className="flex items-center gap-1 text-[11px] text-ok-deep font-normal">
                              <span className="h-1.5 w-1.5 rounded-full bg-ok animate-pulse" />
                              在线
                            </span>
                          </>
                        )
                      })()
                    ) : (
                      <>
                        {renderConvIcon(selConv.type)}
                        <span>
                          {selConv.name || `会话 ${selConv.id.slice(0, 6)}`}
                        </span>
                        {selConv.type === 'group' && <Pill tone="ok">群聊</Pill>}
                        {selConv.autoCreated && <Pill tone="purple">自动建题</Pill>}
                      </>
                    )}
                    {archived && (
                      <Pill tone="neutral">
                        <Lock size={10} /> 已归档
                      </Pill>
                    )}
                  </div>
                  {selConv.targetType && (
                    <div className="flex items-center gap-1.5 text-[11px] text-txt-low mt-0.5">
                      <button
                        type="button"
                        onClick={() => {
                          if (selConv.targetType === 'defect') nav.go('defects', selConv.targetId)
                          else if (selConv.targetType === 'requirement') nav.go('requirements', selConv.targetId)
                          else if (selConv.targetType === 'roadmap') nav.go('roadmap', selConv.targetId)
                          else if (selConv.targetType === 'goal') nav.go('goals', selConv.targetId)
                        }}
                        className="cursor-pointer"
                      >
                        <Pill tone="brand">
                          {selConv.targetType === 'defect'
                            ? '关联缺陷'
                            : selConv.targetType === 'requirement'
                              ? '关联需求'
                              : selConv.targetType === 'roadmap'
                                ? '关联 RoadMap'
                                : selConv.targetType === 'goal'
                                  ? '关联战略目标'
                                  : `关联 ${selConv.targetType}`}
                        </Pill>
                      </button>
                      <span>· 对象化讨论</span>
                    </div>
                  )}
                </div>
              </div>

              <div ref={boxRef} className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
                {history.hasPreviousPage && (
                  <div className="text-center">
                    <button
                      type="button"
                      onClick={loadMore}
                      disabled={history.isFetchingPreviousPage}
                      className="cursor-pointer rounded-full border border-line bg-card px-3 py-1 text-[11px] text-txt-mid hover:bg-ink-700 disabled:opacity-50"
                    >
                      {history.isFetchingPreviousPage ? '加载中…' : '加载更多历史'}
                    </button>
                  </div>
                )}
                {history.isLoading && (
                  <Empty text="消息加载中…" size="sm" icon={<Spinner size={16} />} />
                )}
                {rows.map(renderMsg)}
                {rows.length === 0 && !history.isFetching && !history.isLoading && (
                  <div className="py-12 text-center text-sm text-txt-low">
                    已建立连接，还没有消息，说点什么吧
                  </div>
                )}
              </div>

              <div className="border-t border-line p-3 bg-card/30">
                {archived ? (
                  <div className="flex items-center gap-1.5 rounded-input bg-ink-700 px-3 py-2">
                    <span className="flex items-center gap-1.5 text-xs text-txt-low">
                      <Lock size={12} /> 话题已归档（{ARCHIVE_REASON[selConv.archivedReason ?? ''] ?? '已归档'}），仅供检索回放
                    </span>
                  </div>
                ) : (
                  <div className="flex items-center gap-2">
                    <input
                      type="file"
                      ref={fileInputRef}
                      className="hidden"
                      onChange={(e) => void handleFileSelect(e)}
                    />
                    <button
                      type="button"
                      disabled={uploading}
                      onClick={() => fileInputRef.current?.click()}
                      className="cursor-pointer rounded p-2 text-txt-mid hover:bg-ink-700 hover:text-txt-hi disabled:opacity-50"
                      title="上传文件附件（支持文档/压缩包/图片，≤50MB）"
                    >
                      <Paperclip size={16} className={uploading ? 'animate-spin text-brand' : undefined} />
                    </button>
                    <div className="relative flex-1">
                      {mentionOpen && filteredCandidates.length > 0 && (
                        <div className="absolute bottom-full left-0 mb-2 w-64 max-h-56 overflow-y-auto rounded-xl border border-line bg-card shadow-2xl p-1.5 z-40">
                          <div className="px-2 py-1 text-[10px] font-semibold text-txt-low flex items-center justify-between border-b border-line mb-1">
                            <span className="flex items-center gap-1">
                              <AtSign size={11} className="text-brand" /> 提及成员
                            </span>
                            <span>↑↓ 切换 · Enter 确认</span>
                          </div>
                          {filteredCandidates.map((c, i) => (
                            <button
                              key={c.id}
                              type="button"
                              onMouseDown={(e) => {
                                e.preventDefault()
                                insertMention(c.name)
                              }}
                              className={`flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs cursor-pointer ${
                                i === mentionIndex
                                  ? 'bg-brand text-white font-semibold'
                                  : 'text-txt-hi hover:bg-ink-700'
                              }`}
                            >
                              {c.id === 'all' ? (
                                <span className="flex h-5 w-5 items-center justify-center rounded-full bg-orange-500/20 text-[10px] font-bold text-orange-500">
                                  @
                                </span>
                              ) : (
                                <RemoteAvatar userId={c.id} users={users} size={20} />
                              )}
                              <span className="min-w-0 flex-1 truncate">{c.name}</span>
                              <span
                                className={`text-[10px] ${
                                  i === mentionIndex ? 'text-white/80' : 'text-txt-low'
                                }`}
                              >
                                {c.role}
                              </span>
                            </button>
                          ))}
                        </div>
                      )}
                      <input
                        ref={inputRef}
                        value={text}
                        onChange={handleInputChange}
                        onKeyDown={(e) => {
                          if (mentionOpen && filteredCandidates.length > 0) {
                            if (e.key === 'ArrowDown') {
                              e.preventDefault()
                              setMentionIndex((i) => (i + 1) % filteredCandidates.length)
                              return
                            }
                            if (e.key === 'ArrowUp') {
                              e.preventDefault()
                              setMentionIndex((i) => (i - 1 + filteredCandidates.length) % filteredCandidates.length)
                              return
                            }
                            if (e.key === 'Enter' || e.key === 'Tab') {
                              e.preventDefault()
                              insertMention(filteredCandidates[mentionIndex].name)
                              return
                            }
                            if (e.key === 'Escape') {
                              e.preventDefault()
                              setMentionOpen(false)
                              return
                            }
                          }
                          if (e.key === 'Enter') void send()
                        }}
                        placeholder={
                          uploading
                            ? '附件上传中…'
                            : selConv?.type === 'dm'
                              ? '输入私聊消息，Enter 发送…'
                              : '输入消息，键入 @ 提及成员，Enter 发送…'
                        }
                        className={inputCls}
                        disabled={uploading}
                      />
                    </div>
                    <Btn variant="primary" onClick={() => void send()} disabled={uploading || !text.trim()}>
                      <Send size={14} />
                    </Btn>
                  </div>
                )}
              </div>
            </>
          ) : (
            <div className="flex flex-1 flex-col items-center justify-center gap-3 text-txt-low">
              <MessageSquare size={36} className="text-txt-low/40" />
              <div className="text-sm font-medium">从左侧选择或发起会话</div>
              <div className="text-xs text-txt-low/70">
                支持对象化讨论话题 · 点对点私聊 · 多人协作群聊 · MinIO 文件两步制附件 · 消息撤回
              </div>
            </div>
          )}
        </section>

        {/* 右栏：干系人与成员 */}
        <aside className="flex w-52 shrink-0 flex-col border-l border-line bg-card">
          <div className="border-b border-line px-3 py-2.5 text-xs font-semibold text-txt-mid flex items-center justify-between">
            <span>{selConv?.type === 'topic' ? '干系人' : selConv?.type === 'dm' ? '聊天双方' : '参与成员'}</span>
            <span className="rounded-full bg-ink-700 px-1.5 py-0.5 text-[10px] font-bold text-txt-low">
              {currentMembers.length}
            </span>
          </div>
          <ul className="flex-1 overflow-y-auto p-2 space-y-1">
            {currentMembers.map((uid) => (
              <li key={uid} className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-ink-700">
                <RemoteAvatar userId={uid} users={users} size={24} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm text-txt-hi">
                    {uid === myId ? '我' : (remoteName(users, uid) || uid)}
                  </div>
                  <div className="text-[10px] text-txt-low truncate">
                    {users.get(uid)?.title || (selConv?.type === 'topic' ? '干系人' : '成员')}
                  </div>
                </div>
              </li>
            ))}
            {currentMembers.length === 0 && (
              <li className="px-2 py-2 text-[11px] text-txt-low">选择会话后查看成员</li>
            )}
          </ul>
          {selConv?.type === 'topic' && currentMembers.length > 0 && (
            <div className="p-2 border-t border-line text-[11px] text-txt-low/80 text-center">
              已自动拉入对象责任人与干系人
            </div>
          )}
        </aside>
      </div>

      {/* 弹窗：发起私聊 */}
      {showDmModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-md rounded-xl border border-line bg-card p-5 shadow-2xl">
            <div className="flex items-center justify-between pb-3 border-b border-line">
              <h3 className="text-sm font-bold text-txt-hi flex items-center gap-1.5">
                <UserIcon size={16} className="text-blue-500" /> 发起私聊
              </h3>
              <button
                type="button"
                onClick={() => setShowDmModal(false)}
                className="text-txt-low hover:text-txt-hi cursor-pointer"
              >
                <X size={16} />
              </button>
            </div>
            <div className="mt-3 max-h-60 overflow-y-auto space-y-1">
              {userRows
                .filter((u) => u.id !== myId)
                .map((u) => (
                  <button
                    key={u.id}
                    type="button"
                    disabled={creatingConv}
                    onClick={() => void handleStartDm(u.id)}
                    className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left hover:bg-ink-700 cursor-pointer disabled:opacity-50"
                  >
                    <RemoteAvatar userId={u.id} users={users} size={28} />
                    <div className="min-w-0 flex-1">
                      <div className="text-sm font-medium text-txt-hi">{u.displayName || u.username}</div>
                      <div className="text-[11px] text-txt-low">{u.title} · {u.username}</div>
                    </div>
                  </button>
                ))}
            </div>
          </div>
        </div>
      )}

      {/* 弹窗：新建群聊 */}
      {showGroupModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-md rounded-xl border border-line bg-card p-5 shadow-2xl">
            <div className="flex items-center justify-between pb-3 border-b border-line">
              <h3 className="text-sm font-bold text-txt-hi flex items-center gap-1.5">
                <Users size={16} className="text-green-500" /> 新建群聊
              </h3>
              <button
                type="button"
                onClick={() => setShowGroupModal(false)}
                className="text-txt-low hover:text-txt-hi cursor-pointer"
              >
                <X size={16} />
              </button>
            </div>
            <div className="mt-4 space-y-3">
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">群聊名称</label>
                <input
                  value={groupName}
                  onChange={(e) => setGroupName(e.target.value)}
                  placeholder="输入群聊名称…"
                  className={inputCls}
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">选择成员</label>
                <div className="max-h-48 overflow-y-auto space-y-1 rounded border border-line p-2">
                  {userRows
                    .filter((u) => u.id !== myId)
                    .map((u) => {
                      const checked = groupMembers.includes(u.id)
                      return (
                        <label
                          key={u.id}
                          className="flex items-center gap-2.5 rounded px-2 py-1.5 hover:bg-ink-700 cursor-pointer text-xs"
                        >
                          <input
                            type="checkbox"
                            checked={checked}
                            onChange={(e) => {
                              if (e.target.checked) setGroupMembers((prev) => [...prev, u.id])
                              else setGroupMembers((prev) => prev.filter((id) => id !== u.id))
                            }}
                          />
                          <RemoteAvatar userId={u.id} users={users} size={22} />
                          <span className="font-medium text-txt-hi">{u.displayName || u.username}</span>
                          <span className="text-txt-low text-[10px]">({u.title})</span>
                        </label>
                      )
                    })}
                </div>
              </div>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <Btn variant="ghost" onClick={() => setShowGroupModal(false)}>
                取消
              </Btn>
              <Btn
                variant="primary"
                onClick={() => void handleCreateGroup()}
                disabled={creatingConv || !groupName.trim()}
              >
                创建群聊
              </Btn>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
