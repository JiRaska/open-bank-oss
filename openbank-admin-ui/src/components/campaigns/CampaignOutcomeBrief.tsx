// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * The campaign detail's decision layer.
 *
 * This uses the campaign service's whole-campaign aggregates, never the visible page of send rows.
 * "Handed off" is intentionally not called delivered: Campaign-service knows it accepted a send
 * request, while notification-service owns the later delivery confirmation. The distinction is
 * critical on a marketing screen because a successful-looking number otherwise becomes a promise
 * about a customer's device.
 */
export function CampaignOutcomeBrief({
  state,
  audience,
  handedOff,
  suppressed,
  conversion,
  conversionLabel,
  nextAction,
  nextActionDetail,
}: {
  state: string
  audience: number
  handedOff: number
  suppressed: number
  /** Null means the campaign is not configured to measure an outcome. */
  conversion: number | null
  conversionLabel?: string
  nextAction: string
  nextActionDetail: string
}) {
  const { t, language } = useLanguage()
  const metrics = [
    { label: t('Publikum', 'Audience'), value: audience, detail: t('zařazeno', 'enrolled') },
    { label: t('Předáno', 'Handed off'), value: handedOff, detail: t('notification službě', 'to notification service') },
    { label: t('Chráněno', 'Protected'), value: suppressed, detail: t('potlačeno pravidlem', 'suppressed by policy') },
  ]

  return (
    <section className="campaign-outcome-brief" aria-labelledby="campaign-outcome-title" data-testid="campaign-outcome-brief">
      <div className="campaign-outcome-main">
        <p className="campaign-preview-kicker">{t('Pulse kampaně', 'Campaign pulse')}</p>
        <div className="campaign-outcome-title-row">
          <h2 id="campaign-outcome-title">{t('Další rozhodnutí opřete o data', 'Make the next decision with evidence')}</h2>
          <span data-campaign-state={state}>{state}</span>
        </div>
        <div className="campaign-outcome-metrics">
          {metrics.map(metric => (
            <div key={metric.label}>
              <strong>{metric.value.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong>
              <span>{metric.label}</span>
              <small>{metric.detail}</small>
            </div>
          ))}
          <div className="campaign-outcome-conversion" data-conversion={conversion === null ? 'unmeasured' : 'measured'}>
            <strong>{conversion === null ? '—' : conversion.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</strong>
            <span>{conversionLabel ?? t('Výsledek', 'Outcome')}</span>
            <small>{conversion === null ? t('neměřeno', 'not measured') : t('během zařazení', 'while enrolled')}</small>
          </div>
        </div>
        <p className="campaign-outcome-evidence">{t('Jen provoz kampaně — in-app engagement se této kampani zatím nepřipisuje.', 'Campaign operations only — no in-app engagement is attributed to this campaign yet.')}</p>
      </div>
      <aside className="campaign-outcome-action" data-testid="campaign-outcome-next-action">
        <p>{t('Další nejlepší akce', 'Next best action')}</p>
        <strong>{nextAction}</strong>
        <span>{nextActionDetail}</span>
      </aside>
    </section>
  )
}
