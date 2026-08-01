// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, Megaphone } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge, type Tone } from '@/components/ui'

interface Campaign {
  id: string
  name: string
  goal: string
  segmentRef: { name: string; version: number }
  state: string
  createdBy: string
  approvedBy: string | null
  steps: { order: number; template: string; delaySeconds: number }[]
}

interface Enrolment {
  id: string
  partyId: string
  state: string
  currentStep: number
}

interface Send {
  id: string
  partyId: string
  stepOrder: number
  outcome: string
  occurredAt: string
}

type Detail = {
  campaign: Campaign | null
  enrolments: Enrolment[]
  sends: Send[]
  sources: Record<string, string>
}

/**
 * Outcome colouring, passed explicitly rather than added to the shared tone map (see `tone.ts`:
 * a domain that disagrees passes `tone`, it does not edit the table).
 *
 * A suppression is NEUTRAL, not a warning: consent withdrawn, a frequency cap or quiet hours are
 * the system doing exactly what ADR-0200 D6 asks of it. Colouring them red would train operators to
 * treat correct behaviour as an incident. Only FAILED — the send that was supposed to happen and
 * did not — is a problem.
 */
function outcomeTone(outcome: string): Tone {
  if (outcome === 'SENT') return 'success'
  if (outcome === 'FAILED') return 'danger'
  return 'neutral'
}

export default function CampaignDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { t, language } = useLanguage()
  // Params are unwrapped in an effect rather than with React `use()`. `use()` suspends until the
  // promise settles, which forces every caller — including tests — to provide a Suspense boundary
  // and, in jsdom, leaves the tree on the fallback indefinitely. An effect keeps the page mountable
  // anywhere and costs one render.
  const [id, setId] = useState<string | null>(null)
  useEffect(() => {
    params.then(p => setId(p.id))
  }, [params])
  const [detail, setDetail] = useState<Detail | null>(null)
  const [unavailable, setUnavailable] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!id) return
    fetch(`/api/campaigns/${id}`)
      .then(r => r.json())
      .then((d: Detail) => {
        if (d.sources?.campaign !== 'ok') {
          setUnavailable(d.sources?.campaign === 'unauthorized' ? 'unauthorized' : 'unreachable')
          return
        }
        setDetail(d)
      })
      .catch(() => setUnavailable('unreachable'))
      .finally(() => setLoading(false))
  }, [id])

  const c = detail?.campaign
  const sends = detail?.sends ?? []
  const suppressed = sends.filter(s => s.outcome.startsWith('SUPPRESSED')).length

  const fmtDateTime = (iso: string | null | undefined) =>
    iso
      ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', {
          dateStyle: 'medium', timeStyle: 'short',
        }).format(new Date(iso))
      : '—'

  /**
   * Human phrasing for a send outcome. The raw enum is what the API returns and what an engineer
   * debugs with; a marketer needs to know WHY nothing was sent, in words. The raw value stays in
   * the badge title so the two are never disconnected.
   */
  const outcomeLabel = (o: string): string => {
    switch (o) {
      case 'SENT': return t('Odesláno', 'Sent')
      case 'SUPPRESSED_CONSENT': return t('Odvolaný souhlas', 'Consent withdrawn')
      case 'SUPPRESSED_CAP': return t('Limit četnosti', 'Frequency cap')
      case 'SUPPRESSED_QUIET_HOURS': return t('Tiché hodiny', 'Quiet hours')
      case 'FAILED': return t('Selhalo', 'Failed')
      default: return o
    }
  }

  const stateLabel = (s: string): string => {
    switch (s) {
      case 'ACTIVE': return t('Aktivní', 'Active')
      case 'COMPLETED': return t('Dokončeno', 'Completed')
      case 'TERMINATED_CONSENT_REVOKED': return t('Ukončeno — odvolaný souhlas', 'Ended — consent withdrawn')
      case 'TERMINATED_SUPPRESSED': return t('Ukončeno — potlačeno', 'Ended — suppressed')
      default: return s
    }
  }

  /** Short id for scanning; the full value stays in `title` and is what you copy. */
  const shortId = (id: string) => id.slice(0, 8)

  // Suppressions grouped by reason: "2 suppressed" tells an operator something is off,
  // "2 × quiet hours" tells them whether to act.
  const byReason = sends
    .filter(s => s.outcome.startsWith('SUPPRESSED'))
    .reduce<Record<string, number>>((acc, s) => ({ ...acc, [s.outcome]: (acc[s.outcome] ?? 0) + 1 }), {})

  return (
    <div className="space-y-6">
      <Link href="/campaigns" className="inline-flex items-center gap-1 text-sm hover:underline">
        <ArrowLeft className="h-4 w-4" /> {t('Kampaně', 'Campaigns')}
      </Link>

      <PageHeader
        title={c?.name ?? t('Kampaň', 'Campaign')}
        subtitle={c?.goal}
        icon={<Megaphone className="h-6 w-6" />}
      />

      {loading && <p className="text-sm text-muted-foreground">{t('Načítám…', 'Loading…')}</p>}
      {!loading && unavailable && (
        <DataUnavailable kind={unavailable} service="Campaign-service" feature={t('Detail kampaně', 'Campaign detail')} />
      )}

      {!loading && !unavailable && c && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label={t('Stav', 'State')} value={c.state} />
            <StatCard label={t('Segment', 'Segment')} value={`${c.segmentRef?.name}@${c.segmentRef?.version}`} />
            <StatCard label={t('Zařazeno', 'Enrolled')} value={String(detail?.enrolments.length ?? 0)} />
            {/* Surfaced as a headline number on purpose: "how many were deliberately not
                contacted" is the question the send log exists to answer (#2895). */}
            <StatCard label={t('Potlačených odeslání', 'Suppressed sends')} value={String(suppressed)} />
          </div>

          {Object.keys(byReason).length > 0 && (
            <p className="text-xs text-muted-foreground">
              {t('Potlačeno: ', 'Suppressed: ')}
              {Object.entries(byReason)
                .map(([reason, n]) => `${n}× ${outcomeLabel(reason)}`)
                .join(', ')}
            </p>
          )}

          <section className="space-y-2">
            <h2 className="text-sm font-semibold">{t('Kroky', 'Steps')}</h2>
            {/* The steps were fetched all along and never shown — a campaign whose content you
                cannot see is hard to reason about when its sends are being suppressed. */}
            <div className="overflow-x-auto rounded-lg border">
              <table className="w-full text-sm">
                <thead className="bg-muted/50 text-left">
                  <tr>
                    <th className="px-4 py-2 font-medium">#</th>
                    <th className="px-4 py-2 font-medium">{t('Šablona', 'Template')}</th>
                    <th className="px-4 py-2 font-medium">{t('Zpoždění', 'Delay')}</th>
                  </tr>
                </thead>
                <tbody>
                  {(c.steps ?? []).map(step => (
                    <tr key={step.order} className="border-t">
                      <td className="px-4 py-2">{step.order}</td>
                      <td className="px-4 py-2 font-mono text-xs">{step.template}</td>
                      <td className="px-4 py-2 text-xs">
                        {step.delaySeconds === 0
                          ? t('ihned', 'immediately')
                          : `${Math.round(step.delaySeconds / 60)} min`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section className="space-y-2">
            <h2 className="text-sm font-semibold">{t('Čtyři oči', 'Four-eyes')}</h2>
            <p className="text-sm text-muted-foreground">
              {t('Vytvořil', 'Created by')} <span className="font-mono">{c.createdBy}</span>{t(', schválil ', ', approved by ')}
              <span className="font-mono">{c.approvedBy ?? t('— zatím neschváleno', '— not yet approved')}</span>
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-sm font-semibold">{t('Zařazení', 'Enrolments')}</h2>
            {detail?.sources.enrolments !== 'ok' ? (
              <DataUnavailable
                kind={detail?.sources.enrolments === 'unauthorized' ? 'unauthorized' : detail?.sources.enrolments === 'not_deployed' ? 'not_deployed' : 'unreachable'}
                service="Campaign-service"
                feature={t('Zařazení', 'Enrolments')}
                dense
              />
            ) : (
              <div className="overflow-x-auto rounded-lg border">
                <table className="w-full text-sm">
                  <thead className="bg-muted/50 text-left">
                    <tr>
                      <th className="px-4 py-2 font-medium">{t('Party', 'Party')}</th>
                      <th className="px-4 py-2 font-medium">{t('Stav', 'State')}</th>
                      <th className="px-4 py-2 font-medium">{t('Krok', 'Step')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.enrolments.map(e => (
                      <tr key={e.id} className="border-t">
                        <td className="px-4 py-2 font-mono text-xs" title={e.partyId}>{shortId(e.partyId)}</td>
                        <td className="px-4 py-2">
                          <span title={e.state}><StatusBadge status={e.state} label={stateLabel(e.state)} /></span>
                        </td>
                        <td className="px-4 py-2">{e.currentStep}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="space-y-2">
            <h2 className="text-sm font-semibold">{t('Log odeslání', 'Send log')}</h2>
            <p className="text-xs text-muted-foreground">
              {t(
                'Včetně potlačených pokusů — výsledek je jediné místo, kde je vidět odvolaný souhlas, limit četnosti nebo tiché hodiny.',
                'Includes suppressed attempts — the outcome is the only place a consent withdrawal, frequency cap or quiet-hours skip is visible.',
              )}
            </p>
            {/* Stated on screen because an evening test run produces nothing but quiet-hours
                suppressions, which reads as a broken campaign rather than a working rule. */}
            <p className="text-xs text-muted-foreground">
              {t(
                'Tiché hodiny: 21:00–8:00. Odeslání v tomto okně se potlačí (ADR-0200 D6).',
                'Quiet hours: 21:00–08:00. Sends in that window are suppressed (ADR-0200 D6).',
              )}
            </p>
            {detail?.sources.sends !== 'ok' ? (
              <DataUnavailable
                kind={detail?.sources.sends === 'unauthorized' ? 'unauthorized' : detail?.sources.sends === 'not_deployed' ? 'not_deployed' : 'unreachable'}
                service="Campaign-service"
                feature={t('Log odeslání', 'Send log')}
                dense
              />
            ) : sends.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('Zatím nic odesláno ani pokusem.', 'Nothing sent or attempted yet.')}</p>
            ) : (
              <div className="overflow-x-auto rounded-lg border">
                <table className="w-full text-sm">
                  <thead className="bg-muted/50 text-left">
                    <tr>
                      <th className="px-4 py-2 font-medium">{t('Party', 'Party')}</th>
                      <th className="px-4 py-2 font-medium">{t('Krok', 'Step')}</th>
                      <th className="px-4 py-2 font-medium">{t('Výsledek', 'Outcome')}</th>
                      <th className="px-4 py-2 font-medium">{t('Kdy', 'When')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sends.map(s => (
                      <tr key={s.id} className="border-t">
                        <td className="px-4 py-2 font-mono text-xs" title={s.partyId}>{shortId(s.partyId)}</td>
                        <td className="px-4 py-2">{s.stepOrder}</td>
                        <td className="px-4 py-2">
                          <span title={s.outcome}>
                            <StatusBadge status={s.outcome} tone={outcomeTone(s.outcome)} label={outcomeLabel(s.outcome)} />
                          </span>
                        </td>
                        <td className="px-4 py-2 text-xs whitespace-nowrap">{fmtDateTime(s.occurredAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  )
}
