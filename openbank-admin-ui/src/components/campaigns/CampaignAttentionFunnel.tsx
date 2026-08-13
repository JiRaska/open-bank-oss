// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { Eye, Lightbulb, MousePointer2, X } from 'lucide-react'
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

type SurfaceEvidence = {
  surface: CampaignAttentionMetric['surface']
  impressions: number
  clicks: number
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

/**
 * Shows only verified app events; a delivery handoff is never treated as a made-up impression.
 *
 * The resulting prompt is deliberately an *evidence* next step, not a recommendation engine:
 * app attention can help someone decide what to inspect next, but cannot establish product value
 * or causal lift. That distinction is important enough to live beside the numbers rather than in
 * a tooltip at the bottom of the screen.
 */
export function CampaignAttentionFunnel({
  metrics,
  hasMeasuredOutcome,
  hasHoldout,
}: {
  metrics: CampaignAttentionMetric[]
  hasMeasuredOutcome: boolean
  hasHoldout: boolean
}) {
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
  // These are independent aggregate events, not a deduplicated impression → click funnel. The
  // ratio is therefore a compact comparison of event volumes, and is intentionally allowed to
  // exceed 100% when the app records clicks without a prior impression event.
  const formatEventRatio = (clicks: number, impressions: number) => impressions > 0 ? `${((clicks / impressions) * 100).toFixed(1)} %` : '—'
  const evidenceBySurface = new Map<CampaignAttentionMetric['surface'], SurfaceEvidence>()
  for (const row of ordered) {
    const evidence = evidenceBySurface.get(row.surface) ?? { surface: row.surface, impressions: 0, clicks: 0 }
    evidence.impressions += row.impressions
    evidence.clicks += row.clicks
    evidenceBySurface.set(row.surface, evidence)
  }
  const observed = [...evidenceBySurface.values()]
    .filter(surface => surface.impressions > 0)
    // Select an app surface by all of its observed rows, never by the most flattering individual
    // step or channel. A rate can help exploration, but does not imply a winning funnel.
    .sort((a, b) => b.impressions - a.impressions || b.clicks - a.clicks)[0]
  const observedDescription = observed
    ? t(
      `${formatNumber(observed.clicks)} událostí otevření; ${formatNumber(observed.impressions)} událostí zobrazení na ploše ${copy.surface[observed.surface]} (poměr událostí ${formatEventRatio(observed.clicks, observed.impressions)}).`,
      `${formatNumber(observed.clicks)} click events; ${formatNumber(observed.impressions)} impression events on ${copy.surface[observed.surface]} (event ratio ${formatEventRatio(observed.clicks, observed.impressions)}).`,
    )
    : null
  const insight = !observed
    ? {
      title: t('Počkejte na první ověřené zobrazení', 'Wait for the first verified exposure'),
      detail: t('Dokud aplikace nepotvrdí zobrazení, není z čeho posuzovat obsah ani plochu.', 'Until the app confirms an exposure, there is no basis to assess the content or surface.'),
    }
    : !hasMeasuredOutcome
      ? {
        title: t('Než zvolíte vítěznou plochu, změřte cíl', 'Set a measurable outcome before choosing a surface'),
        detail: t('Otevření ukazuje pozornost v aplikaci; samo o sobě není bankovní výsledek.', 'A tap shows attention in the app; it is not a banking outcome on its own.'),
      }
      : !hasHoldout
        ? {
          title: t('Porovnejte pozornost se skutečným výsledkem', 'Compare attention with the real outcome'),
          detail: t('Před změnou cesty ověřte, zda se pozorovaná odezva potkává s měřeným cílem. Tohle srovnání ještě neprokazuje příčinu.', 'Before changing the journey, check whether observed attention aligns with the measured outcome. That comparison does not establish causality yet.'),
        }
        : {
          title: t('Ověřte účinek přes kontrolní skupinu', 'Validate the effect with the control group'),
          detail: t('Pozornost určí, co zkoumat; teprve kontrolní skupina ukáže, zda cesta přidala skutečnou hodnotu.', 'Attention tells you what to investigate; only the control group can show whether the journey added real value.'),
        }

  return (
    <section className="campaign-attention-funnel" data-testid="campaign-attention-funnel">
      <div className="campaign-attention-heading">
        <div><p>{t('Pozorovaná odezva v aplikaci', 'Observed app attention')}</p><h2>{t('Co lidé skutečně udělali', 'What people actually did')}</h2></div>
        <span>{t('bez domněnek', 'no inference')}</span>
      </div>
      <p className="campaign-attention-intro">{t('Imprese, otevření a zavření jsou připsané jen přes ověřený odkaz kampaně. Nejsou to lidé ani obchodní konverze.', 'Impressions, taps and dismissals are shown only for a verified campaign reference. They are neither people nor business conversions.')}</p>
      <aside className="campaign-attention-insight" data-testid="campaign-attention-next-evidence">
        <Lightbulb aria-hidden="true" />
        <div>
          <p>{t('Další krok s daty', 'Next evidence step')}</p>
          <strong>{insight.title}</strong>
          <span>{insight.detail}</span>
          {observedDescription && <small>{observedDescription}</small>}
        </div>
      </aside>
      {ordered.length === 0 ? (
        <p className="campaign-attention-empty">{t('Zatím nepřišla žádná přiřaditelná odezva z aplikace.', 'No attributable app response has arrived yet.')}</p>
      ) : (
        <div className="campaign-attention-grid">
          {ordered.map(row => (
            <article key={`${row.stepOrder}-${row.channel}-${row.surface}`} className="campaign-attention-card">
              <div className="campaign-attention-card-heading"><span>{t(`Krok ${row.stepOrder}`, `Step ${row.stepOrder}`)}</span><strong>{copy.surface[row.surface]}</strong><small>{copy.channel[row.channel]}</small></div>
              <div className="campaign-attention-metrics">
                <div><Eye aria-hidden="true" /><strong>{formatNumber(row.impressions)}</strong><span>{t('události zobrazení', 'impression events')}</span></div>
                <div><MousePointer2 aria-hidden="true" /><strong>{formatNumber(row.clicks)}</strong><span>{t('události otevření', 'click events')}</span></div>
                <div><X aria-hidden="true" /><strong>{formatNumber(row.dismissals)}</strong><span>{t('události zavření', 'dismiss events')}</span></div>
              </div>
              <p>{t('Poměr událostí otevření / zobrazení', 'Click-event / impression-event ratio')} <strong>{formatEventRatio(row.clicks, row.impressions)}</strong></p>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
