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
  const { t } = useLanguage()
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
                        <td className="px-4 py-2 font-mono text-xs">{e.partyId}</td>
                        <td className="px-4 py-2"><StatusBadge status={e.state} /></td>
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
                        <td className="px-4 py-2 font-mono text-xs">{s.partyId}</td>
                        <td className="px-4 py-2">{s.stepOrder}</td>
                        <td className="px-4 py-2">
                          <StatusBadge status={s.outcome} tone={outcomeTone(s.outcome)} />
                        </td>
                        <td className="px-4 py-2 text-xs">{s.occurredAt}</td>
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
