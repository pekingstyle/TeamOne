// 即时沟通（v2.1）：频道 / 话题 / 私信 三类会话统一收口
// 话题 = 对象化讨论（自动建题、干系人拉人）；归档话题折叠收纳（同 ZCode 会话归档逻辑），可检索可重开
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  ChevronDown, ChevronRight, CircleDot, GitPullRequest, Hash, Lock, MessageSquarePlus,
  Package, RotateCcw, Send, Workflow, X,
} from 'lucide-react'
import type { PageProps } from '../../nav'
import type { MsgAttachment, Message, PageId, Topic, TopicTargetType } from '../../data/types'
import {
  addTopicMessage, channelById, channels, createTopic, CURRENT_USER_ID, defects, goals,
  mergeRequests, messagesOf, openChannel, releases, reopenTopic, requirements, repos,
  roadmapItems, sprints, sendImMessage, targetLabel, targetPage, tasks, testTasks, topicById,
  topics, useStore, userById,
} from '../../data/store'
import { Avatar, Btn, Pill } from '../../components/ui'

const targetTypeZh: Record<TopicTargetType, string> = {
  goal: '目标', requirement: '需求', roadmap: 'RoadMap', sprint: '迭代', task: '任务', testtask: '测试任务', defect: '缺陷',
  release: '版本', repo: '仓库', worktree: '工作树', baseline: '基线', mr: 'MR',
}
const targetTone: Record<TopicTargetType, 'purple' | 'info' | 'teal' | 'orange' | 'brand' | 'neutral'> = {
  goal: 'purple', requirement: 'purple', roadmap: 'purple', sprint: 'info', task: 'info', testtask: 'teal', defect: 'orange',
  release: 'teal', repo: 'neutral', worktree: 'neutral', baseline: 'teal', mr: 'brand',
}

const inputCls = 'w-full rounded-input border border-line bg-canvas px-2.5 py-1.5 text-sm text-txt-hi outline-none transition-colors placeholder:text-txt-low/70 focus:border-brand'

function targetOptions(t: TopicTargetType): { id: string; label: string }[] {
  switch (t) {
    case 'goal': return goals.map((g) => ({ id: g.id, label: `${g.key} ${g.name}` }))
    case 'requirement': return requirements.map((r) => ({ id: r.id, label: `${r.key} ${r.title}` }))
    case 'roadmap': return roadmapItems.map((r) => ({ id: r.id, label: r.name }))
    case 'task': return tasks.map((w) => ({ id: w.id, label: `${w.key} ${w.title}` }))
    case 'testtask': return testTasks.map((w) => ({ id: w.id, label: `${w.key} ${w.title}` }))
    case 'defect': return defects.map((w) => ({ id: w.id, label: `${w.key} ${w.title}` }))
    case 'release': return releases.map((r) => ({ id: r.id, label: `${r.name} · 计划 ${r.planDate.slice(5)}` }))
    case 'sprint': return sprints.map((s) => ({ id: s.id, label: s.name }))
    case 'mr': return mergeRequests.map((m) => ({ id: m.id, label: `!${m.number} ${m.title}` }))
    default: return repos.map((r) => ({ id: r.id, label: r.name }))
  }
}

/** 引用卡：type → 图标 / 主题色 / 跳转页 */
const attMeta: Record<MsgAttachment['type'], { icon: typeof GitPullRequest; cls: string; page: PageId }> = {
  mr: { icon: GitPullRequest, cls: 'text-cat-teal', page: 'mr' },
  defect: { icon: CircleDot, cls: 'text-cat-orange', page: 'defects' },
  workitem: { icon: CircleDot, cls: 'text-cat-blue', page: 'tasks' },
  pipeline: { icon: Workflow, cls: 'text-cat-purple', page: 'pipeline' },
  release: { icon: Package, cls: 'text-cat-teal', page: 'delivery' },
  topic: { icon: Hash, cls: 'text-brand', page: 'topics' },
}

function AttachmentCard({ att, onGo }: { att: MsgAttachment; onGo: () => void }) {
  const meta = attMeta[att.type]
  const Icon = meta.icon
  return (
    <button
      type="button"
      onClick={onGo}
      className="inline-flex max-w-full cursor-pointer items-center gap-1.5 rounded-md border border-line bg-canvas px-2 py-1 text-xs text-txt-mid transition-colors hover:border-brand hover:text-brand"
    >
      <Icon size={12} className={`shrink-0 ${meta.cls}`} />
      <span className="truncate">{att.label}</span>
    </button>
  )
}

function AvatarStack({ ids, size = 20 }: { ids: string[]; size?: number }) {
  return (
    <div className="flex shrink-0 -space-x-1.5">
      {ids.slice(0, 8).map((uid) => (
        <span key={uid} className="inline-flex rounded-full ring-2 ring-canvas"><Avatar userId={uid} size={size} /></span>
      ))}
    </div>
  )
}

function Row({ label, icon, active, badge, dimmed, onClick }: { label: ReactNode; icon: ReactNode; active: boolean; badge?: ReactNode; dimmed?: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-sm transition-colors ${
        active ? 'bg-brand-bg font-semibold text-brand-deep' : dimmed ? 'text-txt-low hover:bg-ink-700' : 'text-txt-mid hover:bg-ink-700 hover:text-txt-hi'
      }`}
    >
      {icon}
      <span className={`min-w-0 flex-1 truncate ${dimmed ? 'line-through decoration-txt-low/50' : ''}`}>{label}</span>
      {badge}
    </button>
  )
}

const numBadge = (n: number, red = false) =>
  n > 0 ? <span className={`rounded-full px-1.5 text-[11px] leading-4 font-bold ${red ? 'bg-bad text-white' : 'bg-brand-bg text-brand-deep'}`}>{n}</span> : undefined

export default function ImPage({ nav, id }: PageProps) {
  useStore()
  const initial = id && topicById(id) ? id : id && channelById(id) ? id : 'c1'
  const [sel, setSel] = useState(initial)
  const [showArchived, setShowArchived] = useState(false)
  const [text, setText] = useState('')
  const [toast, setToast] = useState('')
  const [convMsg, setConvMsg] = useState<Message | null>(null)
  const [convType, setConvType] = useState<TopicTargetType>('task')
  const [convTarget, setConvTarget] = useState('w1')
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (id && (topicById(id) || channelById(id))) setSel(id)
  }, [id])

  const activeTopic = topicById(sel)
  const ch = channelById(sel)
  const msgs: Message[] = activeTopic ? (activeTopic.messages as Message[]) : messagesOf(sel)
  const chans = channels.filter((c) => c.kind === 'channel')
  const dms = channels.filter((c) => c.kind === 'dm')
  const activeTopics = topics.filter((t) => t.status === 'active')
  const archivedTopics = topics.filter((t) => t.status !== 'active')

  useEffect(() => {
    const box = boxRef.current
    if (box) box.scrollTop = box.scrollHeight
  }, [sel, msgs.length])
  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(''), 4200)
    return () => clearTimeout(t)
  }, [toast])

  const pick = (cid: string) => { setSel(cid); openChannel(cid) }
  const send = () => {
    if (!text.trim()) return
    if (activeTopic) addTopicMessage(activeTopic.id, text.trim())
    else sendImMessage(sel, text.trim())
    setText('')
  }
  const openConvert = (m: Message) => {
    setConvMsg(m)
    setConvType('task')
    setConvTarget(targetOptions('task')[0]?.id ?? '')
  }
  const confirmConvert = () => {
    if (!convMsg || !convTarget) return
    const topic = createTopic({
      targetType: convType, targetId: convTarget, title: convMsg.text.slice(0, 20),
      firstMessage: convMsg.text, autoCreated: false,
    })
    setConvMsg(null)
    setToast(`已转为话题「${topic.title}…」，自动拉入 ${topic.participantIds.length} 名干系人`)
  }

  return (
    <div>
      <div className="flex h-[72vh] min-h-[520px] overflow-hidden rounded-card border border-line bg-canvas">
        {/* 左栏：频道 / 话题 / 私信 三分类 */}
        <aside className="flex w-60 shrink-0 flex-col overflow-y-auto border-r border-line bg-card">
          <div className="px-3 pt-3 pb-1 text-[11px] font-semibold tracking-widest text-txt-low">频道</div>
          <ul className="space-y-0.5 px-2">
            {chans.map((c) => (
              <li key={c.id}>
                <Row
                  label={c.name}
                  icon={<span className="text-txt-low">#</span>}
                  active={!activeTopic && sel === c.id}
                  badge={numBadge(c.unread, true)}
                  onClick={() => pick(c.id)}
                />
              </li>
            ))}
          </ul>

          <div className="px-3 pt-4 pb-1 text-[11px] font-semibold tracking-widest text-txt-low">话题 · 对象化讨论</div>
          <ul className="space-y-0.5 px-2">
            {activeTopics.map((t) => (
              <li key={t.id}>
                <Row
                  label={t.title}
                  icon={<Hash size={14} className="shrink-0 text-brand" />}
                  active={sel === t.id}
                  badge={numBadge(t.participantIds.length)}
                  onClick={() => setSel(t.id)}
                />
              </li>
            ))}
          </ul>
          {/* 已归档话题：折叠收纳（同 ZCode 会话归档），可展开查看/重开 */}
          <button
            type="button"
            onClick={() => setShowArchived((v) => !v)}
            className="mx-2 mt-1 flex cursor-pointer items-center gap-1 rounded px-2 py-1 text-[11px] font-semibold text-txt-low hover:text-txt-mid"
          >
            {showArchived ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
            已归档 · {archivedTopics.length}
          </button>
          {showArchived && (
            <ul className="space-y-0.5 px-2 pb-1">
              {archivedTopics.map((t) => (
                <li key={t.id}>
                  <Row
                    label={t.title}
                    icon={<Lock size={12} className="shrink-0 text-txt-low" />}
                    active={sel === t.id}
                    dimmed
                    onClick={() => setSel(t.id)}
                  />
                </li>
              ))}
              {archivedTopics.length === 0 && <li className="px-2.5 py-1 text-[11px] text-txt-low">暂无归档话题</li>}
            </ul>
          )}

          <div className="px-3 pt-4 pb-1 text-[11px] font-semibold tracking-widest text-txt-low">私信</div>
          <ul className="space-y-0.5 px-2 pb-3">
            {dms.map((c) => (
              <li key={c.id}>
                <Row
                  label={c.name}
                  icon={<Avatar userId={c.memberIds.find((m) => m !== CURRENT_USER_ID) ?? c.memberIds[0]} size={18} />}
                  active={!activeTopic && sel === c.id}
                  badge={numBadge(c.unread, true)}
                  onClick={() => pick(c.id)}
                />
              </li>
            ))}
          </ul>
        </aside>

        {/* 中栏：会话（频道消息 或 话题时间线） */}
        <section className="flex min-w-0 flex-1 flex-col">
          {activeTopic ? (
            <>
              <TopicHeader topic={activeTopic} nav={nav} />
              <div ref={boxRef} className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
                {activeTopic.pinnedConclusion && (
                  <div className="rounded-lg border border-cat-teal/30 bg-info-bg px-3 py-2 text-xs leading-5 text-txt-hi">
                    <span className="font-semibold text-cat-teal">置顶结论 · </span>{activeTopic.pinnedConclusion}
                  </div>
                )}
                {activeTopic.messages.map((m) => (
                  <div key={m.id} className="flex gap-2.5">
                    <Avatar userId={m.authorId} size={32} />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-baseline gap-2">
                        <span className="text-sm font-semibold text-txt-hi">{userById(m.authorId)?.name ?? '系统'}</span>
                        <span className="text-[11px] tabular-nums text-txt-low">{m.createdAt}</span>
                      </div>
                      <div className="mt-0.5 text-sm leading-6 whitespace-pre-wrap text-txt-mid">{m.text}</div>
                    </div>
                  </div>
                ))}
                {activeTopic.messages.length === 0 && (
                  <div className="py-10 text-center text-sm text-txt-low">话题创建于对象建立时，还没有消息，说点什么吧</div>
                )}
              </div>
              <div className="border-t border-line p-3">
                {activeTopic.status === 'active' ? (
                  <div className="flex items-center gap-2">
                    <input
                      value={text}
                      onChange={(e) => setText(e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Enter') send() }}
                      placeholder={`回复话题「${activeTopic.title.slice(0, 18)}…」，Enter 发送`}
                      className={inputCls}
                    />
                    <Btn variant="primary" onClick={send}><Send size={14} /></Btn>
                  </div>
                ) : (
                  <div className="flex items-center justify-between gap-2 rounded-input bg-ink-700 px-3 py-2">
                    <span className="flex items-center gap-1.5 text-xs text-txt-low"><Lock size={12} /> 话题已归档（{activeTopic.archivedReason === 'object_closed' ? '对象已关闭' : activeTopic.archivedReason === 'sprint_ended' ? '迭代结束' : activeTopic.archivedReason === 'release_released' ? '发布完成' : '基线冻结'}），仅供检索回放</span>
                    <Btn variant="ghost" onClick={() => { reopenTopic(activeTopic.id); setToast('话题已重开，全部干系人可见') }}>
                      <RotateCcw size={12} /> 重开
                    </Btn>
                  </div>
                )}
              </div>
            </>
          ) : (
            <>
              <div className="flex items-center gap-3 border-b border-line px-4 py-2.5">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1 text-sm font-bold text-txt-hi">
                    {ch?.kind === 'dm' ? '@' : '#'} {ch?.name ?? '—'}
                  </div>
                  <div className="truncate text-xs text-txt-mid">{ch?.description || '私信会话'}</div>
                </div>
                {ch && <AvatarStack ids={ch.memberIds} />}
              </div>
              <div ref={boxRef} className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
                {msgs.map((m) => {
                  if (m.authorId === 'system') {
                    return (
                      <div key={m.id} className="mx-auto max-w-[88%] text-center">
                        <div className="rounded-lg bg-ink-700 px-3 py-2 text-xs leading-5 text-txt-mid">{m.text}</div>
                        {m.attachments && m.attachments.length > 0 && (
                          <div className="mt-1 flex flex-wrap justify-center gap-1.5">
                            {m.attachments.map((a, i) => (
                              <AttachmentCard key={i} att={a} onGo={() => nav.go(attMeta[a.type].page, a.refId)} />
                            ))}
                          </div>
                        )}
                      </div>
                    )
                  }
                  return (
                    <div key={m.id} className="group flex gap-2.5">
                      <Avatar userId={m.authorId} size={32} />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-baseline gap-2">
                          <span className="text-sm font-semibold text-txt-hi">{userById(m.authorId)?.name ?? '未知'}</span>
                          <span className="text-[11px] tabular-nums text-txt-low">{m.createdAt}</span>
                          <button
                            type="button"
                            onClick={() => openConvert(m)}
                            className="ml-auto inline-flex cursor-pointer items-center gap-1 rounded px-1.5 py-0.5 text-[11px] text-brand opacity-0 transition-opacity hover:bg-brand-bg group-hover:opacity-100"
                            title="将该消息转为对象话题"
                          >
                            <MessageSquarePlus size={12} /> 转为话题
                          </button>
                        </div>
                        <div className="mt-0.5 text-sm leading-6 whitespace-pre-wrap text-txt-mid">{m.text}</div>
                        {m.attachments && m.attachments.length > 0 && (
                          <div className="mt-1 flex flex-wrap gap-1.5">
                            {m.attachments.map((a, i) => (
                              <AttachmentCard key={i} att={a} onGo={() => nav.go(attMeta[a.type].page, a.refId)} />
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
              <div className="border-t border-line p-3">
                <div className="flex items-center gap-2">
                  <input
                    value={text}
                    onChange={(e) => setText(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') send() }}
                    placeholder={`发送到 ${ch?.kind === 'dm' ? '@' : '#'} ${ch?.name ?? ''}，Enter 发送…`}
                    className={inputCls}
                  />
                  <Btn variant="primary" onClick={send}><Send size={14} /></Btn>
                </div>
              </div>
            </>
          )}
        </section>

        {/* 右栏：成员 / 干系人 */}
        <aside className="flex w-48 shrink-0 flex-col border-l border-line bg-card">
          <div className="border-b border-line px-3 py-2.5 text-xs font-semibold text-txt-mid">
            {activeTopic ? `干系人 · ${activeTopic.participantIds.length}` : `成员 · ${ch?.memberIds.length ?? 0}`}
          </div>
          <ul className="flex-1 overflow-y-auto p-2">
            {(activeTopic ? activeTopic.participantIds : ch?.memberIds ?? []).map((uid) => {
              const u = userById(uid)
              return (
                <li key={uid} className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-ink-700">
                  <Avatar userId={uid} size={24} />
                  <span className="min-w-0 flex-1 truncate text-sm text-txt-hi">{u?.name ?? uid}</span>
                  <span title={u?.online ? '在线' : '离线'} className={`h-1.5 w-1.5 shrink-0 rounded-full ${u?.online ? 'bg-ok' : 'bg-txt-low/40'}`} />
                </li>
              )
            })}
          </ul>
          {activeTopic && <div className="border-t border-line px-3 py-2 text-[11px] leading-4 text-txt-low">干系人按对象规则自动拉入：负责人 / 报告人 / 评审人 / 版本负责人等</div>}
        </aside>
      </div>

      {/* 消息转话题小弹窗（预填首条消息） */}
      {convMsg && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-txt-hi/25" onClick={() => setConvMsg(null)} />
          <div className="relative w-[420px] max-w-full rounded-card border border-line bg-canvas p-5 shadow-xl">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="flex items-center gap-1.5 text-base font-bold text-txt-hi"><MessageSquarePlus size={16} className="text-brand" /> 消息转为话题</h3>
              <button type="button" onClick={() => setConvMsg(null)} className="cursor-pointer rounded-md p-1 text-txt-mid hover:bg-ink-700 hover:text-txt-hi"><X size={16} /></button>
            </div>
            <div className="mb-3 max-h-24 overflow-y-auto rounded-lg bg-ink-700 p-2.5 text-xs leading-5 text-txt-mid">{convMsg.text}</div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="mb-1 block text-xs font-medium text-txt-mid">对象类型</label>
                <select
                  value={convType}
                  onChange={(e) => {
                    const t = e.target.value as TopicTargetType
                    setConvType(t)
                    setConvTarget(targetOptions(t)[0]?.id ?? '')
                  }}
                  className={inputCls}
                >
                  {(Object.keys(targetTypeZh) as TopicTargetType[]).map((t) => <option key={t} value={t}>{targetTypeZh[t]}</option>)}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-txt-mid">关联对象</label>
                <select value={convTarget} onChange={(e) => setConvTarget(e.target.value)} className={inputCls}>
                  {targetOptions(convType).map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
                </select>
              </div>
            </div>
            <p className="mt-3 text-xs text-txt-low">首条消息将预填该条 IM 原文，并按干系人规则自动拉人。</p>
            <div className="mt-4 flex justify-end gap-2">
              <Btn variant="ghost" onClick={() => setConvMsg(null)}>取消</Btn>
              <Btn variant="primary" onClick={confirmConvert} disabled={!convTarget}>创建话题</Btn>
            </div>
          </div>
        </div>
      )}

      {toast && (
        <div className="fixed bottom-6 left-1/2 z-60 -translate-x-1/2 rounded-full bg-txt-hi px-4 py-2 text-sm font-medium text-canvas shadow-lg">{toast}</div>
      )}
    </div>
  )
}

/** 话题会话头：对象胶囊（可跳转）+ 归档状态 + 干系人 */
function TopicHeader({ topic, nav }: { topic: Topic; nav: PageProps['nav'] }) {
  const tone = targetTone[topic.targetType]
  const autoReason = topic.autoCreated
    ? topic.autoCreateReason === 'severity_high' ? '致命/严重自动建题' : topic.autoCreateReason === 'release_testing' ? '提测自动建题' : '随对象创建自动生成'
    : '手动创建'
  return (
    <div className="flex items-center gap-3 border-b border-line px-4 py-2.5">
      <div className="min-w-0 flex-1">
        <div className="flex min-w-0 items-center gap-2">
          <Hash size={14} className="shrink-0 text-brand" />
          <span className="truncate text-sm font-bold text-txt-hi">{topic.title}</span>
          {topic.autoCreated && <Pill tone="purple">自动</Pill>}
          {topic.status !== 'active' && <Pill tone="neutral"><Lock size={10} /> 已归档</Pill>}
        </div>
        <div className="mt-1 flex items-center gap-1.5 text-[11px] text-txt-low">
          <button
            type="button"
            onClick={() => { const p = targetPageOf(topic); nav.go(p.page, p.id) }}
            className="cursor-pointer"
          >
            <Pill tone={tone}>{targetTypeZh[topic.targetType]} · {targetLabel(topic.targetType, topic.targetId)}</Pill>
          </button>
          <span>{autoReason}</span>
          <span>· 建于 {topic.createdAt}</span>
        </div>
      </div>
      <AvatarStack ids={topic.participantIds} />
    </div>
  )
}

function targetPageOf(t: Topic): { page: PageId; id?: string } {
  return targetPage(t.targetType, t.targetId) as { page: PageId; id?: string }
}
