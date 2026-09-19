// W4 前端批 · 远端用户头像/名字（uuid 用户，store 本地 u1~u8 之外）
// 样式复用 components/ui.tsx Avatar 的类名体系（rounded-full 彩底白字 + 在线点），不新增主题色
import { useRemoteUsers, fallbackName, toBrief } from './users'
import type { RemoteUserBrief } from './users'

/** 与 ui.Avatar 同视觉的远端用户头像：名字末字符 + 稳定色 */
export function RemoteAvatar({ userId, users, size = 28 }: { userId: string; users?: Map<string, RemoteUserBrief>; size?: number }) {
  const { data } = useRemoteUsers()
  const map = users ?? toBrief(data)
  const u = map.get(userId)
  const name = u?.name ?? fallbackName(userId)
  return (
    <span
      title={`${name}${u?.title ? ` · ${u.title}` : ''}`}
      className="relative inline-flex shrink-0 items-center justify-center rounded-full font-semibold text-white select-none"
      style={{ width: size, height: size, fontSize: size * 0.42, background: u?.color ?? '#9ca0a8' }}
    >
      {name.slice(-1)}
    </span>
  )
}

/** 远端用户名（兜底「成员 xxx」） */
export function remoteName(users: Map<string, RemoteUserBrief> | undefined, userId: string): string {
  if (!users) return fallbackName(userId)
  return users.get(userId)?.name ?? fallbackName(userId)
}
