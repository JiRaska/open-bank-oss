// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One loan application, drawn as the ADR-0211 lifecycle it is actually in.
//
// The lending console (ADR-0230 D1) lists applications as rows with a status string. That answers
// "which applications exist" and not "where is this one, how did it get there, and what is it
// waiting for" — which is the question the credit desk actually asks. This screen answers that:
// the state graph with the application's real path on it, the ADR-0214 evidence trail behind each
// step, and the one intervention that is safe to expose here.
//
// WHAT THIS SCREEN DELIBERATELY DOES NOT DO
// Credit decisions and disbursement are NOT buttons here. ADR-0227 D4 puts money-path disposal in
// the approval inbox behind SCA, and `/lending`'s own contract says mutations live there. Those
// actions are rendered VISIBLE and DISABLED with the reason and a link, rather than hidden — an
// operator who cannot find the control assumes the platform is broken, while a disabled control
// with a reason teaches them where the control lives. Manual advance is currently not configured
// in the policy bundle, so this screen states that honestly instead of offering a button that the
// backend will deny.
//
// A 403 on the evidence read is ORDINARY (it is ROLE_COMPLIANCE / ROLE_CREDIT_RISK / ROLE_ADMIN,
// while the queue is also open to ROLE_OPERATOR). It must never render as "this application has no
// history" — an empty trail on an audit screen is the most dangerous thing it can show.

'use client'

import { use, useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, GitBranch, RefreshCw, ShieldAlert } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { PageHeader, StatusBadge } from '@/components/ui'
import { EntityChip } from '@/components/entities/EntityChip'
import { OriginationFlow, STATE_LABELS, type StepFact } from '@/components/lending/OriginationFlow'

type Application = {
  id: string
  partyId: string
  status: string
  requestedAmount?: { amount: number; currency: string }
  termPeriods?: number
  nominalAnnualRate?: number
  jurisdiction?: string | null
  productType?: string | null
  packVersion?: number | null
  proposedBy?: string
  createdAt?: string
}

/** The ADR-0214 transition envelope, as emitted by `LendingService.transitionEvidence`. */
type TransitionPayload = {
  fromState?: string
  toState?: string
  actorId?: string
  actorKind?: string
  reason?: string
  occurredAt?: string
  packVersion?: number | null
}

type EvidenceEvent = { eventId: string; eventType: string; occurredAt: string; payload: string }

type ReadState = 'ok' | 'forbidden' | 'unavailable'

function readStateFor(status: number): ReadState {
  return status === 401 || status === 403 ? 'forbidden' : 'unavailable'
}

export default function ApplicationFlowPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const dateLocale = numberLocale

  const [app, setApp] = useState<Application | null>(null)
  const [events, setEvents] = useState<EvidenceEvent[]>([])
  const [appState, setAppState] = useState<ReadState>('ok')
  const [evidenceState, setEvidenceState] = useState<ReadState>('ok')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [appRes, evRes] = await Promise.all([
        fetch(svcUrl('lending-service', `/api/v1/lending/applications/${id}`), { cache: 'no-store' }),
        fetch(svcUrl('lending-service', `/api/v1/lending/applications/${id}/evidence`), { cache: 'no-store' }),
      ])
      if (appRes.ok) {
        setApp(await appRes.json())
        setAppState('ok')
      } else {
        setApp(null)
        setAppState(readStateFor(appRes.status))
      }
      if (evRes.ok) {
        const body = await evRes.json()
        setEvents(Array.isArray(body?.events) ? body.events : [])
        setEvidenceState('ok')
      } else {
        setEvents([])
        setEvidenceState(readStateFor(evRes.status))
      }
      setError(null)
    } catch {
      setApp(null)
      setEvents([])
      setAppState('unavailable')
      setEvidenceState('unavailable')
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => { void load() }, [load])

  /** Rebuild the path walked from the transition envelopes. A malformed payload is skipped rather
   *  than failing the page — the flow degrades to "no timestamp on this node", not to a blank screen. */
  const history: StepFact[] = useMemo(() => {
    const out: StepFact[] = []
    for (const e of events) {
      if (e.eventType !== 'credit.application.transition') continue
      let p: TransitionPayload
      try {
        p = JSON.parse(e.payload) as TransitionPayload
      } catch {
        continue
      }
      if (!p.toState) continue
      out.push({
        state: p.toState,
        at: p.occurredAt ?? e.occurredAt,
        actor: p.actorId,
        actorKind: p.actorKind,
        reason: p.reason,
      })
    }
    return out
  }, [events])

  const stateLabel = (s?: string) =>
    s ? (STATE_LABELS[s] ? (language === 'cs' ? STATE_LABELS[s].cs : STATE_LABELS[s].en) : s) : '—'

  const money = (m?: { amount: number; currency: string }) =>
    m ? `${m.amount.toLocaleString(numberLocale)} ${m.currency}` : '—'

  return (
    <div>
      <PageHeader
        title={t('Průběh žádosti o úvěr', 'Loan application progress')}
        subtitle={t(
          'Životní cyklus podle ADR-0211 se skutečnou cestou této žádosti. Rozhodnutí a čerpání se schvalují ve frontě schvalování, ne zde.',
          'The ADR-0211 lifecycle with this application\'s real path on it. Decisions and disbursement are approved in the approval inbox, not here.',
        )}
        icon={<GitBranch size={18} style={{ color: 'var(--accent)' }} />}
        actions={
          <div style={{ display: 'flex', gap: 8 }}>
            <Link href="/lending" className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
              <ArrowLeft size={14} /> {t('Zpět na konzoli', 'Back to console')}
            </Link>
            <button type="button" onClick={() => void load()} disabled={loading} aria-busy={loading} aria-label={t('Obnovit žádost o úvěr', 'Refresh lending application')} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
              <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
            </button>
          </div>
        }
      />

      {error && (
        <div className="card" data-testid="error" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 13 }}>
          {error}
        </div>
      )}
      {notice && (
        <div className="card" data-testid="notice" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--success)', fontSize: 13 }}>
          {notice}
        </div>
      )}

      {appState !== 'ok' && (
        <div className="card" data-testid="app-unavailable" style={{ padding: 16, fontSize: 13 }}>
          {appState === 'forbidden'
            ? t('Na tuto žádost nemáte oprávnění.', 'You are not permitted to view this application.')
            : t('lending-service je nedostupný.', 'lending-service is unreachable.')}
        </div>
      )}

      {app && (
        <>
          <div className="card" style={{ padding: 14, marginBottom: 16, display: 'flex', flexWrap: 'wrap', gap: 24, alignItems: 'center' }}>
            <div>
              <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Klient', 'Party')}</div>
              <EntityChip type="party" id={app.partyId} />
            </div>
            <div>
              <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Částka', 'Amount')}</div>
              <div style={{ fontWeight: 600 }}>{money(app.requestedAmount)}</div>
            </div>
            <div>
              <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Stav', 'State')}</div>
              <StatusBadge status={app.status} label={stateLabel(app.status)} withDot />
            </div>
            <div>
              <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Pack', 'Pack')}</div>
              <div style={{ fontSize: 13 }}>
                {app.jurisdiction ?? '—'} / {app.productType ?? '—'}
                {app.packVersion != null ? ` · v${app.packVersion}` : ''}
              </div>
            </div>
          </div>

          <div className="section-title">{t('Stavový průběh', 'Lifecycle')}</div>
          <div className="card" style={{ padding: 14, marginBottom: 16, overflowX: 'auto' }}>
            <OriginationFlow current={app.status} history={history} lang={language} />
          </div>

          <div className="section-title">{t('Zásahy', 'Interventions')}</div>
          <div className="card" style={{ padding: 14, marginBottom: 16, display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center' }}>
            <span role="status" style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>
              {t('Ruční posun není v této instalaci nakonfigurován.', 'Manual advance is not configured in this installation.')}
            </span>
            <button className="btn btn-secondary" style={{ fontSize: 12 }} disabled data-testid="decide-disabled">
              {t('Rozhodnout (4 oči)', 'Decide (four-eyes)')}
            </button>
            <button className="btn btn-secondary" style={{ fontSize: 12 }} disabled data-testid="disburse-disabled">
              {t('Vyčerpat', 'Disburse')}
            </button>
            <span style={{ fontSize: 12, color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: 6 }}>
              <ShieldAlert size={13} />
              {t(
                'Rozhodnutí a čerpání se schvalují ve frontě schvalování se silným ověřením (ADR-0227 D4) — ne jedním kliknutím zde.',
                'Decisions and disbursement are approved in the approval inbox under strong authentication (ADR-0227 D4) — not by one click here.',
              )}
              <Link href="/approvals" style={{ color: 'var(--accent)' }}>{t('Otevřít frontu', 'Open the inbox')}</Link>
            </span>
          </div>

          {/* NOT a duplicate of the rail above. The rail keys facts by STATE, so an application
              that revisits one (ASSESSMENT → DOCS_REQUIRED → ASSESSMENT, a perfectly ordinary
              re-request for documents) collapses into a single dot showing only the last visit.
              The table is every transition in order, which is what an auditor asks for. */}
          <div className="section-title">{t('Auditní stopa', 'Evidence trail')}</div>
          <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
            {evidenceState !== 'ok' && (
              <div data-testid="evidence-restricted" style={{ padding: 16, fontSize: 13 }}>
                {evidenceState === 'forbidden'
                  ? t(
                      'Auditní stopu vidí jen compliance a credit-risk. Prázdno zde NEZNAMENÁ, že žádost nemá historii.',
                      'The evidence trail is restricted to compliance and credit-risk. Empty here does NOT mean this application has no history.',
                    )
                  : t('Auditní stopu se nepodařilo načíst.', 'The evidence trail could not be loaded.')}
              </div>
            )}
            {evidenceState === 'ok' && history.length === 0 && (
              <div data-testid="evidence-empty" style={{ padding: 16, fontSize: 13, color: 'var(--text-tertiary)' }}>
                {t('Zatím žádný zaznamenaný přechod.', 'No recorded transition yet.')}
              </div>
            )}
            {evidenceState === 'ok' && history.length > 0 && (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr style={{ background: 'var(--surface-2)', textAlign: 'left' }}>
                    <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Kdy', 'When')}</th>
                    <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Přechod do', 'Moved to')}</th>
                    <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Kdo', 'Actor')}</th>
                    <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Důvod', 'Reason')}</th>
                  </tr>
                </thead>
                <tbody>
                  {history.map((h, i) => (
                    <tr key={`${h.state}-${h.at}-${i}`} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={{ padding: '10px 14px', color: 'var(--text-tertiary)', fontSize: 12 }}>
                        {h.at ? new Date(h.at).toLocaleString(dateLocale) : '—'}
                      </td>
                      <td style={{ padding: '10px 14px' }}>
                        <span title={h.state}>{stateLabel(h.state)}</span>
                      </td>
                      <td style={{ padding: '10px 14px' }}>
                        {h.actor ?? '—'}
                        {h.actorKind && (
                          <span className="pill" style={{ marginLeft: 6, fontSize: 10 }}>{h.actorKind}</span>
                        )}
                      </td>
                      <td style={{ padding: '10px 14px', color: 'var(--text-tertiary)', fontSize: 12 }}>{h.reason ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </>
      )}
    </div>
  )
}
