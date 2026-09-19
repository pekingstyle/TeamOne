// 版本与发布（W4 API 化 + R-9 去 mock · B2 批）：版本列车数据来源 = 真实 API（M1）GET /api/v1/releases
// + publish 按钮 → mutation POST /releases/{key}/publish —— 422 T1-PRD-4230（致命/严重缺陷）/
//   T1-PRD-4231（未完结工作项，R-9 硬门禁）时渲染门禁明细（红标卡）
// + 订阅 gate:{key} → defect.blocked_changed 时刷新对应卡片（blocked=false 解锁视觉反馈）
// 流水线/部署区 = R-9 真实 API（eng 侧供数）：GET /releases/{id}/pipelines、GET /releases/{id}/deployments
//   + 管理员「登记部署」（POST 同路径）；构建自动联动属 M4/M5（docs/v2/11 CI/CD 规划），本批登记+展示闭环
// 原型演示卡（环境部署/制品库 store mock）已按全栈评审意见移除
import { Fragment, useEffect, useState } from 'react'
import { ArrowRight, CheckCircle2, Lock, Rocket, RefreshCw, Unlock } from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { dateStr, daysBetween } from '../../data/store'
import {
  deploymentsApi, releasesApi,
  useGateChannel, useReleaseDeployments, useReleasePipelines, useReleases,
} from '../../api/queries'
import type { BlockedChangedPayload, ReleaseDeploymentItem, RemoteRelease } from '../../api/queries'
import { ApiError } from '../../api/client'
import { useAuth } from '../../api/AuthContext'
import { Btn, Card, CardHeader, Empty, PageHeader, Pill } from '../../components/ui'
import type { Nav, PageProps } from '../../nav'

/** 后端版本状态 → 中文 + 胶囊 tone（实测 status ∈ planned/coding/code_freeze/blocked/released） */
const statusZh: Record<string, string> = {
  planned: '已规划', coding: '编码中', code_freeze: '代码冻结', blocked: '发布阻塞', released: '已发布',
}
function StatusPill({ status }: { status: string }) {
  if (status === 'released') return <Pill tone="ok">{statusZh[status]}</Pill>
  if (status === 'blocked') return <Pill tone="bad">{statusZh[status]}</Pill>
  if (status === 'code_freeze') return <Pill tone="warn">{statusZh[status]}</Pill>
  if (status === 'coding') return <span className="inline-flex items-center rounded-full bg-info-bg px-2 py-0.5 text-xs font-semibold leading-4 text-cat-blue">{statusZh[status]}</span>
  return <Pill tone="neutral">{statusZh[status] ?? status}</Pill>
}

/** 部署状态 → 中文徽标（R-9：running/success/failed/rolled_back） */
function DeployPill({ status }: { status: string }) {
  if (status === 'success') return <Pill tone="ok">成功</Pill>
  if (status === 'running') return <Pill tone="warn">部署中</Pill>
  if (status === 'failed') return <Pill tone="bad">失败</Pill>
  if (status === 'rolled_back') return <Pill tone="orange">已回滚</Pill>
  return <Pill tone="neutral">{status}</Pill>
}

/** 部署环境 → 中文（R-9 登记口径 dev/staging/prod） */
const envZh: Record<string, string> = { dev: '开发', staging: '预发', prod: '生产' }

/** ISO 时间 → 页面展示（YYYY-MM-DD HH:mm） */
function fmtTime(iso?: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 倒计时：≤3 天红 / ≤7 天 warn / released 显示发布日 */
function countdownOf(r: RemoteRelease): { text: string; cls: string } {
  if (r.status === 'released') return { text: `发布日 ${(r.releasedAt ?? r.planDate ?? '').slice(0, 10)}`, cls: 'text-ok-deep' }
  if (!r.planDate) return { text: '计划未定', cls: 'text-txt-mid' }
  const d = daysBetween(dateStr(0), r.planDate)
  if (d < 0) return { text: `逾期 ${-d} 天`, cls: 'text-bad font-bold' }
  if (d <= 3) return { text: `T-${d} 天`, cls: 'text-bad font-bold' }
  if (d <= 7) return { text: `T-${d} 天`, cls: 'text-warn-deep font-semibold' }
  return { text: `T-${d} 天`, cls: 'text-txt-mid' }
}

function TrainCard({ r, selected, justUnlocked, onSelect, nav }: { r: RemoteRelease; selected: boolean; justUnlocked: boolean; onSelect: () => void; nav: Nav }) {
  const cd = countdownOf(r)
  return (
    <div
      onClick={onSelect}
      className={`min-w-[252px] shrink-0 cursor-pointer rounded-card border bg-card p-3 transition-shadow hover:shadow-card ${
        r.blocked ? 'border-2 border-bad' : justUnlocked ? 'border-2 border-ok' : selected ? 'border-brand ring-2 ring-brand-bg' : 'border-line'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className="font-mono text-sm font-bold text-txt-hi">{r.key}</span>
        <span className="truncate text-xs text-txt-low" title={r.name}>{r.name}</span>
        <span className="ml-auto"><StatusPill status={r.status} /></span>
      </div>
      {r.planDate && (
        <div className="mt-2 flex items-end gap-2">
          <span className="text-lg font-bold leading-6 text-txt-hi">{r.planDate}</span>
          <span className={`text-xs ${cd.cls}`}>{cd.text}</span>
        </div>
      )}
      {justUnlocked && (
        <div className="mt-2 flex items-center gap-1 rounded-md bg-ok-bg px-2 py-1.5 text-[11px] font-bold text-ok-deep">
          <Unlock size={11} /> 门禁已解除 · 可发布
        </div>
      )}
      {r.blocked && (
        <div className="mt-2 rounded-md bg-bad-bg px-2 py-1.5">
          <div className="flex items-center gap-1 text-[11px] font-bold text-bad-deep"><Lock size={11} /> 发布锁定 · 阻塞缺陷 {r.blockedDefectKeys.length} 个</div>
          <div className="mt-1 flex flex-wrap gap-1">
            {r.blockedDefectKeys.map((key) => (
              <button
                key={key}
                type="button"
                onClick={(e) => { e.stopPropagation(); nav.go('defects', key) }}
                className="cursor-pointer rounded bg-canvas px-1.5 py-0.5 text-[11px] font-bold text-bad-deep hover:bg-bad/20"
              >
                {key}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

/** 「流水线」区（R-9 真数据 GET /releases/{id}/pipelines；构建联动规划见 docs/v2/11，本批只读展示） */
function PipelineSection({ releaseId }: { releaseId: string | undefined }) {
  const { data: items = [], isLoading } = useReleasePipelines(releaseId)
  return (
    <Card className="mt-4">
      <CardHeader title="流水线" extra={<span className="text-xs text-txt-low">构建 → 测试 → 质量门禁（只读）· GET /releases/{releaseId}/pipelines · 最新 20 条</span>} />
      <div className="px-4 py-3">
        {isLoading && <div className="py-2 text-sm text-txt-low">加载中…</div>}
        {!isLoading && items.length === 0 && (
          <Empty text="该版本尚未关联流水线（构建联动规划见 CI/CD 规划）" size="sm" />
        )}
        <div className="space-y-2">
          {items.map((p) => (
            <div key={p.id} className="rounded-md border border-line bg-canvas px-3 py-2">
              <div className="flex flex-wrap items-center gap-2 text-xs">
                <span className="font-semibold text-txt-hi">{p.repoName}</span>
                <span className="font-mono text-txt-mid">{p.branch}</span>
                <span className="font-mono text-txt-low">{p.commitSha.slice(0, 7)}</span>
                <span className="ml-auto text-txt-low">{fmtTime(p.createdAt)}</span>
                {p.status === 'passed' ? <Pill tone="ok">通过</Pill>
                  : p.status === 'failed' ? <Pill tone="bad">失败</Pill>
                  : p.status === 'running' ? <Pill tone="warn">执行中</Pill>
                  : <Pill tone="neutral">{p.status}</Pill>}
              </div>
              {p.stages.length > 0 && (
                <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                  {p.stages.map((s, i) => (
                    <Fragment key={`${p.id}-${i}`}>
                      {i > 0 && <ArrowRight size={10} className="text-txt-low" />}
                      <span className={`rounded px-1.5 py-0.5 text-[11px] font-semibold ${
                        s.status === 'passed' ? 'bg-ok-bg text-ok-deep' : s.status === 'failed' ? 'bg-bad-bg text-bad-deep' : 'bg-ink-700 text-txt-mid'
                      }`} title={s.name}>{s.name}</span>
                    </Fragment>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </Card>
  )
}

/** 「部署」区（R-9 真数据 GET /releases/{id}/deployments）+ 管理员「登记部署」小表单 */
function DeploySection({ releaseId }: { releaseId: string | undefined }) {
  const { user } = useAuth()
  // platform:manage 同族：OWNER/ADMIN 可登记（与冲突中心 recompute 入口口径一致）
  const isAdmin = user?.platformRole === 'OWNER' || user?.platformRole === 'ADMIN'
  const { data: items = [], isLoading } = useReleaseDeployments(releaseId)
  const [env, setEnv] = useState('dev')
  const [artifactVersion, setArtifactVersion] = useState('')
  const [note, setNote] = useState('')
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)

  const registerMut = useMutation({
    mutationFn: () =>
      deploymentsApi.register(releaseId!, {
        env,
        artifactVersion: artifactVersion.trim() || undefined,
        note: note.trim() || undefined,
      }),
    onSuccess: () => {
      setMsg({ ok: true, text: '部署登记成功' })
      setArtifactVersion('')
      setNote('')
    },
    onError: (err) => {
      setMsg({ ok: false, text: err instanceof ApiError ? `登记失败：${err.message}` : '登记失败：网络异常' })
    },
  })

  return (
    <Card className="mt-4">
      <CardHeader title="部署" extra={<span className="text-xs text-txt-low">开发 → 预发 → 生产 · GET/POST /releases/{releaseId}/deployments · 真实执行联动属 M4/M5</span>} />
      <div className="grid gap-4 px-4 py-3 lg:grid-cols-3">
        <div className="lg:col-span-2">
          {isLoading && <div className="py-2 text-sm text-txt-low">加载中…</div>}
          {!isLoading && items.length === 0 && <Empty text="该版本暂无部署记录" size="sm" />}
          {!isLoading && items.length > 0 && (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-txt-low">
                  {['环境', '状态', '制品版本', '部署时间', '备注'].map((h) => <th key={h} className="py-2 pr-3 font-medium">{h}</th>)}
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {items.map((d: ReleaseDeploymentItem, i) => (
                  <tr key={`${d.env}-${d.deployedAt}-${i}`} className="hover:bg-ink-700/40">
                    <td className="py-2 pr-3 font-medium text-txt-hi">{envZh[d.env] ?? d.env}</td>
                    <td className="py-2 pr-3"><DeployPill status={d.status} /></td>
                    <td className="py-2 pr-3 font-mono text-xs font-bold text-brand-deep">{d.artifactVersion ?? '—'}</td>
                    <td className="py-2 pr-3 tabular-nums text-txt-mid">{fmtTime(d.deployedAt)}</td>
                    <td className="max-w-[220px] truncate py-2 pr-3 text-xs text-txt-low" title={d.note}>{d.note ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        {isAdmin && (
          <div className="rounded-md border border-line bg-canvas p-3">
            <div className="text-xs font-semibold text-txt-mid">登记部署</div>
            <div className="mt-2 space-y-2">
              <select value={env} onChange={(e) => setEnv(e.target.value)} className="w-full cursor-pointer rounded-input border border-line bg-card px-2 py-1.5 text-xs text-txt-hi">
                <option value="dev">开发（dev）</option>
                <option value="staging">预发（staging）</option>
                <option value="prod">生产（prod）</option>
              </select>
              <input
                value={artifactVersion}
                onChange={(e) => setArtifactVersion(e.target.value)}
                placeholder="制品版本，如 2.4.0-rc.3"
                className="w-full rounded-input border border-line bg-card px-2 py-1.5 text-xs text-txt-hi placeholder:text-txt-low"
              />
              <input
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="备注（可选）"
                className="w-full rounded-input border border-line bg-card px-2 py-1.5 text-xs text-txt-hi placeholder:text-txt-low"
              />
              <Btn
                variant="primary"
                className="w-full justify-center"
                disabled={!releaseId || registerMut.isPending}
                onClick={() => registerMut.mutate()}
              >
                {registerMut.isPending ? '登记中…' : '登记部署'}
              </Btn>
              {msg && (
                <div className={`rounded px-2 py-1 text-[11px] font-semibold ${msg.ok ? 'bg-ok-bg text-ok-deep' : 'bg-bad-bg text-bad-deep'}`}>{msg.text}</div>
              )}
              <div className="text-[10px] leading-4 text-txt-low">POST /releases/{releaseId}/deployments · platform:manage · 审计留痕</div>
            </div>
          </div>
        )}
      </div>
    </Card>
  )
}

export default function DeliveryPage({ nav, id }: PageProps) {
  const queryClient = useQueryClient()
  // 数据来源：真实 API（M1）——版本列车 / 发布门禁
  const { data: releases = [], isLoading, refetch, isFetching } = useReleases()
  const sorted = [...releases].sort((a, b) => (a.planDate ?? '9999').localeCompare(b.planDate ?? '9999'))

  const [selId, setSelId] = useState<string | undefined>(id)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [blockers, setBlockers] = useState<string[]>([])
  const [justUnlocked, setJustUnlocked] = useState<Record<string, boolean>>({})
  useEffect(() => { if (id) setSelId(id) }, [id])
  useEffect(() => { setMsg(null); setBlockers([]) }, [selId])

  // 门禁频道 gate:{id} → defect.blocked_changed → 刷新对应卡片；blocked=false 给解锁视觉反馈
  useGateChannel(releases.map((r) => r.id), (p: BlockedChangedPayload) => {
    if (!p.blocked) {
      setJustUnlocked((s) => ({ ...s, [p.releaseId]: true }))
      setTimeout(() => setJustUnlocked((s) => ({ ...s, [p.releaseId]: false })), 8000)
    }
  })

  const sel = releases.find((r) => r.id === selId) ?? sorted[0]

  const publishMut = useMutation({
    mutationFn: (r: RemoteRelease) => releasesApi.publish(r.key),
    onSuccess: (raw) => {
      setBlockers([])
      setMsg({ ok: true, text: `${raw.name}（${raw.key}）发布成功！` })
      void queryClient.invalidateQueries({ queryKey: ['releases'] })
      void queryClient.invalidateQueries({ queryKey: ['defects'] })
    },
    onError: (err, r) => {
      if (err instanceof ApiError && err.status === 422 && (err.code === 'T1-PRD-4230' || err.code === 'T1-PRD-4231')) {
        // 4230：details = 阻塞缺陷清单（实测 "D-96 致命 admin"）；4231（R-9）：details = 未完结工作项分组明细
        setBlockers(err.details)
        const text = err.code === 'T1-PRD-4231'
          ? `发布被门禁拦截：${r.key} 存在未完结工作项，请先流转至完成态。`
          : `发布被门禁拦截：${r.key} 存在未关闭致命/严重缺陷 ${err.details.length} 个，请先修复并回归。`
        setMsg({ ok: false, text })
      } else {
        setMsg({ ok: false, text: err instanceof ApiError ? `发布失败：${err.message}` : '发布失败：网络异常' })
      }
    },
  })

  return (
    <div>
      <PageHeader
        title="版本与发布"
        desc="版本列车 · 发布门禁（致命/严重缺陷未关闭即锁定 · 未完结工作项拦截）· Deadline 倒计时 · 数据来源：真实 API（M1）"
        actions={
          <Btn onClick={() => void refetch()} disabled={isFetching}>
            <RefreshCw size={14} className={isFetching ? 'animate-spin' : undefined} /> 刷新
          </Btn>
        }
      />

      {/* 版本列车 */}
      <Card>
        <CardHeader title="版本列车" extra={<span className="text-xs text-txt-low">按 planDate 排序 · 点击卡片查看详情 · GET /api/v1/releases</span>} />
        <div className="flex items-stretch gap-2 overflow-x-auto px-4 py-3">
          {isLoading && <div className="px-2 py-6 text-sm text-txt-low">加载中…</div>}
          {!isLoading && sorted.length === 0 && <div className="w-full"><Empty text="暂无版本" /></div>}
          {sorted.map((r, i) => (
            <Fragment key={r.id}>
              {i > 0 && <ArrowRight size={15} className="mt-12 hidden shrink-0 text-txt-low md:block" />}
              <TrainCard r={r} selected={sel?.id === r.id} justUnlocked={!!justUnlocked[r.id]} onSelect={() => setSelId(r.id)} nav={nav} />
            </Fragment>
          ))}
        </div>
      </Card>

      {/* 选中版本详情 */}
      {sel && (
        <Card className="mt-4">
          <CardHeader
            title={<span className="flex items-center gap-2"><span className="font-mono">{sel.key}</span><span className="text-xs font-normal text-txt-mid">{sel.name}</span><StatusPill status={sel.status} /></span>}
            extra={<span className="text-xs text-txt-low">发布成功后关联话题自动归档（服务端联动）</span>}
          />
          <div className="grid gap-4 px-4 py-3 lg:grid-cols-3">
            <div className="lg:col-span-2">
              <div className="text-xs font-semibold text-txt-mid">发布说明</div>
              <div className="mt-1.5 whitespace-pre-wrap rounded-md border border-line bg-canvas p-3 text-sm leading-6 text-txt-hi">（待补充）</div>
              {msg && (
                <div className={`mt-2 flex items-start gap-1.5 rounded-md px-3 py-2 text-sm ${msg.ok ? 'bg-ok-bg text-ok-deep' : 'bg-bad-bg text-bad-deep'}`}>
                  {msg.ok ? <CheckCircle2 size={15} className="mt-0.5 shrink-0" /> : <Lock size={15} className="mt-0.5 shrink-0" />}
                  {msg.text}
                </div>
              )}
              {/* 422 门禁明细：T1-PRD-4230 = "KEY 严重度 负责人"；T1-PRD-4231 = 分组明细（任务×2（T-103、T-104）） */}
              {blockers.length > 0 && (
                <div className="mt-2 rounded-md border border-bad/30 bg-bad-bg p-3">
                  <div className="flex items-center gap-1.5 text-xs font-bold text-bad-deep"><Lock size={12} /> 门禁阻塞清单</div>
                  <div className="mt-1.5 space-y-1">
                    {blockers.map((line) => {
                      const parts = line.split(' ')
                      // 4230 缺陷行三段式可下钻缺陷中心；其余（4231 分组行）按纯文本展示
                      if (parts.length >= 3 && /^[\w]+-\d+$/.test(parts[0])) {
                        const [key, sev, owner] = parts
                        return (
                          <button key={line} type="button" onClick={() => nav.go('defects', key)} className="flex w-full cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-left text-xs hover:bg-canvas">
                            <span className="font-mono font-bold text-bad-deep">{key}</span>
                            <Pill tone={sev === '致命' ? 'bad' : 'orange'}>{sev}</Pill>
                            <span className="ml-auto text-txt-mid">{owner ?? '—'}</span>
                          </button>
                        )
                      }
                      return (
                        <div key={line} className="px-1 py-0.5 text-xs text-bad-deep">{line}</div>
                      )
                    })}
                  </div>
                </div>
              )}
              {!blockers.length && sel.blocked && (
                <div className="mt-2 rounded-md bg-bad-bg p-3">
                  <div className="text-xs font-bold text-bad-deep">阻塞清单（未关闭致命/严重缺陷）</div>
                  <div className="mt-1.5 space-y-1">
                    {sel.blockedDefectKeys.map((key) => (
                      <button key={key} type="button" onClick={() => nav.go('defects', key)} className="flex w-full cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-left text-xs hover:bg-canvas">
                        <span className="font-mono font-bold text-bad-deep">{key}</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>
            <div className="space-y-2.5 text-sm">
              <div className="flex items-center gap-2">
                <span className="w-14 shrink-0 text-xs text-txt-low">版本名</span>
                <span className="text-txt-hi">{sel.name}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="w-14 shrink-0 text-xs text-txt-low">计划发布</span>
                {sel.planDate ? (
                  <>
                    <span className="font-semibold text-txt-hi">{sel.planDate}</span>
                    <span className="text-xs">{(() => { const c = countdownOf(sel); return <span className={c.cls}>{c.text}</span> })()}</span>
                  </>
                ) : <span className="text-txt-low">未排期</span>}
              </div>
              {/* R-9 去 mock：原「发布进度」100/60/30 硬编码进度条移除（无真实进度数据源，状态见徽标） */}
              <div className="flex items-center gap-2">
                <span className="w-14 shrink-0 text-xs text-txt-low">乐观锁</span>
                <span className="font-mono text-xs text-txt-mid">v{(sel as unknown as { version: number }).version} · 更新 {sel.updatedAt.slice(0, 16).replace('T', ' ')}</span>
              </div>
              <div className="border-t border-line pt-3">
                {sel.status === 'released' ? (
                  <Pill tone="ok"><CheckCircle2 size={12} /> 已于 {(sel.releasedAt ?? '').slice(0, 10)} 发布</Pill>
                ) : sel.blocked ? (
                  <Btn variant="danger" disabled className="w-full justify-center">
                    <Lock size={14} /> 发布锁定 · 未关闭致命/严重缺陷 {sel.blockedDefectKeys.length} 个
                  </Btn>
                ) : (
                  <Btn variant="primary" className="w-full justify-center" onClick={() => publishMut.mutate(sel)} disabled={publishMut.isPending}>
                    <Rocket size={14} /> {publishMut.isPending ? '发布中…' : `发布 ${sel.key}`}
                  </Btn>
                )}
                <div className="mt-1.5 text-[11px] text-txt-low">POST /releases/{sel.key}/publish · 门禁 422 T1-PRD-4230/4231 · 发布成功后关联话题自动归档</div>
              </div>
            </div>
          </div>
        </Card>
      )}

      {/* 流水线（R-9 真数据；空态给 CI/CD 规划指引） */}
      {sel && <PipelineSection releaseId={sel.id} />}

      {/* 部署（R-9 真数据 + 管理员登记） */}
      {sel && <DeploySection releaseId={sel.id} />}
    </div>
  )
}
