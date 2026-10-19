// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0344 D7 corrections of a booked deal: back office proposes changed terms with a reason and an
// effective date and sees the preview the service computed — every changed flow before and after,
// and the delta journals approval would post. A breach of the counterparty limit can be proposed as
// a request for a senior override. Approve / reject / override are offered only to a human who is no
// term author (not the proposer, not the deal's creator) and never to an AI principal; the service
// refuses all of it regardless.

'use client'

import { useCallback, useEffect, useState } from 'react'
import { hasPermission } from '@/lib/auth/roles'
import { wbGet, wbPost, wbUrl, type WbRead } from './client'
import { canDecideCorrection } from './payments'
import { isAiPrincipal } from './principal'
import { correctionListSchema, correctionSchema, type Correction } from './types'
import { Field, Notice, ReadFailure, Section, td, tdNum, th, useFmt, writeFailureText } from './ui'

type DealRef = { dealId: string; createdBy?: string | null; state?: string | null }

export function CorrectionsPanel({ deal, actor, roles, onChanged }: { deal: DealRef; actor: string | null; roles: string[]; onChanged: () => void }) {
  const { t, money } = useFmt()
  const ai = isAiPrincipal({ roles })
  const canPropose = hasPermission(roles, 'treasury:backoffice:write') && !ai
  const holdsApprove = hasPermission(roles, 'treasury:decision:approve')
  const holdsOverride = hasPermission(roles, 'treasury:deal:override-limit')
  const [list, setList] = useState<WbRead<Correction[]> | null>(null)
  const [form, setForm] = useState({ changes: '', reason: '', effectiveFrom: '', fullUnwind: false, requestLimitOverride: false })
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const base = `/deals/${encodeURIComponent(deal.dealId)}/corrections`

  const load = useCallback(async () => { setList(await wbGet(wbUrl(base), correctionListSchema)) }, [base])
  useEffect(() => { void load() }, [load])

  const propose = async () => {
    let changes: unknown = {}
    if (!form.fullUnwind) {
      try { changes = form.changes.trim() ? JSON.parse(form.changes) : null } catch { changes = null }
      if (changes === null || typeof changes !== 'object') {
        setNotice({ tone: 'danger', text: t('Změny zadejte jako JSON objekt (např. {"rate": 4.1} nebo {"legs": [...]}).', 'Enter the changes as a JSON object (e.g. {"rate": 4.1} or {"legs": [...]}).') })
        return
      }
    }
    if (!form.reason.trim() || !form.effectiveFrom) { setNotice({ tone: 'danger', text: t('Důvod a datum účinnosti jsou povinné.', 'A reason and an effective date are required.') }); return }
    setBusy(true)
    const res = await wbPost(wbUrl(base), {
      changes: form.fullUnwind ? undefined : changes, fullUnwind: form.fullUnwind, reason: form.reason.trim(),
      effectiveFrom: form.effectiveFrom, requestLimitOverride: form.requestLimitOverride,
    }, correctionSchema)
    setBusy(false)
    if (res.ok) { setNotice({ tone: 'success', text: t('Oprava navržena — čeká na schválení jinou osobou.', 'Correction proposed — a different person must approve it.') }); setForm(f => ({ ...f, changes: '', reason: '' })); void load() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Návrh opravy', 'Propose correction'), t) })
  }

  const decide = async (c: Correction, action: 'approve' | 'reject' | 'override-limit') => {
    let body: { reason: string } | undefined
    if (action !== 'approve') {
      const reason = window.prompt(action === 'reject' ? t('Důvod zamítnutí', 'Reason for rejecting') : t('Důvod překročení limitu', 'Reason for the limit override'))
      if (!reason?.trim()) return
      body = { reason: reason.trim() }
    }
    setBusy(true)
    const res = await wbPost(wbUrl(`${base}/${encodeURIComponent(c.correctionId)}/${action}`), body, correctionSchema)
    setBusy(false)
    if (res.ok) { setNotice({ tone: 'success', text: t('Hotovo.', 'Done.') }); void load(); onChanged() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Rozhodnutí o opravě', 'Correction decision'), t) })
  }

  const who = (holds: boolean) => ({ actor, holds, ai })

  return (
    <Section title={t('Opravy obchodu', 'Deal corrections')} id="deal-corrections">
      {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}
      {list === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
      {list && !list.ok && <ReadFailure result={list} what={t('Opravy', 'Corrections')} />}
      {list?.ok && list.data.length === 0 && <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Žádné opravy.', 'No corrections.')}</p>}
      {list?.ok && list.data.map(c => {
        const mayDecide = canDecideCorrection(c, deal, who(holdsApprove))
        const mayOverride = c.limitOverrideRequested && !c.limitOverrideBy && canDecideCorrection(c, deal, who(holdsOverride))
        return (
          <div key={c.correctionId} className="card" style={{ marginBottom: 12 }} data-testid="correction">
            <p style={{ fontSize: 13 }}>
              <strong>{c.status}</strong>{` · ${c.reason} · ${t('účinnost od', 'effective from')} ${c.effectiveFrom} · ${t('navrhl', 'proposed by')} ${c.proposedBy}`}
              {c.fullUnwind ? ` · ${t('úplné zrušení', 'full unwind')}` : ''}
              {c.limitOverrideRequested ? ` · ${c.limitOverrideBy ? t(`limit překročen se souhlasem ${c.limitOverrideBy}`, `limit overridden by ${c.limitOverrideBy}`) : t('žádá o překročení limitu', 'requests a limit override')}` : ''}
              {c.decisionReason ? ` · ${c.decisionReason}` : ''}
            </p>
            {(c.preview?.changedFlows?.length ?? 0) > 0 && (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12, marginBottom: 8 }} data-testid="correction-flows">
                <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Změněné toky', 'Changed flows')}</caption>
                <thead><tr>{[t('Tok', 'Flow'), t('Platba před', 'Payment before'), t('Platba po', 'Payment after')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}<th scope="col" style={{ ...th, textAlign: 'right' }}>{t('Částka před', 'Amount before')}</th><th scope="col" style={{ ...th, textAlign: 'right' }}>{t('Částka po', 'Amount after')}</th></tr></thead>
                <tbody>
                  {c.preview!.changedFlows!.map(ch => (
                    <tr key={ch.slot} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={td}>{ch.slot}</td>
                      <td style={td}>{ch.before?.paymentDate ?? '—'}</td>
                      <td style={td}>{ch.after?.paymentDate ?? '—'}</td>
                      <td style={tdNum}>{ch.before?.amount != null ? `${money(ch.before.amount)} ${ch.before.currency}` : '—'}</td>
                      <td style={tdNum}>{ch.after?.amount != null ? `${money(ch.after.amount)} ${ch.after.currency}` : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {(c.preview?.deltaJournals?.length ?? 0) > 0 && (
              <ul style={{ fontSize: 12 }} data-testid="correction-deltas">
                {c.preview!.deltaJournals!.map(d => (
                  <li key={d.key}>{`${d.valueDate} · ${d.sourceEvent} · `}{d.lines.map(l => `${l.side === 'DEBIT' ? 'Dr' : 'Cr'} ${l.role} ${money(l.amount)} ${l.currency}`).join(', ')}</li>
                ))}
              </ul>
            )}
            <div style={{ display: 'flex', gap: 8 }}>
              {mayDecide && <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void decide(c, 'approve')}>{t('Schválit opravu', 'Approve correction')}</button>}
              {mayDecide && <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void decide(c, 'reject')}>{t('Zamítnout opravu', 'Reject correction')}</button>}
              {mayOverride && <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void decide(c, 'override-limit')}>{t('Povolit překročení limitu', 'Override the limit')}</button>}
              {c.status === 'PENDING' && (holdsApprove || holdsOverride) && !mayDecide && <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{t('Rozhodnout nesmíte: jste autorem podmínek.', 'You may not decide: you authored the terms.')}</span>}
            </div>
          </div>
        )
      })}
      {canPropose && (
        <div className="card">
          <h3 style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>{t('Navrhnout opravu', 'Propose a correction')}</h3>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <Field label={t('Změny podmínek (JSON)', 'Changed terms (JSON)')}><textarea className="input" rows={3} value={form.changes} disabled={form.fullUnwind} onChange={e => setForm(f => ({ ...f, changes: e.target.value }))} /></Field>
            <Field label={t('Důvod opravy', 'Correction reason')}><input className="input" value={form.reason} onChange={e => setForm(f => ({ ...f, reason: e.target.value }))} /></Field>
            <Field label={t('Účinnost od', 'Effective from')}><input className="input" type="date" value={form.effectiveFrom} onChange={e => setForm(f => ({ ...f, effectiveFrom: e.target.value }))} /></Field>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, justifyContent: 'end' }}>
              <label><input type="checkbox" checked={form.fullUnwind} onChange={e => setForm(f => ({ ...f, fullUnwind: e.target.checked }))} /> {t('Úplné zrušení obchodu', 'Full unwind')}</label>
              <label><input type="checkbox" checked={form.requestLimitOverride} onChange={e => setForm(f => ({ ...f, requestLimitOverride: e.target.checked }))} /> {t('Žádat o překročení limitu', 'Request a limit override')}</label>
            </div>
          </div>
          <button type="button" className="btn btn-primary btn-sm" style={{ marginTop: 8 }} disabled={busy} onClick={() => void propose()}>{t('Navrhnout opravu', 'Propose correction')}</button>
        </div>
      )}
    </Section>
  )
}
