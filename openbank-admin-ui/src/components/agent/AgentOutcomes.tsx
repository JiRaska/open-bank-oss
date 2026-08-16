// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Sparkles } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  deriveAgentOutcomes,
  deriveWeeklyOutcomes,
  formatLatency,
  MIN_DECIDED_FOR_RATE,
  PROPOSAL_PAGE_CAP,
  type ProposalOutcomeInput,
} from '@/lib/governance/agentOutcomes'

function Card({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ background: 'var(--surface)', border: '1px solid var(--border)',
      borderRadius: 'var(--r-lg)', padding: '20px 24px', marginBottom: '20px' }}>
      {children}
    </div>
  )
}

// ── Outcome metrics (#4462) ────────────────────────────────────────────────
// Acceptance rate and review latency for one charter, from the ADR-0031 D4 proposal
// store. Every figure is rendered WITH its denominator, and the denominators differ:
// the rate is over decided proposals, the latency over decided proposals whose two
// timestamps are both usable. A rate without its coverage would be the UI version of
// a gate that passes without reaching its subject, so the coverage line is not
// optional chrome — it is the reason this panel can be shown at all.
function Figure({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div style={{ flex: '1 1 140px' }}>
      <div style={{ fontSize: '10px', textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-tertiary)' }}>{label}</div>
      <div style={{ fontSize: '22px', fontWeight: 700, color: 'var(--text-primary)', lineHeight: 1.3 }}>{value}</div>
      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{sub}</div>
    </div>
  )
}

// ── Weekly trend (#4462: "per week") ────────────────────────────────────────
// Most weeks will legitimately read "insufficient data" — the queue is thin. Each row
// still states its own decided count, so a reader can see WHY a week is blank rather
// than wondering if the week rendered a zero.
function WeeklyTrend({ items }: { items: ProposalOutcomeInput[] }) {
  const { t } = useLanguage()
  const weeks = deriveWeeklyOutcomes(items)

  if (weeks.length === 0) return null

  return (
    <div style={{ marginTop: '14px', paddingTop: '12px', borderTop: '1px solid var(--border)' }}>
      <div style={{ fontSize: '10px', textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-tertiary)', marginBottom: '8px' }}>
        {t('Míra schválení podle týdne', 'Approval rate by week')}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
        {weeks.map(w => (
          <div key={w.weekStart} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: 'var(--text-secondary)' }}>
            <span>{w.weekStart}</span>
            <span>
              {w.insufficientData
                ? t(`nedostatek dat (${w.decided} rozhodnuto)`, `insufficient data (${w.decided} decided)`)
                : t(`${Math.round((w.approvalRate as number) * 100)}% (${w.approved} z ${w.decided})`, `${Math.round((w.approvalRate as number) * 100)}% (${w.approved} of ${w.decided})`)}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

export function OutcomeMetricsCard({ items }: { items: ProposalOutcomeInput[] }) {
  const { t } = useLanguage()
  const m = deriveAgentOutcomes(items)

  return (
    <Card>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
        <Sparkles size={14} style={{ color: '#6366f1' }} />
        <span style={{ fontSize: '13px', fontWeight: 700 }}>{t('Výsledky návrhů', 'Proposal outcomes')}</span>
      </div>
      <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', margin: '0 0 14px' }}>
        {t(
          'Z úložiště návrhů (ADR-0031 D4), ne z auditní stopy — rozhodnutí tam nese schvalovatele i čas u 100 % případů.',
          'From the proposal store (ADR-0031 D4), not the audit trail — a decision there carries its approver and its time on 100% of rows.',
        )}
      </p>

      {items.length === 0 ? (
        <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
          {t('Žádné návrhy — není co měřit.', 'No proposals — nothing to measure.')}
        </div>
      ) : (
        <>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '20px' }}>
            <Figure
              label={t('Míra schválení', 'Approval rate')}
              value={m.approvalRate === null ? t('nedostatek dat', 'insufficient data') : `${Math.round(m.approvalRate * 100)}%`}
              sub={m.approvalRate === null
                ? t(`${m.decided} rozhodnuto, potřeba ${MIN_DECIDED_FOR_RATE}`, `${m.decided} decided, needs ${MIN_DECIDED_FOR_RATE}`)
                : t(`${m.approved} z ${m.decided} rozhodnutých`, `${m.approved} of ${m.decided} decided`)}
            />
            <Figure
              label={t('Latence — medián', 'Review latency — median')}
              value={m.latencyP50Seconds === null ? t('nedostatek dat', 'insufficient data') : formatLatency(m.latencyP50Seconds)}
              sub={t(`${m.latencySamples} vzorků`, `${m.latencySamples} samples`)}
            />
            <Figure
              label={t('Latence — p95', 'Review latency — p95')}
              value={m.latencyP95Seconds === null ? t('nedostatek dat', 'insufficient data') : formatLatency(m.latencyP95Seconds)}
              sub={t(`${m.latencySamples} vzorků`, `${m.latencySamples} samples`)}
            />
          </div>

          {/* Coverage — the denominator, stated rather than implied. */}
          <div style={{ marginTop: '14px', paddingTop: '12px', borderTop: '1px solid var(--border)', fontSize: '11px', color: 'var(--text-secondary)', lineHeight: 1.7 }}>
            <div>
              {t(
                `Pokrytí: ${m.decided} z ${m.total} návrhů rozhodnuto (${m.pending} stále čeká a do žádné míry nevstupuje).`,
                `Coverage: ${m.decided} of ${m.total} proposals decided (${m.pending} still pending, and in no rate's denominator).`,
              )}
            </div>
            <div>
              {t(
                `Latence měřena na ${m.latencySamples} z ${m.decided} rozhodnutých${m.latencyExcluded > 0 ? `; ${m.latencyExcluded} vyřazeno pro nepoužitelné časy` : ''}.`,
                `Latency measured over ${m.latencySamples} of ${m.decided} decided${m.latencyExcluded > 0 ? `; ${m.latencyExcluded} excluded for unusable timestamps` : ''}.`,
              )}
            </div>
            {m.lastDecisionAt && (
              <div>
                {t('Poslední rozhodnutí: ', 'Last decision: ')}
                {new Date(m.lastDecisionAt).toISOString().slice(0, 10)}
                {m.pending > 0 && t(
                  ` — ${m.pending} návrhů od té doby nebo dříve stále čeká.`,
                  ` — ${m.pending} proposals still awaiting a reviewer.`,
                )}
              </div>
            )}
            {m.truncated && (
              <div style={{ color: '#d97706' }}>
                {t(
                  `Seznam dosáhl stropu ${PROPOSAL_PAGE_CAP} položek — čísla platí pro toto okno, ne pro celou historii.`,
                  `The list hit the ${PROPOSAL_PAGE_CAP}-row page cap — these figures describe that window, not the full history.`,
                )}
              </div>
            )}
          </div>

          <WeeklyTrend items={items} />
        </>
      )}
    </Card>
  )
}
