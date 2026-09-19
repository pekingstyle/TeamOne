// R-12 系统设置页（B6 批）：分组 tab（项目/仓库/服务器/凭据/大模型置灰「规划中」）+
// 按组拉取/保存（行级乐观锁，冲突提示刷新）+ 秘密字段密码框（保存后回显掩码）+ 仓库组「测试连接」。
// 权限：后端 settings:read/edit 仅管理员放行；入口（nav）由 App.tsx 按角色过滤，本页为纵深第二层。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { CheckCircle2, Eye, EyeOff, Loader2, Plus, RefreshCw, Save, XCircle } from 'lucide-react'
import { Btn, Card, Empty, PageHeader, Pill } from '../../components/ui'
import type { PageProps } from '../../nav'
import {
  SETTING_GROUP_LABELS,
  settingsApi,
  useSettings,
  type CredentialTestResult,
  type SettingGroup,
  type SettingItemView,
} from '../../api/queries'
import { ApiError } from '../../api/client'

/** 行编辑态：后端行 + 本地草稿（值/秘密开关）；秘密行输入为空且已配置 = 保留已存值 */
interface DraftRow {
  key: string
  secret: boolean
  /** 秘密行 = 密码框输入（空=不改）；非秘密行 = 文本输入（提交时原样发送，未动过则发原值） */
  value: string
  configured: boolean
  /** 回显掩码（秘密行） */
  masked?: string | null
  version: number
  isNew?: boolean
}

/** 分组固定字段引导（首次进入空组时的预填建议行，可改名保存） */
const GROUP_PRESETS: Record<SettingGroup, { key: string; label: string; secret: boolean }[]> = {
  project: [
    { key: 'name', label: '项目名称', secret: false },
    { key: 'description', label: '项目描述', secret: false },
  ],
  repo: [
    { key: 'url', label: '仓库地址（https，可点「测试连接」）', secret: false },
    { key: 'token', label: '访问令牌（可选，保存后仅回显掩码）', secret: true },
  ],
  server: [
    { key: 'endpoint', label: '服务器地址', secret: false },
    { key: 'ssh_key', label: '部署私钥', secret: true },
  ],
  credential: [
    { key: 'note', label: '说明', secret: false },
    { key: 'secret', label: '凭据内容', secret: true },
  ],
  llm: [],
}

function toDraft(s: SettingItemView): DraftRow {
  return {
    key: s.key,
    secret: s.secret,
    value: '',
    configured: s.configured,
    masked: s.masked,
    version: s.version,
  }
}

export default function SettingsPage(_props: PageProps) {
  const [group, setGroup] = useState<SettingGroup>('repo')
  const query = useSettings(group)
  const [rows, setRows] = useState<DraftRow[]>([])
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'ok' | 'bad'; text: string } | null>(null)
  const [conflict, setConflict] = useState(false)
  // 仓库组连通性测试状态：key → 结果（进行中/loading 也在表内）
  const [testing, setTesting] = useState<Record<string, 'loading' | CredentialTestResult>>({})

  // 拉取成功 → 重建草稿（以服务端为准；空组补引导行）
  useEffect(() => {
    if (!query.data) return
    const drafts = query.data.items.map(toDraft)
    if (drafts.length === 0) {
      for (const p of GROUP_PRESETS[group]) {
        drafts.push({ key: p.key, secret: p.secret, value: '', configured: false, version: 0, isNew: true })
      }
    }
    setRows(drafts)
    setNotice(null)
    setConflict(false)
    setTesting({})
  }, [query.data, group])

  const reload = useCallback(() => { void query.refetch() }, [query])

  const patchRow = (idx: number, patch: Partial<DraftRow>) => {
    setRows((rs) => rs.map((r, i) => (i === idx ? { ...r, ...patch } : r)))
  }

  const addRow = () => {
    setRows((rs) => [...rs, { key: '', secret: false, value: '', configured: false, version: 0, isNew: true }])
  }

  const save = async () => {
    if (saving) return
    const payload = rows
      .filter((r) => r.key.trim()) // 未填键的新行忽略
      .map((r) => ({
        key: r.key.trim(),
        secret: r.secret,
        // 秘密行输入为空且已配置 → 不带 value（保留已存密文）；否则提交输入值
        value: r.value === '' && !(r.secret && r.configured) ? null : r.value,
        updateVersion: r.version,
      }))
    if (payload.length === 0) {
      setNotice({ tone: 'bad', text: '没有可保存的配置项' })
      return
    }
    setSaving(true)
    setNotice(null)
    setConflict(false)
    try {
      await settingsApi.save(group, payload)
      setNotice({ tone: 'ok', text: '已保存（秘密项仅回显掩码）' })
      await query.refetch()
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        // 乐观锁冲突：提示刷新（ApiError.currentVersion 为服务端最新版本）
        setConflict(true)
        setNotice({ tone: 'bad', text: `配置已被他人修改（服务端版本 ${e.currentVersion ?? '?'}），请刷新后重做` })
      } else {
        setNotice({ tone: 'bad', text: e instanceof ApiError ? e.message : '保存失败，请稍后重试' })
      }
    } finally {
      setSaving(false)
    }
  }

  const testConnect = async (key: string) => {
    if (testing[key] === 'loading') return
    setTesting((t) => ({ ...t, [key]: 'loading' }))
    try {
      const res = await settingsApi.testCredential(key)
      setTesting((t) => ({ ...t, [key]: res }))
    } catch (e) {
      setTesting((t) => ({
        ...t,
        [key]: { supported: true, ok: false, message: e instanceof ApiError ? e.message : '测试请求失败' },
      }))
    }
  }

  const groups = useMemo(() => Object.keys(SETTING_GROUP_LABELS) as SettingGroup[], [])
  const llmGrey = (g: SettingGroup) => g === 'llm'

  return (
    <div>
      <PageHeader
        title="系统设置"
        desc="平台运行配置 · 秘密值加密存储（AES-256-GCM），读取仅回显掩码；仅管理员可访问"
        actions={
          <Btn onClick={() => void reload()} disabled={query.isFetching}>
            <RefreshCw size={14} className={query.isFetching ? 'animate-spin' : ''} /> 刷新
          </Btn>
        }
      />

      {/* 分组 tab：llm 置灰「规划中」 */}
      <div className="mb-4 flex items-center gap-1 border-b border-line">
        {groups.map((g) => {
          const active = group === g
          if (llmGrey(g)) {
            return (
              <span key={g} className="flex cursor-not-allowed items-center gap-1.5 rounded-t-input px-3.5 py-2 text-sm text-txt-low opacity-60">
                {SETTING_GROUP_LABELS[g]} <Pill tone="neutral">规划中</Pill>
              </span>
            )
          }
          return (
            <button
              key={g}
              type="button"
              onClick={() => setGroup(g)}
              className={`cursor-pointer rounded-t-input px-3.5 py-2 text-sm transition-colors ${
                active ? 'border-b-2 border-brand font-semibold text-brand-deep' : 'text-txt-mid hover:bg-ink-700 hover:text-txt-hi'
              }`}
            >
              {SETTING_GROUP_LABELS[g]}
            </button>
          )
        })}
      </div>

      {(notice || conflict) && (
        <div
          className={`mb-3 flex items-center justify-between rounded-md px-3 py-2 text-xs leading-4 ${
            notice?.tone === 'ok' ? 'bg-ok-bg text-ok-deep' : 'bg-bad-bg text-bad-deep'
          }`}
          role={notice?.tone === 'ok' ? 'status' : 'alert'}
        >
          <span>{notice?.text ?? ''}</span>
          {conflict && (
            <button type="button" onClick={() => void reload()} className="cursor-pointer font-semibold underline">
              刷新重做
            </button>
          )}
        </div>
      )}

      <Card className="p-0">
        {query.isLoading ? (
          <div className="flex items-center justify-center gap-2 py-12 text-sm text-txt-low">
            <Loader2 size={16} className="animate-spin" /> 加载中…
          </div>
        ) : query.isError ? (
          <div className="p-10 text-center text-sm text-bad-deep">
            {query.error instanceof ApiError && query.error.status === 403
              ? '无权限访问系统设置（仅管理员）'
              : '设置加载失败，请刷新重试'}
          </div>
        ) : rows.length === 0 ? (
          <Empty text="该分组暂无配置项" />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-txt-low">
                <th className="w-64 px-4 py-2.5 font-medium">配置键</th>
                <th className="px-4 py-2.5 font-medium">值</th>
                <th className="w-28 px-4 py-2.5 font-medium">秘密</th>
                <th className="w-56 px-4 py-2.5 font-medium">{group === 'repo' ? '操作' : ''}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i} className="border-b border-line/60 last:border-0">
                  <td className="px-4 py-2.5 align-middle">
                    <input
                      value={r.key}
                      onChange={(e) => patchRow(i, { key: e.target.value })}
                      readOnly={!r.isNew}
                      placeholder="新配置键"
                      className={`w-full rounded-input border border-line bg-canvas px-2 py-1.5 font-mono text-xs text-txt-hi outline-none focus:border-brand ${
                        r.isNew ? '' : 'cursor-not-allowed opacity-70'
                      }`}
                    />
                  </td>
                  <td className="px-4 py-2.5 align-middle">
                    {r.secret ? (
                      <SecretInput
                        row={r}
                        onChange={(v) => patchRow(i, { value: v })}
                        onToggle={() => patchRow(i, { secret: !r.secret, value: '' })}
                      />
                    ) : (
                      <input
                        value={r.value}
                        onChange={(e) => patchRow(i, { value: e.target.value })}
                        placeholder={r.configured && r.value === '' ? '（留空 = 不修改）' : '请输入值'}
                        className="w-full rounded-input border border-line bg-canvas px-2 py-1.5 text-xs text-txt-hi outline-none focus:border-brand"
                      />
                    )}
                  </td>
                  <td className="px-4 py-2.5 align-middle">
                    <label className="flex cursor-pointer items-center gap-1.5 text-xs text-txt-mid">
                      <input
                        type="checkbox"
                        checked={r.secret}
                        onChange={(e) => patchRow(i, { secret: e.target.checked, value: '' })}
                        className="accent-[var(--color-brand)]"
                      />
                      {r.secret ? '加密存储' : '明文'}
                    </label>
                  </td>
                  <td className="px-4 py-2.5 align-middle">
                    {group === 'repo' && !r.isNew && !r.secret && (
                      <span className="flex items-center gap-2">
                        <Btn onClick={() => void testConnect(r.key)} disabled={testing[r.key] === 'loading'}>
                          {testing[r.key] === 'loading' ? <Loader2 size={13} className="animate-spin" /> : null} 测试连接
                        </Btn>
                        <TestResultBadge state={testing[r.key]} />
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <div className="mt-4 flex items-center gap-2">
        <Btn onClick={addRow}><Plus size={14} /> 新增配置项</Btn>
        <div className="flex-1" />
        <Btn onClick={() => void save()} disabled={saving}>
          {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />} 保存
        </Btn>
      </div>

      <p className="mt-3 text-[11px] leading-4 text-txt-low">
        说明：秘密项保存后仅回显掩码（前 4 位 + ****），需更换时重新输入即可；保存采用行级乐观锁，
        与他人并发修改冲突时会提示刷新。
      </p>
    </div>
  )
}

/** 连通性测试结果徽标（独立组件：props 收窄 'loading' 联合类型，避免索引访问二次求值） */
function TestResultBadge({ state }: { state: 'loading' | CredentialTestResult | undefined }) {
  if (!state || state === 'loading') return null
  return (
    <span className={`flex items-center gap-1 text-xs ${state.ok ? 'text-ok-deep' : 'text-bad-deep'}`} title={state.message}>
      {state.ok ? <CheckCircle2 size={13} /> : <XCircle size={13} />}
      {state.supported ? (state.ok ? '连通' : '不通') : '不支持'}
    </span>
  )
}

/** 秘密字段：密码框 + 掩码回显 + 明文/加密形态切换提示 */
function SecretInput({ row, onChange, onToggle }: {
  row: DraftRow
  onChange: (v: string) => void
  onToggle: () => void
}) {
  const [show, setShow] = useState(false)
  const placeholder = row.configured
    ? (row.masked ?? '****') + '（输入新值可更换，留空保留）'
    : '请输入秘密值（保存后仅回显掩码）'
  return (
    <div className="flex items-center gap-1.5">
      <div className="relative flex-1">
        <input
          type={show ? 'text' : 'password'}
          value={row.value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          autoComplete="new-password"
          className="w-full rounded-input border border-line bg-canvas px-2 py-1.5 pr-7 font-mono text-xs text-txt-hi outline-none focus:border-brand"
        />
        <button
          type="button"
          onClick={() => setShow((s) => !s)}
          className="absolute top-1/2 right-1.5 -translate-y-1/2 cursor-pointer text-txt-low hover:text-txt-hi"
          title={show ? '隐藏' : '显示'}
        >
          {show ? <EyeOff size={13} /> : <Eye size={13} />}
        </button>
      </div>
      {row.configured && (
        <button
          type="button"
          onClick={onToggle}
          className="cursor-pointer whitespace-nowrap text-[11px] text-txt-low hover:text-txt-hi"
          title="切换明文/加密形态（保存后生效）"
        >
          转明文
        </button>
      )}
    </div>
  )
}
