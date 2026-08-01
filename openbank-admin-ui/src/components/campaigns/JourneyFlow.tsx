// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Tone } from '@/components/ui/tone'

/**
 * The journey as a marketer reads it: who the campaign reached at each step, and where everyone
 * else went.
 *
 * The send log answers "what happened to party X at step 2" and is the right screen for an engineer
 * holding an incident. It is the wrong screen for deciding whether a campaign is working, because
 * the question there is never about one row — it is "how many, and why not more". This renders the
 * same data as flow and proportion, which is the form that question is actually asked in.
 *
 * Three rules it follows, each of which the send-log table breaks on purpose:
 *
 *  - **No enum ever reaches the screen.** `SUPPRESSED_QUIET_HOURS` is a value the API returns; what
 *    a marketer needs is "it was night — we do not email people at night". The raw value stays in
 *    `title` so the screen and the API can never be describing different things.
 *  - **Every number is a server-side aggregate.** A funnel is read as the whole picture by
 *    definition, so one folded from a loaded page would understate every campaign larger than that
 *    page while looking exactly as authoritative.
 *  - **A drop-off is never rendered as failure.** Most of these are the platform doing its job —
 *    consent withheld, quiet hours, frequency cap. Colouring them red teaches people to ignore red,
 *    and the one that IS a failure (`FAILED`) then reads like the others.
 *
 * Colour comes from the ADR-0208 D2 tone vocabulary, never a literal — see `tone.ts`.
 */

export interface SuppressionCount {
  reason: string
  count: number
}

export interface StepFunnel {
  stepOrder: number
  reached: number
  delivered: number
  failed: number
  suppressed: SuppressionCount[]
}

export interface JourneyStep {
  order: number
  template: string
  delaySeconds: number
}

/** Colour by MEANING, not by severity: a suppression is the platform working, not an error. */
const REASON_TONE: Record<string, Tone> = {
  SUPPRESSED_CONSENT: 'info',
  SUPPRESSED_QUIET_HOURS: 'neutral',
  SUPPRESSED_CAP: 'warning',
  FAILED: 'danger',
}

export function JourneyFlow({
  steps,
  funnel,
  audienceSize,
}: {
  steps: JourneyStep[]
  funnel: StepFunnel[]
  audienceSize: number | null
}) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const n = (v: number) => v.toLocaleString(locale)

  /** Plain language, and never the enum. The raw value travels in `title` instead. */
  const reasonLabel = (reason: string): string =>
    ({
      SUPPRESSED_CONSENT: t('Bez marketingového souhlasu', 'No marketing consent'),
      SUPPRESSED_QUIET_HOURS: t('Tiché hodiny (21–8)', 'Quiet hours (21–8)'),
      SUPPRESSED_CAP: t('Frekvenční strop', 'Frequency cap'),
      FAILED: t('Nepodařilo se odeslat', 'Delivery failed'),
    })[reason] ?? reason

  /** "hned" reads better than "za 0 dní", and a delay in seconds is not a human unit. */
  const delayLabel = (seconds: number): string => {
    if (seconds <= 0) return t('hned', 'immediately')
    const days = Math.floor(seconds / 86400)
    if (days >= 1) return t(`za ${days} d`, `after ${days} d`)
    const hours = Math.floor(seconds / 3600)
    if (hours >= 1) return t(`za ${hours} h`, `after ${hours} h`)
    return t(`za ${Math.floor(seconds / 60)} min`, `after ${Math.floor(seconds / 60)} min`)
  }

  /** Templates are catalogue ids; a marketer picked one by its meaning, so show that. */
  const templateLabel = (template: string): string =>
    ({
      MARKETING_PRODUCT_OFFER: t('Nabídka produktu', 'Product offer'),
    })[template] ?? template

  const byStep = new Map(funnel.map(f => [f.stepOrder, f]))
  const ordered = [...steps].sort((a, b) => a.order - b.order)

  // Nothing has run yet: say so, rather than drawing an empty funnel that reads as "nobody matched".
  const anyActivity = funnel.some(f => f.reached > 0)

  return (
    <div className="space-y-4">
      {audienceSize !== null && (
        <div className="flex flex-wrap items-baseline gap-2 text-sm">
          <span className="text-muted-foreground">{t('Publikum', 'Audience')}</span>
          <strong className="text-lg tabular-nums">{n(audienceSize)}</strong>
          <span className="text-xs text-muted-foreground">
            {t(
              '— velikost segmentu před ověřením souhlasu a potlačením',
              '— segment size, before consent checks and suppression',
            )}
          </span>
        </div>
      )}

      {!anyActivity && (
        <p className="text-sm text-muted-foreground">
          {t(
            'Kampaň zatím nikoho neoslovila — po zařazení publika se tu objeví průchod jednotlivými kroky.',
            'The campaign has not reached anyone yet — once the audience is enrolled, the per-step flow appears here.',
          )}
        </p>
      )}

      <ol className="space-y-3">
        {ordered.map((step, i) => {
          const f = byStep.get(step.order)
          const reached = f?.reached ?? 0
          const delivered = f?.delivered ?? 0
          const drops: SuppressionCount[] = [
            ...(f?.suppressed ?? []),
            ...(f && f.failed > 0 ? [{ reason: 'FAILED', count: f.failed }] : []),
          ]
          const pct = (v: number) => (reached > 0 ? Math.round((v / reached) * 100) : 0)

          return (
            <li key={step.order}>
              {i > 0 && (
                // The wait between steps is part of the journey a marketer designed, so it is drawn
                // rather than listed in a column they have to correlate by eye.
                <div className="flex items-center gap-2 py-1 pl-4 text-xs text-muted-foreground">
                  <span aria-hidden className="inline-block h-4 w-px bg-border" />
                  {delayLabel(step.delaySeconds)}
                </div>
              )}

              <div className="rounded-lg border p-4">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <div className="flex items-baseline gap-2">
                    <span className="text-xs text-muted-foreground">
                      {t('Krok', 'Step')} {step.order}
                    </span>
                    <span className="font-medium" title={step.template}>
                      {templateLabel(step.template)}
                    </span>
                    <span className="text-xs text-muted-foreground">{t('e-mailem', 'by email')}</span>
                  </div>
                  {/* A step nobody has reached says so. "0 doručeno z 0" is three numbers that
                      look like a result and are the absence of one — and on a journey whose later
                      steps are still waiting out their delay, that is most of the screen. */}
                  {reached === 0 ? (
                    <span className="text-xs text-muted-foreground">
                      {t('zatím nikoho', 'nobody yet')}
                    </span>
                  ) : (
                    <div className="text-sm">
                      <strong className="tabular-nums">{n(delivered)}</strong>
                      <span className="text-muted-foreground">
                        {' '}
                        {t('doručeno z', 'delivered of')} {n(reached)}
                      </span>
                      <span className="ml-2 text-xs text-muted-foreground"> ({pct(delivered)} %)</span>
                    </div>
                  )}
                </div>

                {reached > 0 && (
                  <>
                    {/* Proportion first, numbers second: "most of them were asleep" is the shape of
                        the answer, and a table of counts makes you compute it yourself. */}
                    <div
                      className="funnel-bar mt-3"
                      role="img"
                      aria-label={t(
                        `Krok ${step.order}: doručeno ${delivered} z ${reached}`,
                        `Step ${step.order}: ${delivered} delivered of ${reached}`,
                      )}
                    >
                      {delivered > 0 && (
                        <span
                          className="funnel-seg funnel-seg-success"
                          style={{ width: `${pct(delivered)}%` }}
                          title={`SENT — ${delivered}`}
                        />
                      )}
                      {drops.map(d => (
                        <span
                          key={d.reason}
                          className={`funnel-seg funnel-seg-${REASON_TONE[d.reason] ?? 'neutral'}`}
                          style={{ width: `${pct(d.count)}%` }}
                          title={`${d.reason} — ${d.count}`}
                        />
                      ))}
                    </div>

                    {drops.length > 0 && (
                      <ul className="mt-3 flex flex-wrap gap-2">
                        {drops.map(d => (
                          <li
                            key={d.reason}
                            className={`badge badge-${REASON_TONE[d.reason] ?? 'neutral'} text-xs`}
                            // The enum stays reachable so the screen and the API can never drift
                            // apart without someone noticing.
                            title={d.reason}
                          >
                            {n(d.count)} × {reasonLabel(d.reason)}
                          </li>
                        ))}
                      </ul>
                    )}
                  </>
                )}
              </div>
            </li>
          )
        })}
      </ol>
    </div>
  )
}
