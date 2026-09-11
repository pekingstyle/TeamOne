// 团队与权限（R6）：部门树 + 权限矩阵可视化（四步判定） + ACL 条目清单
import type { Department, ResourceAction, User } from '../../data/types'
import {
  actionText, baselineById, componentById, components, departmentById, departments, goalById,
  productById, products, releaseById, repoById, resourcePermissions, roleText, resourceTypeText,
  useStore, userById, users,
} from '../../data/store'
import { Avatar, Card, CardHeader, PageHeader, Pill } from '../../components/ui'
import type { Nav, PageProps } from '../../nav'

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

/** 简化版四步判定（§6.1 按序短路，默认拒绝）。subjectType=role 条目因成员无部门角色字段而跳过。 */
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

/** ACL resourceId → 展示名 */
function resourceLabel(type: string, id: string): string {
  const name = type === 'product' ? productById(id)?.name
    : type === 'component' ? componentById(id)?.name
    : type === 'release' ? releaseById(id)?.name
    : type === 'repo' ? repoById(id)?.name
    : type === 'baseline' ? baselineById(id)?.name
    : type === 'goal' ? goalById(id)?.name
    : undefined
  return name ?? id
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
  const stats = [
    { label: '部门', value: departments.length, tone: 'text-txt-hi' },
    { label: '产品', value: products.length, tone: 'text-cat-blue' },
    { label: '成员', value: users.length, tone: 'text-txt-hi' },
    { label: 'ACL 条目', value: resourcePermissions.length, tone: 'text-cat-purple' },
  ]
  const roots = departments.filter((d) => !d.parentId)
  const childrenOf = (pid: string) => departments.filter((d) => d.parentId === pid)

  return (
    <div>
      <PageHeader
        title="团队与权限"
        desc="部门 × 角色 × 资源级 ACL 三层权限模型 · 判定按「平台角色 → 部门负责人 → 组件负责人 → ACL → 默认拒绝」短路"
      />

      {/* 顶部统计卡 */}
      <div className="mb-5 grid grid-cols-2 gap-4 xl:grid-cols-4">
        {stats.map((s) => (
          <Card key={s.label} className="p-4">
            <div className="text-sm text-txt-mid">{s.label}</div>
            <div className={`mt-1 text-2xl font-bold tabular-nums ${s.tone}`}>{s.value}</div>
          </Card>
        ))}
      </div>

      {/* 部门树 */}
      <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
        {roots.map((d) => (
          <div key={d.id} className="space-y-3">
            <DeptCard dept={d} nav={nav} />
            {childrenOf(d.id).map((c) => <DeptCard key={c.id} dept={c} nav={nav} />)}
          </div>
        ))}
      </div>

      {/* 权限矩阵 */}
      <Card className="mt-4">
        <CardHeader
          title="权限矩阵"
          extra={<span className="text-xs text-txt-low">行 = 成员 · 列 = 资源 · 单元格 = 有效动作集合（hover 看判定来源）</span>}
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
                      {platformRoleText[u.platformRole] && <Pill tone="purple">{platformRoleText[u.platformRole]}</Pill>}
                    </span>
                  </td>
                  {MATRIX_COLS.map((c) => {
                    const r = effective(u, c)
                    return (
                      <td key={c.id} title={`${u.name} × ${c.name}：${r.source}`} className="px-3 py-2 text-center">
                        {r.actions.length === 0
                          ? <span className="text-txt-low">—</span>
                          : r.actions.map((a) => <span key={a} className={`mr-1.5 text-[11px] font-bold ${actionCls[a]}`}>{actionText[a]}</span>)}
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* ACL 条目清单 */}
      <Card className="mt-4">
        <CardHeader title="ACL 授权条目" extra={<Pill tone="purple">{resourcePermissions.length} 条</Pill>} />
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-txt-low">
                {['资源类型', '资源', '主体', '动作', '授予人', '授予时间'].map((h) => <th key={h} className="px-4 py-2 font-medium">{h}</th>)}
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {resourcePermissions.map((g) => (
                <tr key={g.id} className="hover:bg-ink-700">
                  <td className="px-4 py-2 text-txt-mid">{resourceTypeText[g.resourceType] ?? g.resourceType}</td>
                  <td className="px-4 py-2 font-medium text-txt-hi">{resourceLabel(g.resourceType, g.resourceId)}</td>
                  <td className="px-4 py-2">
                    {g.subjectType === 'user' ? (
                      <span className="flex items-center gap-1.5"><Avatar userId={g.subjectId} size={18} />{userById(g.subjectId)?.name ?? g.subjectId}</span>
                    ) : g.subjectType === 'dept' ? (
                      <span>{departmentById(g.subjectId)?.name ?? g.subjectId}（部门）</span>
                    ) : (
                      <span>{roleText[g.subjectId] ?? g.subjectId}（角色）</span>
                    )}
                  </td>
                  <td className="px-4 py-2">
                    <span className="flex flex-wrap gap-1">
                      {g.actions.map((a) => (
                        <span key={a} className={`rounded bg-canvas px-1.5 py-0.5 text-[11px] font-bold ${actionCls[a]}`}>{actionText[a]}</span>
                      ))}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-txt-mid">{userById(g.grantedById)?.name ?? g.grantedById}</td>
                  <td className="px-4 py-2 tabular-nums text-txt-low">{g.createdAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}
