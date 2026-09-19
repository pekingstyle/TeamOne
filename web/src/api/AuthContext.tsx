// W4 前端批 · 鉴权上下文 + 登录视图
// - AuthProvider：me 状态 / login / logout；令牌持久于 localStorage（client.ts tokenStore）
// - 刷新页面：先读本地令牌 → GET /auth/me 拉当前用户；401 走 api 层静默刷新；失败回登录页
// - LoginView：Fancy 风格登录卡，复用现有 token 体系类名（bg-canvas/border-line/brand…），不新增主题色
import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { ArrowRight, ShieldCheck } from 'lucide-react'
import { ApiError, authApi, onAuthLost } from './client'
import { teamOneWs } from './ws'
import type { RemoteUser } from './client'

interface AuthState {
  user: RemoteUser | null
  /** 恢复登录态中（刷新页面后的静默 me/refresh） */
  initializing: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  /** 重拉 /auth/me 并更新本地用户态（R-11：改昵称/主题后即时刷新，无需重新登录） */
  refreshUser: () => Promise<void>
}

const AuthContext = createContext<AuthState>({
  user: null,
  initializing: true,
  login: async () => {},
  logout: async () => {},
  refreshUser: async () => {},
})

export function useAuth(): AuthState {
  return useContext(AuthContext)
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<RemoteUser | null>(null)
  const [initializing, setInitializing] = useState(true)

  // 刷新页面恢复登录态：有本地令牌即尝试 me（401 由 api 层静默刷新重放）
  useEffect(() => {
    const hasToken = !!(localStorage.getItem('teamone.access') || localStorage.getItem('teamone.refresh'))
    if (!hasToken) {
      setInitializing(false)
      return
    }
    let alive = true
    authApi
      .me()
      .then((me) => alive && setUser(me))
      .catch(() => alive && setUser(null))
      .finally(() => alive && setInitializing(false))
    return () => { alive = false }
  }, [])

  // 任意 API 401 刷新失败 → 广播登出 → 关 WS 单例（防旧订阅泄入新账号）+ 回登录页
  useEffect(() => onAuthLost(() => {
    teamOneWs.close() // MF-2：清 channels/eventSubs 并断开，换账号后 connect() 重新握手
    setUser(null)
  }), [])

  const value = useMemo<AuthState>(() => ({
    user,
    initializing,
    async login(username, password) {
      const payload = await authApi.login(username, password)
      setUser(payload.user)
    },
    async logout() {
      teamOneWs.close() // MF-2：登出即关 WS 单例（emitAuthLost 回调兜底同调，幂等）
      await authApi.logout()
      setUser(null)
    },
    async refreshUser() {
      // R-11 个人设置保存后调用：401 由 api 层静默刷新重放，失败保持现状不登出
      try {
        const me = await authApi.me()
        setUser(me)
      } catch { /* 网络抖动时保留旧用户态 */ }
    },
  }), [user, initializing])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// ---------------- 登录卡（Fancy 风格，全屏居中） ----------------
export function LoginView() {
  const { login } = useAuth()
  // V-20 安全加固：生产化移除前端默认预填账号，避免凭据泄漏与非授权尝试
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    if (!username.trim() || !password || busy) return
    setBusy(true)
    setError(null)
    try {
      await login(username.trim(), password)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '网络异常，请稍后重试')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex h-full items-center justify-center bg-page p-4">
      <div className="w-[400px] max-w-full">
        {/* 品牌头 */}
        <div className="mb-5 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-brand to-cat-teal text-base font-black text-white">T1</div>
          <div>
            <div className="text-lg font-bold tracking-wide text-txt-hi">TeamOne</div>
            <div className="text-xs text-txt-low">一站式研发协同平台 · 平台本体研发</div>
          </div>
        </div>

        <form
          className="rounded-card border border-line bg-canvas p-6 shadow-xl"
          onSubmit={(e) => { e.preventDefault(); submit() }}
        >
          <div className="flex items-center gap-2">
            <ShieldCheck size={16} className="text-brand" />
            <h1 className="text-base font-bold text-txt-hi">登录 TeamOne</h1>
          </div>
          <p className="mt-1 text-xs text-txt-low">企业级身份认证 · JWT 双令牌与 HttpOnly 安全防护</p>

          <label className="mt-4 block text-xs font-medium text-txt-mid">
            用户名
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoFocus
              placeholder="请输入用户名"
              className="mt-1 w-full rounded-input border border-line bg-canvas px-2.5 py-2 text-sm text-txt-hi outline-none transition-colors focus:border-brand"
            />
          </label>
          <label className="mt-3 block text-xs font-medium text-txt-mid">
            密码
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              className="mt-1 w-full rounded-input border border-line bg-canvas px-2.5 py-2 text-sm text-txt-hi outline-none transition-colors focus:border-brand"
            />
          </label>

          {error && (
            <div className="mt-3 rounded-md bg-bad-bg px-2.5 py-2 text-xs leading-4 text-bad-deep" role="alert">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={busy || !username.trim() || !password}
            className="mt-4 flex w-full cursor-pointer items-center justify-center gap-1.5 rounded-md bg-brand px-3 py-2 text-sm font-semibold text-white transition-colors hover:bg-brand-deep disabled:cursor-not-allowed disabled:opacity-45"
          >
            {busy ? '登录中…' : <>登 录 <ArrowRight size={14} /></>}
          </button>

          {/* V-20 安全加固：移除开发明文密码提示，替换为生产级安全认证标识 */}
          <div className="mt-4 flex items-center justify-center gap-1.5 border-t border-line pt-3 text-[11px] leading-4 text-txt-low">
            <ShieldCheck size={13} className="text-ok" />
            <span>企业级统一身份认证 · 双令牌安全通道保护</span>
          </div>
        </form>
      </div>
    </div>
  )
}
