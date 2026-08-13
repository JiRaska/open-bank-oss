// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { Eye, MousePointer2, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export type CampaignAttentionMetric = {
  stepOrder: number
  channel: 'PUSH' | 'BANNER'
  surface: 'HOME_BANNER' | 'HOME_CAROUSEL' | 'PRODUCT_FEED' | 'REWARDS_HUB'
  type: 'IMPRESSION' | 'CLICK' | 'DISMISS'
  count: number
}

type AttentionRow = {
  stepOrder: number
  channel: CampaignAttentionMetric['channel']
  surface: CampaignAttentionMetric['surface']
  impressions: number
  clicks: number
  dismissals: number
}

function labels(t: (cs: string, en: string) => string) {
  return {
    channel: { PUSH: t('Push do aplikace', 'App push'), BANNER: t('Obsah v aplikaci', 'In-app content') },
    surface: {
      HOME_BANNER: t('Domovský banner', 'Home banner'),
      HOME_CAROUSEL: t('Domovský carousel', 'Home carousel'),
      PRODUCT_FEED: t('Feed produktů', 'Product feed'),
      REWARDS_HUB: t('Centrum odměn', 'Rewards hub'),
    },
  }
}

/** Shows only verified app events; a delivery handoff is never treated as a made-up impression. */
export function CampaignAttentionFunnel({ metrics }: { metrics: CampaignAttentionMetric[] }) {
  const { t, language } = useLanguage()
  const copy = labels(t)
  const rows = new Map<string, AttentionRow>()
  for (const metric of metrics) {
    const key = `${metric.stepOrder}-${metric.channel}-${metric.surface}`
    const row = rows.get(key) ?? { stepOrder: metric.stepOrder, channel: metric.channel, surface: metric.surface, impressions: 0, clicks: 0, dismissals: 0 }
    if (metric.type === 'IMPRESSION') row.impressions += metric.count
    if (metric.type === 'CLICK') row.clicks += metric.count
    if (metric.type === 'DISMISS') row.dismissals += metric.count
    rows.set(key, row)
  }
  const ordered = [...rows.values()].sort((a, b) => a.stepOrder - b.stepOrder || a.channel.localeCompare(b.channel) || a.surface.localeCompare(b.surface))
  const formatNumber = (value: number) => value.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')
  const formatRate = (clicks: number, impressions: number) => impressions > 0 ? `${((clicks / impressions) * 100).toFixed(1)} %` : '—'

  return (
    <section className="campaign-attention-funnel" data-testid="campaign-attention-funnel">
      <div className="campaign-attention-heading">
        <div><p>{t('Pozorovaná odezva v aplikaci', 'Observed app attention')}</p><h2>{t('Co lidé skutečně udělali', 'What people actually did')}</h2></div>
        <span>{t('bez domněnek', 'no inference')}</span>
      </div>
      <p className="campaign-attention-intro">{t('Imprese, otevření a zavření jsou připsané jen přes ověřený odkaz kampaně. Nejsou to lidé ani obchodní konverze.', 'Impressions, taps and dismissals are shown only for a verified campaign reference. They are neither people nor business conversions.')}</p>
      {ordered.length === 0 ? (
        <p className="campaign-attention-empty">{t('Zatím nepřišla žádná přiřaditelná odezva z aplikace.', 'No attributable app response has arrived yet.')}</p>
      ) : (
        <div className="campaign-attention-grid">
          {ordered.map(row => (
            <article key={`${row.stepOrder}-${row.channel}-${row.surface}`} className="campaign-attention-card">
              <div className="campaign-attention-card-heading"><span>{t(`Krok ${row.stepOrder}`, `Step ${row.stepOrder}`)}</span><strong>{copy.surface[row.surface]}</strong><small>{copy.channel[row.channel]}</small></div>
              <div className="campaign-attention-metrics">
                <div><Eye aria-hidden="true" /><strong>{formatNumber(row.impressions)}</strong><span>{t('viděno', 'seen')}</span></div>
                <div><MousePointer2 aria-hidden="true" /><strong>{formatNumber(row.clicks)}</strong><span>{t('otevřeno', 'tapped')}</span></div>
                <div><X aria-hidden="true" /><strong>{formatNumber(row.dismissals)}</strong><span>{t('zavřeno', 'dismissed')}</span></div>
              </div>
              <p>{t('Míra otevření', 'Tap rate')} <strong>{formatRate(row.clicks, row.impressions)}</strong></p>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
