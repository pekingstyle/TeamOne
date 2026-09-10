// 工程底座 · MR 详情：单测检测门禁（R8）/ rebase / 冲突解决 / 基线关联（R9）
import { useState } from 'react'
import type { ReactNode } from 'react'
import { AlertTriangle, ArrowLeft, CheckCircle2, FileCode2, FlaskConical, GitBranch, GitMerge, GitPullRequest, Lock, ShieldCheck, Undo2 } from 'lucide-react'
import { addMrComment, baselineById, exemptMrUnitTest, markRebased, mergeMr, mrById, repoById, resolveMrConflict, reviewMr, useStore, userById, workItemByKey } from '../../data/store'
import type { FileDiff, MergeRequest } from '../../data/types'
import { Avatar, Badge, Btn, Card, CardHeader, Empty, Pill, StatusDot, runStatusText } from '../../components/ui'
import type { PageProps } from '../../nav'
import { UnitTestBadge } from './ReviewPage'

type Nav = PageProps['nav']
const isTestPath = (p: string) => p.includes('/test/') || p.includes('.test.') || p.includes('Test.java')
const MR_STATUS: Record<MergeRequest['status'], { tone: 'ok' | 'neutral' | 'purple' | 'bad'; text: string }> = {
  open: { tone: 'ok', text: '开启中' },
  draft: { tone: 'neutral', text: '草稿' },
  merged: { tone: 'purple', text: '已合并' },
  closed: { tone: 'bad', text: '已关闭' },
}
const BL_TEXT = { draft: '草稿', in_review: '审批中', approved: '已定版', superseded: '已废止' } as const

export default function MrDetailPage({ nav, id }: PageProps) {
  useStore()
  const mr = id ? mrById(id) : undefined
  const [tab, setTab] = useState<'diffs' | 'comments'>('diffs')

  if (!mr) {
    return (
      <div>
        <Btn variant="ghost" onClick={() => nav.go('review')} className="mb-4 -ml-3">
          <ArrowLeft size={14} />返回评审列表
        </Btn>
        <Empty text="MR 不存在或已被删除" />
      </div>
    )
  }

  return (
    <div>
      <Btn variant="ghost" onClick={() => nav.go('review')} className="mb-3 -ml-3">
        <ArrowLeft size={14} />返回评审列表
      </Btn>

      <MrHeader mr={mr} nav={nav} />

      {mr.status === 'merged' && (
        <div className="mb-4 flex items-center gap-2 rounded-lg border border-brand/25 bg-brand-bg px-4 py-3 text-sm font-medium text-brand-deep">
          <GitMerge size={16} />
          此 MR 已合并到 {mr.targetBranch}，评审与单测门禁记录已归档留痕。
        </div>
      )}

      <UnitTestCard mr={mr} />
      {mr.status !== 'merged' && <GateBar mr={mr} />}

      <div className="mt-4 grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(0,1fr)_17rem]">
        <Card className="min-w-0">
          <div className="flex gap-1 border-b border-line px-3 pt-1">
            {([['diffs', `变更 ${mr.diffs.length}`], ['comments', `评论 ${mr.comments.length}`]] as const).map(([key, label]) => (
              <button
                key={key}
                type="button"
                onClick={() => setTab(key)}
                className={`-mb-px cursor-pointer border-b-2 px-3.5 py-2.5 text-sm transition-colors ${
                  tab === key ? 'border-brand font-semibold text-txt-hi' : 'border-transparent text-txt-mid hover:text-txt-hi'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          {tab === 'diffs' ? (
            mr.diffs.length === 0 ? <Empty text="无变更文件" /> : mr.diffs.map((d) => <DiffCard key={d.path} d={d} />)
          ) : (
            <CommentsPanel mr={mr} />
          )}
        </Card>

        <aside className="space-y-4">
          <ReviewersCard mr={mr} />
          <ChecksCard mr={mr} />
          <BaselineCard mr={mr} nav={nav} />
        </aside>
      </div>
    </div>
  )
}

// ==================== 头部 ====================
function MrHeader({ mr, nav }: { mr: MergeRequest; nav: Nav }) {
  const repo = repoById(mr.repoId)
  const author = userById(mr.authorId)
  const wi = mr.linkedWorkItemKey ? workItemByKey(mr.linkedWorkItemKey) : undefined
  const st = MR_STATUS[mr.status]
  return (
    <div className="mb-4">
      <div className="flex flex-wrap items-center gap-2.5">
        <GitPullRequest size={20} className={mr.status === 'open' ? 'text-cat-green' : mr.status === 'merged' ? 'text-cat-purple' : 'text-txt-low'} />
        <h1 className="text-xl font-bold text-txt-hi">{mr.title}</h1>
        <span className="font-mono text-sm text-txt-low">!{mr.number}</span>
        <Pill tone={st.tone}>{st.text}</Pill>
        <UnitTestBadge utc={mr.unitTestCheck} />
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-txt-mid">
        <span className="font-mono">{repo?.name}</span>
        <span className="flex items-center gap-1 font-mono"><GitBranch size={11} />{mr.sourceBranch} → {mr.targetBranch}</span>
        <span className="flex items-center gap-1.5"><Avatar userId={mr.authorId} size={18} />{author?.name} 创建于 {mr.createdAt}</span>
        {wi && (
          <button
            type="button"
            onClick={() => nav.go(wi.key.startsWith('D-') ? 'defects' : 'tasks', wi.id)}
            title={wi.title}
            className="cursor-pointer font-mono text-brand-deep hover:underline"
          >
            {wi.key}
          </button>
        )}
      </div>
      <p className="mt-2.5 whitespace-pre-wrap text-sm text-txt-mid">{mr.description}</p>
    </div>
  )
}

// ==================== R8 单测检测卡 ====================
function UnitTestCard({ mr }: { mr: MergeRequest }) {
  const utc = mr.unitTestCheck
  const [applying, setApplying] = useState(false)
  const [reason, setReason] = useState('')
  const fails: string[] = []
  if (!utc.hasTests) fails.push('未检测到关联单测文件（启发式 A 未命中）')
  if (!utc.passed) fails.push('单测运行未通过')
  if (utc.coverageTotal < 60) fails.push(`整体覆盖率 ${utc.coverageTotal}% 低于门禁 60%`)
  if (utc.coverageDelta < 80) fails.push(`patch 覆盖率 ${utc.coverageDelta}% 低于门禁 80%`)
  return (
    <Card>
      <CardHeader
        title={<span className="flex items-center gap-1.5"><FlaskConical size={14} className="text-cat-teal" />单测检测（R8 门禁：整体 ≥60% · patch ≥80%）</span>}
        extra={
          utc.gatePassed ? (
            utc.exempt ? <Pill tone="teal">豁免放行</Pill> : <Pill tone="ok"><CheckCircle2 size={11} />门禁通过</Pill>
          ) : (
            <Pill tone="warn"><AlertTriangle size={11} />门禁未通过</Pill>
          )
        }
      />
      <div className="px-4 py-3">
        {utc.exempt ? (
          <div className="flex flex-wrap items-center gap-2 rounded-md bg-info-bg px-3 py-2.5 text-sm text-cat-teal">
            <CheckCircle2 size={14} className="shrink-0" />
            <span className="font-medium">已豁免：{utc.exempt.reason}</span>
            <span className="flex items-center gap-1.5 text-xs text-txt-mid">
              批准人 <Avatar userId={utc.exempt.approvedById} size={18} /> {userById(utc.exempt.approvedById)?.name}
            </span>
          </div>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-sm">
              <span className="flex items-center gap-1.5 font-medium">
                {utc.hasTests ? (
                  <><CheckCircle2 size={15} className="text-ok" /><span className="text-ok-deep">含单测 ✓</span></>
                ) : (
                  <><AlertTriangle size={15} className="text-cat-orange" /><span className="font-semibold text-cat-orange">无单测 ⚠</span></>
                )}
              </span>
              <span className="text-txt-mid">
                单测运行：
                {utc.passed ? <span className="font-medium text-ok-deep">passed ✓</span> : <span className="font-medium text-bad-deep">failed ✗</span>}
              </span>
              <CovItem label="整体覆盖率" value={utc.coverageTotal} gate={60} />
              <CovItem label="patch 覆盖率" value={utc.coverageDelta} gate={80} />
            </div>
            {utc.testFiles.length > 0 && (
              <div className="mt-2.5 flex flex-wrap items-center gap-1.5 text-xs text-txt-low">
                测试文件：
                {utc.testFiles.map((f) => (
                  <span key={f} className="rounded bg-ink-700 px-1.5 py-0.5 font-mono text-txt-mid">{f}</span>
                ))}
              </div>
            )}
            {!utc.gatePassed && (
              <div className="mt-3 rounded-md bg-warn-bg px-3 py-2.5 text-sm text-warn-deep">
                <div className="flex items-center gap-1.5 font-semibold"><AlertTriangle size={14} />未达门禁项</div>
                <ul className="mt-1 list-disc pl-5 text-xs leading-5">
                  {fails.map((f) => <li key={f}>{f}</li>)}
                </ul>
                {!applying ? (
                  <Btn variant="ghost" className="mt-1.5" onClick={() => setApplying(true)}>申请豁免</Btn>
                ) : (
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    <input
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                      placeholder="填写豁免理由（如：启发式误报 / 纯依赖升级无逻辑变更）"
                      className="min-w-[14rem] flex-1 rounded-md border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none focus:border-brand"
                    />
                    <Btn variant="primary" disabled={!reason.trim()} onClick={() => { exemptMrUnitTest(mr.id, reason); setApplying(false) }}>
                      提交豁免
                    </Btn>
                    <Btn variant="ghost" onClick={() => setApplying(false)}>取消</Btn>
                  </div>
                )}
                <p className="mt-1.5 text-[11px] opacity-80">豁免需评审人批准并留审计记录；未通过或未豁免时合并按钮保持禁用。</p>
              </div>
            )}
          </>
        )}
      </div>
    </Card>
  )
}

function CovItem({ label, value, gate }: { label: string; value: number; gate: number }) {
  const ok = value >= gate
  return (
    <span className="flex items-center gap-1.5">
      <span className="text-xs text-txt-low">{label}</span>
      <span className={`text-sm font-semibold tabular-nums ${ok ? 'text-ok-deep' : 'text-bad-deep'}`}>{value}%</span>
      <span className={`rounded px-1 py-px text-[10px] font-medium ${ok ? 'bg-ok-bg text-ok-deep' : 'bg-bad-bg text-bad-deep'}`}>门禁 ≥{gate}%</span>
    </span>
  )
}

// ==================== 合并门禁操作条 ====================
function GateBar({ mr }: { mr: MergeRequest }) {
  const [solutions, setSolutions] = useState<Record<string, string>>({})
  const [mergeErr, setMergeErr] = useState<string[] | undefined>(undefined)
  const blockers: string[] = []
  if (mr.reviewers.some((r) => r.state !== 'approved')) blockers.push('评审未全部批准（右侧评审人卡可操作）')
  const failed = mr.checks.filter((c) => c.status === 'failed')
  if (failed.length > 0) blockers.push(`失败检查项：${failed.map((c) => c.name).join('、')}`)
  if (!mr.unitTestCheck.gatePassed) blockers.push('单测门禁未通过（见上方检测卡，可申请豁免）')
  const canMerge = mr.status === 'open' && blockers.length === 0 && mr.conflictFiles.length === 0
  const doMerge = () => {
    const res = mergeMr(mr.id)
    setMergeErr(res.ok ? undefined : res.reasons)
  }
  return (
    <Card className="mt-4">
      <CardHeader
        title={<span className="flex items-center gap-1.5"><ShieldCheck size={14} />合并门禁</span>}
        extra={<Pill tone={canMerge ? 'ok' : 'warn'}>{canMerge ? '全部满足' : `${blockers.length + mr.conflictFiles.length} 项未满足`}</Pill>}
      />
      <div className="space-y-2 px-4 py-3">
        {mr.rebaseRequired && (
          <div className="flex flex-wrap items-center gap-2 rounded-md bg-warn-bg px-3 py-2 text-sm text-warn-deep">
            <AlertTriangle size={14} className="shrink-0" />
            <span className="min-w-0 flex-1">源分支落后目标分支 {mr.targetBranch}，合并前需先 rebase</span>
            <Btn onClick={() => markRebased(mr.id)}><Undo2 size={13} />标记已 rebase</Btn>
          </div>
        )}
        {mr.conflictFiles.map((f) => (
          <div key={f} className="flex flex-wrap items-center gap-2 rounded-md bg-bad-bg px-3 py-2">
            <AlertTriangle size={14} className="shrink-0 text-bad" />
            <span className="font-mono text-xs text-bad-deep">{f}</span>
            <input
              value={solutions[f] ?? ''}
              onChange={(e) => setSolutions({ ...solutions, [f]: e.target.value })}
              placeholder="填写冲突解决方案说明（确认后经评审人复核留痕）"
              className="min-w-[12rem] flex-1 rounded-md border border-line bg-canvas px-2 py-1 text-xs text-txt-hi outline-none focus:border-brand"
            />
            <Btn variant="danger" disabled={!(solutions[f] ?? '').trim()} onClick={() => resolveMrConflict(mr.id, f, solutions[f] ?? '')}>
              标记已解决
            </Btn>
          </div>
        ))}
        {(mr.conflictResolutions?.length ?? 0) > 0 && (
          <div className="rounded-md border border-line px-3 py-2.5">
            <div className="text-xs font-semibold text-txt-low">冲突解决记录（确认 → 复核留痕）</div>
            {mr.conflictResolutions!.map((r, i) => (
              <div key={i} className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
                <span className="font-mono text-txt-mid">{r.filePath}</span>
                <span className="text-txt-mid">{r.solution}</span>
                <span className="flex items-center gap-1 text-txt-low">确认 <Avatar userId={r.confirmedById} size={15} /></span>
                <span className="flex items-center gap-1 text-txt-low">复核 <Avatar userId={r.reviewedById} size={15} /></span>
                <span className="text-txt-low tabular-nums">{r.resolvedAt}</span>
              </div>
            ))}
          </div>
        )}
        {blockers.map((t) => (
          <div key={t} className="flex items-center gap-2 text-xs text-txt-mid">
            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-warn" />{t}
          </div>
        ))}
        <div className="flex flex-wrap items-center gap-3 border-t border-line pt-3">
          <Btn variant="primary" disabled={!canMerge} onClick={doMerge}>
            <GitMerge size={15} />合并到 {mr.targetBranch}
          </Btn>
          {mr.status !== 'open' ? (
            <span className="text-xs text-txt-mid">当前状态「{MR_STATUS[mr.status].text}」，不可合并</span>
          ) : canMerge ? (
            <span className="flex items-center gap-1 text-xs font-medium text-ok-deep"><CheckCircle2 size={13} />全部门禁满足，可执行合并</span>
          ) : (
            <span className="text-xs text-txt-mid">满足全部门禁后可合并（评审 / 检查 / 单测 / rebase / 冲突）</span>
          )}
        </div>
        {mergeErr && (
          <div className="rounded-md bg-bad-bg px-3 py-2 text-xs text-bad-deep">合并被拦截：{mergeErr.join('；')}</div>
        )}
      </div>
    </Card>
  )
}

// ==================== 变更 diff ====================
function DiffCard({ d }: { d: FileDiff }) {
  return (
    <div className="border-b border-line last:border-b-0">
      <div className="flex flex-wrap items-center gap-2 border-b border-line bg-card px-4 py-2">
        <FileCode2 size={13} className="shrink-0 text-txt-low" />
        <span className="min-w-0 truncate font-mono text-xs font-medium text-txt-hi">{d.path}</span>
        <Badge tone={d.status === 'added' ? 'ok' : d.status === 'removed' ? 'bad' : 'neutral'}>
          {d.status === 'added' ? '新增' : d.status === 'removed' ? '删除' : '修改'}
        </Badge>
        {isTestPath(d.path) ? <Badge tone="ok">有测试</Badge> : <Badge tone="warn">缺测试</Badge>}
        <span className="ml-auto shrink-0 text-xs tabular-nums">
          <span className="text-ok-deep">+{d.additions}</span> <span className="text-bad-deep">-{d.deletions}</span>
        </span>
      </div>
      <table className="w-full table-fixed border-collapse font-mono text-xs leading-5">
        <tbody>
          {d.lines.map((l, i) => (
            <tr key={i} className={l.type === 'add' ? 'bg-ok-bg' : l.type === 'del' ? 'bg-bad-bg' : ''}>
              <td className="w-10 select-none border-r border-line px-2 text-right align-top text-txt-low">{l.oldNo ?? ''}</td>
              <td className="w-10 select-none border-r border-line px-2 text-right align-top text-txt-low">{l.newNo ?? ''}</td>
              <td className={`whitespace-pre-wrap break-all px-3 ${l.type === 'add' ? 'text-ok-deep' : l.type === 'del' ? 'text-bad-deep' : 'text-txt-mid'}`}>
                {l.text}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ==================== 评论 ====================
function CommentsPanel({ mr }: { mr: MergeRequest }) {
  const [text, setText] = useState('')
  return (
    <div className="px-4 py-3">
      <ul className="space-y-3">
        {mr.comments.map((c) => (
          <li key={c.id} className="flex gap-2.5">
            <Avatar userId={c.authorId} size={26} />
            <div className="min-w-0 flex-1 rounded-lg border border-line bg-canvas px-3 py-2">
              <div className="flex items-center gap-2 text-xs">
                <span className="font-semibold text-txt-hi">{userById(c.authorId)?.name}</span>
                <span className="text-txt-low tabular-nums">{c.createdAt}</span>
              </div>
              <p className="mt-1 whitespace-pre-wrap text-sm text-txt-mid">{c.text}</p>
            </div>
          </li>
        ))}
        {mr.comments.length === 0 && <li className="py-8 text-center text-sm text-txt-low">暂无评论，发起第一条讨论吧</li>}
      </ul>
      <div className="mt-4 flex items-center gap-2">
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && text.trim()) { addMrComment(mr.id, text); setText('') } }}
          placeholder="写下评审意见…"
          className="min-w-0 flex-1 rounded-md border border-line bg-canvas px-3 py-2 text-sm text-txt-hi outline-none focus:border-brand"
        />
        <Btn variant="primary" disabled={!text.trim()} onClick={() => { addMrComment(mr.id, text); setText('') }}>发表评论</Btn>
      </div>
    </div>
  )
}

// ==================== 侧栏 ====================
function ReviewersCard({ mr }: { mr: MergeRequest }) {
  const pill: Record<MergeRequest['reviewers'][number]['state'], ReactNode> = {
    approved: <Pill tone="ok">已批准</Pill>,
    changes_requested: <Pill tone="bad">请求修改</Pill>,
    pending: <Pill tone="neutral">待评审</Pill>,
  }
  return (
    <Card>
      <CardHeader
        title="评审人"
        extra={<span className="text-xs tabular-nums text-txt-low">{mr.reviewers.filter((r) => r.state === 'approved').length}/{mr.reviewers.length} 批准</span>}
      />
      <div className="space-y-2.5 px-4 py-3">
        {mr.reviewers.map((r) => {
          const u = userById(r.userId)
          return (
            <div key={r.userId} className="flex items-center gap-2">
              <Avatar userId={r.userId} size={24} />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm text-txt-hi">{u?.name}</div>
                <div className="truncate text-[11px] text-txt-low">{u?.title}</div>
              </div>
              {pill[r.state]}
            </div>
          )
        })}
        {mr.status === 'open' && (
          <div className="flex gap-2 pt-1">
            <Btn className="flex-1" onClick={() => reviewMr(mr.id, 'approved')}><CheckCircle2 size={13} />批准</Btn>
            <Btn variant="danger" className="flex-1" onClick={() => reviewMr(mr.id, 'changes_requested')}>请求修改</Btn>
          </div>
        )}
      </div>
    </Card>
  )
}

function ChecksCard({ mr }: { mr: MergeRequest }) {
  return (
    <Card>
      <CardHeader title="检查项" />
      <div className="space-y-2 px-4 py-3">
        {mr.checks.map((c) => (
          <div key={c.name} className="flex items-center gap-2 text-sm">
            <StatusDot status={c.status} />
            <span className="min-w-0 flex-1 truncate text-txt-mid">{c.name}</span>
            <span className={`shrink-0 text-xs ${c.status === 'failed' ? 'font-medium text-bad-deep' : 'text-txt-low'}`}>{runStatusText[c.status]}</span>
          </div>
        ))}
        {mr.checks.length === 0 && <div className="text-xs text-txt-low">无检查项</div>}
      </div>
    </Card>
  )
}

function BaselineCard({ mr, nav }: { mr: MergeRequest; nav: Nav }) {
  const bl = mr.basedOnBaselineId ? baselineById(mr.basedOnBaselineId) : undefined
  return (
    <Card>
      <CardHeader title="基线关联" />
      <div className="px-4 py-3">
        {bl ? (
          <button
            type="button"
            onClick={() => nav.go('repo', `${mr.repoId}#baselines`)}
            className="w-full cursor-pointer rounded-md border border-line px-3 py-2.5 text-left transition-colors hover:bg-ink-700"
          >
            <div className="flex items-center gap-1.5">
              <Lock size={12} className="shrink-0 text-cat-teal" />
              <span className="min-w-0 truncate text-sm font-medium text-txt-hi">{bl.name}</span>
            </div>
            <div className="mt-1.5 flex items-center gap-2 text-xs text-txt-low">
              <span className="font-mono">{bl.tagRef}</span>
              {bl.status === 'approved' ? (
                <Pill tone="ok"><Lock size={10} />{BL_TEXT[bl.status]}</Pill>
              ) : (
                <Pill tone={bl.status === 'in_review' ? 'warn' : 'neutral'}>{BL_TEXT[bl.status]}</Pill>
              )}
            </div>
            <div className="mt-1.5 text-[11px] font-medium text-brand-deep">查看仓库基线 Tab →</div>
          </button>
        ) : (
          <div className="text-xs text-txt-low">该 MR 未关联基线</div>
        )}
      </div>
    </Card>
  )
}
