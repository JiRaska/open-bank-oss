// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import Link from 'next/link'
import { CalendarDays, Radio, Sparkles } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export interface CampaignPlan {
  campaignId: string
  name: string
  state: string
  entry: 'SCHEDULED' | 'EVENT' | 'MANUAL'
  cadence?: string | null
  cadenceHumanForm?: string | null
  zone?: string | null
  nextScheduledWindowAt?: string | null
  endAt?: string | null
  trigger?: string | null
}

/**
 * A marketer-facing plan, not a fabricated execution log. The Campaign service calculates each
 * declared window from its reviewed cadence; this component only formats that evidence and keeps
 * inactive/event-driven entries visibly distinct from a planned run.
 */
export function CampaignPlanningBoard({
  items,
  state,
}: {
  items: CampaignPlan[]
  state: 'loading' | 'ok' | 'unavailable'
}) {
  const { t, language } = useLanguage()
  const formatter = new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', {
    weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Prague',
  })
  const scheduled = items.filter(item => item.entry === 'SCHEDULED')
  const upcoming = scheduled.filter(item => item.nextScheduledWindowAt).slice(0, 3)
  const notLive = scheduled.filter(item => !item.nextScheduledWindowAt && item.state !== 'ACTIVE')
  const noNextWindow = scheduled.filter(item => !item.nextScheduledWindowAt && item.state === 'ACTIVE')
  const events = items.filter(item => item.entry === 'EVENT').slice(0, 2)

  return (
    <section className="campaign-planning-board" aria-label={t('Plán kampaní', 'Campaign plan')} data-testid="campaign-planning-board">
      <div className="campaign-plan-head">
        <div>
          <p><CalendarDays size={14} /> {t('Plánovací radar', 'Planning radar')}</p>
          <h2>{t('Kdy se příště může něco stát', 'When something can happen next')}</h2>
        </div>
        <span>{t('Europe/Prague', 'Europe/Prague')}</span>
      </div>

      {state === 'loading' && <p className="campaign-plan-empty">{t('Načítám plán…', 'Loading the plan…')}</p>}
      {state === 'unavailable' && (
        <p className="campaign-plan-empty" data-testid="campaign-plan-unavailable">
          {t('Plán zatím není dostupný. Neznamená to, že nic neběží.', 'The plan is not available yet. It does not mean nothing is running.')}
        </p>
      )}
      {state === 'ok' && (
        <div className="campaign-plan-grid">
          <div className="campaign-plan-upcoming">
            {upcoming.length > 0 ? upcoming.map((item, index) => (
              <Link href={`/campaigns/${item.campaignId}`} key={`${item.campaignId}-${item.entry}`} className="campaign-plan-card" data-plan-campaign={item.campaignId}>
                <span className="campaign-plan-index">0{index + 1}</span>
                <span className="campaign-plan-copy">
                  <strong>{item.name}</strong>
                  <small>{item.cadenceHumanForm}</small>
                </span>
                <time dateTime={item.nextScheduledWindowAt ?? undefined}>
                  {item.nextScheduledWindowAt ? formatter.format(new Date(item.nextScheduledWindowAt)) : '—'}
                </time>
              </Link>
            )) : (
              <p className="campaign-plan-empty">{t('Žádná aktivní pravidelná cesta zatím nemá další okno.', 'No active recurring journey has a next window yet.')}</p>
            )}
            <p className="campaign-plan-footnote">
              {t('Jsou to deklarovaná okna cadence, ne slíbená doručení ani odhad překryvu audience.', 'These are declared cadence windows, not promised sends or an audience-overlap estimate.')}
            </p>
          </div>
          <aside className="campaign-plan-aside">
            <div>
              <Sparkles size={14} />
              <strong>{t('Ještě neběží', 'Not live yet')}</strong>
              <span>{notLive.length}</span>
            </div>
            <p>{notLive.length > 0
              ? t('Naplánované koncepty, čekající na druhé oči nebo pozastavené cesty nemají vypsané další okno.', 'Scheduled drafts, approval work and paused journeys do not show a next window.')
              : t('Všechny pravidelné cesty jsou buď živé, nebo bez čekajícího schválení.', 'Every recurring journey is either live or has no pending approval work.')}
            </p>
            {noNextWindow.length > 0 && (
              <div className="campaign-plan-events" data-testid="campaign-plan-no-next-window">
                <CalendarDays size={13} />
                <span>{noNextWindow.length} {t('aktivní pravidelné cesty už nemají další deklarované okno.', 'active recurring journeys have no next declared window.')}</span>
              </div>
            )}
            {events.length > 0 && (
              <div className="campaign-plan-events">
                <Radio size={13} />
                <span>{events.length} {t('eventové vstupy reagují až na produktovou událost.', 'event entries react only to a product event.')}</span>
              </div>
            )}
          </aside>
        </div>
      )}
    </section>
  )
}
