// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { EditorStep } from '@/components/campaigns/JourneyEditor'

type ReadinessState = 'ready' | 'attention' | 'waiting'

interface ReadinessItem {
  id: string
  state: ReadinessState
  label: string
  detail: string
}

/**
 * Turns platform constraints into a launch conversation at the point of authoring.
 *
 * A disabled Create button is technically correct but tells a marketer neither what is missing nor
 * whether a policy will still change the reachable audience. This list has no hidden validation:
 * every item is derived from the same state sent to campaign-service, and policy remains explicitly
 * read-only rather than offering a fake override.
 */
export function CampaignLaunchReadiness({
  audienceChosen,
  audienceSize,
  entryConfigured,
  incomplete,
  conversionRule,
  contentExperiment,
  steps,
}: {
  audienceChosen: boolean
  audienceSize: number | null
  entryConfigured: boolean
  incomplete: boolean
  conversionRule: string | null
  contentExperiment: boolean
  steps: EditorStep[]
}) {
  const { t, language } = useLanguage()
  const n = (value: number) => value.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')
  const push = steps.find(step => step.channel === 'PUSH')
  const readyCount = [audienceChosen, entryConfigured, !incomplete, !contentExperiment || conversionRule !== null].filter(Boolean).length

  const items: ReadinessItem[] = [
    audienceChosen
      ? {
          id: 'audience', state: audienceSize === null ? 'attention' : 'ready',
          label: t('Publikum je vybrané', 'Audience is selected'),
          detail: audienceSize === null
            ? t('Dosah se ještě dopočítává.', 'Reach is still being calculated.')
            : t(`Před kontrolou souhlasu: přibližně ${n(audienceSize)} lidí.`, `Before consent checks: roughly ${n(audienceSize)} people.`),
        }
      : { id: 'audience', state: 'waiting', label: t('Vyberte publikum', 'Choose an audience'), detail: t('Segment určí, kdo může do cesty vstoupit.', 'A segment decides who may enter the journey.') },
    entryConfigured
      ? { id: 'entry', state: 'ready', label: t('Vstup do cesty je jasný', 'Journey entry is clear'), detail: t('Každý vstup je dohledatelný a přezkoumatelný.', 'Every entry is traceable and reviewable.') }
      : { id: 'entry', state: 'waiting', label: t('Vyberte, kdy cesta začne', 'Choose when the journey starts'), detail: t('Jednorázově, opakovaně nebo po události.', 'One time, recurring, or after an event.') },
    incomplete
      ? { id: 'content', state: 'waiting', label: t('Doplňte obsah kroku', 'Complete step content'), detail: t('Každý krok potřebuje všechny hodnoty své schválené šablony.', 'Every step needs all values from its approved template.') }
      : { id: 'content', state: 'ready', label: t('Cesta má kompletní obsah', 'Journey content is complete'), detail: t(`${steps.length} ${steps.length === 1 ? 'krok je' : 'kroky jsou'} připravené ke kontrole.`, `${steps.length} ${steps.length === 1 ? 'step is' : 'steps are'} ready for review.`) },
    contentExperiment && conversionRule === null
      ? { id: 'measurement', state: 'waiting', label: t('Experiment potřebuje měřitelný cíl', 'Experiment needs a measurable goal'), detail: t('Bez skutečné bankovní konverze by A/B srovnání nemělo výsledek.', 'Without a real banking conversion, A/B comparison has no outcome.') }
      : conversionRule
        ? { id: 'measurement', state: 'ready', label: t('Výsledek je měřitelný', 'Outcome is measurable'), detail: t('Měříme bankovní událost, ne domnělý proklik.', 'This measures a banking event, not an assumed click.') }
        : { id: 'measurement', state: 'attention', label: t('Kampaň neměří výsledek', 'Campaign does not measure an outcome'), detail: t('Můžete pokračovat, ale po spuštění nepůjde porovnat skutečný dopad.', 'You can continue, but the actual outcome cannot be compared after launch.') },
    push
      ? { id: 'destination', state: 'ready', label: t('Push má bezpečný cíl v aplikaci', 'Push has a safe in-app destination'), detail: t('Jen schválený deep link; žádná URL z kampaně.', 'An approved deep link only; no campaign-entered URL.') }
      : { id: 'destination', state: 'attention', label: t('Cesta zatím nemá push krok', 'Journey has no push step yet'), detail: t('Přidejte ho, pokud má kampaň přivést lidi zpět do aplikace.', 'Add one if the campaign should bring people back into the app.') },
    { id: 'policy', state: 'ready', label: t('Ochrana kontaktu zůstává zapnutá', 'Contact protection stays on'), detail: t('Souhlas, tiché hodiny a frekvenční limit se ověřují při doručení.', 'Consent, quiet hours and frequency limits are checked at delivery.') },
  ]

  return (
    <section className="campaign-launch-readiness" aria-labelledby="launch-readiness-title" data-testid="campaign-launch-readiness">
      <div className="campaign-readiness-heading">
        <div>
          <p className="campaign-preview-kicker">{t('Před spuštěním', 'Before launch')}</p>
          <h3 id="launch-readiness-title">{t('Připraveno na kontrolu?', 'Ready for review?')}</h3>
        </div>
        <strong>{readyCount}/4</strong>
      </div>
      <p className="campaign-readiness-intro">
        {t('Jeden pohled na to, co lidé uvidí a co může změnit skutečný dosah.', 'One view of what people see and what can change actual reach.')}
      </p>
      <ul className="campaign-readiness-list">
        {items.map(item => (
          <li key={item.id} data-readiness={item.id} data-state={item.state}>
            <span aria-hidden="true">{item.state === 'ready' ? '✓' : item.state === 'attention' ? '!' : '·'}</span>
            <div><strong>{item.label}</strong><p>{item.detail}</p></div>
          </li>
        ))}
      </ul>
    </section>
  )
}
