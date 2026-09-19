import React, { useState } from 'react'
import type { Department, ResourceAction, User } from '../../data/types'
import {
  actionText, componentById, components, departmentById, departments, goalById,
  productById, products, resourcePermissions,
  useStore, userById, users, fmt,
} from '../../data/store'
import { Avatar, Btn, Card, CardHeader, PageHeader, Pill } from '../../components/ui'
import type { Nav, PageProps } from '../../nav'
import {
  adminUsersApi,
  migrationApi,
  tokensApi,
  useAdminUsers,
  useAuditLogs,
  useMyTokens,
  type RemoteUserSummary,
  type RemoteValidationReport,
} from '../../api/queries'
import {
  AlertTriangle,
  CheckCircle2,
  Copy,
  Download,
  FileSpreadsheet,
  Key,
  Plus,
  RefreshCw,
  Shield,
  ShieldCheck,
  Trash2,
  UploadCloud,
  UserPlus,
  Users,
  X,
} from 'lucide-react'

const ALL_ACTIONS: ResourceAction[] = ['view', 'edit', 'approve', 'release', 'manage']
const actionCls: Record<ResourceAction, string> = {
  view: 'text-txt-low', edit: 'text-cat-blue', approve: 'text-warn-deep', release: 'text-cat-purple', manage: 'text-bad',
}
const platformRoleText: Record<string, string> = { super_admin: '超级管理员', org_admin: '组织管理员', member: '' }
const goalBadge = 'cursor-pointer rounded bg-brand-bg px-1.5 py-0.5 text-[11px] font-semibold text-cat-purple hover:bg-brand/20'
const prodBadge = 'rounded bg-info-bg px-1.5 py-0.5 text-[11px] font-semibold text-cat-blue'
const compBadge = 'rounded bg-info-bg px-1.5 py-0.5 text-[11px] font-semibold text-cat-teal'

interface MatrixCol { type: 'product' | 'component'; id: string; name: string; sub: string }
const MATRIX_COLS: MatrixCol[] = [
  ...products.map((p) => ({ type: 'product' as const, id: p.id, name: p.name, sub: `产品 ${p.key}` })),
  ...components.map((c) => ({ type: 'component' as const, id: c.id, name: c.name, sub: `组件 · ${productById(c.productId)?.key ?? ''}` })),
]

/** 简化版四步判定（§6.1 按序短路，默认拒绝）。 */
function effective(u: User, col: MatrixCol): { actions: ResourceAction[]; source: string } {
  if (u.platformRole === 'super_admin' || u.platformRole === 'org_admin') {
    return { actions: ALL_ACTIONS, source: `平台角色 ${platformRoleText[u.platformRole]} → 全部动作` }
  }
  const deptId = col.type === 'product'
    ? productById(col.id)?.departmentId
    : productById(componentById(col.id)?.productId ?? '')?.departmentId
  if (deptId && departments.find((d) => d.id === deptId)?.leadId === u.id) {
    return { actions: ALL_ACTIONS, source: `${departmentById(deptId)?.name}负责人 → 全部动作` }
  }
  if (col.type === 'component' && componentById(col.id)?.leadId === u.id) {
    return { actions: ['view', 'edit', 'approve'], source: '组件负责人 → 查看/编辑/审批' }
  }
  const grants = resourcePermissions.filter((g) => g.subjectType === 'user' && g.subjectId === u.id && g.resourceType === col.type && g.resourceId === col.id)
  if (grants.length > 0) {
    return { actions: [...new Set(grants.flatMap((g) => g.actions))], source: `ACL 授权（${grants.length} 条）→ 动作并集` }
  }
  return { actions: [], source: '默认拒绝（无平台角色 / 部门角色 / ACL）' }
}

function DeptCard({ dept, nav }: { dept: Department; nav: Nav }) {
  const lead = userById(dept.leadId)
  return (
    <Card>
      <CardHeader
        title={<span className="flex items-center gap-2">{dept.name}<Pill tone="neutral">{dept.memberIds.length} 人</Pill></span>}
        extra={lead && <span className="flex items-center gap-1.5 text-xs text-txt-mid"><Avatar userId={lead.id} size={20} />负责人 {lead.name}</span>}
      />
      <div className="space-y-3 px-4 py-3">
        <div className="flex">
          {dept.memberIds.map((id, i) => (
            <span key={id} className={i === 0 ? '' : '-ml-1.5'}><Avatar userId={id} size={24} /></span>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="w-10 shrink-0 text-[11px] font-semibold text-txt-low">产品</span>
          {dept.productIds.length === 0 && <span className="text-[11px] text-txt-low">—</span>}
          {dept.productIds.map((pid) => <span key={pid} className={prodBadge}>{productById(pid)?.name ?? pid}</span>)}
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="w-10 shrink-0 text-[11px] font-semibold text-txt-low">组件</span>
          {dept.componentIds.length === 0 && <span className="text-[11px] text-txt-low">—</span>}
          {dept.componentIds.map((cid) => <span key={cid} className={compBadge}>{componentById(cid)?.name ?? cid}</span>)}
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="w-10 shrink-0 text-[11px] font-semibold text-txt-low">目标</span>
          {dept.goalIds.length === 0 && <span className="text-[11px] text-txt-low">—</span>}
          {dept.goalIds.map((gid) => (
            <button key={gid} type="button" onClick={() => nav.go('goals', gid)} className={goalBadge} title="查看战略目标">
              {goalById(gid)?.key ?? gid} {goalById(gid)?.name}
            </button>
          ))}
        </div>
      </div>
    </Card>
  )
}

export default function TeamPage({ nav }: PageProps) {
  useStore()

  // ---- 当前活跃 Tab ----
  type ActiveTab = 'members' | 'tokens' | 'migration' | 'audit' | 'departments' | 'matrix'
  const [activeTab, setActiveTab] = useState<ActiveTab>('members')

  // ==================== 1. 成员管理模块状态 ====================
  const [userQuery, setUserQuery] = useState('')
  const [userRoleFilter, setUserRoleFilter] = useState<string>('')
  const { data: adminUsersData, refetch: refetchUsers, isLoading: usersLoading } = useAdminUsers(userQuery, undefined, userRoleFilter || undefined)
  const [showCreateUserModal, setShowCreateUserModal] = useState(false)
  const [newUsername, setNewUsername] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [newDisplayName, setNewDisplayName] = useState('')
  const [newTitle, setNewTitle] = useState('')
  const [newEmail, setNewEmail] = useState('')
  const [newRole, setNewRole] = useState<'OWNER' | 'ADMIN' | 'MEMBER'>('MEMBER')
  const [newCapacity, setNewCapacity] = useState(8)
  const [userCreating, setUserCreating] = useState(false)

  // 密码重置弹窗
  const [resettingUser, setResettingUser] = useState<RemoteUserSummary | null>(null)
  const [resetPasswordVal, setResetPasswordVal] = useState('')
  const [resetting, setResetting] = useState(false)

  // ==================== 2. PAT 访问令牌状态 ====================
  const { data: tokensData, refetch: refetchTokens, isLoading: tokensLoading } = useMyTokens()
  const [showCreatePatModal, setShowCreatePatModal] = useState(false)
  const [patName, setPatName] = useState('')
  const [patDays, setPatDays] = useState(30)
  const [patScopes, setPatScopes] = useState('all')
  const [patCreating, setPatCreating] = useState(false)
  // 创建成功后单次展示的 Raw Token 弹窗
  const [createdPatRaw, setCreatedPatRaw] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  // ==================== 3. 数据迁移与质量门禁状态 ====================
  const [selectedProductId, setSelectedProductId] = useState<string>(products[0]?.id ?? '')
  const [csvInput, setCsvInput] = useState<string>('')
  const [validating, setValidating] = useState(false)
  const [validationReport, setValidationReport] = useState<RemoteValidationReport | null>(null)
  const [skipErrors, setSkipErrors] = useState(false)
  const [importing, setImporting] = useState(false)
  const [importResult, setImportResult] = useState<{ importedCount: number; skippedCount: number; createdKeys: string[] } | null>(null)

  // ==================== 4. 审计日志状态 ====================
  const [auditPage, setAuditPage] = useState(0)
  const { data: auditData, isLoading: auditLoading } = useAuditLogs(auditPage, 20)

  // ---- 处理创建用户 ----
  const handleCreateUser = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!newUsername.trim() || !newPassword || !newDisplayName.trim()) return
    setUserCreating(true)
    try {
      await adminUsersApi.create({
        username: newUsername.trim(),
        password: newPassword,
        displayName: newDisplayName.trim(),
        title: newTitle.trim() || undefined,
        email: newEmail.trim() || undefined,
        platformRole: newRole,
        dailyCapacityHours: newCapacity,
      })
      setShowCreateUserModal(false)
      setNewUsername('')
      setNewPassword('')
      setNewDisplayName('')
      setNewTitle('')
      setNewEmail('')
      void refetchUsers()
    } catch (err) {
      alert('创建用户失败: ' + (err as Error).message)
    } finally {
      setUserCreating(false)
    }
  }

  // ---- 切换用户启停状态 ----
  const handleToggleUserStatus = async (u: RemoteUserSummary) => {
    const nextStatus = u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    const confirmMsg = nextStatus === 'DISABLED' ? `确定要停用成员 ${u.displayName} 吗？` : `确定重新启用成员 ${u.displayName} 吗？`
    if (!window.confirm(confirmMsg)) return
    try {
      await adminUsersApi.updateStatus(u.id, nextStatus)
      void refetchUsers()
    } catch (err) {
      alert('状态更新失败: ' + (err as Error).message)
    }
  }

  // ---- 重置密码 ----
  const handleResetPassword = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!resettingUser || !resetPasswordVal) return
    setResetting(true)
    try {
      await adminUsersApi.resetPassword(resettingUser.id, resetPasswordVal)
      alert(`已成功将 ${resettingUser.displayName} 的密码重置！`)
      setResettingUser(null)
      setResetPasswordVal('')
    } catch (err) {
      alert('重置密码失败: ' + (err as Error).message)
    } finally {
      setResetting(false)
    }
  }

  // ---- 处理生成 PAT ----
  const handleCreatePat = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!patName.trim()) return
    setPatCreating(true)
    try {
      const res = await tokensApi.create(patName.trim(), patScopes, patDays > 0 ? patDays : undefined)
      setShowCreatePatModal(false)
      setPatName('')
      setCreatedPatRaw(res.rawToken)
      void refetchTokens()
    } catch (err) {
      alert('生成令牌失败: ' + (err as Error).message)
    } finally {
      setPatCreating(false)
    }
  }

  // ---- 撤销 PAT ----
  const handleRevokePat = async (id: string, name: string) => {
    if (!window.confirm(`确定要吊销令牌 "${name}" 吗？该令牌将立即失效。`)) return
    try {
      await tokensApi.revoke(id)
      void refetchTokens()
    } catch (err) {
      alert('吊销令牌失败: ' + (err as Error).message)
    }
  }

  // ---- 数据迁移：文件读取 ----
  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (evt) => {
      const text = evt.target?.result as string
      setCsvInput(text)
      setValidationReport(null)
      setImportResult(null)
    }
    reader.readAsText(file)
  }

  // ---- 数据迁移：质量预检 ----
  const handleValidate = async () => {
    if (!selectedProductId || !csvInput.trim()) {
      alert('请选择目标产品并提供导入数据内容')
      return
    }
    setValidating(true)
    setImportResult(null)
    try {
      const report = await migrationApi.validate(selectedProductId, csvInput, 'upload_data.csv')
      setValidationReport(report)
    } catch (err) {
      alert('数据质量检查失败: ' + (err as Error).message)
    } finally {
      setValidating(false)
    }
  }

  // ---- 数据迁移：确认导入 ----
  const handleExecuteImport = async () => {
    if (!validationReport || !selectedProductId) return
    setImporting(true)
    try {
      const result = await migrationApi.import(selectedProductId, validationReport.previewRows, skipErrors)
      setImportResult(result)
      setValidationReport(null)
      setCsvInput('')
    } catch (err) {
      alert('执行导入失败: ' + (err as Error).message)
    } finally {
      setImporting(false)
    }
  }

  // 复制令牌到剪贴板
  const handleCopyToken = () => {
    if (!createdPatRaw) return
    navigator.clipboard.writeText(createdPatRaw).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  const roots = departments.filter((d) => !d.parentId)
  const childrenOf = (pid: string) => departments.filter((d) => d.parentId === pid)

  return (
    <div>
      <PageHeader
        title="企业治理与数据协同中心"
        desc="成员生命周期管理 · 开发者访问令牌 (PAT) · 操作审计流 · 存量数据质量门禁迁移"
      />

      {/* 顶部核心导航 Tabs */}
      <div className="mb-6 flex flex-wrap items-center gap-2 border-b border-line pb-3">
        {[
          { key: 'members', label: '成员管理', icon: Users },
          { key: 'tokens', label: '访问令牌 (PAT)', icon: Key },
          { key: 'migration', label: '数据迁移中心', icon: UploadCloud },
          { key: 'audit', label: '操作安全审计', icon: ShieldCheck },
          { key: 'departments', label: '部门拓扑', icon: Shield },
          { key: 'matrix', label: '权限判定矩阵', icon: FileSpreadsheet },
        ].map((tab) => {
          const Icon = tab.icon
          const active = activeTab === tab.key
          return (
            <button
              key={tab.key}
              type="button"
              onClick={() => setActiveTab(tab.key as ActiveTab)}
              className={`flex cursor-pointer items-center gap-2 rounded-lg px-3.5 py-2 text-sm font-semibold transition-all ${
                active
                  ? 'bg-brand text-white shadow-sm'
                  : 'bg-card text-txt-mid hover:bg-ink-700 hover:text-txt-hi'
              }`}
            >
              <Icon size={16} />
              {tab.label}
            </button>
          )
        })}
      </div>

      {/* ============================================================ */}
      {/* TAB 1: 成员全生命周期管理后台 */}
      {/* ============================================================ */}
      {activeTab === 'members' && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <input
                type="text"
                value={userQuery}
                onChange={(e) => setUserQuery(e.target.value)}
                placeholder="搜索账号名、姓名..."
                className="w-64 rounded-input border border-line bg-canvas px-3 py-1.5 text-sm text-txt-hi outline-none focus:border-brand"
              />
              <select
                value={userRoleFilter}
                onChange={(e) => setUserRoleFilter(e.target.value)}
                className="rounded-input border border-line bg-canvas px-3 py-1.5 text-sm text-txt-hi outline-none focus:border-brand"
              >
                <option value="">全部平台角色</option>
                <option value="OWNER">所有者 (OWNER)</option>
                <option value="ADMIN">管理员 (ADMIN)</option>
                <option value="MEMBER">成员 (MEMBER)</option>
              </select>
            </div>
            <Btn variant="primary" onClick={() => setShowCreateUserModal(true)}>
              <UserPlus size={16} />
              新建系统成员
            </Btn>
          </div>

          <Card>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-line bg-canvas/60 text-xs font-semibold text-txt-low">
                    <th className="px-4 py-3">成员身份</th>
                    <th className="px-4 py-3">平台权限角色</th>
                    <th className="px-4 py-3">账号状态</th>
                    <th className="px-4 py-3">职位与邮箱</th>
                    <th className="px-4 py-3">标准日产能</th>
                    <th className="px-4 py-3 text-right">操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {usersLoading ? (
                    <tr>
                      <td colSpan={6} className="px-4 py-8 text-center text-txt-low">
                        加载成员列表中...
                      </td>
                    </tr>
                  ) : adminUsersData?.content && adminUsersData.content.length > 0 ? (
                    adminUsersData.content.map((u) => (
                      <tr key={u.id} className="hover:bg-ink-700/40">
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2.5">
                            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-brand/20 font-bold text-brand">
                              {u.displayName.slice(0, 1)}
                            </div>
                            <div>
                              <div className="font-semibold text-txt-hi">{u.displayName}</div>
                              <div className="text-xs text-txt-low">@{u.username}</div>
                            </div>
                          </div>
                        </td>
                        <td className="px-4 py-3">
                          {u.platformRole === 'OWNER' && <Pill tone="purple">平台所有者</Pill>}
                          {u.platformRole === 'ADMIN' && <Pill tone="brand">系统管理员</Pill>}
                          {u.platformRole === 'MEMBER' && <Pill tone="neutral">普通成员</Pill>}
                        </td>
                        <td className="px-4 py-3">
                          {u.status === 'ACTIVE' ? (
                            <span className="inline-flex items-center gap-1.5 text-xs font-medium text-ok-deep">
                              <span className="h-2 w-2 rounded-full bg-ok" /> 正常
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1.5 text-xs font-medium text-bad-deep">
                              <span className="h-2 w-2 rounded-full bg-bad" /> 已停用
                            </span>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          <div className="text-txt-mid">{u.title || '—'}</div>
                          <div className="text-xs text-txt-low">{u.email || '—'}</div>
                        </td>
                        <td className="px-4 py-3 text-txt-mid">{u.dailyCapacityHours} 小时/天</td>
                        <td className="px-4 py-3 text-right">
                          <div className="flex items-center justify-end gap-2">
                            <button
                              type="button"
                              onClick={() => setResettingUser(u)}
                              className="rounded px-2 py-1 text-xs text-txt-mid hover:bg-ink-700 hover:text-brand cursor-pointer"
                              title="重置密码"
                            >
                              重置密码
                            </button>
                            <button
                              type="button"
                              onClick={() => handleToggleUserStatus(u)}
                              className={`rounded px-2 py-1 text-xs cursor-pointer ${
                                u.status === 'ACTIVE'
                                  ? 'text-bad-deep hover:bg-bad-bg'
                                  : 'text-ok-deep hover:bg-ok-bg'
                              }`}
                            >
                              {u.status === 'ACTIVE' ? '停用' : '启用'}
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={6} className="px-4 py-8 text-center text-txt-low">
                        暂无匹配的成员记录
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </Card>
        </div>
      )}

      {/* ============================================================ */}
      {/* TAB 2: 个人访问令牌 (PAT) */}
      {/* ============================================================ */}
      {activeTab === 'tokens' && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h3 className="font-semibold text-txt-hi">个人访问令牌 (Personal Access Tokens)</h3>
              <p className="text-xs text-txt-mid mt-0.5">
                用于 Git 客户端命令行 (HTTP 克隆/推送) 或调用 TeamOne OpenAPI。系统仅安全保存 SHA-256 哈希。
              </p>
            </div>
            <Btn variant="primary" onClick={() => setShowCreatePatModal(true)}>
              <Plus size={16} />
              生成新访问令牌
            </Btn>
          </div>

          <Card>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-line bg-canvas/60 text-xs font-semibold text-txt-low">
                    <th className="px-4 py-3">令牌描述名称</th>
                    <th className="px-4 py-3">令牌脱敏前缀</th>
                    <th className="px-4 py-3">权限作用域</th>
                    <th className="px-4 py-3">创建时间</th>
                    <th className="px-4 py-3">有效期 / 状态</th>
                    <th className="px-4 py-3">最近活跃时间</th>
                    <th className="px-4 py-3 text-right">操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {tokensLoading ? (
                    <tr>
                      <td colSpan={7} className="px-4 py-8 text-center text-txt-low">
                        加载令牌中...
                      </td>
                    </tr>
                  ) : tokensData && tokensData.length > 0 ? (
                    tokensData.map((t) => (
                      <tr key={t.id} className="hover:bg-ink-700/40">
                        <td className="px-4 py-3 font-semibold text-txt-hi">{t.name}</td>
                        <td className="px-4 py-3">
                          <code className="rounded bg-canvas px-2 py-0.5 font-mono text-xs text-txt-mid border border-line">
                            {t.tokenPrefix}
                          </code>
                        </td>
                        <td className="px-4 py-3">
                          <Pill tone="teal">{t.scopes}</Pill>
                        </td>
                        <td className="px-4 py-3 text-xs text-txt-low">{fmt(new Date(t.createdAt))}</td>
                        <td className="px-4 py-3 text-xs">
                          {t.expired ? (
                            <span className="font-semibold text-bad-deep">已过期</span>
                          ) : t.expiresAt ? (
                            <span className="text-txt-mid">到期于 {fmt(new Date(t.expiresAt))}</span>
                          ) : (
                            <span className="text-ok-deep">永久有效</span>
                          )}
                        </td>
                        <td className="px-4 py-3 text-xs text-txt-low">
                          {t.lastUsedAt ? fmt(new Date(t.lastUsedAt)) : '从未使用'}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <button
                            type="button"
                            onClick={() => handleRevokePat(t.id, t.name)}
                            className="rounded px-2 py-1 text-xs text-bad-deep hover:bg-bad-bg cursor-pointer"
                          >
                            <Trash2 size={14} className="inline mr-1" />
                            吊销
                          </button>
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={7} className="px-4 py-8 text-center text-txt-low">
                        尚未创建任何访问令牌。点击右上角按钮即可生成。
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </Card>
        </div>
      )}

      {/* ============================================================ */}
      {/* TAB 3: 数据迁移与数据质量检查门禁 */}
      {/* ============================================================ */}
      {activeTab === 'migration' && (
        <div className="space-y-5">
          <Card className="p-5">
            <h3 className="text-base font-semibold text-txt-hi flex items-center gap-2">
              <UploadCloud size={20} className="text-brand" />
              存量工作项导入与数据质量门禁
            </h3>
            <p className="mt-1 text-xs text-txt-mid leading-relaxed">
              为防止脏数据破坏项目底层表间复杂关联关系，导入系统严格实行
              <b className="text-brand">「两阶段预检与质量诊断机制」</b>。
              系统会自动核验<b>经办人用户是否存在并激活</b>、<b>所属迭代是否归属当前产品</b>、<b>优先级与类型枚举</b>，预检 100% 通过或确认跳过异常行方可落库。
            </p>

            <div className="mt-5 grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* 步骤 1：下载标准模板 */}
              <div className="rounded-lg border border-line bg-canvas p-4">
                <div className="text-xs font-semibold text-txt-low uppercase">步骤 1 · 获取标准模板</div>
                <div className="mt-2 text-sm font-semibold text-txt-hi">下载 Excel / CSV 工作项标准格式模板</div>
                <p className="mt-1 text-xs text-txt-mid">
                  模板包含必填标识、枚举字典与标准示例数据，支持中文 Excel 直接双击打开编辑。
                </p>
                <div className="mt-3">
                  <a
                    href={selectedProductId ? migrationApi.getTemplateUrl(selectedProductId) : '#'}
                    className="inline-flex items-center gap-1.5 rounded bg-brand px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-deep"
                  >
                    <Download size={14} />
                    下载工作项模板 (.csv)
                  </a>
                </div>
              </div>

              {/* 步骤 2：选择归属产品 */}
              <div className="rounded-lg border border-line bg-canvas p-4">
                <div className="text-xs font-semibold text-txt-low uppercase">步骤 2 · 确认迁移归属</div>
                <div className="mt-2 text-sm font-semibold text-txt-hi">选择存量数据拟迁入的目标产品</div>
                <div className="mt-2">
                  <select
                    value={selectedProductId}
                    onChange={(e) => {
                      setSelectedProductId(e.target.value)
                      setValidationReport(null)
                    }}
                    className="w-full rounded-input border border-line bg-ink-850 px-3 py-1.5 text-sm text-txt-hi outline-none focus:border-brand"
                  >
                    {products.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name} ({p.key})
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            </div>

            {/* 步骤 3：数据录入与上传 */}
            <div className="mt-4 rounded-lg border border-line bg-canvas p-4">
              <div className="text-xs font-semibold text-txt-low uppercase mb-2">步骤 3 · 上传或粘贴 CSV 数据文本</div>
              <div className="space-y-2">
                <input
                  type="file"
                  accept=".csv,.txt"
                  onChange={handleFileChange}
                  className="text-xs text-txt-mid file:mr-3 file:rounded file:border-0 file:bg-brand file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-white hover:file:bg-brand-deep cursor-pointer"
                />
                <textarea
                  rows={4}
                  value={csvInput}
                  onChange={(e) => {
                    setCsvInput(e.target.value)
                    setValidationReport(null)
                  }}
                  placeholder="在此直接粘贴 CSV 文本内容（或通过上方按钮选择文件）..."
                  className="w-full rounded-input border border-line bg-ink-850 p-2.5 font-mono text-xs text-txt-hi outline-none focus:border-brand"
                />
              </div>

              <div className="mt-3 flex items-center justify-end">
                <Btn variant="primary" onClick={handleValidate} disabled={validating || !csvInput.trim()}>
                  <RefreshCw size={14} className={validating ? 'animate-spin' : ''} />
                  {validating ? '正在执行质量门禁诊断...' : '执行数据质量预检 (Pre-check)'}
                </Btn>
              </div>
            </div>

            {/* 步骤 4：数据质量诊断报告 */}
            {validationReport && (
              <div className="mt-5 space-y-4 rounded-lg border border-line bg-canvas p-4">
                <div className="flex items-center justify-between">
                  <h4 className="font-semibold text-txt-hi flex items-center gap-2">
                    <ShieldCheck size={18} className="text-brand" />
                    数据质量检验诊断报告
                  </h4>
                  <div className="flex items-center gap-3 text-xs">
                    <span className="text-txt-mid">总行数: <b>{validationReport.totalRows}</b></span>
                    <span className="text-ok-deep">合规行: <b>{validationReport.validRows}</b></span>
                    <span className={validationReport.errorRows > 0 ? 'text-bad-deep font-bold' : 'text-txt-low'}>
                      异常行: <b>{validationReport.errorRows}</b>
                    </span>
                  </div>
                </div>

                {/* 状态结论 Banner */}
                {validationReport.canImport ? (
                  <div className="flex items-center gap-2 rounded-lg bg-ok-bg p-3 text-xs font-semibold text-ok-deep">
                    <CheckCircle2 size={16} />
                    数据质量检验全部通过！未发现任何外键引用断裂或枚举异常，已准备好导入系统。
                  </div>
                ) : (
                  <div className="flex items-center gap-2 rounded-lg bg-bad-bg p-3 text-xs font-semibold text-bad-deep">
                    <AlertTriangle size={16} />
                    检测到 {validationReport.errorRows} 处数据质量缺陷！请查看下方诊断清单修复，或勾选跳过异常行后落库。
                  </div>
                )}

                {/* 错误行级清单 */}
                {validationReport.errors.length > 0 && (
                  <div className="space-y-1.5">
                    <div className="text-xs font-semibold text-bad-deep">缺陷诊断清单：</div>
                    <div className="max-h-48 overflow-y-auto space-y-1.5">
                      {validationReport.errors.map((err, idx) => (
                        <div
                          key={idx}
                          className="flex items-center gap-2 rounded border border-bad/20 bg-bad-bg/40 px-3 py-1.5 text-xs text-bad-deep"
                        >
                          <span className="font-bold">第 {err.rowNumber} 行</span>
                          <span className="rounded bg-bad-bg px-1.5 py-0.5 text-[10px] font-mono">[{err.columnName}]</span>
                          <span className="flex-1">{err.message}</span>
                          {err.rawValue && <span className="opacity-70 text-[11px]">(原始值: {err.rawValue})</span>}
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* 预览数据列表 */}
                {validationReport.previewRows.length > 0 && (
                  <div>
                    <div className="text-xs font-semibold text-txt-mid mb-2">解析预览（前 30 条）：</div>
                    <div className="max-h-56 overflow-y-auto rounded border border-line">
                      <table className="w-full text-left text-xs">
                        <thead>
                          <tr className="border-b border-line bg-ink-850 text-txt-low">
                            <th className="px-3 py-2">行号</th>
                            <th className="px-3 py-2">合规</th>
                            <th className="px-3 py-2">类型</th>
                            <th className="px-3 py-2">标题</th>
                            <th className="px-3 py-2">优先级</th>
                            <th className="px-3 py-2">经办人</th>
                            <th className="px-3 py-2">所属迭代</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-line">
                          {validationReport.previewRows.map((r) => (
                            <tr key={r.rowNumber} className={r.valid ? 'hover:bg-ink-700/30' : 'bg-bad-bg/20'}>
                              <td className="px-3 py-1.5 font-mono text-txt-low">#{r.rowNumber}</td>
                              <td className="px-3 py-1.5">
                                {r.valid ? (
                                  <span className="text-ok-deep font-bold">✓</span>
                                ) : (
                                  <span className="text-bad-deep font-bold" title={r.errorMessages.join('; ')}>✕</span>
                                )}
                              </td>
                              <td className="px-3 py-1.5">{r.type}</td>
                              <td className="px-3 py-1.5 font-medium text-txt-hi">{r.title}</td>
                              <td className="px-3 py-1.5">{r.priority}</td>
                              <td className="px-3 py-1.5 text-txt-mid">{r.assigneeUsername || '—'}</td>
                              <td className="px-3 py-1.5 text-txt-mid">{r.sprintName || '—'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                )}

                {/* 确认导入提交 */}
                <div className="flex items-center justify-between border-t border-line pt-3">
                  <label className="flex items-center gap-2 text-xs text-txt-mid cursor-pointer select-none">
                    <input
                      type="checkbox"
                      checked={skipErrors}
                      onChange={(e) => setSkipErrors(e.target.checked)}
                      className="rounded border-line bg-canvas text-brand focus:ring-0"
                    />
                    自动跳过质量异常行，仅导入完全合规的数据行
                  </label>

                  <Btn
                    variant="primary"
                    disabled={importing || (!validationReport.canImport && !skipErrors)}
                    onClick={handleExecuteImport}
                  >
                    <UploadCloud size={14} />
                    {importing ? '正在执行事务导入落库...' : '确认执行原子导入落库'}
                  </Btn>
                </div>
              </div>
            )}

            {/* 导入成功反馈 */}
            {importResult && (
              <div className="mt-5 rounded-lg border border-ok/30 bg-ok-bg p-4 space-y-2">
                <div className="flex items-center gap-2 font-bold text-ok-deep">
                  <CheckCircle2 size={20} />
                  数据迁移导入成功！
                </div>
                <div className="text-xs text-txt-hi">
                  已成功落库入编 <b>{importResult.importedCount}</b> 条工作项
                  {importResult.skippedCount > 0 && `（跳过 ${importResult.skippedCount} 条异常数据）`}。
                </div>
                <div className="text-xs text-txt-mid">
                  生成的业务单号：
                  <span className="font-mono font-semibold text-brand ml-1">
                    {importResult.createdKeys.join(', ')}
                  </span>
                </div>
                <div className="pt-2">
                  <Btn variant="default" onClick={() => nav.go('requirements')}>
                    前往查看需求与工作项
                  </Btn>
                </div>
              </div>
            )}
          </Card>
        </div>
      )}

      {/* ============================================================ */}
      {/* TAB 4: 操作安全审计日志 */}
      {/* ============================================================ */}
      {activeTab === 'audit' && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="font-semibold text-txt-hi">全局操作审计日志 (Audit Event Log)</h3>
            <div className="text-xs text-txt-low">仅系统管理员与合规官可见 · 严格不可变追加记录</div>
          </div>

          <Card>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-line bg-canvas/60 text-xs font-semibold text-txt-low">
                    <th className="px-4 py-3">事件时间</th>
                    <th className="px-4 py-3">操作人员</th>
                    <th className="px-4 py-3">动作标识</th>
                    <th className="px-4 py-3">涉及资源</th>
                    <th className="px-4 py-3">结构化详情</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {auditLoading ? (
                    <tr>
                      <td colSpan={5} className="px-4 py-8 text-center text-txt-low">
                        加载审计日志中...
                      </td>
                    </tr>
                  ) : auditData?.content && auditData.content.length > 0 ? (
                    auditData.content.map((log) => (
                      <tr key={log.id} className="hover:bg-ink-700/40">
                        <td className="px-4 py-3 text-xs font-mono text-txt-low">
                          {fmt(new Date(log.createdAt))}
                        </td>
                        <td className="px-4 py-3 font-semibold text-txt-hi">{log.actorName}</td>
                        <td className="px-4 py-3">
                          <Pill tone="purple">{log.action}</Pill>
                        </td>
                        <td className="px-4 py-3 text-xs text-txt-mid">
                          <span className="font-mono text-brand">{log.resourceType}</span>
                          {log.resourceId && <span className="text-txt-low ml-1">#{log.resourceId}</span>}
                        </td>
                        <td className="px-4 py-3 text-xs font-mono text-txt-mid max-w-xs truncate">
                          {JSON.stringify(log.detail)}
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={5} className="px-4 py-8 text-center text-txt-low">
                        暂无操作审计事件
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            {/* 分页控制器 */}
            {auditData && auditData.totalPages > 1 && (
              <div className="flex items-center justify-between border-t border-line px-4 py-3 text-xs text-txt-mid">
                <span>共 {auditData.totalElements} 条事件记录</span>
                <div className="flex items-center gap-2">
                  <Btn
                    variant="ghost"
                    disabled={auditPage <= 0}
                    onClick={() => setAuditPage((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Btn>
                  <span>第 {auditPage + 1} / {auditData.totalPages} 页</span>
                  <Btn
                    variant="ghost"
                    disabled={auditPage >= auditData.totalPages - 1}
                    onClick={() => setAuditPage((p) => p + 1)}
                  >
                    下一页
                  </Btn>
                </div>
              </div>
            )}
          </Card>
        </div>
      )}

      {/* ============================================================ */}
      {/* TAB 5: 部门架构拓扑 (原视图保留) */}
      {/* ============================================================ */}
      {activeTab === 'departments' && (
        <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
          {roots.map((d) => (
            <div key={d.id} className="space-y-3">
              <DeptCard dept={d} nav={nav} />
              {childrenOf(d.id).map((c) => <DeptCard key={c.id} dept={c} nav={nav} />)}
            </div>
          ))}
        </div>
      )}

      {/* ============================================================ */}
      {/* TAB 6: 权限判定矩阵 (原视图保留) */}
      {/* ============================================================ */}
      {activeTab === 'matrix' && (
        <Card>
          <CardHeader
            title="权限矩阵"
            extra={<span className="text-xs text-txt-low">行 = 成员 · 列 = 资源 · 单元格 = 有效动作集合</span>}
          />
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-txt-low">
                  <th className="sticky left-0 bg-ink-850 px-4 py-2 font-medium">成员</th>
                  {MATRIX_COLS.map((c) => (
                    <th key={c.id} className="min-w-24 px-3 py-2 text-center font-medium">
                      <div className={c.type === 'product' ? 'font-semibold text-cat-blue' : 'font-semibold text-cat-teal'}>{c.name}</div>
                      <div className="text-[10px] font-normal text-txt-low">{c.sub}</div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {users.map((u) => (
                  <tr key={u.id} className="hover:bg-ink-700">
                    <td className="sticky left-0 bg-ink-850 px-4 py-2">
                      <span className="flex items-center gap-2">
                        <Avatar userId={u.id} size={22} />
                        <span className="font-medium text-txt-hi">{u.name}</span>
                      </span>
                    </td>
                    {MATRIX_COLS.map((col) => {
                      const eff = effective(u, col)
                      return (
                        <td key={col.id} className="px-3 py-2 text-center" title={eff.source}>
                          {eff.actions.length === 0 ? (
                            <span className="text-xs text-txt-low/40">—</span>
                          ) : (
                            <span className="flex items-center justify-center gap-1 font-mono text-[11px]">
                              {eff.actions.map((a) => (
                                <span key={a} className={actionCls[a]}>{actionText[a]}</span>
                              ))}
                            </span>
                          )}
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* ============================================================ */}
      {/* 弹窗：新建成员 Modal */}
      {/* ============================================================ */}
      {showCreateUserModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="w-full max-w-md rounded-xl border border-line bg-card p-6 shadow-2xl">
            <div className="flex items-center justify-between mb-4">
              <h3 className="font-bold text-txt-hi">新建平台成员</h3>
              <button
                type="button"
                onClick={() => setShowCreateUserModal(false)}
                className="text-txt-low hover:text-txt-hi cursor-pointer"
              >
                <X size={18} />
              </button>
            </div>
            <form onSubmit={handleCreateUser} className="space-y-3 text-sm">
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">登录账号名 (Username)*</label>
                <input
                  type="text"
                  required
                  value={newUsername}
                  onChange={(e) => setNewUsername(e.target.value)}
                  placeholder="如: zhangsan"
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">初始密码* (≥6位)</label>
                <input
                  type="password"
                  required
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  placeholder="请输入初始密码"
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">姓名 / 昵称*</label>
                <input
                  type="text"
                  required
                  value={newDisplayName}
                  onChange={(e) => setNewDisplayName(e.target.value)}
                  placeholder="如: 张三"
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">职位头衔</label>
                <input
                  type="text"
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="如: 后端架构师"
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">平台权限角色</label>
                <select
                  value={newRole}
                  onChange={(e) => setNewRole(e.target.value as any)}
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand"
                >
                  <option value="MEMBER">普通成员 (MEMBER)</option>
                  <option value="ADMIN">系统管理员 (ADMIN)</option>
                  <option value="OWNER">所有者 (OWNER)</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">标准日工时 (小时/天)</label>
                <input
                  type="number"
                  min={1}
                  max={24}
                  value={newCapacity}
                  onChange={(e) => setNewCapacity(Number(e.target.value))}
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand"
                />
              </div>
              <div className="flex justify-end gap-2 pt-3">
                <Btn variant="ghost" onClick={() => setShowCreateUserModal(false)}>
                  取消
                </Btn>
                <Btn variant="primary" disabled={userCreating}>
                  {userCreating ? '创建中...' : '确认创建'}
                </Btn>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ============================================================ */}
      {/* 弹窗：重置密码 Modal */}
      {/* ============================================================ */}
      {resettingUser && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="w-full max-w-sm rounded-xl border border-line bg-card p-6 shadow-2xl">
            <div className="flex items-center justify-between mb-3">
              <h3 className="font-bold text-txt-hi">重置登录密码</h3>
              <button
                type="button"
                onClick={() => setResettingUser(null)}
                className="text-txt-low hover:text-txt-hi cursor-pointer"
              >
                <X size={18} />
              </button>
            </div>
            <p className="text-xs text-txt-mid mb-3">
              即将重置成员 <b className="text-txt-hi">{resettingUser.displayName}</b> (@{resettingUser.username}) 的密码。
            </p>
            <form onSubmit={handleResetPassword} className="space-y-3">
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">新密码 (≥6位)</label>
                <input
                  type="password"
                  required
                  value={resetPasswordVal}
                  onChange={(e) => setResetPasswordVal(e.target.value)}
                  placeholder="请输入新密码"
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand text-sm"
                />
              </div>
              <div className="flex justify-end gap-2 pt-2">
                <Btn variant="ghost" onClick={() => setResettingUser(null)}>
                  取消
                </Btn>
                <Btn variant="primary" disabled={resetting || resetPasswordVal.length < 6}>
                  {resetting ? '正在重置...' : '确认重置'}
                </Btn>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ============================================================ */}
      {/* 弹窗：新建 PAT 令牌 Modal */}
      {/* ============================================================ */}
      {showCreatePatModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="w-full max-w-md rounded-xl border border-line bg-card p-6 shadow-2xl">
            <div className="flex items-center justify-between mb-4">
              <h3 className="font-bold text-txt-hi">生成个人访问令牌 (PAT)</h3>
              <button
                type="button"
                onClick={() => setShowCreatePatModal(false)}
                className="text-txt-low hover:text-txt-hi cursor-pointer"
              >
                <X size={18} />
              </button>
            </div>
            <form onSubmit={handleCreatePat} className="space-y-3 text-sm">
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">令牌名称 / 用途*</label>
                <input
                  type="text"
                  required
                  value={patName}
                  onChange={(e) => setPatName(e.target.value)}
                  placeholder="如: 本地 VSCode 插件, CI 构建机"
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">有效期限</label>
                <select
                  value={patDays}
                  onChange={(e) => setPatDays(Number(e.target.value))}
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand"
                >
                  <option value={30}>30 天</option>
                  <option value={90}>90 天</option>
                  <option value={365}>1 年</option>
                  <option value={0}>永不过期</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-semibold text-txt-mid mb-1">权限作用域 (Scopes)</label>
                <select
                  value={patScopes}
                  onChange={(e) => setPatScopes(e.target.value)}
                  className="w-full rounded-input border border-line bg-canvas px-3 py-1.5 text-txt-hi outline-none focus:border-brand"
                >
                  <option value="all">全量权限 (all)</option>
                  <option value="repo:read,repo:write">代码仓库读写 (repo:read, repo:write)</option>
                  <option value="repo:read">仅代码只读 (repo:read)</option>
                  <option value="work_item:manage">研发工作项管理 (work_item:manage)</option>
                </select>
              </div>
              <div className="flex justify-end gap-2 pt-3">
                <Btn variant="ghost" onClick={() => setShowCreatePatModal(false)}>
                  取消
                </Btn>
                <Btn variant="primary" disabled={patCreating}>
                  {patCreating ? '生成中...' : '立即生成'}
                </Btn>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ============================================================ */}
      {/* 弹窗：PAT 令牌生成成功（单次展示完整明文） */}
      {/* ============================================================ */}
      {createdPatRaw && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4">
          <div className="w-full max-w-lg rounded-xl border border-line bg-card p-6 shadow-2xl space-y-4">
            <div className="flex items-center gap-2 text-ok-deep font-bold">
              <CheckCircle2 size={20} />
              个人访问令牌生成成功！
            </div>
            <div className="rounded-lg bg-warn-bg/60 p-3 text-xs text-warn-deep flex items-start gap-2">
              <AlertTriangle size={16} className="shrink-0 mt-0.5" />
              <span>
                出于企业安全防护要求，<b>此明文令牌仅在此处展示一次</b>。关闭此窗口后将无法再次查阅，请立即复制并妥善保管在密码管理器或环境变量中！
              </span>
            </div>

            <div className="flex items-center gap-2">
              <input
                type="text"
                readOnly
                value={createdPatRaw}
                className="w-full rounded-input border border-line bg-canvas px-3 py-2 font-mono text-xs text-txt-hi select-all"
              />
              <Btn variant="primary" onClick={handleCopyToken}>
                <Copy size={14} />
                {copied ? '已复制！' : '复制'}
              </Btn>
            </div>

            <div className="flex justify-end pt-2">
              <Btn variant="default" onClick={() => setCreatedPatRaw(null)}>
                我已妥善保存，关闭窗口
              </Btn>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
