// SPDX-License-Identifier: Apache-2.0

'use client'

import { useEffect, useId, useState } from 'react'
import { z } from 'zod'
import { ROLES } from '@/lib/auth/roles'
import { useAuth } from '@/lib/auth/useAuth'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'

const url = svcUrl('lending-service', '/api/v1/lending/risk/policy/proposals')
const revision = z.object({
  id: z.uuid(), revision: z.number().int(), effectiveFrom: z.iso.date(), contentHash: z.string().regex(/^[a-f0-9]{64}$/),
  state: z.enum(['PROPOSED', 'EXECUTED', 'REJECTED']), proposedBy: z.string(), policyJson: z.string(),
})
type Revision = z.infer<typeof revision>

async function fetchPending(signal?: AbortSignal): Promise<Revision[] | null> {
  try {
    const response = await fetch(url, { cache: 'no-store', signal })
    return response.ok ? z.array(revision).parse(await response.json()) : null
  } catch {
    return null
  }
}

export function PolicyRevisions() {
  const { hasRole, user } = useAuth()
  return user?.id && hasRole(ROLES.CREDIT_RISK) ? <RevisionQueue actor={user.id} /> : null
}

function RevisionQueue({ actor }: { actor: string }) {
  const { t } = useLanguage()
  const editorId = useId()
  const reasonId = useId()
  const [pending, setPending] = useState<Revision[] | null | undefined>(undefined)
  const [editor, setEditor] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState<{ text: string; error: boolean } | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    void fetchPending(controller.signal).then(proposals => {
      if (!controller.signal.aborted) setPending(proposals)
    })
    return () => controller.abort()
  }, [])

  async function submit(path: string, body: string, onSuccess: (saved: Revision) => void) {
    setBusy(true)
    setFeedback(null)
    try {
      const response = await fetch(path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body,
      })
      if (response.status === 422) {
        setFeedback({ error: true, text: t('Rozhodnout musí jiný pracovník.', 'A different staff member must decide.') })
      } else if (response.status === 409) {
        setFeedback({ error: true, text: t('O návrhu už bylo rozhodnuto nebo je verze či datum obsazené.', 'The proposal was already decided or the version or date is in use.') })
      } else if (!response.ok) {
        setFeedback({ error: true, text: t(`Zápis se nezdařil (${response.status}).`, `Write failed (${response.status}).`) })
      } else {
        onSuccess(revision.parse(await response.json()))
      }
      setPending(await fetchPending())
    } catch {
      setFeedback({ error: true, text: t('Výsledek zápisu se nepodařilo potvrdit. Obnovte seznam návrhů.', 'The write could not be confirmed. Refresh the proposal list.') })
    } finally {
      setBusy(false)
    }
  }

  function propose() {
    try {
      JSON.parse(editor)
    } catch {
      setFeedback({ error: true, text: t('Zadejte platný JSON politiky.', 'Enter valid policy JSON.') })
      return
    }
    void submit(url, editor, saved => {
      setEditor('')
      setFeedback({ error: false, text: t(`Verze ${saved.revision}: ${saved.state}.`, `Version ${saved.revision}: ${saved.state}.`) })
    })
  }

  function decide(proposal: Revision, approve: boolean) {
    const decision = JSON.stringify({ approve, reason: reason.trim() || null })
    void submit(`${url}/${proposal.id}/decide`, decision, saved => {
      setSelected(null)
      setReason('')
      setFeedback({ error: false, text: saved.state === 'EXECUTED'
        ? t(`Schváleno s účinností od ${saved.effectiveFrom}.`, `Approved effective ${saved.effectiveFrom}.`)
        : t('Návrh byl zamítnut.', 'Proposal rejected.') })
    })
  }

  return (
    <section className="card" style={{ padding: 16, display: 'grid', gap: 12 }}>
      <h3>{t('Návrhy české úvěrové politiky', 'Czech credit policy revisions')}</h3>
      <form onSubmit={event => { event.preventDefault(); propose() }} style={{ display: 'grid', gap: 8 }}>
        <label htmlFor={editorId}>{t('JSON nové verze pravidel', 'Policy JSON for a new version')}</label>
        <textarea id={editorId} rows={5} spellCheck={false} value={editor} onChange={event => setEditor(event.target.value)} />
        <button type="submit" className="btn" disabled={busy || !editor.trim()}>{t('Uložit návrh', 'Save proposal')}</button>
      </form>
      {feedback && <p role={feedback.error ? 'alert' : 'status'}>{feedback.text}</p>}
      <button type="button" className="btn" disabled={busy} onClick={() => void fetchPending().then(setPending)}>{t('Obnovit návrhy', 'Refresh proposals')}</button>
      {pending === undefined && <p>{t('Načítám návrhy…', 'Loading proposals…')}</p>}
      {pending === null && <p>{t('Seznam návrhů zatím není k dispozici.', 'Proposal list is currently unavailable.')}</p>}
      {pending?.length === 0 && <p>{t('Žádné návrhy nečekají na rozhodnutí.', 'No proposals are awaiting a decision.')}</p>}
      {pending?.map(proposal => (
        <article key={proposal.id} style={{ borderTop: '1px solid var(--border)', paddingTop: 10 }}>
          <button type="button" className="btn" disabled={busy} aria-expanded={selected === proposal.id}
            onClick={() => { setSelected(selected === proposal.id ? null : proposal.id); setReason('') }}>
            {t(`Verze ${proposal.revision} od ${proposal.effectiveFrom}`, `Version ${proposal.revision} from ${proposal.effectiveFrom}`)}
          </button>
          {selected === proposal.id && (
            <div style={{ display: 'grid', gap: 8, marginTop: 8 }}>
              <div>SHA-256 <code style={{ overflowWrap: 'anywhere' }}>{proposal.contentHash}</code></div>
              <pre style={{ overflow: 'auto', maxHeight: '24rem' }}>{proposal.policyJson}</pre>
              {proposal.proposedBy === actor ? <p>{t('Váš návrh posoudí jiný pracovník.', 'Another staff member will review your proposal.')}</p> : (
                <>
                  <label htmlFor={reasonId}>{t('Odůvodnění rozhodnutí', 'Decision reason')}</label>
                  <input id={reasonId} value={reason} onChange={event => setReason(event.target.value)} />
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                    <button type="button" className="btn" disabled={busy} onClick={() => decide(proposal, false)}>{t('Zamítnout verzi', 'Reject revision')}</button>
                    <button type="button" className="btn" disabled={busy} onClick={() => decide(proposal, true)}>{t('Schválit verzi', 'Approve revision')}</button>
                  </div>
                </>
              )}
            </div>
          )}
        </article>
      ))}
    </section>
  )
}
