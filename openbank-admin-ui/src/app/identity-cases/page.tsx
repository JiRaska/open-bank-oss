// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { Fingerprint, RefreshCw, ShieldAlert, Users, Check, Search } from 'lucide-react'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'

const SVC = 'pid-service'

// ── Types (mirror pid openapi VerificationCaseResponse) ─────────────────────────

type Verdict = 'LINK_TO_EXISTING' | 'DISTINCT_NEW' | 'REJECT'
type Trigger = 'RN_COLLISION' | 'NAMESAKE_CANDIDATE' | 'PROBABILISTIC_CANDIDATE'
type Status = 'OPEN' | 'AWAITING_SECOND_APPROVAL' | 'DECIDED'

interface VerificationCase {
  id: string
  trigger: Trigger
  status: Status
  applicant: {
    givenName: string
    familyName: string
    birthdate: string
    birthplace: string | null
    nationalities: string[]
  }
  candidatePartyIds: string[]
  firstApprover: string | null
  firstVerdict: Verdict | null
  firstLinkPartyId: string | null
  firstNotes: string | null
  firstAt: string | null
  secondApprover: string | null
  finalVerdict: Verdict | null
  decidedAt: string | null
  createdAt: string
}

// ── Display helpers ─────────────────────────────────────────────────────────────

const TRIGGER_COLOR: Record<Trigger, string> = {
  RN_COLLISION: '#dc2626',
  NAMESAKE_CANDIDATE: '#d97706',
  PROBABILISTIC_CANDIDATE: '#7c3aed',
}
const VERDICT_COLOR: Record<Verdict, string> = {
  LINK_TO_EXISTING: '#16a34a',
  DISTINCT_NEW: '#2563eb',
  REJECT: '#dc2626',
}

function shortId(id: string): string {
  return id.slice(0, 8)
}

// ── Per-case decision form ──────────────────────────────────────────────────────

function DecisionForm({
  c,
  onDecided,
}: {
  c: VerificationCase
  onDecided: () => void
}) {
  const { t } = useLanguage()
  const [verdict, setVerdict] = useState<Verdict>(c.firstVerdict ?? 'DISTINCT_NEW')
  const [linkPartyId, setLinkPartyId] = useState<string>(c.firstLinkPartyId ?? c.candidatePartyIds[0] ?? '')
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [decisionIntent, setDecisionIntent] = useState<'vote' | 'reopen' | null>(null)
  const decisionTriggerRef = useRef<HTMLButtonElement | null>(null)

  const isSecond = c.status === 'AWAITING_SECOND_APPROVAL'

  // NOTE ON SCOPE (#7096): this lock stops ONE approver double-activating. It does
  // NOT and cannot serialize two DIFFERENT approvers racing the same case — nothing
  // in this browser observes the other operator. That race is arbitrated server-side
  // and answers 409 (handled below); claiming otherwise would be a control that
  // reports healthy while the race is still there.
  const flight = useSingleFlight()

  const submit = useCallback(async () => {
    let succeeded = false
    const outcome = await flight.run(`identity-case:${c.id}`, async () => {
    setBusy(true)
    setError(null)
    try {
      const body: Record<string, unknown> = { verdict, notes: notes || null }
      if (verdict === 'LINK_TO_EXISTING') body.linkPartyId = linkPartyId
      const res = await fetch(svcUrl(SVC, `/api/v1/parties/cases/${c.id}/decision`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(8000),
      })
      if (!res.ok) {
        const msg = await res.json().catch(() => null)
        setError(
          res.status === 409
            ? t(
                'Konflikt: nemůžete hlasovat dvakrát, nebo druhý schvalovatel nesouhlasí (znovu otevřete).',
                'Conflict: you cannot vote twice, or the second approver disagrees (reopen to re-adjudicate).',
              )
            : (msg?.message ?? t('Hlasování se nezdařilo.', 'Vote failed.')),
        )
        return
      }
      succeeded = true
      onDecided()
    } catch {
      setError(t('Služba je nedostupná.', 'Service unavailable.'))
    } finally {
      setBusy(false)
    }
    })
    if (wasSkipped(outcome)) return false
    return succeeded
  }, [flight, verdict, linkPartyId, notes, c.id, onDecided, t])

  const reopen = useCallback(async () => {
    let succeeded = false
    const outcome = await flight.run(`identity-case:${c.id}`, async () => {
    setBusy(true)
    setError(null)
    try {
      const res = await fetch(svcUrl(SVC, `/api/v1/parties/cases/${c.id}/reopen`), {
        method: 'POST',
        signal: AbortSignal.timeout(8000),
      })
      if (!res.ok) {
        setError(t('Znovuotevření se nezdařilo.', 'Reopen failed.'))
        return
      }
      succeeded = true
      onDecided()
    } catch {
      setError(t('Služba je nedostupná.', 'Service unavailable.'))
    } finally {
      setBusy(false)
    }
    })
    if (wasSkipped(outcome)) return false
    return succeeded
  }, [flight, c.id, onDecided, t])

  const openDecisionReview = (intent: 'vote' | 'reopen', trigger: HTMLButtonElement) => {
    decisionTriggerRef.current = trigger
    setError(null)
    setDecisionIntent(intent)
  }

  const closeDecisionReview = () => {
    if (busy) return
    setDecisionIntent(null)
    requestAnimationFrame(() => decisionTriggerRef.current?.focus())
  }

  return (
    <div style={{ marginTop: '12px', paddingTop: '12px', borderTop: '1px solid var(--border)' }}>
      {isSecond && (
        <div
          style={{
            fontSize: '12px',
            color: 'var(--text-secondary)',
            marginBottom: '10px',
            padding: '8px 10px',
            background: 'var(--surface-2)',
            borderRadius: '8px',
          }}
        >
          {t('První hlas:', 'First vote:')} <strong>{c.firstApprover}</strong> →{' '}
          <span style={{ color: VERDICT_COLOR[c.firstVerdict ?? 'DISTINCT_NEW'], fontWeight: 600 }}>
            {c.firstVerdict}
          </span>
          {'. '}
          {t(
            'Pro rozhodnutí musí potvrdit JINÝ schvalovatel stejný verdikt (čtyři oči).',
            'To decide, a DIFFERENT approver must confirm the same verdict (four-eyes).',
          )}
        </div>
      )}

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', alignItems: 'center' }}>
        <select
          aria-label={t('Verdikt případu identity', 'Identity case verdict')}
          className="input"
          value={verdict}
          onChange={e => setVerdict(e.target.value as Verdict)}
          disabled={busy}
          style={{ fontSize: '13px', padding: '6px 8px' }}
        >
          <option value="LINK_TO_EXISTING">{t('Je to existující osoba (LINK)', 'Same existing person (LINK)')}</option>
          <option value="DISTINCT_NEW">{t('Jiná osoba (DISTINCT)', 'Distinct person (DISTINCT)')}</option>
          <option value="REJECT">{t('Zamítnout (REJECT)', 'Reject (REJECT)')}</option>
        </select>

        {verdict === 'LINK_TO_EXISTING' && (
          <select
            aria-label={t('Propojit s existující party', 'Link to existing party')}
            className="input"
            value={linkPartyId}
            onChange={e => setLinkPartyId(e.target.value)}
            disabled={busy}
            style={{ fontSize: '13px', padding: '6px 8px' }}
          >
            {c.candidatePartyIds.map(pid => (
              <option key={pid} value={pid}>
                {t('strana', 'party')} {shortId(pid)}
              </option>
            ))}
          </select>
        )}

        <input
          aria-label={t('Poznámka k rozhodnutí', 'Decision notes')}
          className="input"
          placeholder={t('Poznámka (volitelné)', 'Notes (optional)')}
          value={notes}
          onChange={e => setNotes(e.target.value)}
          disabled={busy}
          style={{ fontSize: '13px', padding: '6px 8px', flex: '1 1 160px', minWidth: '140px' }}
        />

        <button type="button" className="btn btn-primary" onClick={event => openDecisionReview('vote', event.currentTarget)} disabled={busy} style={{ fontSize: '13px' }}>
          <Check size={14} aria-hidden="true" style={{ marginRight: '4px' }} />
          {isSecond ? t('Potvrdit a rozhodnout', 'Confirm & decide') : t('Odeslat hlas', 'Submit vote')}
        </button>

        {isSecond && (
          <button type="button" className="btn btn-secondary" onClick={event => openDecisionReview('reopen', event.currentTarget)} disabled={busy} style={{ fontSize: '13px' }}>
            {t('Znovu otevřít', 'Reopen')}
          </button>
        )}
      </div>

      {error && !decisionIntent && <div role="alert" style={{ fontSize: '12px', color: 'var(--danger)', marginTop: '8px' }}>{error}</div>}
      {decisionIntent && <IdentityDecisionReviewDialog
        c={c}
        intent={decisionIntent}
        verdict={verdict}
        linkPartyId={linkPartyId}
        notes={notes}
        busy={busy}
        error={error}
        onCancel={closeDecisionReview}
        onConfirm={async () => {
          const succeeded = decisionIntent === 'vote' ? await submit() : await reopen()
          if (succeeded) setDecisionIntent(null)
        }}
      />}
    </div>
  )
}

function IdentityDecisionReviewDialog({ c, intent, verdict, linkPartyId, notes, busy, error, onCancel, onConfirm }: {
  c: VerificationCase
  intent: 'vote' | 'reopen'
  verdict: Verdict
  linkPartyId: string
  notes: string
  busy: boolean
  error: string | null
  onCancel: () => void
  onConfirm: () => Promise<void>
}) {
  const { t } = useLanguage()
  const dialogRef = useRef<HTMLDivElement>(null)
  const titleId = `identity-${c.id}-decision-title`
  const impactId = `identity-${c.id}-decision-impact`
  const isReopen = intent === 'reopen'

  return <div
    ref={dialogRef}
    role="alertdialog"
    aria-modal="true"
    aria-labelledby={titleId}
    aria-describedby={impactId}
    aria-busy={busy}
    onKeyDown={event => {
      if (event.key === 'Escape' && !busy) onCancel()
      trapDialogFocus(event, dialogRef.current)
    }}
    style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.72)', display: 'grid', placeItems: 'center', padding: 20 }}
  >
    <div className="card" style={{ width: 'min(620px, 100%)', maxHeight: 'calc(100dvh - 40px)', overflowY: 'auto', padding: 22 }}>
      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
        <Fingerprint size={20} aria-hidden="true" style={{ color: 'var(--accent)', flexShrink: 0, marginTop: 2 }} />
        <div>
          <h2 id={titleId} style={{ margin: 0, fontSize: 17, fontWeight: 750 }}>{isReopen ? t('Znovu otevřít případ identity', 'Reopen identity case') : isSecondVote(c) ? t('Potvrdit druhý hlas', 'Confirm second vote') : t('Odeslat první hlas', 'Submit first vote')}</h2>
          <p id={impactId} style={{ margin: '6px 0 0', fontSize: 12.5, lineHeight: 1.5, color: 'var(--text-secondary)' }}>
            {isReopen
              ? t('Neshodný případ vrátíte do otevřeného posouzení. Předchozí hlasy zůstanou v auditní stopě; nový verdikt bude znovu vyžadovat čtyři oči.', 'The disputed case returns to open adjudication. Prior votes remain in the audit trail; a new verdict again requires four-eyes.')
              : isSecondVote(c)
                ? t('Stejný verdikt od jiného schvalovatele případ dokončí. Odlišný verdikt služba odmítne a případ lze znovu otevřít.', 'The same verdict from a different approver completes the case. The service rejects a disagreement and the case can be reopened.')
                : t('Tímto uložíte první hlas. Konečné rozhodnutí vznikne až po stejném hlasu jiného oprávněného schvalovatele.', 'This records the first vote. A final decision exists only after the same vote from another authorized approver.')}
          </p>
        </div>
      </div>
      <dl style={{ margin: '14px 0 0', padding: 12, borderRadius: 9, border: '1px solid var(--border)', background: 'var(--surface-2)', display: 'grid', gap: 8, fontSize: 12.5 }}>
        <div><dt style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Žadatel', 'Applicant')}</dt><dd style={{ margin: '2px 0 0', fontWeight: 650 }}>{c.applicant.givenName} {c.applicant.familyName} · {c.applicant.birthdate}</dd></div>
        <div><dt style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Případ a spouštěč', 'Case and trigger')}</dt><dd className="mono" style={{ margin: '2px 0 0', wordBreak: 'break-all' }}>{c.id} · {c.trigger}</dd></div>
        {!isReopen && <div><dt style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Verdikt', 'Verdict')}</dt><dd style={{ margin: '2px 0 0', fontWeight: 700, color: VERDICT_COLOR[verdict] }}>{verdict}{verdict === 'LINK_TO_EXISTING' ? ` → ${linkPartyId}` : ''}</dd></div>}
        {!isReopen && <div><dt style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Poznámka', 'Notes')}</dt><dd style={{ margin: '2px 0 0' }}>{notes || t('bez poznámky', 'no notes')}</dd></div>}
        {c.firstApprover && <div><dt style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{t('První hlas', 'First vote')}</dt><dd style={{ margin: '2px 0 0' }}>{c.firstApprover} → <strong>{c.firstVerdict}</strong>{c.firstLinkPartyId ? ` → ${c.firstLinkPartyId}` : ''}{c.firstNotes ? ` · ${c.firstNotes}` : ''}</dd></div>}
      </dl>
      {error && <p role="alert" style={{ margin: '12px 0 0', padding: '10px 12px', borderRadius: 8, color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: 12 }}>{error}</p>}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
        <button type="button" autoFocus className="btn btn-secondary" disabled={busy} onClick={onCancel}>{t('Zpět ke kontrole', 'Back to review')}</button>
        <button type="button" className="btn btn-primary" disabled={busy} aria-busy={busy} onClick={() => void onConfirm()}>{busy ? t('Ukládám…', 'Recording…') : isReopen ? t('Potvrdit znovuotevření', 'Confirm reopen') : t('Potvrdit hlas', 'Confirm vote')}</button>
      </div>
    </div>
  </div>
}

function isSecondVote(c: VerificationCase): boolean {
  return c.status === 'AWAITING_SECOND_APPROVAL'
}

// ── Page ────────────────────────────────────────────────────────────────────────

export default function IdentityCasesPage() {
  const { t, language } = useLanguage()
  const [cases, setCases] = useState<VerificationCase[]>([])
  const [unavail, setUnavail] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'OPEN' | 'AWAITING_SECOND_APPROVAL'>('ALL')
  const [triggerFilter, setTriggerFilter] = useState<'ALL' | Trigger>('ALL')
  const loadGeneration = useRef(0)

  const load = useCallback(async () => {
    const generation = ++loadGeneration.current
    setLoading(true)
    setUnavail(null)
    try {
      const res = await fetch(svcUrl(SVC, '/api/v1/parties/cases'), { signal: AbortSignal.timeout(6000) })
      if (generation !== loadGeneration.current) return
      if (!res.ok) {
        const failure = await classifyBffFailure(res)
        if (generation !== loadGeneration.current) return
        setUnavail(failure)
        setCases([])
        return
      }
      const nextCases = await res.json()
      if (generation !== loadGeneration.current) return
      setCases(nextCases)
    } catch {
      if (generation !== loadGeneration.current) return
      setUnavail('unreachable')
      setCases([])
    } finally {
      if (generation === loadGeneration.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
    return () => { loadGeneration.current += 1 }
  }, [load])

  const awaitingCount = cases.filter(c => c.status === 'AWAITING_SECOND_APPROVAL').length
  const visibleCases = useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase(language === 'cs' ? 'cs-CZ' : 'en-US')
    return cases
      .filter(c => statusFilter === 'ALL' || c.status === statusFilter)
      .filter(c => triggerFilter === 'ALL' || c.trigger === triggerFilter)
      .filter(c => {
        if (!normalizedQuery) return true
        return [c.applicant.givenName, c.applicant.familyName, c.id, ...c.candidatePartyIds]
          .some(value => value.toLocaleLowerCase(language === 'cs' ? 'cs-CZ' : 'en-US').includes(normalizedQuery))
      })
      .sort((a, b) => {
        const priority = Number(b.status === 'AWAITING_SECOND_APPROVAL') - Number(a.status === 'AWAITING_SECOND_APPROVAL')
        return priority || b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id)
      })
  }, [cases, language, query, statusFilter, triggerFilter])

  const triggerLabel = (trigger: Trigger) => ({
    RN_COLLISION: t('Kolize rodného čísla', 'National-ID collision'),
    NAMESAKE_CANDIDATE: t('Možný jmenovec', 'Possible namesake'),
    PROBABILISTIC_CANDIDATE: t('Pravděpodobná shoda', 'Probable match'),
  })[trigger]
  const activeCaseLabel = (count: number) => language === 'cs'
    ? `${count} ${count === 1 ? 'aktivní případ' : count >= 2 && count <= 4 ? 'aktivní případy' : 'aktivních případů'}`
    : `${count} active ${count === 1 ? 'case' : 'cases'}`
  const awaitingLabel = (count: number) => language === 'cs'
    ? `${count} ${count === 1 ? 'čeká' : 'čekají'} na nezávislý druhý hlas`
    : `${count} ${count === 1 ? 'awaits' : 'await'} an independent second vote`
  const candidateLabel = (count: number) => language === 'cs'
    ? `${count} ${count === 1 ? 'kandidát' : count >= 2 && count <= 4 ? 'kandidáti' : 'kandidátů'}`
    : `${count} ${count === 1 ? 'candidate' : 'candidates'}`

  return <AuthGuard permission="identity-cases:view">
    <div>
      <PageHeader
        icon={<Fingerprint size={20} aria-hidden="true" />}
        title={t('Ověření identity — čtyři oči', 'Identity Verification — Four-Eyes')}
        subtitle={t('Nejednoznačné identity z onboardingu (kolize RČ nebo jmenovci). Rozhodnutí vyžaduje dva různé schvalovatele (ADR-0072 / ADR-0030).', 'Ambiguous onboarding identities (RČ collisions or namesakes). A decision requires two distinct approvers (ADR-0072 / ADR-0030).')}
        actions={<button type="button" aria-busy={loading} aria-label={t('Obnovit případy identity', 'Refresh identity cases')} className="btn btn-secondary" onClick={load} disabled={loading} style={{ fontSize: '13px' }}>
          <RefreshCw size={14} aria-hidden="true" style={{ marginRight: '4px' }} />{t('Obnovit', 'Refresh')}
        </button>}
      />
      {loading && cases.length === 0 ? (
        <div className="card" role="status" aria-live="polite" style={{ padding: '28px', display: 'flex', gap: 12, alignItems: 'center' }}>
          <RefreshCw size={18} aria-hidden="true" className="animate-spin" />
          <div>
            <div style={{ fontWeight: 650 }}>{t('Načítám frontu případů…', 'Loading the case queue…')}</div>
            <div style={{ marginTop: 3, fontSize: 12, color: 'var(--text-secondary)' }}>{t('Ověřuji otevřené případy a pořadí druhých hlasů.', 'Checking active cases and second-vote priority.')}</div>
          </div>
        </div>
      ) : unavail ? (
        <DataUnavailable kind={unavail} service="pid-service" feature={t('ověření identity', 'identity verification')} lang={language} />
      ) : cases.length === 0 && !loading ? (
        <div className="card" style={{ padding: '40px', textAlign: 'center' }}>
          <ShieldAlert size={28} style={{ color: 'var(--success)', marginBottom: '10px' }} />
          <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>
            {t('Žádné otevřené případy', 'No open cases')}
          </div>
          <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginTop: '4px' }}>
            {t('Resolver nemá žádnou nejednoznačnost k posouzení.', 'The resolver has no ambiguity awaiting review.')}
          </div>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          <section className="card" aria-label={t('Třídění fronty případů', 'Case queue triage')} style={{ padding: 14 }}>
            <div aria-live="polite" style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 12, fontSize: 12 }}>
              <strong>{activeCaseLabel(cases.length)}</strong>
              <span style={{ color: 'var(--text-secondary)' }}>· {awaitingLabel(awaitingCount)}</span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 10 }}>
              <label style={{ display: 'grid', gap: 5, fontSize: 12, color: 'var(--text-secondary)' }}>
                {t('Hledat osobu nebo případ', 'Find a person or case')}
                <span style={{ position: 'relative' }}>
                  <Search size={14} aria-hidden="true" style={{ position: 'absolute', left: 10, top: 10, color: 'var(--text-tertiary)' }} />
                  <input className="input" value={query} onChange={event => setQuery(event.target.value)} placeholder={t('Jméno, ID případu nebo party', 'Name, case ID, or party ID')} autoComplete="off" spellCheck={false} style={{ width: '100%', paddingLeft: 30 }} />
                </span>
              </label>
              <label style={{ display: 'grid', gap: 5, fontSize: 12, color: 'var(--text-secondary)' }}>
                {t('Fáze kontroly', 'Review stage')}
                <select className="input" value={statusFilter} onChange={event => setStatusFilter(event.target.value as typeof statusFilter)}>
                  <option value="ALL">{t('Všechny aktivní', 'All active')}</option>
                  <option value="AWAITING_SECOND_APPROVAL">{t('Čeká na druhý hlas', 'Awaiting second vote')}</option>
                  <option value="OPEN">{t('Čeká na první hlas', 'Awaiting first vote')}</option>
                </select>
              </label>
              <label style={{ display: 'grid', gap: 5, fontSize: 12, color: 'var(--text-secondary)' }}>
                {t('Důvod kontroly', 'Review reason')}
                <select className="input" value={triggerFilter} onChange={event => setTriggerFilter(event.target.value as typeof triggerFilter)}>
                  <option value="ALL">{t('Všechny důvody', 'All reasons')}</option>
                  <option value="RN_COLLISION">{triggerLabel('RN_COLLISION')}</option>
                  <option value="NAMESAKE_CANDIDATE">{triggerLabel('NAMESAKE_CANDIDATE')}</option>
                  <option value="PROBABILISTIC_CANDIDATE">{triggerLabel('PROBABILISTIC_CANDIDATE')}</option>
                </select>
              </label>
            </div>
          </section>
          {visibleCases.length === 0 && (
            <div className="card" role="status" style={{ padding: 28, textAlign: 'center' }}>
              <strong>{t('Filtrům neodpovídá žádný případ', 'No cases match these filters')}</strong>
              <div style={{ marginTop: 5, fontSize: 12, color: 'var(--text-secondary)' }}>{t('Změňte hledaný text nebo některý filtr. Aktivní fronta zůstává beze změny.', 'Change the search text or a filter. The active queue is unchanged.')}</div>
            </div>
          )}
          {visibleCases.map(c => (
            <div key={c.id} className="card" style={{ padding: '18px' }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '12px' }}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                    <span
                      style={{
                        fontSize: '11px',
                        fontWeight: 700,
                        padding: '2px 8px',
                        borderRadius: '20px',
                        background: `${TRIGGER_COLOR[c.trigger]}15`,
                        color: TRIGGER_COLOR[c.trigger],
                        border: `1px solid ${TRIGGER_COLOR[c.trigger]}30`,
                      }}
                    >
                      {triggerLabel(c.trigger)}
                    </span>
                    <span className="mono" style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                      {t('případ', 'case')} {shortId(c.id)}
                    </span>
                    {c.status === 'AWAITING_SECOND_APPROVAL' && (
                      <span style={{ fontSize: '11px', fontWeight: 600, color: '#d97706' }}>
                        {t('čeká na 2. schválení', 'awaiting 2nd approval')}
                      </span>
                    )}
                  </div>
                  <div style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)' }}>
                    {c.applicant.givenName} {c.applicant.familyName}
                  </div>
                  <div style={{ fontSize: '12.5px', color: 'var(--text-secondary)', marginTop: '2px' }}>
                    {t('nar.', 'born')} {c.applicant.birthdate}
                    {c.applicant.birthplace ? ` · ${c.applicant.birthplace}` : ''}
                    {c.applicant.nationalities.length ? ` · ${c.applicant.nationalities.join(', ')}` : ''}
                  </div>
                </div>
                <div
                  style={{
                    fontSize: '12px',
                    color: 'var(--text-secondary)',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '5px',
                    whiteSpace: 'nowrap',
                  }}
                >
                  <Users size={14} />
                  {candidateLabel(c.candidatePartyIds.length)}
                </div>
              </div>

              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '8px' }}>
                {t('Kandidáti:', 'Candidates:')}{' '}
                {c.candidatePartyIds.map(pid => (
                  <span key={pid} className="mono" style={{ marginRight: '8px' }}>
                    {shortId(pid)}
                  </span>
                ))}
              </div>

              <Can permission="identity-cases:decide" fallback={<div style={{ marginTop: '12px', color: 'var(--text-secondary)', fontSize: '12px' }}>{t('Rozhodnutí může provést pouze oprávněný schvalovatel.', 'Only an authorized approver can decide this case.')}</div>}>
                <DecisionForm c={c} onDecided={load} />
              </Can>
            </div>
          ))}
        </div>
      )}
    </div>
  </AuthGuard>
}
