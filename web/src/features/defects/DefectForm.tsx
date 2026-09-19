// R-8 缺陷登记表单（B4 批 · docs/v2/12 §1 R-8）：迭代任务页「登记缺陷」与缺陷中心共用组件。
// 字段 = 标题* / 严重度*（四档）/ 优先级 / 所属组件 / 描述 / 复现步骤 / 发现实环境 / 处理人（报告人=当前登录人，后端 actor 落 reporter_id）；
// 必填仅 标题+严重度；DefectsPage 原有字段（产品/阻塞版本/处理人）全保留，TasksPage 入口附加 迭代/版本/截止日 上下文。
// 后端落点说明：WorkItemService.CreateSpec 无「发现环境/复现步骤」独立列——两者合入 description
// （【发现环境】前缀 + 【复现步骤】分节），缺陷中心抽屉按 description 原样展示，无需迁移。
import { useEffect, useMemo, useState } from 'react'
import { FlaskConical, X } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import type { DefectSeverity, WorkPriority } from '../../data/types'
import { severityTone } from '../../data/store'
import { defectsApi, parseFieldError, useComponents, useProducts, useReleases, useSprints } from '../../api/queries'
import type { RemoteProduct } from '../../api/queries'
import { toBrief, useRemoteUsers } from '../../api/users'
import { Btn, Pill } from '../../components/ui'

const inputCls = 'w-full rounded-input border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none placeholder:text-txt-low/70 focus:border-brand'
const SEVS: DefectSeverity[] = ['致命', '严重', '一般', '轻微']

export interface DefectFormDefaults {
  productId?: string
  assigneeId?: string
  sprintId?: string
  releaseId?: string
  dueDate?: string
}

export interface DefectFormModalProps {
  /** 预置上下文（TasksPage 迭代入口传当前迭代/版本；缺陷中心不传） */
  defaults?: DefectFormDefaults
  /** 是否显示 迭代/版本/截止日 上下文字段（TasksPage 登记缺陷入口 = true） */
  showSchedule?: boolean
  onClose: () => void
  onCreated: (key: string) => void
  onError?: (message: string) => void
}

/**
 * 缺陷登记弹窗（POST /work-items type=defect，真实 API 单一通道）。
 * 两入口共用本组件保证字段一致（R-8 AC①）：缺陷中心传 defaults={}，任务页传 showSchedule。
 */
export function DefectFormModal({ defaults, showSchedule = false, onClose, onCreated, onError }: DefectFormModalProps) {
  const queryClient = useQueryClient()
  const { data: productRows } = useProducts()
  const { data: componentRows } = useComponents()
  const { data: releaseRows } = useReleases()
  const { data: sprintRows } = useSprints()
  const { data: userRows } = useRemoteUsers()
  const users = toBrief(userRows)

  const [title, setTitle] = useState('')
  const [severity, setSeverity] = useState<DefectSeverity>('一般')
  const [priority, setPriority] = useState<WorkPriority>('P1')
  const [productId, setProductId] = useState(defaults?.productId ?? '')
  const [componentId, setComponentId] = useState('')
  const [assigneeId, setAssigneeId] = useState(defaults?.assigneeId ?? '')
  const [description, setDescription] = useState('')
  const [reproSteps, setReproSteps] = useState('')
  const [foundEnv, setFoundEnv] = useState('')
  const [blockedReleaseId, setBlockedReleaseId] = useState('')
  const [sprintId, setSprintId] = useState(defaults?.sprintId ?? '')
  const [releaseId, setReleaseId] = useState(defaults?.releaseId ?? '')
  const [dueDate, setDueDate] = useState(defaults?.dueDate ?? '')
  const [busy, setBusy] = useState(false)
  // P2-2：后端 PLT_4000 message 带字段前缀 → 红字落到对应字段；无字段名 → 表单顶部
  const [fieldErr, setFieldErr] = useState<Record<string, string>>({})
  const [topErr, setTopErr] = useState('')

  const products: RemoteProduct[] = useMemo(() => productRows ?? [], [productRows])
  // 产品可见预选（QA 复审 MUST-FIX）：默认选中并展示第一个产品，避免下拉显示「未选择」却静默落库
  useEffect(() => {
    if (!productId && products.length > 0) setProductId(products[0].id)
  }, [productId, products])
  // 所属组件按产品联动过滤（组件实体自带 productId）
  const componentOptions = useMemo(
    () => (componentRows ?? []).filter((c) => !productId || c.productId === productId),
    [componentRows, productId],
  )

  const fieldTag = (f: string) => (
    fieldErr[f] ? <span className="mt-1 block text-[11px] text-bad">{fieldErr[f]}</span> : null
  )

  const submit = async () => {
    if (busy) return
    setFieldErr({})
    setTopErr('')
    if (!title.trim()) {
      setFieldErr({ title: '标题必填' })
      return
    }
    // R-8：必填仅 标题+严重度（severity 四档恒有默认值）；productId 是后端 path 根硬约束，缺省提前拦截
    const pid = productId
    if (!pid) {
      const msg = 'productId 或 goalId 必填其一（当前无可用产品，请先在产品/版本页确认数据）'
      setTopErr(msg)
      onError?.(msg)
      return
    }
    setBusy(true)
    try {
      // 「发现环境/复现步骤」无独立列 → 合入 description（见文件头说明），缺陷中心原样展示
      const descParts = [
        foundEnv.trim() ? `【发现环境】${foundEnv.trim()}` : '',
        description.trim(),
        reproSteps.trim() ? `【复现步骤】\n${reproSteps.trim()}` : '',
      ].filter(Boolean)
      const created = await defectsApi.create({
        title: title.trim(),
        severity,
        priority,
        assigneeId: assigneeId || [...users.values()][0]?.id || '',
        productId: pid,
        blockedReleaseId: blockedReleaseId || undefined,
        description: descParts.join('\n\n') || undefined,
        componentId: componentId || undefined,
        sprintId: sprintId || undefined,
        releaseId: releaseId || undefined,
        dueDate: dueDate || undefined,
      })
      void queryClient.invalidateQueries({ queryKey: ['defects'] })
      void queryClient.invalidateQueries({ queryKey: ['work-items'] })
      onCreated(created.key)
      onClose()
    } catch (e) {
      const parsed = parseFieldError(e)
      if (parsed.field) setFieldErr({ [parsed.field]: parsed.message })
      else setTopErr(parsed.message)
      onError?.(parsed.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" onClick={onClose}>
      <div className="max-h-[92vh] w-[480px] max-w-full overflow-y-auto rounded-card border border-line bg-canvas p-5 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-1.5 text-sm font-bold text-txt-hi">
            <FlaskConical size={15} className="text-cat-orange" /> 登记缺陷
          </h3>
          <button type="button" onClick={onClose} className="cursor-pointer text-txt-low hover:text-txt-hi"><X size={16} /></button>
        </div>
        <div className="mt-3 space-y-2.5">
          <label className="block text-xs text-txt-mid">
            标题（必填）
            <input value={title} onChange={(e) => setTitle(e.target.value)} autoFocus placeholder="缺陷现象一句话…" className={`mt-1 ${inputCls} ${fieldErr.title ? 'border-bad' : ''}`} />
            {fieldTag('title')}
          </label>
          <div className="grid grid-cols-2 gap-2.5">
            <label className="block text-xs text-txt-mid">
              严重度（必填，四档）
              <select value={severity} onChange={(e) => setSeverity(e.target.value as DefectSeverity)} className={`mt-1 ${inputCls}`}>
                {SEVS.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
              {fieldTag('severity')}
            </label>
            <label className="block text-xs text-txt-mid">
              优先级
              <select value={priority} onChange={(e) => setPriority(e.target.value as WorkPriority)} className={`mt-1 ${inputCls}`}>
                {(['P0', 'P1', 'P2', 'P3'] as const).map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
              {fieldTag('priority')}
            </label>
            <label className="block text-xs text-txt-mid">
              产品
              <select
                value={productId}
                onChange={(e) => { setProductId(e.target.value); setComponentId('') }}
                className={`mt-1 ${inputCls}`}
              >
                <option value="">选择产品…</option>
                {products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
              {fieldTag('productId')}
            </label>
            <label className="block text-xs text-txt-mid">
              所属组件
              <select value={componentId} onChange={(e) => setComponentId(e.target.value)} className={`mt-1 ${inputCls}`}>
                <option value="">（不指定）</option>
                {componentOptions.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
              {fieldTag('componentId')}
            </label>
            <label className="block text-xs text-txt-mid">
              处理人（修复人）
              <select value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)} className={`mt-1 ${inputCls}`}>
                <option value="">（默认自己）</option>
                {[...users.values()].map((u) => <option key={u.id} value={u.id}>{u.name} · {u.title}</option>)}
              </select>
              {fieldTag('assigneeId')}
            </label>
            <label className="block text-xs text-txt-mid">
              报告人
              <div className="mt-1 flex h-[34px] items-center rounded-input border border-line bg-ink-800/60 px-2.5 text-xs text-txt-low">当前登录人（服务端落 reporter）</div>
            </label>
            {showSchedule && (
              <>
                <label className="block text-xs text-txt-mid">
                  迭代
                  <select value={sprintId} onChange={(e) => setSprintId(e.target.value)} className={`mt-1 ${inputCls}`}>
                    <option value="">（不挂迭代）</option>
                    {(sprintRows ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                  </select>
                  {fieldTag('sprintId')}
                </label>
                <label className="block text-xs text-txt-mid">
                  版本
                  <select value={releaseId} onChange={(e) => setReleaseId(e.target.value)} className={`mt-1 ${inputCls}`}>
                    <option value="">（不挂版本）</option>
                    {(releaseRows ?? []).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                  </select>
                  {fieldTag('releaseId')}
                </label>
                <label className="block text-xs text-txt-mid">
                  截止日
                  <input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} className={`mt-1 ${inputCls}`} />
                  {fieldTag('dueDate')}
                </label>
              </>
            )}
            <label className={`block text-xs text-txt-mid ${showSchedule ? '' : 'col-span-2'}`}>
              阻塞版本（致命/严重未关闭时锁定发布）
              <select value={blockedReleaseId} onChange={(e) => setBlockedReleaseId(e.target.value)} className={`mt-1 ${inputCls}`}>
                <option value="">（不阻塞版本）</option>
                {(releaseRows ?? []).filter((r) => r.status !== 'released').map((r) => (
                  <option key={r.id} value={r.id}>{r.name}{r.planDate ? ` · 计划 ${r.planDate.slice(0, 10)}` : ''}</option>
                ))}
              </select>
              {fieldTag('blockedReleaseId')}
            </label>
          </div>
          <label className="block text-xs text-txt-mid">
            描述
            <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} placeholder="缺陷影响与期望行为（可选）" className={`mt-1 ${inputCls} resize-none`} />
            {fieldTag('description')}
          </label>
          <label className="block text-xs text-txt-mid">
            复现步骤
            <textarea value={reproSteps} onChange={(e) => setReproSteps(e.target.value)} rows={3} placeholder={'1. 打开…\n2. 点击…\n3. 观察…（可选，随描述入库）'} className={`mt-1 ${inputCls} resize-none`} />
          </label>
          <label className="block text-xs text-txt-mid">
            发现实环境
            <input value={foundEnv} onChange={(e) => setFoundEnv(e.target.value)} placeholder="如：Windows 11 · Chrome 126 · v2.4.0-rc.2（可选）" className={`mt-1 ${inputCls}`} />
          </label>
          {(severity === '致命' || severity === '严重') && (
            <div className="rounded-lg bg-bad-bg px-2.5 py-2 text-[11px] text-bad-deep">
              <Pill tone={severityTone[severity]}>{severity}</Pill>
              <span className="ml-1.5">缺陷为强制事件：服务端将自动创建话题并按干系人规则拉人（报告人 / 修复人 / 版本负责人）；未关闭时阻塞所属版本发布。</span>
            </div>
          )}
          {topErr && <div className="rounded-lg bg-bad-bg px-2.5 py-2 text-[11px] text-bad-deep">{topErr}</div>}
        </div>
        <div className="mt-4 flex justify-end gap-2 border-t border-line pt-3">
          <Btn variant="ghost" onClick={onClose}>取消</Btn>
          <Btn variant="primary" onClick={() => void submit()} disabled={!title.trim() || busy}>{busy ? '登记中…' : '登记'}</Btn>
        </div>
      </div>
    </div>
  )
}
