// 版本与发布（R2 发布门禁 + R3 Deadline）：版本列车 + 版本详情/发布门禁 + 环境部署 + 制品库
import { Fragment, useEffect, useState } from 'react'
import { ArrowRight, CheckCircle2, Lock, Rocket } from 'lucide-react'
import type { DeployEnv, Release, ReleaseStatus } from '../../data/types'
import {
  artifacts, baselineById, completeSprint, dateStr, daysBetween, deployEnvs, deployToEnv,
  pipelineById, productById, publishRelease, releases, sprints, useStore, userById, workItemById,
} from '../../data/store'
import { Avatar, Bar, Btn, Card, CardHeader, PageHeader, Pill } from '../../components/ui'
import type { Nav, PageProps } from '../../nav'

const blStatusText: Record<string, string> = { draft: '草稿', in_review: '评审中', approved: '已批准·冻结', superseded: '已废止' }

function StatusPill({ status }: { status: ReleaseStatus }) {
  if (status === 'released') return <Pill tone="ok">已发布</Pill>
  if (status === 'testing') return <Pill tone="info">测试中</Pill>
  if (status === 'coding') return <span className="inline-flex items-center rounded-full bg-info-bg px-2 py-0.5 text-xs font-semibold leading-4 text-cat-blue">编码中</span>
  return <Pill tone="neutral">已规划</Pill>
}

/** 倒计时：≤3 天红 / ≤7 天 warn / released 显示发布日 */
function countdownOf(r: Release): { text: string; cls: string } {
  if (r.status === 'released') return { text: `发布日 ${r.releasedAt ?? r.planDate}`, cls: 'text-ok-deep' }
  const d = daysBetween(dateStr(0), r.planDate)
  if (d < 0) return { text: `逾期 ${-d} 天`, cls: 'text-bad font-bold' }
  if (d <= 3) return { text: `T-${d} 天`, cls: 'text-bad font-bold' }
  if (d <= 7) return { text: `T-${d} 天`, cls: 'text-warn-deep font-semibold' }
  return { text: `T-${d} 天`, cls: 'text-txt-mid' }
}

function TrainCard({ r, selected, onSelect, nav }: { r: Release; selected: boolean; onSelect: () => void; nav: Nav }) {
  const p = productById(r.productId)
  const cd = countdownOf(r)
  const readyPct = r.testTaskTotalCount === 0 ? 0 : Math.round((r.testTaskDoneCount / r.testTaskTotalCount) * 100)
  return (
    <div
      onClick={onSelect}
      className={`min-w-[252px] shrink-0 cursor-pointer rounded-card border bg-card p-3 transition-shadow hover:shadow-card ${
        r.blocked ? 'border-2 border-bad' : selected ? 'border-brand ring-2 ring-brand-bg' : 'border-line'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className="font-mono text-sm font-bold text-txt-hi">{r.name}</span>
        <span className="rounded bg-info-bg px-1 text-[10px] font-bold text-cat-blue">{p?.key ?? r.productId}</span>
        <span className="ml-auto"><StatusPill status={r.status} /></span>
      </div>
      <div className="mt-2 flex items-end gap-2">
        <span className="text-lg font-bold leading-6 text-txt-hi">{r.planDate}</span>
        <span className={`text-xs ${cd.cls}`}>{cd.text}</span>
      </div>
      <div className="mt-0.5 text-[11px] text-txt-low">冻结 {r.codeFreezeDate}</div>
      <div className="mt-2 flex items-center gap-2">
        <Bar value={r.progress} tone="brand" className="flex-1" />
        <span className="text-[11px] font-bold tabular-nums text-txt-mid">{r.progress}%</span>
      </div>
      {/* 发布就绪分：测试任务完成率 */}
      <div className="mt-1.5 flex items-center gap-2">
        <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-ink-700">
          <div className="h-full rounded-full bg-cat-teal transition-all duration-500" style={{ width: `${readyPct}%` }} />
        </div>
        <span className="text-[11px] tabular-nums text-txt-mid">就绪 {r.testTaskDoneCount}/{r.testTaskTotalCount}</span>
      </div>
      {r.blocked && (
        <div className="mt-2 rounded-md bg-bad-bg px-2 py-1.5">
          <div className="flex items-center gap-1 text-[11px] font-bold text-bad-deep"><Lock size={11} /> 发布锁定 · 阻塞缺陷</div>
          <div className="mt-1 flex flex-wrap gap-1">
            {r.blockedDefectIds.map((id) => {
              const d = workItemById(id)
              return (
                <button
                  key={id}
                  type="button"
                  onClick={(e) => { e.stopPropagation(); nav.go('defects', id) }}
                  className="cursor-pointer rounded bg-canvas px-1.5 py-0.5 text-[11px] font-bold text-bad-deep hover:bg-bad/20"
                  title={d?.title ?? id}
                >
                  {d?.key ?? id}
                </button>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

function EnvCard({ env, sel, onSel, onDeploy, confirming, onConfirm, onCancel }: {
  env: DeployEnv; sel: string; onSel: (v: string) => void; onDeploy: () => void
  confirming: boolean; onConfirm: () => void; onCancel: () => void
}) {
  const dot = env.status === 'healthy' ? 'bg-ok' : env.status === 'deploying' ? 'bg-warn animate-pulse' : 'bg-bad'
  return (
    <div className="min-w-0 flex-1 rounded-card border border-line bg-card p-3">
      <div className="flex items-center gap-2">
        <span className={`h-2 w-2 shrink-0 rounded-full ${dot}`} />
        <span className="text-sm font-semibold text-txt-hi">{env.name}</span>
        <span className="ml-auto truncate text-[11px] text-txt-low">{env.url}</span>
      </div>
      <div className="mt-2 truncate font-mono text-sm font-bold text-brand-deep" title={env.currentVersion}>{env.currentVersion}</div>
      <div className="mt-1.5 flex items-center gap-1.5 text-[11px] text-txt-low">
        <Avatar userId={env.deployedById} size={16} /> {userById(env.deployedById)?.name ?? '—'} · {env.lastDeployAt}
      </div>
      {env.status === 'deploying' ? (
        <div className="mt-2"><Pill tone="warn">部署中…</Pill></div>
      ) : confirming ? (
        <div className="mt-2 rounded-md bg-bad-bg p-2">
          <div className="text-[11px] font-bold leading-4 text-bad-deep">⚠ 生产环境部署影响线上用户，需二次确认</div>
          <div className="mt-1.5 flex gap-1.5">
            <Btn variant="danger" onClick={onConfirm}>确认部署</Btn>
            <Btn variant="ghost" onClick={onCancel}>取消</Btn>
          </div>
        </div>
      ) : (
        <div className="mt-2 flex items-center gap-1.5">
          <select value={sel} onChange={(e) => onSel(e.target.value)} className="min-w-0 flex-1 cursor-pointer rounded-input border border-line bg-canvas px-1.5 py-1 text-xs text-txt-hi">
            {artifacts.map((a) => <option key={a.id} value={a.id}>{a.name} {a.version}</option>)}
          </select>
          <Btn onClick={onDeploy}>部署</Btn>
        </div>
      )}
    </div>
  )
}

export default function DeliveryPage({ nav, id }: PageProps) {
  useStore()
  const sorted = [...releases].sort((a, b) => a.planDate.localeCompare(b.planDate))
  const [selId, setSelId] = useState(id ?? sorted[0]?.id)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [deployPick, setDeployPick] = useState<Record<string, string>>({})
  const [confirmEnv, setConfirmEnv] = useState<string | null>(null)
  useEffect(() => { if (id) setSelId(id) }, [id])
  useEffect(() => { setMsg(null); setConfirmEnv(null) }, [selId])

  const sel = releases.find((r) => r.id === selId) ?? sorted[0]
  const bl = sel?.baselineId ? baselineById(sel.baselineId) : undefined
  const relSprint = sel ? sprints.find((s) => s.releaseId === sel.id && s.status === 'active') : undefined
  const linkedSprints = sel ? sprints.filter((s) => s.releaseId === sel.id) : []

  const handlePublish = () => {
    if (!sel) return
    const res = publishRelease(sel.id)
    if (!res.ok) {
      setMsg({ ok: false, text: `发布被门禁拦截：阻塞缺陷 ${res.blockers.map((d) => d.key).join('、')} 未关闭，请先修复并回归。` })
    } else {
      setMsg({ ok: true, text: `${sel.name} 发布成功！发布通告已推送「发布通告」频道，关联话题已自动归档并沉淀发布结论。` })
    }
  }

  const doDeploy = (env: DeployEnv, confirmed: boolean) => {
    const art = artifacts.find((a) => a.id === (deployPick[env.id] ?? artifacts[0]?.id)) ?? artifacts[0]
    if (!art) return
    if (env.name === '生产' && !confirmed) { setConfirmEnv(env.id); return }
    deployToEnv(env.id, art.version, sel && sel.artifactIds.includes(art.id) ? sel.id : undefined)
    setConfirmEnv(null)
  }

  return (
    <div>
      <PageHeader
        title="版本与发布"
        desc="版本列车 · 发布门禁（致命/严重缺陷未关闭即锁定）· Deadline 倒计时 · 环境晋升 · 制品追溯"
      />

      {/* 版本列车 */}
      <Card>
        <CardHeader title="版本列车" extra={<span className="text-xs text-txt-low">按 planDate 排序 · 点击卡片查看详情</span>} />
        <div className="flex items-stretch gap-2 overflow-x-auto px-4 py-3">
          {sorted.map((r, i) => (
            <Fragment key={r.id}>
              {i > 0 && <ArrowRight size={15} className="mt-12 hidden shrink-0 text-txt-low md:block" />}
              <TrainCard r={r} selected={sel?.id === r.id} onSelect={() => setSelId(r.id)} nav={nav} />
            </Fragment>
          ))}
        </div>
      </Card>

      {/* 选中版本详情 */}
      {sel && (
        <Card className="mt-4">
          <CardHeader
            title={<span className="flex items-center gap-2"><span className="font-mono">{sel.name}</span><StatusPill status={sel.status} /></span>}
            extra={relSprint
              ? <Btn onClick={() => completeSprint(relSprint.id)}>完成迭代「{relSprint.name}」</Btn>
              : <span className="text-xs text-txt-low">关联迭代：{linkedSprints.map((s) => s.name).join('、') || '无'}</span>}
          />
          <div className="grid gap-4 px-4 py-3 lg:grid-cols-3">
            <div className="lg:col-span-2">
              <div className="text-xs font-semibold text-txt-mid">发布说明</div>
              <div className="mt-1.5 whitespace-pre-wrap rounded-md border border-line bg-canvas p-3 text-sm leading-6 text-txt-hi">{sel.releaseNotes || '（暂无）'}</div>
              {msg && (
                <div className={`mt-2 flex items-start gap-1.5 rounded-md px-3 py-2 text-sm ${msg.ok ? 'bg-ok-bg text-ok-deep' : 'bg-bad-bg text-bad-deep'}`}>
                  {msg.ok ? <CheckCircle2 size={15} className="mt-0.5 shrink-0" /> : <Lock size={15} className="mt-0.5 shrink-0" />}
                  {msg.text}
                </div>
              )}
              {sel.blocked && (
                <div className="mt-2 rounded-md bg-bad-bg p-3">
                  <div className="text-xs font-bold text-bad-deep">阻塞清单（严重级 / 负责人）</div>
                  <div className="mt-1.5 space-y-1">
                    {sel.blockedDefectIds.map((did) => {
                      const d = workItemById(did)
                      return (
                        <button key={did} type="button" onClick={() => nav.go('defects', did)} className="flex w-full cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-left text-xs hover:bg-canvas">
                          <span className="font-mono font-bold text-bad-deep">{d?.key ?? did}</span>
                          <span className="min-w-0 flex-1 truncate text-txt-hi">{d?.title}</span>
                          <span className="text-txt-mid">{userById(d?.assigneeId ?? '')?.name}</span>
                          {d?.type === 'defect' && <Pill tone={d.severity === '致命' ? 'bad' : 'orange'}>{d.severity}</Pill>}
                        </button>
                      )
                    })}
                  </div>
                </div>
              )}
            </div>
            <div className="space-y-2.5 text-sm">
              <div className="flex items-center gap-2">
                <span className="w-14 shrink-0 text-xs text-txt-low">负责人</span>
                <span className="flex items-center gap-1">
                  {sel.ownerIds.map((oid) => <Avatar key={oid} userId={oid} size={22} />)}
                  <span className="ml-1 text-xs text-txt-mid">{sel.ownerIds.map((oid) => userById(oid)?.name).join('、')}</span>
                </span>
              </div>
              <div className="flex items-center gap-2">
                <span className="w-14 shrink-0 text-xs text-txt-low">计划发布</span>
                <span className="font-semibold text-txt-hi">{sel.planDate}</span>
                <span className="text-xs">{(() => { const c = countdownOf(sel); return <span className={c.cls}>{c.text}</span> })()}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="w-14 shrink-0 text-xs text-txt-low">代码冻结</span>
                <span className="text-txt-hi">{sel.codeFreezeDate}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="w-14 shrink-0 text-xs text-txt-low">关联基线</span>
                {bl
                  ? <span className="flex items-center gap-1.5"><Pill tone={bl.status === 'approved' ? 'ok' : bl.status === 'in_review' ? 'warn' : 'neutral'}>{bl.name}</Pill><span className="text-xs text-txt-low">{blStatusText[bl.status]}</span></span>
                  : <span className="text-txt-low">未关联</span>}
              </div>
              <div className="flex items-center gap-2">
                <span className="w-14 shrink-0 text-xs text-txt-low">关联制品</span>
                <span className="text-txt-hi">{sel.artifactIds.length} 个 · 测试就绪 {sel.testTaskDoneCount}/{sel.testTaskTotalCount}</span>
              </div>
              <div className="border-t border-line pt-3">
                {sel.status === 'released' ? (
                  <Pill tone="ok"><CheckCircle2 size={12} /> 已于 {sel.releasedAt ?? sel.planDate} 发布</Pill>
                ) : sel.blocked ? (
                  <Btn variant="danger" disabled className="w-full justify-center">
                    <Lock size={14} /> 发布锁定 · 存在未关闭致命/严重缺陷 {sel.blockedDefectIds.length} 个
                  </Btn>
                ) : (
                  <Btn variant="primary" className="w-full justify-center" onClick={handlePublish}>
                    <Rocket size={14} /> 发布 {sel.name}
                  </Btn>
                )}
                <div className="mt-1.5 text-[11px] text-txt-low">发布成功后关联话题自动归档，通告推送 #发布通告 频道</div>
              </div>
            </div>
          </div>
        </Card>
      )}

      {/* 环境部署 */}
      <Card className="mt-4">
        <CardHeader title="环境部署" extra={<span className="text-xs text-txt-low">开发 → 测试 → 预发 → 生产 · 生产部署需二次确认</span>} />
        <div className="flex flex-col gap-3 px-4 py-3 xl:flex-row xl:items-stretch">
          {deployEnvs.map((env, i) => (
            <Fragment key={env.id}>
              {i > 0 && <ArrowRight size={14} className="hidden shrink-0 self-center text-txt-low xl:block" />}
              <EnvCard
                env={env}
                sel={deployPick[env.id] ?? artifacts[0]?.id ?? ''}
                onSel={(v) => setDeployPick((s) => ({ ...s, [env.id]: v }))}
                onDeploy={() => doDeploy(env, false)}
                confirming={confirmEnv === env.id}
                onConfirm={() => doDeploy(env, true)}
                onCancel={() => setConfirmEnv(null)}
              />
            </Fragment>
          ))}
        </div>
      </Card>

      {/* 制品库 */}
      <Card className="mt-4">
        <CardHeader title="制品库" extra={<Pill tone="teal">{artifacts.length} 个制品</Pill>} />
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-txt-low">
                {['名称', '版本', '类型', '大小', 'Checksum', '构建时间', '来源流水线'].map((h) => <th key={h} className="px-4 py-2 font-medium">{h}</th>)}
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {artifacts.map((a) => {
                const pl = pipelineById(a.pipelineId)
                return (
                  <tr key={a.id} className="hover:bg-ink-700">
                    <td className="px-4 py-2 font-medium text-txt-hi">{a.name}</td>
                    <td className="px-4 py-2 font-mono text-xs font-bold text-brand-deep">{a.version}</td>
                    <td className="px-4 py-2"><Pill tone="teal">{a.type}</Pill></td>
                    <td className="px-4 py-2 tabular-nums text-txt-mid">{a.size}</td>
                    <td className="px-4 py-2 font-mono text-[11px] text-txt-low">{a.checksum}</td>
                    <td className="px-4 py-2 tabular-nums text-txt-low">{a.builtAt}</td>
                    <td className="px-4 py-2">
                      <button type="button" onClick={() => nav.go('pipeline', a.pipelineId)} title={pl?.title} className="cursor-pointer font-mono text-xs font-semibold text-brand hover:underline">
                        {pl ? pl.title.split('·')[0].trim() : a.pipelineId}
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}
