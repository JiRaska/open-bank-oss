// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Four-eyes disposition and the suspicious-transaction report for one AML case (ADR-0371 §5, §10).
//
// A pack-monitoring case cannot be closed by one analyst: one person proposes CLEAR or REPORT with
// a structured outcome, a different person approves or rejects it, and only then does aml-service
// move the case. The report to the FAU follows the same rule — drafted by one, approved by
// another, then the filing reference is recorded. Every rule is enforced by the service against
// the authenticated principal; this panel only offers the next legal step and shows who did what.

import { useCallback, useEffect, useState } from 'react'
import { Users, FileWarning, Check, X } from 'lucide-react'
import { svcUrl } from '@/lib/services/bff'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { StatusBadge } from '@/components/ui'
import type { AmlCase } from '@/lib/aml/amlCaseContract'
import {
  DISPOSITIONS, OUTCOME_CODES, describeFailure, outcomesFor, parseList, parseProposal, parseReport,
  type Disposition, type DispositionProposal, type StrReport,
} from '@/lib/aml/fourEyes'

const MONITORING = '/api/v1/aml/monitoring'
const REPORTS = '/api/v1/aml/reports'

async function call(path: string, method: 'GET' | 'POST', body?: unknown): Promise<Response> {
  return fetch(svcUrl('aml-service', path), {
    method,
    cache: 'no-store',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

export function AmlFourEyesPanel({ amlCase, onCaseChanged }: { amlCase: AmlCase; onCaseChanged: () => void }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString(locale) : '—')
  const outcomeLabel = (code: string | null) => {
    const o = OUTCOME_CODES.find(x => x.code === code)
    return o ? (language === 'cs' ? o.cs : o.en) : code ?? '—'
  }

  const fourEyes = amlCase.requiresFourEyesDisposition
  const monitoring = amlCase.screeningType === 'TRANSACTION_MONITORING'
  const open = ['OPEN', 'UNDER_REVIEW', 'ESCALATED'].includes(amlCase.status)

  const [proposals, setProposals] = useState<DispositionProposal[] | null>(null)
  const [reports, setReports] = useState<StrReport[] | null>(null)
  const [listError, setListError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [ok, setOk] = useState<string | null>(null)

  const [disposition, setDisposition] = useState<Disposition>('CLEAR')
  const [outcome, setOutcome] = useState<string>('')
  const [note, setNote] = useState('')
  const [checkerReason, setCheckerReason] = useState('')
  const [narrative, setNarrative] = useState('')
  const [filingRef, setFilingRef] = useState('')
  const [filedAt, setFiledAt] = useState('')
  const [closeOutcome, setCloseOutcome] = useState('CONFIRMED_SUSPICION')

  const load = useCallback(async () => {
    try {
      const [p, r] = await Promise.all([
        fourEyes ? call(`${MONITORING}/disposition-proposals?limit=200`, 'GET') : Promise.resolve(null),
        monitoring ? call(`${REPORTS}?limit=200`, 'GET') : Promise.resolve(null),
      ])
      if (p) {
        if (p.ok) setProposals(parseList(await p.json(), parseProposal).filter(x => x.caseId === amlCase.id))
        else setListError(await describeFailure(p, t))
      }
      if (r) {
        if (r.ok) setReports(parseList(await r.json(), parseReport).filter(x => x.caseIds.includes(amlCase.id)))
        else setListError(await describeFailure(r, t))
      }
    } catch {
      setListError(t('Čtyřoční přehled nelze načíst.', 'The four-eyes overview could not be loaded.'))
    }
  }, [fourEyes, monitoring, amlCase.id, t])

  useEffect(() => {
    if (!fourEyes && !monitoring) return
    const handle = setTimeout(() => { void load() }, 0)
    return () => clearTimeout(handle)
  }, [load, fourEyes, monitoring])

  if (!fourEyes && !monitoring) return null

  async function act(path: string, body: unknown, success: string, caseMoves = false) {
    setBusy(true); setError(null); setOk(null)
    try {
      const res = await call(path, 'POST', body)
      if (!res.ok) { setError(await describeFailure(res, t)); return }
      setOk(success)
      await load()
      if (caseMoves) onCaseChanged()
    } catch {
      setError(t('Akci se nepodařilo provést. Zkuste to znovu.', 'The action could not be completed. Try again.'))
    } finally { setBusy(false) }
  }

  const pending = proposals?.find(p => p.state === 'PROPOSED') ?? null
  const decided = (proposals ?? []).filter(p => p.state !== 'PROPOSED')
  const outcomeChoices = outcomesFor(disposition)
  const label = { fontSize: 12, fontWeight: 600 } as const
  const muted = { fontSize: 12, color: 'var(--text-secondary)', margin: '4px 0' } as const

  return (
    <section className="card" style={{ padding: 20, marginBottom: 16, minWidth: 0 }} aria-label={t('Čtyři oči a hlášení FAÚ', 'Four eyes and FAU report')}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
        <Users size={16} aria-hidden="true" style={{ color: 'var(--accent)' }} />
        <h2 style={{ margin: 0, fontSize: 15 }}>{t('Čtyři oči a hlášení FAÚ', 'Four eyes and FAU report')}</h2>
      </div>
      {listError && <p role="alert" style={{ ...muted, color: 'var(--danger-text)' }}>{listError}</p>}

      {fourEyes && <div style={{ marginBottom: 20 }}>
        <h3 style={{ fontSize: 13, margin: '0 0 6px' }}>{t('Uzavření případu (navrhuje jeden, schvaluje druhý)', 'Closing the case (one proposes, another approves)')}</h3>
        <p style={muted}>{t('Případ z monitoringu transakcí nelze uzavřít ani eskalovat jedním rozhodnutím. Navrhněte uzavření; schválit jej musí jiný compliance officer.', 'A transaction-monitoring case cannot be closed or escalated by one decision. Propose a disposition; another compliance officer must approve it.')}</p>

        {proposals === null && !listError && <p role="status" style={muted}>{t('Načítám návrhy…', 'Loading proposals…')}</p>}

        {pending && <div style={{ border: '1px solid var(--warning-border, var(--border))', background: 'var(--warning-bg, var(--surface-2))', borderRadius: 8, padding: 12, marginTop: 8 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <StatusBadge status="PROPOSED" label={t('Čeká na druhé schválení', 'Awaiting second approval')} />
            <strong style={{ fontSize: 13 }}>{pending.disposition === 'REPORT' ? t('Nahlásit FAÚ', 'Report to FAU') : t('Uzavřít bez nálezu', 'Clear')}</strong>
            <span style={{ fontSize: 12 }}>· {outcomeLabel(pending.outcomeCode)}</span>
          </div>
          <p style={muted}>{t('Navrhl/a', 'Proposed by')} {pending.proposedBy} · {fmt(pending.proposedAt)}</p>
          {pending.note && <p style={{ fontSize: 13, margin: '6px 0' }}>{pending.note}</p>}
          <label htmlFor="aml-checker-reason" style={label}>{t('Zdůvodnění schvalovatele', "Approver's reason")}</label>
          <textarea id="aml-checker-reason" className="input" rows={2} maxLength={2000} value={checkerReason} onChange={e => setCheckerReason(e.target.value)} style={{ width: '100%', marginTop: 4 }} />
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void act(`${MONITORING}/disposition-proposals/${pending.proposalId}/decision`, { approve: true, reason: checkerReason.trim() || null }, t('Návrh schválen, případ byl uzavřen.', 'Proposal approved; the case was closed.'), true)}>
              <Check size={12} aria-hidden="true" /> {t('Schválit', 'Approve')}
            </button>
            <button type="button" className="btn btn-secondary btn-sm" disabled={busy || checkerReason.trim().length < 10} title={t('Zamítnutí vyžaduje zdůvodnění (min. 10 znaků)', 'Rejecting requires a reason (min. 10 characters)')} onClick={() => void act(`${MONITORING}/disposition-proposals/${pending.proposalId}/decision`, { approve: false, reason: checkerReason.trim() }, t('Návrh zamítnut.', 'Proposal rejected.'))}>
              <X size={12} aria-hidden="true" /> {t('Zamítnout', 'Reject')}
            </button>
          </div>
          <p style={{ ...muted, fontSize: 11 }}>{t('Vlastní návrh schválit nemůžete — služba to odmítne.', 'You cannot approve your own proposal — the service refuses it.')}</p>
        </div>}

        {!pending && proposals !== null && open && <div style={{ display: 'grid', gap: 8, marginTop: 8 }}>
          <div role="radiogroup" aria-label={t('Návrh uzavření', 'Proposed disposition')} style={{ display: 'flex', gap: 8 }}>
            {DISPOSITIONS.map(d => <button key={d} type="button" role="radio" aria-checked={disposition === d} className={`btn btn-sm ${disposition === d ? 'btn-primary' : 'btn-secondary'}`} onClick={() => { setDisposition(d); setOutcome('') }}>
              {d === 'REPORT' ? t('Navrhnout oznámení FAÚ', 'Propose FAU report') : t('Navrhnout uzavření bez nálezu', 'Propose clear')}
            </button>)}
          </div>
          <label htmlFor="aml-outcome" style={label}>{t('Výsledek posouzení *', 'Assessment outcome *')}</label>
          <select id="aml-outcome" className="input" value={outcome} onChange={e => setOutcome(e.target.value)}>
            <option value="">{t('— vyberte —', '— choose —')}</option>
            {outcomeChoices.map(o => <option key={o.code} value={o.code}>{language === 'cs' ? o.cs : o.en}</option>)}
          </select>
          <label htmlFor="aml-proposal-note" style={label}>{t('Odůvodnění návrhu *', 'Rationale *')}</label>
          <textarea id="aml-proposal-note" className="input" rows={3} maxLength={2000} value={note} onChange={e => setNote(e.target.value)} placeholder={t('Co jste ověřili a proč navrhujete toto uzavření…', 'What you verified and why you propose this…')} />
          <div>
            <button type="button" className="btn btn-primary btn-sm" disabled={busy || !outcome || note.trim().length < 10} onClick={() => void act(`${MONITORING}/cases/${amlCase.id}/disposition-proposals`, { disposition, outcomeCode: outcome, note: note.trim() }, t('Návrh odeslán ke druhému schválení.', 'Proposal sent for second approval.')).then(() => { setNote(''); setOutcome('') })}>
              {t('Odeslat ke schválení', 'Send for approval')}
            </button>
          </div>
        </div>}

        {decided.length > 0 && <ul style={{ listStyle: 'none', padding: 0, margin: '10px 0 0' }}>
          {decided.map(p => <li key={p.proposalId} style={{ fontSize: 12, padding: '6px 0', borderBottom: '1px dashed var(--border)' }}>
            <StatusBadge status={p.state} /> {p.disposition} · {outcomeLabel(p.outcomeCode)} · {t('navrhl/a', 'by')} {p.proposedBy}{p.decidedBy ? ` · ${t('rozhodl/a', 'decided by')} ${p.decidedBy} ${fmt(p.decidedAt)}` : ''}{p.decisionReason ? ` — ${p.decisionReason}` : ''}
          </li>)}
        </ul>}
      </div>}

      {monitoring && <div>
        <h3 style={{ fontSize: 13, margin: '0 0 6px', display: 'flex', gap: 6, alignItems: 'center' }}><FileWarning size={14} aria-hidden="true" /> {t('Oznámení podezřelého obchodu (FAÚ)', 'Suspicious-transaction report (FAU)')}</h3>
        <p style={muted}>{t('Koncept připraví jeden officer, schválí jiný (typicky MLRO), poté se zaznamená referenční číslo podání FAÚ. Oznámení se nesděluje klientovi (zákaz tipping-off).', 'One officer drafts, another (typically the MLRO) approves, then the FAU filing reference is recorded. The report is never disclosed to the customer (no tipping-off).')}</p>

        {reports === null && !listError && <p role="status" style={muted}>{t('Načítám oznámení…', 'Loading reports…')}</p>}
        {reports?.map(r => <div key={r.reportId} style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 12, marginTop: 8 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <StatusBadge status={r.state} />
            <span style={{ fontSize: 12 }}>{t('Koncept', 'Drafted by')} {r.draftedBy} · {fmt(r.draftedAt)}</span>
            {r.approvedBy && <span style={{ fontSize: 12 }}>· {t('schválil/a', 'approved by')} {r.approvedBy} {fmt(r.approvedAt)}</span>}
            {r.filingReference && <span style={{ fontSize: 12 }}>· {t('podáno', 'filed')} {r.filingReference} ({fmt(r.filedAt)})</span>}
            {r.withdrawnBy && <span style={{ fontSize: 12 }}>· {t('staženo', 'withdrawn by')} {r.withdrawnBy}</span>}
          </div>
          <p style={{ fontSize: 13, whiteSpace: 'pre-wrap', margin: '8px 0' }}>{r.narrative}</p>
          {r.caseIds.length > 1 && <p style={muted}>{t('Zahrnuje případů', 'Covers cases')}: {r.caseIds.length}</p>}
          {r.state === 'DRAFT' && <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void act(`${REPORTS}/${r.reportId}/approval`, undefined, t('Oznámení schváleno k podání.', 'Report approved for filing.'))}>{t('Schválit k podání (druhá osoba)', 'Approve for filing (second person)')}</button>}
          {r.state === 'APPROVED' && <div style={{ display: 'grid', gap: 6, marginTop: 6 }}>
            <label htmlFor={`ref-${r.reportId}`} style={label}>{t('Referenční číslo podání FAÚ', 'FAU filing reference')}</label>
            <input id={`ref-${r.reportId}`} className="input" maxLength={120} value={filingRef} onChange={e => setFilingRef(e.target.value)} />
            <label htmlFor={`at-${r.reportId}`} style={label}>{t('Podáno dne', 'Filed at')}</label>
            <input id={`at-${r.reportId}`} type="datetime-local" className="input" value={filedAt} onChange={e => setFiledAt(e.target.value)} />
            <label htmlFor={`oc-${r.reportId}`} style={label}>{t('Výsledek', 'Outcome')}</label>
            <select id={`oc-${r.reportId}`} className="input" value={closeOutcome} onChange={e => setCloseOutcome(e.target.value)}>
              {OUTCOME_CODES.map(o => <option key={o.code} value={o.code}>{language === 'cs' ? o.cs : o.en}</option>)}
            </select>
            <div style={{ display: 'flex', gap: 8 }}>
              <button type="button" className="btn btn-primary btn-sm" disabled={busy || !filingRef.trim() || !filedAt} onClick={() => void act(`${REPORTS}/${r.reportId}/filing`, { filingReference: filingRef.trim(), filedAt: new Date(filedAt).toISOString(), outcomeCode: closeOutcome }, t('Podání FAÚ zaznamenáno.', 'FAU filing recorded.'))}>{t('Zaznamenat podání', 'Record filing')}</button>
              <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void act(`${REPORTS}/${r.reportId}/withdrawal`, { outcomeCode: closeOutcome }, t('Oznámení staženo.', 'Report withdrawn.'))}>{t('Stáhnout', 'Withdraw')}</button>
            </div>
          </div>}
        </div>)}

        {reports !== null && !reports.some(r => r.state === 'DRAFT' || r.state === 'APPROVED') && <div style={{ display: 'grid', gap: 6, marginTop: 10 }}>
          <label htmlFor="aml-str-narrative" style={label}>{t('Nový koncept oznámení — popis skutkového stavu', 'New report draft — narrative')}</label>
          <textarea id="aml-str-narrative" className="input" rows={5} maxLength={10000} value={narrative} onChange={e => setNarrative(e.target.value)}
            placeholder={t('Kdo, co, kdy, kolik, proč je to podezřelé, jaké podklady máte…', 'Who, what, when, how much, why it is suspicious, what evidence you hold…')} />
          <div>
            <button type="button" className="btn btn-secondary btn-sm" disabled={busy || narrative.trim().length < 30} onClick={() => void act(REPORTS, { partyId: amlCase.partyId, narrative: narrative.trim(), caseIds: [amlCase.id], movements: [] }, t('Koncept oznámení uložen; čeká na schválení druhou osobou.', 'Report draft saved; awaiting approval by a second person.')).then(() => setNarrative(''))}>
              {t('Uložit koncept oznámení', 'Save report draft')}
            </button>
          </div>
        </div>}
      </div>}

      {error && <p role="alert" style={{ color: 'var(--danger-text)', fontSize: 13, marginTop: 10 }}>{error}</p>}
      {ok && <p role="status" style={{ color: 'var(--success-text, var(--success))', fontSize: 13, marginTop: 10 }}>{ok}</p>}
    </section>
  )
}
