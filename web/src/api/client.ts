// W4 前端批 · fetch 封装（字段均以 2026-09-11 curl 实测为准）：
// - Bearer 注入（localStorage teamone.access / teamone.refresh，登录态持久）
// - 401 → refreshToken 静默刷新（单飞）重放一次；刷新失败清空令牌并广播登出
// - 错误信封 {code,message,details} → ApiError；409 details ["currentVersion=N"] → currentVersion
// 实测响应样例：
//   401 {code:"T1-PLT-4011",message:"用户名或密码错误",details:[]}
//   409 {code:"T1-PLT-4091",message:"版本冲突，请刷新后重试",details:["currentVersion=2"]}
//   422 {code:"T1-PRD-4230",message:"发布被致命/严重缺陷阻塞",details:["D-96 致命 admin"]}
export const ACCESS_KEY = 'teamone.access'
export const REFRESH_KEY = 'teamone.refresh'

export class ApiError extends Error {
  status: number
  code: string
  details: string[]
  /** 409 时从 details 提取的服务端最新 version */
  currentVersion?: number

  constructor(status: number, code: string, message: string, details: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = Array.isArray(details) ? details.map(String) : []
    const cur = this.details.find((d) => d.startsWith('currentVersion='))
    if (cur) this.currentVersion = Number(cur.slice('currentVersion='.length))
  }
}

export const tokenStore = {
  access: (): string | null => localStorage.getItem(ACCESS_KEY),
  refresh: (): string | null => localStorage.getItem(REFRESH_KEY),
  save(accessToken: string, refreshToken: string): void {
    localStorage.setItem(ACCESS_KEY, accessToken)
    localStorage.setItem(REFRESH_KEY, refreshToken)
  },
  clear(): void {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
}

/** 刷新失败/主动登出时广播（AuthProvider 监听切回登录页） */
const logoutListeners = new Set<() => void>()
export function onAuthLost(cb: () => void): () => void {
  logoutListeners.add(cb)
  return () => logoutListeners.delete(cb)
}
function emitAuthLost(): void {
  tokenStore.clear()
  logoutListeners.forEach((cb) => cb())
}

interface RefreshPayload {
  accessToken: string
  refreshToken: string
  user?: RemoteUser
}

/** 后端用户投影（GET /api/v1/auth/me、/users 实测字段；themePreference 为 R-11 补列） */
export interface RemoteUser {
  id: string
  username: string
  displayName: string
  title?: string
  platformRole: string
  dailyCapacityHours?: number
  /** 界面主题偏好（light/dark/system；缺省视同 system，登录/刷新后以此应用主题） */
  themePreference?: 'light' | 'dark' | 'system' | null
}

// ---- 静默刷新单飞：并发 401 只发一次 refresh ----
let refreshInflight: Promise<boolean> | null = null

async function refreshTokens(): Promise<boolean> {
  const refreshToken = tokenStore.refresh()
  if (!refreshToken) return false
  try {
    // V-20 安全加固：开启 credentials: 'same-origin' 支持 HttpOnly Cookie 自动携带
    const res = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
      credentials: 'same-origin',
    })
    if (!res.ok) {
      emitAuthLost()
      return false
    }
    const payload = (await res.json()) as RefreshPayload
    tokenStore.save(payload.accessToken, payload.refreshToken)
    return true
  } catch {
    return false
  }
}

/** 供 WS 层复用：确保有可用令牌（过期则静默刷新） */
export async function ensureAccessToken(): Promise<string | null> {
  if (tokenStore.access()) return tokenStore.access()
  if (await refreshTokens()) return tokenStore.access()
  return null
}

export interface ApiOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  headers?: Record<string, string>
  /** 内部使用：401 后已重放一次则不再刷新，避免递归 */
  _replayed?: boolean
}

/** 统一请求入口：Bearer 注入 + 401 静默刷新重放 + 错误信封解析 + SameOrigin 凭证携带 */
export async function api<T = unknown>(path: string, opts: ApiOptions = {}): Promise<T> {
  const headers: Record<string, string> = { ...opts.headers }
  const access = tokenStore.access()
  if (access) headers.Authorization = `Bearer ${access}`
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'

  // V-20 安全加固：开启同源凭据模式，支持 httpOnly Cookie 在前后端交互时静默生效
  const res = await fetch(path, {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    credentials: 'same-origin',
  })

  if (res.status === 401 && !opts._replayed && tokenStore.refresh()) {
    refreshInflight ??= refreshTokens().finally(() => { refreshInflight = null })
    const ok = await refreshInflight
    if (ok) return api<T>(path, { ...opts, _replayed: true })
    const err = await parseError(res)
    throw err
  }

  if (!res.ok) throw await parseError(res)

  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

async function parseError(res: Response): Promise<ApiError> {
  let code = `HTTP-${res.status}`
  let message = res.statusText || `请求失败（${res.status}）`
  let details: unknown = []
  try {
    const envelope = (await res.json()) as { code?: string; message?: string; details?: unknown }
    if (envelope.code) code = envelope.code
    if (envelope.message) message = envelope.message
    details = envelope.details ?? []
  } catch {
    // 非 JSON 错误体（网关/代理层），保留 HTTP 状态描述
  }
  return new ApiError(res.status, code, message, details)
}

// ============================================================
// 业务 API（字段为 curl 实测投影）
// ============================================================

/** 登录响应（POST /api/v1/auth/login 200 实测） */
export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: RemoteUser
}

export const authApi = {
  async login(username: string, password: string): Promise<LoginResponse> {
    const payload = await api<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: { username, password },
      _replayed: true, // 登录请求不做 401 刷新
    })
    tokenStore.save(payload.accessToken, payload.refreshToken)
    return payload
  },
  /** 获取当前登录用户画像与角色权限 */
  me(): Promise<RemoteUser> {
    return api<RemoteUser>('/api/v1/auth/me')
  },
  /**
   * 登出流程：通知后端吊销 Refresh Token 并清除 HttpOnly Cookie，随后清空本地凭证与 WS 连接
   */
  async logout(): Promise<void> {
    try {
      const refreshToken = tokenStore.refresh()
      // V-20 安全加固：主动向后端发起登出请求，清除服务端会话与 HttpOnly Cookie
      await api('/api/v1/auth/logout', {
        method: 'POST',
        body: { refreshToken: refreshToken ?? '' },
        _replayed: true,
      })
    } catch {
      // 忽略服务端网络异常或 401 错误，确保前端本地状态彻底清除
    } finally {
      tokenStore.clear()
      emitAuthLost()
    }
  },
}
