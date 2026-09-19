// R-11 个人设置弹窗（B6 批）：昵称 / 主题偏好（light/dark/system，与 setTheme 联动并持久化）/
// 修改密码（旧密校验 + 新密 ≥8 位；成功后其他设备刷新令牌作废）。
// 替换原「个人设置（原型演示）」死菜单（docs/v2/12 §1 R-11）。
import { useEffect, useState } from 'react'
import { Check, KeyRound, Loader2, X } from 'lucide-react'
import { Avatar, Btn, Pill } from '../../components/ui'
import { useAuth } from '../../api/AuthContext'
import { ApiError } from '../../api/client'
import { meApi } from '../../api/queries'
import { CURRENT_USER_ID, setTheme, userById } from '../../data/store'

/**
 * 后端主题偏好 → setTheme 实参（store 只认 light/dark；system 解析为系统当前配色）。
 * 导出供 App.tsx 登录/刷新后的主题应用复用（后端值优先口径唯一）。
 */
export function resolveTheme(pref: 'light' | 'dark' | 'system'): 'light' | 'dark' {
  if (pref === 'system') {
    return typeof window !== 'undefined'
      && window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return pref
}

const THEME_OPTIONS: { value: 'light' | 'dark' | 'system'; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
]

export default function PersonalSettingsDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { user, refreshUser } = useAuth()
  // ---- 资料区草稿（以后端登录态为初值；open 时重置） ----
  const [displayName, setDisplayName] = useState('')
  const [theme, setThemePref] = useState<'light' | 'dark' | 'system'>('system')
  // ---- 改密区草稿 ----
  const [oldPwd, setOldPwd] = useState('')
  const [newPwd, setNewPwd] = useState('')
  const [confirmPwd, setConfirmPwd] = useState('')
  const [profileBusy, setProfileBusy] = useState(false)
  const [pwdBusy, setPwdBusy] = useState(false)
  const [profileMsg, setProfileMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [pwdMsg, setPwdMsg] = useState<{ ok: boolean; text: string } | null>(null)

  // 每次打开以登录态重置草稿与提示
  useEffect(() => {
    if (!open) return
    setDisplayName(user?.displayName ?? '')
    setThemePref(user?.themePreference ?? 'system')
    setOldPwd(''); setNewPwd(''); setConfirmPwd('')
    setProfileMsg(null); setPwdMsg(null)
  }, [open, user])

  if (!open) return null

  /** 保存资料：PUT /me → AuthContext 即时刷新 + store 用户名/主题同步（侧栏头像区即时可见） */
  const saveProfile = async () => {
    if (profileBusy) return
    const name = displayName.trim()
    if (!name) {
      setProfileMsg({ ok: false, text: '昵称不能为空' })
      return
    }
    setProfileBusy(true)
    setProfileMsg(null)
    try {
      await meApi.update({ displayName: name, themePreference: theme })
      // 侧栏/头像区展示名同步（store 用户对象原地改 + bump，与 setTheme 同款机制）
      const storeUser = userById(CURRENT_USER_ID)
      if (storeUser) storeUser.name = name
      setTheme(resolveTheme(theme)) // 主题即时生效并写入 store
      await refreshUser() // AuthContext 用户态即时刷新（登录态内字段以服务端为准）
      setProfileMsg({ ok: true, text: '已保存' })
    } catch (e) {
      setProfileMsg({ ok: false, text: e instanceof ApiError ? e.message : '保存失败，请稍后重试' })
    } finally {
      setProfileBusy(false)
    }
  }

  /** 修改密码：旧密校验错误内联提示；成功后其他设备下线（当前会话保持） */
  const changePassword = async () => {
    if (pwdBusy) return
    setPwdMsg(null)
    if (newPwd.length < 8) {
      setPwdMsg({ ok: false, text: '新密码强度不足（至少 8 位）' })
      return
    }
    if (newPwd !== confirmPwd) {
      setPwdMsg({ ok: false, text: '两次输入的新密码不一致' })
      return
    }
    setPwdBusy(true)
    try {
      const res = await meApi.changePassword({ oldPassword: oldPwd, newPassword: newPwd })
      setPwdMsg({ ok: true, text: res.message })
      setOldPwd(''); setNewPwd(''); setConfirmPwd('')
    } catch (e) {
      setPwdMsg({ ok: false, text: e instanceof ApiError ? e.message : '修改失败，请稍后重试' })
    } finally {
      setPwdBusy(false)
    }
  }

  const roleZh = user?.platformRole === 'OWNER' || user?.platformRole === 'ADMIN' ? '管理员' : '成员'

  return (
    // 全屏遮罩弹窗（z-60 高于通知面板）
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div
        className="max-h-[88vh] w-[440px] max-w-full overflow-y-auto rounded-card border border-line bg-canvas p-5 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* 头部：头像 + 用户名 + 角色（⑥h 任务①：头像解析真实登录用户 id，不再映射 store 原型 u1） */}
        <div className="flex items-center gap-3">
          {user ? <Avatar userId={user.id} size={40} /> : null}
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-bold text-txt-hi">{user?.displayName ?? '个人设置'}</div>
            <div className="truncate text-xs text-txt-mid">@{user?.username ?? '-'}</div>
          </div>
          <Pill tone={roleZh === '成员' ? 'neutral' : 'brand'}>{roleZh}</Pill>
          <button
            type="button"
            onClick={onClose}
            className="ml-1 cursor-pointer rounded p-1 text-txt-low hover:bg-ink-700 hover:text-txt-hi"
            aria-label="关闭"
          >
            <X size={16} />
          </button>
        </div>

        {/* ---- 资料区 ---- */}
        <div className="mt-4 border-t border-line pt-4">
          <div className="text-xs font-semibold tracking-wide text-txt-low">个人资料</div>
          <label className="mt-2.5 block text-xs font-medium text-txt-mid">
            昵称
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              maxLength={64}
              className="mt-1 w-full rounded-input border border-line bg-page px-2.5 py-2 text-sm text-txt-hi outline-none focus:border-brand"
            />
          </label>
          <div className="mt-3 text-xs font-medium text-txt-mid">主题偏好<span className="ml-1 text-txt-low">（换设备保持）</span></div>
          <div className="mt-1.5 flex gap-1.5">
            {THEME_OPTIONS.map((o) => (
              <button
                key={o.value}
                type="button"
                onClick={() => setThemePref(o.value)}
                className={`cursor-pointer rounded-input border px-3 py-1.5 text-xs transition-colors ${
                  theme === o.value
                    ? 'border-brand bg-brand-bg font-semibold text-brand-deep'
                    : 'border-line text-txt-mid hover:bg-ink-700 hover:text-txt-hi'
                }`}
              >
                {o.label}
              </button>
            ))}
          </div>
          {profileMsg && (
            <div className={`mt-2.5 flex items-center gap-1 text-xs ${profileMsg.ok ? 'text-ok-deep' : 'text-bad-deep'}`} role={profileMsg.ok ? 'status' : 'alert'}>
              {profileMsg.ok ? <Check size={13} /> : null} {profileMsg.text}
            </div>
          )}
          <div className="mt-3 flex justify-end">
            <Btn onClick={() => void saveProfile()} disabled={profileBusy}>
              {profileBusy ? <Loader2 size={14} className="animate-spin" /> : null} 保存资料
            </Btn>
          </div>
        </div>

        {/* ---- 改密区 ---- */}
        <div className="mt-2 border-t border-line pt-4">
          <div className="flex items-center gap-1.5 text-xs font-semibold tracking-wide text-txt-low">
            <KeyRound size={13} /> 修改密码<span className="font-normal text-txt-low">（成功后其他设备将退出登录）</span>
          </div>
          <label className="mt-2.5 block text-xs font-medium text-txt-mid">
            旧密码
            <input
              type="password"
              value={oldPwd}
              onChange={(e) => setOldPwd(e.target.value)}
              autoComplete="current-password"
              className="mt-1 w-full rounded-input border border-line bg-page px-2.5 py-2 text-sm text-txt-hi outline-none focus:border-brand"
            />
          </label>
          <div className="mt-2.5 grid grid-cols-2 gap-2">
            <label className="block text-xs font-medium text-txt-mid">
              新密码（≥8 位）
              <input
                type="password"
                value={newPwd}
                onChange={(e) => setNewPwd(e.target.value)}
                autoComplete="new-password"
                className="mt-1 w-full rounded-input border border-line bg-page px-2.5 py-2 text-sm text-txt-hi outline-none focus:border-brand"
              />
            </label>
            <label className="block text-xs font-medium text-txt-mid">
              确认新密码
              <input
                type="password"
                value={confirmPwd}
                onChange={(e) => setConfirmPwd(e.target.value)}
                autoComplete="new-password"
                className="mt-1 w-full rounded-input border border-line bg-page px-2.5 py-2 text-sm text-txt-hi outline-none focus:border-brand"
              />
            </label>
          </div>
          {pwdMsg && (
            <div className={`mt-2.5 flex items-center gap-1 text-xs ${pwdMsg.ok ? 'text-ok-deep' : 'text-bad-deep'}`} role={pwdMsg.ok ? 'status' : 'alert'}>
              {pwdMsg.ok ? <Check size={13} /> : null} {pwdMsg.text}
            </div>
          )}
          <div className="mt-3 flex justify-end">
            <Btn
              onClick={() => void changePassword()}
              disabled={pwdBusy || !oldPwd || !newPwd || !confirmPwd}
            >
              {pwdBusy ? <Loader2 size={14} className="animate-spin" /> : null} 修改密码
            </Btn>
          </div>
        </div>
      </div>
    </div>
  )
}
