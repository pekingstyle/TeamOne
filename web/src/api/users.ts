// W4 前端批 · 远端用户投影
// - useRemoteUsers → GET /api/v1/users（需 platform:user:list，普通用户 403）：完整字段，
//   供需要角色/部门等完整画像的消费方（如 ImPage 成员选择）使用；
// - useUserBriefs  → GET /api/v1/users/briefs（UT-28，仅需登录态）：名字映射类用途
//   （冲突当事人 humanize、姓名列）一律走 briefs，普通用户也能看到姓名而不是 UUID 前缀。
// 后端工作项的 assigneeId/reporterId 是 uuid，store.ts 本地用户（u1…u8）对不上；
// 页面用这里的数据渲染真实姓名与头像；名字用于 select 选项与文本列。
import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { api } from './client'

/** GET /api/v1/users/briefs 行投影（仅需登录态，UT-28 契约） */
export interface UserBriefRow {
  id: string
  username: string
  displayName: string
  title?: string
}

/** GET /api/v1/users 完整行（需 platform:user:list 权限，普通用户 403） */
export interface RemoteUserRow extends UserBriefRow {
  platformRole: string
  departmentId?: string
  dailyCapacityHours?: number
  status?: string
}

export interface RemoteUserBrief {
  id: string
  name: string
  title: string
  color: string
}

/** 名字 → 稳定颜色（沿用 store 用户色的暖冷分布，不新增主题色） */
const PALETTE = ['#7b68ee', '#ec4899', '#16c0a4', '#ff9500', '#0091ff', '#f94646', '#22c55e', '#ffd66b']
function colorOf(id: string): string {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0
  return PALETTE[h % PALETTE.length]
}

export function useRemoteUsers() {
  return useQuery({
    queryKey: ['users'],
    queryFn: () => api<RemoteUserRow[]>('/api/v1/users'),
    staleTime: 5 * 60_000,
  })
}

/**
 * 名字映射数据源（UT-28）：GET /api/v1/users/briefs，仅需登录态，普通用户可访问。
 * 名字映射类用途（humanize UUID→姓名、当事人 chip 等）统一走这里。
 */
export function useUserBriefs() {
  return useQuery({
    queryKey: ['user-briefs'],
    queryFn: () => api<UserBriefRow[]>('/api/v1/users/briefs'),
    staleTime: 5 * 60_000,
  })
}

/** 行 → 名字/头色映射（briefs 行与 /users 完整行都满足 UserBriefRow 结构，两者通用） */
export function toBrief(rows: UserBriefRow[] | undefined): Map<string, RemoteUserBrief> {
  const map = new Map<string, RemoteUserBrief>()
  for (const r of rows ?? []) {
    map.set(r.id, { id: r.id, name: r.displayName || r.username, title: r.title ?? r.username, color: colorOf(r.id) })
  }
  return map
}

/**
 * id → 用户 brief 查询函数（UT-34）：供 MR 评审人/作者、评论作者等真实 UUID 的姓名解析。
 * briefs 未命中（本地 mock id）返回 undefined，调用方自行兜底。
 */
export function useBriefMap(): (id: string) => RemoteUserBrief | undefined {
  const { data } = useUserBriefs()
  const briefs = useMemo(() => toBrief(data), [data])
  return (id: string) => briefs.get(id)
}

/**
 * 名字兜底：uuid 前 4 位（防御 id 为空导致的 slice 崩溃）。
 * dogfooding 切换：移除 store 原型用户（u1 陈墨 等）映射——远端 id 一律 uuid，原型映射已无意义。
 * @param id 用户唯一标识（可能为 undefined / null）
 * @returns 友好的用户显示名称
 */
export function fallbackName(id?: string | null): string {
  if (!id) return '未指派'
  return `成员 ${id.slice(0, 4)}`
}
