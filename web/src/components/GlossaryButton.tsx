import { useState } from 'react'
import { BookOpen, X } from 'lucide-react'
import { GLOSSARY } from '../data/glossary'

/**
 * 「术语说明」触发按钮 + 弹窗（UT-31）：各页面头部放置，弹出全站术语表。
 * 自包含组件，不依赖 ui.tsx，任何页面一行接入：<GlossaryButton />。
 */
export function GlossaryButton({ className = '' }: { className?: string }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={`inline-flex items-center gap-1 rounded-full border border-line px-2.5 py-1 text-xs text-txt-low transition-colors hover:bg-canvas hover:text-txt ${className}`}
      >
        <BookOpen size={13} /> 术语说明
      </button>
      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => setOpen(false)}>
          <div
            className="max-h-[80vh] w-full max-w-2xl overflow-y-auto rounded-xl border border-line bg-card p-5 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-base font-semibold text-txt">业务术语说明</h3>
              <button type="button" onClick={() => setOpen(false)} className="rounded p-1 text-txt-low hover:bg-canvas hover:text-txt" aria-label="关闭">
                <X size={16} />
              </button>
            </div>
            <div className="space-y-4">
              {GLOSSARY.map((group) => (
                <div key={group.domain}>
                  <div className="mb-1.5 text-xs font-semibold text-brand">{group.domain}</div>
                  <dl className="divide-y divide-line rounded-lg border border-line">
                    {group.terms.map((t) => (
                      <div key={t.code} className="grid grid-cols-[9rem_1fr] gap-2 px-3 py-2">
                        <dt className="text-xs leading-5">
                          <span className="font-mono font-semibold text-txt">{t.code}</span>
                          <span className="ml-1.5 text-txt-low">{t.name}</span>
                        </dt>
                        <dd className="text-xs leading-5 text-txt-low">{t.desc}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </>
  )
}
