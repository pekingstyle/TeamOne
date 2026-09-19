// W4 前端批 · TeamOneWs 单例（信令协议 2026-09-11 wsprobe 实测；R-10 read 帧修正定稿）：
//   C→S auth{token} → S→C ready{userId,serverTime}
//   C→S sub{ch}     → ack{of:'sub',ch} | err{code,message}（非成员 T1-COL-4210）
//   C→S msg{payload:{clientMsgId,ch,kind,body,attachments?,mentions?}} → ack{clientMsgId,msgId,conversationId}
//       归档会话 err T1-COL-4203、非成员 T1-COL-4210、处理失败 SRV-5000（提示重发）
//   C→S read{ch:'conv:{id}', lastReadMessageId?} → ack{of:'read',refId,ch,lastReadMessageId,unread}
//       ——lastReadMessageId 缺省=读到最新；err/超时由调用方走 HTTP 已读兜底
//   S→C event{payload:{kind,payload}}（fanout：message.created / unread / read / notify / defect.blocked_changed…）
// 断线指数退避重连（1s 起，×2，封顶 30s），重连成功后自动重 auth + 重 sub。
import { ensureAccessToken } from './client'

export type WsFrame = { type: string; payload?: Record<string, unknown> }
export interface WsErrPayload { code: string; message: string }

type EventCb = (payload: Record<string, unknown>) => void
type ResolveFn = (payload: Record<string, unknown>) => void
type RejectFn = (err: WsErrPayload) => void

interface AckWaiter {
  resolve: ResolveFn
  reject: RejectFn
  timer: ReturnType<typeof setTimeout>
}

const ACK_TIMEOUT_MS = 10_000

class TeamOneWs {
  private ws: WebSocket | null = null
  private readyPromise: Promise<void> | null = null
  private readyResolve: (() => void) | null = null
  /** 已订阅频道（重连 ready 后自动重 sub） */
  private channels = new Set<string>()
  /** sub 调用方等待者：ch → waiters */
  private subWaiters = new Map<string, AckWaiter[]>()
  /** msg 发送方等待者：clientMsgId → waiter */
  private msgWaiters = new Map<string, AckWaiter>()
  /** read 发送方等待者（R-10a）：请求帧 id → waiter（ack/err 均以 payload.refId 回带） */
  private readWaiters = new Map<string, AckWaiter>()
  /** event(kind) 订阅者 */
  private eventSubs = new Map<string, Set<EventCb>>()
  /** 断线重连回调（IM 实时性批）：重订阅完成后触发，业务侧补偿失效断线窗口内错失的数据 */
  private reconnectCbs = new Set<() => void>()
  private backoffMs = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private pingTimer: ReturnType<typeof setInterval> | null = null
  private closedByUser = false

  /** 连接并在 ready 后 resolve；幂等（重复调用复用同一握手） */
  connect(): Promise<void> {
    if (this.readyPromise) return this.readyPromise
    this.closedByUser = false
    this.readyPromise = new Promise<void>((resolve, reject) => {
      this.readyResolve = resolve
      void this.open(reject)
    })
    return this.readyPromise
  }

  private async open(rejectAuth?: (e: WsErrPayload) => void): Promise<void> {
    const token = await ensureAccessToken()
    if (!token) {
      rejectAuth?.({ code: 'T1-PLT-4010', message: '未登录，WS 不建立连接' })
      return
    }
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const ws = new WebSocket(`${proto}//${location.host}/ws`)
    this.ws = ws
    let errored = false // 本 socket 只因错误触发一次 close（undici/WebSocket 重派发防护）

    ws.onmessage = (e) => this.onFrame(JSON.parse(e.data as string) as WsFrame, rejectAuth)
    ws.onopen = () => {
      // MF-2 验证中发现：auth 帧在 CONNECTING 态 send() 会抛 InvalidStateError —— 必须等 open
      this.sendRaw({ type: 'auth', payload: { token } })
    }
    ws.onclose = () => {
      // MF-2：close()→connect() 快速重连时，旧 socket 的 close 事件晚到会误清新连接状态——
      // 只有当前 socket 的关闭才做清理/重连调度
      if (this.ws !== ws) return
      this.stopPing()
      this.ws = null
      // 挂起的 read 等待者立即失败（调用方马上走 HTTP 已读兜底，不等 10s 超时）
      for (const [refId, waiter] of [...this.readWaiters]) {
        clearTimeout(waiter.timer)
        this.readWaiters.delete(refId)
        waiter.reject({ code: 'T1-WS-CLOSED', message: 'WS 连接断开，read 未应答' })
      }
      // 失败/断开的 ready promise 复位，下次 connect 重新握手
      this.readyPromise = null
      this.readyResolve = null
      if (!this.closedByUser) this.scheduleReconnect()
    }
    ws.onerror = () => {
      if (errored) return
      errored = true
      ws.close()
    }
    // 首帧必须是 auth（服务端：非 auth 帧即断）——见 onopen
  }

  private onFrame(frame: WsFrame, rejectAuth?: (e: WsErrPayload) => void): void {
    const payload = (frame.payload ?? {}) as Record<string, unknown>
    switch (frame.type) {
      case 'ready':
        this.backoffMs = 0
        this.startPing()
        // 重连自动重订阅（服务端 channelSubs 按连接存储，新会话必须重发 sub）
        const hadChannels = this.channels.size > 0
        for (const ch of this.channels) this.sendRaw({ type: 'sub', payload: { ch } })
        // 断线窗口补偿（IM 实时性批）：重连期间错失的 message/unread/notify 帧不可恢复，
        // 通知业务侧失效缓存重拉——water-tight 兜底，用户无需刷新页面
        if (hadChannels) {
          for (const cb of this.reconnectCbs) {
            try { cb() } catch { /* 回调异常不阻断重订阅 */ }
          }
        }
        this.readyResolve?.()
        break
      case 'ack': {
        const of = String(payload.of ?? '')
        if (of === 'sub' || of === 'unsub') {
          this.settleSub(String(payload.ch ?? ''), null)
        } else if (payload.clientMsgId) {
          const id = String(payload.clientMsgId)
          const waiter = this.msgWaiters.get(id)
          if (waiter) {
            clearTimeout(waiter.timer)
            this.msgWaiters.delete(id)
            waiter.resolve(payload)
          }
        } else if (of === 'read') {
          // read ack 按 refId（请求帧 id）回路由（服务端回带）
          const waiter = this.settleRead(String(payload.refId ?? ''))
          waiter?.resolve(payload)
        }
        break
      }
      case 'err': {
        const err: WsErrPayload = {
          code: String(payload.code ?? 'T1-PLT-4000'),
          message: String(payload.message ?? '未知错误'),
        }
        if (err.code === 'T1-PLT-4010') {
          // auth 帧被拒：令牌失效 → 静默刷新后立即重连（退避清零）
          this.backoffMs = 0
          void ensureAccessToken().then(() => this.reconnectNow())
          rejectAuth?.(err)
          return
        }
        // read 的 err 帧带 refId=请求帧 id → 精确路由给 markRead 调用方（失败走 HTTP 兜底）
        const readWaiter = this.settleRead(String(payload.refId ?? ''))
        if (readWaiter) {
          readWaiter.reject(err)
          break
        }
        // sub 的 err 帧带 refId=请求帧 id；本端 sub 未传 id，改用「最后一个该 ch 的等待者」关联
        // （同频道并发 sub 极少；msg 的 err 靠超时兜底，见 sendMsg）
        if (this.subWaiters.size > 0) {
          const ch = matchChannelFromErr(String(payload.message ?? ''))
          if (ch) this.settleSub(ch, err)
        }
        break
      }
      case 'event': {
        const kind = String(payload.kind ?? '')
        const cbs = this.eventSubs.get(kind)
        if (cbs) for (const cb of [...cbs]) cb((payload.payload ?? {}) as Record<string, unknown>)
        break
      }
      default:
        break
    }
  }

  private settleSub(ch: string, err: WsErrPayload | null): void {
    const waiters = this.subWaiters.get(ch)
    if (!waiters || waiters.length === 0) return
    const waiter = waiters.shift()
    if (waiters.length === 0) this.subWaiters.delete(ch)
    if (!waiter) return
    clearTimeout(waiter.timer)
    if (err) {
      this.channels.delete(ch) // 被拒频道不再随重连重放
      waiter.reject(err)
    } else {
      waiter.resolve({})
    }
  }

  // ==================== 断线重连（指数退避） ====================
  private scheduleReconnect(): void {
    if (this.closedByUser || this.reconnectTimer) return
    this.backoffMs = this.backoffMs === 0 ? 1000 : Math.min(this.backoffMs * 2, 30_000)
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.reconnectNow()
    }, this.backoffMs)
  }

  /** 取出并移除指定 refId 的 read 等待者（ack/err 共用；无则返回 undefined） */
  private settleRead(refId: string): AckWaiter | undefined {
    if (!refId) return undefined
    const waiter = this.readWaiters.get(refId)
    if (!waiter) return undefined
    clearTimeout(waiter.timer)
    this.readWaiters.delete(refId)
    return waiter
  }

  private reconnectNow(): void {
    if (this.closedByUser || this.ws) return
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    void this.open()
  }

  private startPing(): void {
    this.stopPing()
    this.pingTimer = setInterval(() => this.sendRaw({ type: 'ping' }), 30_000)
  }

  private stopPing(): void {
    if (this.pingTimer) clearInterval(this.pingTimer)
    this.pingTimer = null
  }

  private sendRaw(frame: unknown): void {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(frame))
  }

  // ==================== 对外 API ====================

  /** 订阅频道；ack resolve / err reject（如 T1-COL-4210 非成员） */
  async sub(ch: string): Promise<void> {
    await this.connect()
    const promise = new Promise<void>((resolve, reject) => {
      const waiter: AckWaiter = {
        resolve: () => resolve(),
        reject: reject as RejectFn,
        timer: setTimeout(() => {
          this.removeSubWaiter(ch, waiter)
          reject({ code: 'T1-WS-TIMEOUT', message: 'WS sub 应答超时' })
        }, ACK_TIMEOUT_MS),
      }
      const list = this.subWaiters.get(ch) ?? []
      list.push(waiter)
      this.subWaiters.set(ch, list)
    })
    this.channels.add(ch)
    this.sendRaw({ type: 'sub', payload: { ch } })
    return promise
  }

  private removeSubWaiter(ch: string, waiter: AckWaiter): void {
    const list = this.subWaiters.get(ch)
    if (!list) return
    const idx = list.indexOf(waiter)
    if (idx >= 0) list.splice(idx, 1)
    if (list.length === 0) this.subWaiters.delete(ch)
  }

  unsub(ch: string): void {
    this.channels.delete(ch)
    this.sendRaw({ type: 'unsub', payload: { ch } })
  }

  /** 订阅 S→C 事件帧（kind：message.created / defect.blocked_changed）；返回解绑函数 */
  onEvent(kind: string, cb: EventCb): () => void {
    const set = this.eventSubs.get(kind) ?? new Set<EventCb>()
    set.add(cb)
    this.eventSubs.set(kind, set)
    return () => set.delete(cb)
  }

  /**
   * 注册断线重连回调（IM 实时性批）：断线窗口内错失的 message/unread/notify 帧不可恢复，
   * 重订阅完成后触发——业务侧应失效缓存重拉（如 queryClient.invalidateQueries()）。
   * 仅在「重」连时触发（首连 ready 时无已订阅频道，天然不触发）；返回解绑函数。
   */
  onReconnect(cb: () => void): () => void {
    this.reconnectCbs.add(cb)
    return () => this.reconnectCbs.delete(cb)
  }

  /**
   * 发送会话消息：clientMsgId 由调用方生成（乐观插入需要先行持有）；
   * ack{clientMsgId,msgId} 后 resolve msgId；err（归档 4203 / 非成员 4210 / SRV-5000）reject。
   * 支持 attachments 数组透传（M2-INC-2 T-5）与 mentions 被@成员 id 数组（V16 R-10e）
   */
  async sendMsg(
    conversationId: string,
    body: string,
    clientMsgId: string,
    kind = 'text',
    attachments?: unknown[],
    mentions?: string[],
  ): Promise<string> {
    await this.connect()
    const promise = new Promise<string>((resolve, reject) => {
      const waiter: AckWaiter = {
        resolve: (p) => resolve(String(p.msgId ?? '')),
        reject: reject as RejectFn,
        timer: setTimeout(() => {
          if (this.msgWaiters.get(clientMsgId) === waiter) this.msgWaiters.delete(clientMsgId)
          reject({ code: 'T1-WS-TIMEOUT', message: 'WS 应答超时，请检查连接后重试' })
        }, ACK_TIMEOUT_MS),
      }
      this.msgWaiters.set(clientMsgId, waiter)
    })
    const payload: Record<string, unknown> = {
      clientMsgId,
      ch: `conv:${conversationId}`,
      kind,
      body,
    }
    if (attachments && attachments.length > 0) {
      payload.attachments = attachments
    }
    if (mentions && mentions.length > 0) {
      payload.mentions = mentions
    }
    this.sendRaw({ type: 'msg', payload })
    return promise
  }

  /**
   * 已读游标推进（INC-2 T-3 / R-10a 帧修正）：单向前进并重算未读。
   *
   * 统一帧 schema：<code>{v:1, id, type:'read', ch:'conv:{id}', lastReadMessageId?}</code>
   * ——lastReadMessageId 缺省=读到最新（R-10b：999999999 哨兵废除，服务端取会话 last_message_id）。
   * ack{of:'read',refId,unread} → resolve（带服务端权威 unread）；err/10s 超时 → reject
   * （调用方应走 HTTP POST /conversations/{id}/read 兜底，见 ImPage markReadNow）。
   */
  async markRead(conversationId: string, lastReadMessageId?: number): Promise<Record<string, unknown>> {
    await this.connect()
    const frameId = crypto.randomUUID()
    const promise = new Promise<Record<string, unknown>>((resolve, reject) => {
      const waiter: AckWaiter = {
        resolve: resolve as ResolveFn,
        reject: reject as RejectFn,
        timer: setTimeout(() => {
          if (this.readWaiters.get(frameId) === waiter) this.readWaiters.delete(frameId)
          reject({ code: 'T1-WS-TIMEOUT', message: 'WS read 应答超时' })
        }, ACK_TIMEOUT_MS),
      }
      this.readWaiters.set(frameId, waiter)
    })
    const frame: Record<string, unknown> = {
      v: 1,
      id: frameId,
      type: 'read',
      ch: `conv:${conversationId}`,
    }
    if (lastReadMessageId != null) {
      frame.lastReadMessageId = lastReadMessageId
    }
    this.sendRaw(frame)
    return promise
  }

  /** 主动关闭（登出时调用；关闭后不再自动重连） */
  close(): void {
    this.closedByUser = true
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    this.stopPing()
    for (const [refId, waiter] of [...this.readWaiters]) {
      clearTimeout(waiter.timer)
      this.readWaiters.delete(refId)
      waiter.reject({ code: 'T1-WS-CLOSED', message: 'WS 已关闭' })
    }
    this.ws?.close()
    this.ws = null
    this.readyPromise = null
    this.readyResolve = null
    this.channels.clear()
    this.eventSubs.clear()
  }
}

/** 服务端 sub err 的 message 形如「非会话成员 (conv:{id})」→ 提取频道名 */
function matchChannelFromErr(message: string): string | null {
  const m = message.match(/\((.+)\)/)
  return m ? m[1] : null
}

export const teamOneWs = new TeamOneWs()
