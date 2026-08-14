// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { BellRing, LayoutPanelTop, MailCheck, PanelsTopLeft, Sparkles } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { EditorStep } from '@/components/campaigns/JourneyEditor'

/**
 * A recipe is a starting shape for a bounded, linear journey — never a hidden automation or a
 * second campaign type. Marketers get the common app experiences in one click while every step
 * remains visible and editable on the canvas immediately below.
 *
 * The recipes intentionally carry no copy. The template contract remains the source of truth for
 * what an author must fill in, and choosing a recipe can therefore never create a sendable draft
 * by accident.
 */
export type JourneyRecipeId = 'RETURN_TO_APP' | 'IN_APP_DISCOVERY' | 'RESPONSIBLE_FALLBACK' | 'PRODUCT_DISCOVERY'

export interface JourneyRecipe {
  id: JourneyRecipeId
  steps: EditorStep[]
}

const empty = (): Record<string, string> => ({})

export const JOURNEY_RECIPES: JourneyRecipe[] = [
  {
    id: 'RETURN_TO_APP',
    steps: [{
      template: 'MARKETING_PRODUCT_OFFER_PUSH', channel: 'PUSH', variables: empty(),
      delaySeconds: 0, mobileDestination: 'HOME',
    }],
  },
  {
    id: 'IN_APP_DISCOVERY',
    steps: [
      {
        template: 'MARKETING_PRODUCT_OFFER_BANNER', channel: 'BANNER', variables: empty(),
        delaySeconds: 0, mobileDestination: 'HOME', inAppSurface: 'HOME_BANNER',
      },
      {
        template: 'MARKETING_PRODUCT_OFFER_CAROUSEL', channel: 'BANNER', variables: empty(),
        delaySeconds: 172800, mobileDestination: 'PRODUCT_HUB', inAppSurface: 'HOME_CAROUSEL',
      },
    ],
  },
  {
    id: 'RESPONSIBLE_FALLBACK',
    steps: [{
      template: 'MARKETING_PRODUCT_OFFER', channel: 'EMAIL', variables: empty(), delaySeconds: 0,
      fallbackToPush: true, mobileDestination: 'HOME',
    }],
  },
  {
    id: 'PRODUCT_DISCOVERY',
    steps: [
      {
        template: 'MARKETING_PRODUCT_OFFER_PRODUCT_FEED', channel: 'BANNER', variables: empty(),
        delaySeconds: 0, mobileDestination: 'PRODUCT_HUB', inAppSurface: 'PRODUCT_FEED',
      },
      {
        template: 'MARKETING_PRODUCT_OFFER_REWARDS_HUB', channel: 'BANNER', variables: empty(),
        delaySeconds: 259200, mobileDestination: 'HOME', inAppSurface: 'REWARDS_HUB',
      },
    ],
  },
]

const visual: Record<JourneyRecipeId, { Icon: typeof BellRing; tone: string }> = {
  RETURN_TO_APP: { Icon: BellRing, tone: 'violet' },
  IN_APP_DISCOVERY: { Icon: PanelsTopLeft, tone: 'amber' },
  RESPONSIBLE_FALLBACK: { Icon: MailCheck, tone: 'blue' },
  PRODUCT_DISCOVERY: { Icon: LayoutPanelTop, tone: 'emerald' },
}

export function JourneyRecipePicker({
  selected,
  onApply,
}: {
  selected: JourneyRecipeId | null
  onApply: (recipe: JourneyRecipe) => void
}) {
  const { t } = useLanguage()
  const copy: Record<JourneyRecipeId, { title: string; detail: string; channels: string }> = {
    RETURN_TO_APP: {
      title: t('Přivést zpět do aplikace', 'Bring people back to the app'),
      detail: t('Jedna push notifikace s bezpečným cílem v aplikaci.', 'One app push with a safe in-app destination.'),
      channels: t('Push', 'Push'),
    },
    IN_APP_DISCOVERY: {
      title: t('Objevit v aplikaci', 'Discover in the app'),
      detail: t('Domovský banner a za dva dny navazující carousel.', 'A home banner followed by a carousel two days later.'),
      channels: t('Banner · Carousel', 'Banner · Carousel'),
    },
    RESPONSIBLE_FALLBACK: {
      title: t('E-mail s bezpečným fallbackem', 'Email with safe fallback'),
      detail: t('E-mail; pouze při chybějícím souhlasu se ověří samostatný push.', 'Email; only absent email consent may try a separately checked push.'),
      channels: t('E-mail · Push fallback', 'Email · Push fallback'),
    },
    PRODUCT_DISCOVERY: {
      title: t('Objevování produktů', 'Product discovery'),
      detail: t('Produktový feed a pozdější připomenutí v centru odměn.', 'Product feed followed by a later rewards-hub reminder.'),
      channels: t('Produktový feed · Odměny', 'Product feed · Rewards'),
    },
  }

  return (
    <section className="campaign-recipe-picker" aria-labelledby="journey-recipe-title">
      <div className="campaign-recipe-heading">
        <div>
          <p><Sparkles className="h-3.5 w-3.5" /> {t('Začněte osvědčeným vzorem', 'Start with a proven pattern')}</p>
          <h2 id="journey-recipe-title">{t('Jaký zážitek chceme vytvořit?', 'What experience do we want to create?')}</h2>
        </div>
        <span>{t('Každý krok pak upravíte na plátně.', 'Every step stays editable on the canvas.')}</span>
      </div>
      <div className="campaign-recipe-grid">
        {JOURNEY_RECIPES.map(recipe => {
          const item = copy[recipe.id]
          const { Icon, tone } = visual[recipe.id]
          const active = recipe.id === selected
          return (
            <button
              key={recipe.id}
              type="button"
              className="campaign-recipe-card"
              data-journey-recipe={recipe.id}
              data-selected={active ? 'true' : 'false'}
              data-tone={tone}
              onClick={() => onApply(recipe)}
            >
              <span className="campaign-recipe-icon"><Icon className="h-5 w-5" /></span>
              <span className="campaign-recipe-copy">
                <strong>{item.title}</strong>
                <small>{item.detail}</small>
              </span>
              <span className="campaign-recipe-channel">{item.channels}</span>
            </button>
          )
        })}
      </div>
    </section>
  )
}
