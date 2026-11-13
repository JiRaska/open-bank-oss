// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// AML case detail — the screen an AML officer actually decides on.
//
// The register used to be a dead end: no row opened anything, the subject was a UUID-shaped
// `customerReference`, and the only button ("Explore graph") silently did nothing. An officer
// could see THAT a case existed but not WHO it was about, WHAT triggered it, or record a
// decision. This page answers those in the order an officer works:
//   1. who   — the party's name, type and KYC state from party-service (non-fatal lookup);
//   2. what  — the alert, what it means for this screening type, the matched entity, and for
//              transaction monitoring the transaction itself from transaction-service;
//   3. context — the party's other AML cases and the evidence graph, loaded automatically;
//   4. decide — only the transitions aml-service will accept, a written rationale required for
//              every decision, and the decision recorded against the signed-in officer.

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useSession } from 'next-auth/react'
import { ArrowLeft, ShieldAlert, UserRound, Landmark, ArrowRightLeft, History, Gavel, ExternalLink, RefreshCw, ClipboardCheck } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { PageHeader, ShortId, StatusBadge } from '@/components/ui'
import { svcUrl, classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  allowedTransitions, caseAgeHours, isOpenAmlStatus, parseAmlCase, parseAmlCases, requiresReason,
  type AmlCase, type AmlStatus,
} from '@/lib/aml/amlCaseContract'
import { fetchPartySummary, type AmlPartySummary } from '@/lib/aml/usePartyNames'
import { AmlCaseInvestigation } from '@/components/context/AmlCaseInvestigation'
import { EntityChip } from '@/components/entities/EntityChip'

type Tr = (cs: string, en: string) => string

interface TxnSummary {
  id: string; referenceNumber: string | null; type: string | null; status: string | null
  amount: string | null; currencyCode: string | null; description: string | null
  sourceAccountId: string | null; targetAccountId: string | null
  initiatedAt: string | null; bookingDate: string | null; rail: string | null
}

function parseTxn(raw: unknown): TxnSummary | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const o = raw as Record<string, unknown>
  const s = (v: unknown) => (typeof v === 'string' && v.trim() ? v : typeof v === 'number' ? String(v) : null)
  const id = s(o.id)
  if (!id) return null
  return {
    id, referenceNumber: s(o.referenceNumber), type: s(o.type), status: s(o.status), amount: s(o.amount),
    currencyCode: s(o.currencyCode), description: s(o.description), sourceAccountId: s(o.sourceAccountId),
    targetAccountId: s(o.targetAccountId), initiatedAt: s(o.initiatedAt), bookingDate: s(o.bookingDate), rail: s(o.rail),
  }
}

/** What the officer is being asked to judge, per screening type. Guidance, not policy. */
function assessmentGuide(c: AmlCase, t: Tr): string[] {
  switch (c.screeningType) {
    case 'CUSTOMER_ONBOARDING': return [
      t('Odpovídá identita klienta (jméno, datum narození, státní příslušnost) dokladům z KYC?', 'Does the customer identity (name, date of birth, nationality) match the KYC documents?'),
      t('Je shoda se sankčním/PEP seznamem skutečně tatáž osoba, nebo jen podobné jméno?', 'Is the sanctions/PEP match the same person, or only a similar name?'),
      t('Je deklarovaný účel vztahu a zdroj prostředků věrohodný pro tento profil?', 'Are the declared purpose of the relationship and source of funds plausible for this profile?'),
      t('Vyžaduje riziko zesílenou kontrolu (EDD) nebo souhlas vedení před otevřením vztahu?', 'Does the risk require enhanced due diligence or senior-management approval before onboarding?'),
    ]
    case 'TRANSACTION_MONITORING': return [
      t('Odpovídá transakce známému profilu klienta (výše, frekvence, protistrana, země)?', 'Does the transaction fit the known customer profile (amount, frequency, counterparty, country)?'),
      t('Má transakce zřejmý ekonomický smysl, nebo nese znaky strukturování či průtokového účtu?', 'Is there a clear economic purpose, or signs of structuring or a pass-through account?'),
      t('Je protistrana nebo jurisdikce vysoce riziková či sankcionovaná?', 'Is the counterparty or jurisdiction high-risk or sanctioned?'),
      t('Zakládá podezření povinnost oznámit podezřelý obchod FAÚ (AML zákon § 18)?', 'Does the suspicion trigger a suspicious-transaction report to the FAU (AML Act § 18)?'),
    ]
    case 'PERIODIC_REVIEW': return [
      t('Změnil se profil klienta, vlastnická struktura nebo chování na účtu od poslední revize?', 'Has the customer profile, ownership or account behaviour changed since the last review?'),
      t('Jsou KYC údaje a doklady stále platné?', 'Are KYC data and documents still valid?'),
      t('Odpovídá aktuální riziková kategorie skutečnosti?', 'Does the current risk rating still reflect reality?'),
    ]
    default: return [
      t('Co přesně podnět vyvolalo a je podložen evidencí?', 'What exactly triggered the referral and is it backed by evidence?'),
      t('Jaké další informace jsou potřeba k rozhodnutí?', 'What further information is needed to decide?'),
    ]
  }
}

function statusLabel(s: AmlStatus, t: Tr): string {
  switch (s) {
    case 'OPEN': return t('Otevřený', 'Open')
    case 'UNDER_REVIEW': return t('V šetření', 'Under review')
    case 'CLEARED': return t('Uzavřít bez nálezu', 'Clear')
    case 'BLOCKED': return t('Zablokovat', 'Block')
    case 'ESCALATED': return t('Eskalovat', 'Escalate')
  }
}

function actionCopy(s: AmlStatus, t: Tr): { label: string; hint: string; cls: string } {
  switch (s) {
    case 'UNDER_REVIEW': return { label: t('Převzít do šetření', 'Take into review'), hint: t('Případ se přiřadí vám a přejde do stavu „V šetření".', 'Assigns the case to you and moves it to Under review.'), cls: 'btn-secondary' }
    case 'CLEARED': return { label: t('Uzavřít bez nálezu', 'Clear — no concern'), hint: t('Podezření se nepotvrdilo. Uveďte, co jste ověřili.', 'Suspicion not confirmed. State what you verified.'), cls: 'btn-primary' }
    case 'ESCALATED': return { label: t('Eskalovat na MLRO', 'Escalate to MLRO'), hint: t('Předá případ pověřené osobě (MLRO) k rozhodnutí, typicky o oznámení FAÚ.', 'Hands the case to the MLRO, typically for an FAU report decision.'), cls: 'btn-secondary' }
    case 'BLOCKED': return { label: t('Zablokovat', 'Block'), hint: t('Klient/transakce zůstává blokována. Zdůvodnění je povinné.', 'Customer/transaction stays blocked. A rationale is mandatory.'), cls: 'btn-danger' }
    default: return { label: s, hint: '', cls: 'btn-secondary' }
  }
}

function Row({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return <div style={{ display: 'grid', gridTemplateColumns: 'minmax(120px, 38%) 1fr', gap: 12, padding: '7px 0', borderBottom: '1px dashed var(--border)' }}>
    <span style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>{label}</span>
    <span style={{ fontSize: 13, color: 'var(--text-primary)', fontWeight: 600, fontFamily: mono ? 'var(--font-mono)' : undefined, overflowWrap: 'anywhere' }}>{value ?? '—'}</span>
  </div>
}

function Card({ title, icon, children, actions }: { title: string; icon: React.ReactNode; children: React.ReactNode; actions?: React.ReactNode }) {
  return <section className="card" style={{ padding: 20, minWidth: 0 }} aria-label={title}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
      <span aria-hidden="true" style={{ color: 'var(--accent)' }}>{icon}</span>
      <h2 style={{ margin: 0, fontSize: 15, flex: 1 }}>{title}</h2>
      {actions}
    </div>
    {children}
  </section>
}

export default function AmlCaseDetailPage() {
  const params = useParams<{ id: string }>()
  const caseId = String(params?.id ?? '')
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const { data: session } = useSession()
  const officer = session?.user?.email ?? session?.user?.name ?? ''

  const [amlCase, setAmlCase] = useState<AmlCase | null>(null)
  const [failure, setFailure] = useState<BffFailure | 'invalid' | null>(null)
  const [loading, setLoading] = useState(true)
  const [party, setParty] = useState<AmlPartySummary | null | undefined>(undefined)
  const [txn, setTxn] = useState<TxnSummary | null | undefined>(undefined)
  const [otherCases, setOtherCases] = useState<AmlCase[] | null>(null)

  const [target, setTarget] = useState<AmlStatus | null>(null)
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [decisionError, setDecisionError] = useState<string | null>(null)
  const [decisionOk, setDecisionOk] = useState<string | null>(null)

  // `loading` starts true and the refresh button sets it itself, so this never sets state
  // synchronously inside the effect that calls it.
  const loadCase = useCallback(async () => {
    try {
      const res = await fetch(svcUrl('aml-service', `/api/v1/aml/cases/${encodeURIComponent(caseId)}`), { cache: 'no-store' })
      if (!res.ok) { setFailure(await classifyBffFailure(res)); return }
      setAmlCase(parseAmlCase(await res.json())); setFailure(null)
    } catch { setFailure('invalid') } finally { setLoading(false) }
  }, [caseId])

  useEffect(() => { void loadCase() }, [loadCase])

  const partyId = amlCase?.partyId
  const transactionId = amlCase?.transactionId
  useEffect(() => {
    if (!partyId) return
    const controller = new AbortController()
    void fetchPartySummary(partyId, controller.signal).then(p => { if (!controller.signal.aborted) setParty(p) })
    void (async () => {
      try {
        const res = await fetch(svcUrl('aml-service', '/api/v1/aml/cases', { partyId, limit: '20' }), { cache: 'no-store', signal: controller.signal })
        if (res.ok) setOtherCases(parseAmlCases(await res.json()))
      } catch { /* non-fatal: the history card says it is unavailable */ }
    })()
    return () => controller.abort()
  }, [partyId])

  useEffect(() => {
    if (!transactionId) return
    const controller = new AbortController()
    void (async () => {
      try {
        const res = await fetch(svcUrl('transaction-service', `/api/v1/transactions/${encodeURIComponent(transactionId)}`), { cache: 'no-store', signal: controller.signal })
        setTxn(res.ok ? parseTxn(await res.json()) : null)
      } catch { if (!controller.signal.aborted) setTxn(null) }
    })()
    return () => controller.abort()
  }, [transactionId])

  const transitions = amlCase ? allowedTransitions(amlCase.status) : []
  const priorCases = useMemo(() => (otherCases ?? []).filter(c => c.id !== caseId), [otherCases, caseId])
  const priorConcerns = priorCases.filter(c => c.status === 'BLOCKED' || c.status === 'ESCALATED').length

  async function submitDecision() {
    if (!amlCase || !target) return
    if (!officer) { setDecisionError(t('Nelze určit přihlášeného officera — obnovte relaci.', 'Cannot identify the signed-in officer — refresh your session.')); return }
    if (requiresReason(target) && reason.trim().length < 10) { setDecisionError(t('Zdůvodnění musí mít alespoň 10 znaků — popište, co jste ověřili.', 'The rationale needs at least 10 characters — describe what you verified.')); return }
    setSubmitting(true); setDecisionError(null); setDecisionOk(null)
    try {
      const res = await fetch(svcUrl('aml-service', `/api/v1/aml/cases/${encodeURIComponent(amlCase.id)}/decision`), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          targetStatus: target,
          decisionReason: reason.trim() || null,
          assignedAnalyst: target === 'UNDER_REVIEW' ? officer : (amlCase.assignedAnalyst ?? officer),
          decidedBy: officer,
        }),
      })
      if (res.status === 403) { setDecisionError(t('K rozhodnutí o AML případu nemáte oprávnění (potřeba ROLE_COMPLIANCE nebo ROLE_OPERATOR).', 'You are not permitted to decide AML cases (requires ROLE_COMPLIANCE or ROLE_OPERATOR).')); return }
      if (res.status === 400) { setDecisionError(t('Služba rozhodnutí odmítla — případ mezitím mohl změnit stav. Načtěte jej znovu.', 'The service refused the decision — the case may have changed meanwhile. Reload it.')); return }
      if (!res.ok) { setDecisionError(t('Rozhodnutí se nepodařilo uložit. Zkuste to znovu.', 'The decision could not be saved. Try again.')); return }
      setAmlCase(parseAmlCase(await res.json()))
      setDecisionOk(t('Rozhodnutí uloženo a zapsáno do auditní stopy.', 'Decision saved and written to the audit trail.'))
      setTarget(null); setReason('')
    } catch {
      setDecisionError(t('Rozhodnutí se nepodařilo uložit. Zkuste to znovu.', 'The decision could not be saved. Try again.'))
    } finally { setSubmitting(false) }
  }

  const subject = party?.name ?? amlCase?.matchedEntity ?? amlCase?.customerReference ?? caseId
  const ageH = amlCase ? caseAgeHours(amlCase.createdAt) : 0
  const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString(locale) : '—')

  return (
    <AuthGuard permission="compliance:view">
      <div style={{ padding: '28px 32px', maxWidth: 1400, animation: 'fadeIn 0.2s ease-out' }}>
        <Link href="/aml" className="btn btn-secondary btn-sm" style={{ marginBottom: 16, display: 'inline-flex', gap: 6 }}>
          <ArrowLeft size={14} aria-hidden="true" /> {t('Zpět na AML případy', 'Back to AML cases')}
        </Link>

        {loading && !amlCase ? (
          <p role="status" style={{ color: 'var(--text-tertiary)' }}><RefreshCw size={14} className="animate-spin" aria-hidden="true" /> {t('Načítám případ…', 'Loading case…')}</p>
        ) : !amlCase ? (
          <DataUnavailable kind={failure === 'invalid' || failure === null ? 'error' : failure} service="aml-service" feature={t('AML případ', 'AML case')} lang={language} />
        ) : <>
          <PageHeader
            title={subject}
            subtitle={`${amlCase.screeningType.replaceAll('_', ' ')} · ${t('otevřen před', 'open for')} ${ageH < 48 ? `${ageH} h` : `${Math.floor(ageH / 24)} ${t('dní', 'days')}`}`}
            icon={<ShieldAlert size={20} aria-hidden="true" />}
            actions={<div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <StatusBadge status={amlCase.riskLevel} />
              <StatusBadge status={amlCase.status} label={amlCase.status.replace('_', ' ')} />
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setLoading(true); void loadCase() }} disabled={loading} aria-label={t('Obnovit případ', 'Refresh case')}>
                <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} />
              </button>
            </div>}
          />

          {priorConcerns > 0 && <div role="alert" style={{ margin: '0 0 16px', padding: '10px 14px', borderRadius: 8, background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', color: 'var(--danger-text)', fontSize: 13, fontWeight: 600 }}>
            {t(`Klient má ${priorConcerns} dřívější blokovaný nebo eskalovaný AML případ.`, `This customer has ${priorConcerns} earlier blocked or escalated AML case(s).`)}
          </div>}

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 16, marginBottom: 16 }}>
            <Card title={t('Klient', 'Customer')} icon={<UserRound size={16} />} actions={<Link href={`/parties/${amlCase.partyId}`} className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', gap: 4 }}>{t('Profil klienta', 'Customer profile')} <ExternalLink size={12} aria-hidden="true" /></Link>}>
              <Row label={t('Jméno', 'Name')} value={party === undefined ? t('Načítám…', 'Loading…') : party?.name ?? <span style={{ color: 'var(--text-tertiary)', fontWeight: 400 }}>{t('Jméno není pro vaši roli dostupné', 'Name not available for your role')}</span>} />
              {party?.masked && <p style={{ margin: '4px 0', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Údaje jsou maskovány; plné zobrazení je v profilu klienta s auditovaným důvodem.', 'Details are masked; full view is in the customer profile with an audited reason.')}</p>}
              <Row label={t('Typ', 'Type')} value={party?.partyType} />
              <Row label={t('Stav KYC', 'KYC status')} value={party?.kycStatus ? <StatusBadge status={party.kycStatus} /> : null} />
              <Row label={t('Stav vztahu', 'Relationship')} value={party?.status} />
              <Row label={t('ID klienta', 'Party ID')} value={<ShortId value={amlCase.partyId} copyLabel={t('Kopírovat ID klienta', 'Copy party ID')} />} />
              {amlCase.accountId && <Row label={t('Účet', 'Account')} value={<EntityChip type="account" id={amlCase.accountId} />} />}
            </Card>

            <Card title={t('Co se má posoudit', 'What to assess')} icon={<ClipboardCheck size={16} />}>
              <Row label={t('Alert', 'Alert')} value={amlCase.alertCode} mono />
              {amlCase.alertDetail && <Row label={t('Detail alertu', 'Alert detail')} value={amlCase.alertDetail} />}
              <Row label={t('Shoda se subjektem', 'Matched entity')} value={amlCase.matchedEntity ?? t('žádná konkrétní shoda', 'no specific match')} />
              <Row label={t('Screening proběhl', 'Screened at')} value={fmt(amlCase.screenedAt)} />
              <Row label={t('Reference', 'Reference')} value={amlCase.customerReference} mono />
              <Row label={t('ID případu', 'Case ID')} value={<ShortId value={amlCase.id} copyLabel={t('Kopírovat ID případu', 'Copy case ID')} />} />
              <ul style={{ margin: '12px 0 0', paddingLeft: 18, fontSize: 13, color: 'var(--text-secondary)', display: 'grid', gap: 6 }}>
                {assessmentGuide(amlCase, t).map(q => <li key={q}>{q}</li>)}
              </ul>
            </Card>
          </div>

          {amlCase.transactionId && <div style={{ marginBottom: 16 }}>
            <Card title={t('Sledovaná transakce', 'Monitored transaction')} icon={<ArrowRightLeft size={16} />}>
              {txn === undefined ? <p role="status" style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>{t('Načítám transakci…', 'Loading transaction…')}</p>
                : txn === null ? <p style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>{t('Transakci nelze načíst z transaction-service.', 'The transaction could not be loaded from transaction-service.')} <ShortId value={amlCase.transactionId} copyLabel={t('Kopírovat ID transakce', 'Copy transaction ID')} /></p>
                : <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', columnGap: 24 }}>
                  <div>
                    <Row label={t('Částka', 'Amount')} value={txn.amount ? `${Number(txn.amount).toLocaleString(locale, { minimumFractionDigits: 2 })} ${txn.currencyCode ?? ''}` : null} />
                    <Row label={t('Typ / kanál', 'Type / rail')} value={[txn.type, txn.rail].filter(Boolean).join(' · ') || null} />
                    <Row label={t('Stav', 'Status')} value={txn.status ? <StatusBadge status={txn.status} /> : null} />
                    <Row label={t('Popis', 'Description')} value={txn.description} />
                  </div>
                  <div>
                    <Row label={t('Z účtu', 'From account')} value={txn.sourceAccountId ? <EntityChip type="account" id={txn.sourceAccountId} /> : null} />
                    <Row label={t('Na účet', 'To account')} value={txn.targetAccountId ? <EntityChip type="account" id={txn.targetAccountId} /> : null} />
                    <Row label={t('Zadáno', 'Initiated')} value={fmt(txn.initiatedAt)} />
                    <Row label={t('Reference', 'Reference')} value={txn.referenceNumber} mono />
                  </div>
                </div>}
            </Card>
          </div>}

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 16, marginBottom: 16 }}>
            <Card title={t('Rozhodnutí', 'Decision')} icon={<Gavel size={16} />}>
              {amlCase.decidedBy && <div style={{ marginBottom: 12 }}>
                <Row label={t('Poslední rozhodnutí', 'Last decision')} value={`${amlCase.decidedBy} · ${fmt(amlCase.decidedAt)}`} />
                {amlCase.decisionReason && <Row label={t('Zdůvodnění', 'Rationale')} value={amlCase.decisionReason} />}
              </div>}
              <Row label={t('Řeší', 'Assigned to')} value={amlCase.assignedAnalyst ?? t('Nepřiřazeno', 'Unassigned')} />
              {!isOpenAmlStatus(amlCase.status)
                ? <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 12 }}>{t('Případ je uzavřen. Další rozhodnutí již nelze zaznamenat.', 'The case is closed. No further decision can be recorded.')}</p>
                : <div style={{ marginTop: 14 }}>
                  <div role="group" aria-label={t('Volba rozhodnutí', 'Decision choice')} style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    {transitions.map(s => {
                      const a = actionCopy(s, t)
                      return <button key={s} type="button" className={`btn btn-sm ${a.cls}`} aria-pressed={target === s} onClick={() => { setTarget(s); setDecisionError(null); setDecisionOk(null) }} style={target === s ? { outline: '2px solid var(--accent)', outlineOffset: 2 } : undefined}>{a.label}</button>
                    })}
                  </div>
                  {target && <div style={{ marginTop: 12 }}>
                    <p style={{ fontSize: 12, color: 'var(--text-secondary)', margin: '0 0 8px' }}>{actionCopy(target, t).hint}</p>
                    <label htmlFor="aml-reason" style={{ fontSize: 12, fontWeight: 600 }}>
                      {t('Zdůvodnění', 'Rationale')}{requiresReason(target) ? ' *' : ` (${t('nepovinné', 'optional')})`}
                    </label>
                    <textarea id="aml-reason" className="input" rows={4} value={reason} onChange={e => setReason(e.target.value)} maxLength={2000}
                      placeholder={t('Co jste ověřili, z jakých zdrojů a proč rozhodujete takto…', 'What you verified, from which sources, and why you decide this way…')}
                      style={{ width: '100%', marginTop: 4, resize: 'vertical' }} />
                    <div style={{ display: 'flex', gap: 8, marginTop: 8, alignItems: 'center' }}>
                      <button type="button" className={`btn btn-sm ${actionCopy(target, t).cls}`} disabled={submitting} aria-busy={submitting} onClick={() => void submitDecision()}>
                        {submitting ? t('Ukládám…', 'Saving…') : t('Potvrdit', 'Confirm')}: {statusLabel(target, t)}
                      </button>
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setTarget(null); setReason('') }}>{t('Zrušit', 'Cancel')}</button>
                      <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Zapíše se jako', 'Recorded as')}: {officer || '—'}</span>
                    </div>
                  </div>}
                </div>}
              {decisionError && <p role="alert" style={{ color: 'var(--danger-text)', fontSize: 13, marginTop: 10 }}>{decisionError}</p>}
              {decisionOk && <p role="status" style={{ color: 'var(--success-text, var(--success))', fontSize: 13, marginTop: 10 }}>{decisionOk}</p>}
            </Card>

            <Card title={t('Historie AML u klienta', 'Customer AML history')} icon={<History size={16} />}>
              {otherCases === null ? <p style={{ fontSize: 13, color: 'var(--text-tertiary)' }}>{t('Historie není k dispozici.', 'History is not available.')}</p>
                : priorCases.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Žádné další AML případy u tohoto klienta.', 'No other AML cases for this customer.')}</p>
                : <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {priorCases.map(c => <li key={c.id} style={{ borderBottom: '1px dashed var(--border)' }}>
                    <Link href={`/aml/${c.id}`} style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '8px 0', fontSize: 12, color: 'var(--text-primary)', textDecoration: 'none' }}>
                      <span style={{ flex: 1 }}>{c.screeningType.replaceAll('_', ' ')} · <span style={{ fontFamily: 'var(--font-mono)' }}>{c.alertCode}</span></span>
                      <StatusBadge status={c.riskLevel} />
                      <StatusBadge status={c.status} label={c.status.replace('_', ' ')} />
                      <span style={{ color: 'var(--text-tertiary)' }}>{new Date(c.createdAt).toLocaleDateString(locale)}</span>
                    </Link>
                  </li>)}
                </ul>}
              <div style={{ marginTop: 10, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <Link href="/sanctions" className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', gap: 4 }}><Landmark size={12} aria-hidden="true" /> {t('Sankční screening', 'Sanctions screening')}</Link>
                <Link href="/kyc" className="btn btn-secondary btn-sm">{t('KYC případ', 'KYC case')}</Link>
              </div>
            </Card>
          </div>

          <AmlCaseInvestigation key={amlCase.id} initialCaseId={amlCase.id} fixedCase />
        </>}
      </div>
    </AuthGuard>
  )
}
