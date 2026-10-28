// ⑦ 关联分支区块（docs/v2/17 §3 · GitLab 语义）：按工作项 KEY 匹配仓库分支，
// 无则「创建分支」（feature/<KEY>，从产品仓库默认分支拉出）。跨页共用（需求/任务抽屉）。
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { GitBranch, Plus } from 'lucide-react'
import { reposApi, useRepoBranches, useRepos } from '../api/queries'
import { Btn } from './ui'

export function RelatedBranches({ workItemKey, productId }: { workItemKey: string; productId?: string }) {
  const queryClient = useQueryClient()
  const { data: repos } = useRepos()
  // QA MUST-FIX：按工作项所属产品定仓（原 fallback 链与 productId 解耦——多产品必错绑）
  const repo = (repos?.items ?? []).find((r) => productId && r.productId === productId)
  const { data: branches } = useRepoBranches(repo?.name)
  const [creating, setCreating] = useState(false)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | undefined>()

  // QA MUST-FIX：KEY 词边界匹配（includes 会把 T-19 误报给 T-1——前缀包含锁死创建按钮）
  const keyRe = new RegExp(`(^|[^A-Za-z0-9])${workItemKey}($|[^A-Za-z0-9])`)
  const related = (branches ?? []).filter((b) => keyRe.test(b.name))
  const createBranch = useMutation({
    mutationFn: () => reposApi.createBranch(repo!.name, `feature/${workItemKey}`, repo!.defaultBranch),
    onSuccess: () => {
      setMsg({ ok: true, text: `分支 feature/${workItemKey} 已创建（GitFlow 规则与保护已即席生效）` })
      void queryClient.invalidateQueries({ queryKey: ['repo', repo?.name, 'branches'] })
      void queryClient.invalidateQueries({ queryKey: ['repos'] })
    },
    onError: (e) => setMsg({ ok: false, text: e instanceof Error ? e.message : '创建失败' }),
    onSettled: () => setCreating(false),
  })

  if (!repo) {
    return (
      <div className="mt-4 border-t border-line pt-3 text-xs text-txt-low">
        产品尚未绑定仓库，无法使用关联分支。
      </div>
    )
  }
  return (
    <div className="mt-4 border-t border-line pt-3">
      <div className="mb-1.5 flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-[11px] font-semibold tracking-wide text-txt-low">
          <GitBranch size={11} /> 关联分支（{repo.name}）
        </span>
        {related.length === 0 && (
          <Btn variant="ghost" disabled={creating || createBranch.isPending} onClick={() => createBranch.mutate()}>
            <Plus size={12} />{createBranch.isPending ? '创建中…' : `创建分支 feature/${workItemKey}`}
          </Btn>
        )}
      </div>
      <div className="space-y-1">
        {related.map((b) => (
          <div key={b.name} className="flex items-center gap-2 rounded-lg border border-line bg-canvas px-2 py-1.5 text-xs">
            <GitBranch size={11} className="shrink-0 text-brand" />
            <span className="min-w-0 flex-1 truncate font-mono text-txt-hi">{b.name}</span>
            <span className="shrink-0 text-txt-low" title={b.commitSubject ?? ''}>{b.commitSha?.slice(0, 7)}</span>
          </div>
        ))}
        {related.length === 0 && (
          <div className="text-[11px] text-txt-low">
            尚无包含 <span className="font-mono">{workItemKey}</span> 的分支——创建后在此自动关联（命名规范 feature/&lt;KEY&gt;）。
          </div>
        )}
      </div>
      {msg && <div className={`mt-1.5 text-[11px] ${msg.ok ? 'text-ok-deep' : 'text-bad-deep'}`}>{msg.text}</div>}
    </div>
  )
}
