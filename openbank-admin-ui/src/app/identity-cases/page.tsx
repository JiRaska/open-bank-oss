// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { Fingerprint, RefreshCw, ShieldAlert, Users, Check } from 'lucide-react'
import { claimSingleFlight, releaseSingleFlight } from '@/lib/single-flight'

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
  const mutationInFlight = useRef(false)

  const isSecond = c.status === 'AWAITING_SECOND_APPROVAL'

  const submit = useCallback(async () => {
    if (!claimSingleFlight(mutationInFlight)) return
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
      onDecided()
    } catch {
      setError(t('Služba je nedostupná.', 'Service unavailable.'))
    } finally {
      releaseSingleFlight(mutationInFlight)
      setBusy(false)
    }
  }, [verdict, linkPartyId, notes, c.id, onDecided, t])

  const reopen = useCallback(async () => {
    if (!claimSingleFlight(mutationInFlight)) return
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
      onDecided()
    } catch {
      setError(t('Služba je nedostupná.', 'Service unavailable.'))
    } finally {
      releaseSingleFlight(mutationInFlight)
      setBusy(false)
    }
  }, [c.id, onDecided, t])

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

        <button type="button" aria-busy={busy} className="btn btn-primary" onClick={submit} disabled={busy} style={{ fontSize: '13px' }}>
          <Check size={14} aria-hidden="true" style={{ marginRight: '4px' }} />
          {busy
            ? t('Odesílám…', 'Submitting…')
            : isSecond ? t('Potvrdit a rozhodnout', 'Confirm & decide') : t('Odeslat hlas', 'Submit vote')}
        </button>

        {isSecond && (
          <button type="button" aria-busy={busy} className="btn btn-secondary" onClick={reopen} disabled={busy} style={{ fontSize: '13px' }}>
            {busy ? t('Pracuji…', 'Working…') : t('Znovu otevřít', 'Reopen')}
          </button>
        )}
      </div>

      {error && <div style={{ fontSize: '12px', color: 'var(--danger)', marginTop: '8px' }}>{error}</div>}
    </div>
  )
}

// ── Page ────────────────────────────────────────────────────────────────────────

export default function IdentityCasesPage() {
  const { t, language } = useLanguage()
  const [cases, setCases] = useState<VerificationCase[]>([])
  const [unavail, setUnavail] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    setUnavail(null)
    try {
      const res = await fetch(svcUrl(SVC, '/api/v1/parties/cases'), { signal: AbortSignal.timeout(6000) })
      if (!res.ok) {
        setUnavail(await classifyBffFailure(res))
        setCases([])
        return
      }
      setCases(await res.json())
    } catch {
      setUnavail('unreachable')
      setCases([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

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
      {unavail ? (
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
          {cases.map(c => (
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
                      {c.trigger}
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
                  {c.candidatePartyIds.length} {t('kandidát(ů)', 'candidate(s)')}
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
