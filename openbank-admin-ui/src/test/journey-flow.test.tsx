// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { JourneyFlow } from '@/components/campaigns/JourneyFlow'

/**
 * The journey view exists because the send-log table is unreadable for the person who decides
 * whether a campaign is working. These pin the three properties that make it readable — each of
 * which is easy to lose in a later edit precisely because the table breaks all three on purpose.
 */

const STEPS = [
  { order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0 },
  { order: 2, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 172800 },
]

const FUNNEL = [
  {
    stepOrder: 1,
    reached: 1000,
    delivered: 600,
    failed: 10,
    suppressed: [
      { reason: 'SUPPRESSED_CONSENT', count: 300 },
      { reason: 'SUPPRESSED_QUIET_HOURS', count: 90 },
    ],
  },
]

function renderFlow(funnel = FUNNEL, audience: number | null = 1000) {
  return render(
    React.createElement(
      LanguageProvider,
      null,
      React.createElement(JourneyFlow, { steps: STEPS, funnel, audienceSize: audience }),
    ),
  )
}

describe('journey flow', () => {
  /**
   * The whole point of the screen. `SUPPRESSED_QUIET_HOURS` is a value the API returns; a marketer
   * needs "it was night". If an enum leaks into the visible text, this view has become the send-log
   * table with extra steps.
   */
  it('never puts a raw enum on the screen', () => {
    const { container } = renderFlow()

    expect(container.textContent).not.toMatch(/SUPPRESSED_/)
    expect(container.textContent).not.toMatch(/\bFAILED\b/)
    expect(screen.getByText(/No marketing consent/)).toBeTruthy()
    expect(screen.getByText(/Quiet hours/)).toBeTruthy()
    expect(screen.getByText(/Delivery failed/)).toBeTruthy()
  })

  /**
   * …but the raw value must stay reachable, or the screen and the API can drift apart with nobody
   * noticing. Relabelling is a translation, not a replacement.
   */
  it('keeps the raw outcome reachable for whoever is debugging', () => {
    renderFlow()

    expect(screen.getAllByTitle('SUPPRESSED_CONSENT').length).toBeGreaterThan(0)
    expect(screen.getAllByTitle('SUPPRESSED_QUIET_HOURS').length).toBeGreaterThan(0)
    expect(screen.getAllByTitle('FAILED').length).toBeGreaterThan(0)
  })

  it('states the delivered share against what the step reached', () => {
    renderFlow()

    expect(screen.getByText(/600/)).toBeTruthy()
    expect(screen.getByText(/delivered of/)).toBeTruthy()
    expect(screen.getByText(/60 %/)).toBeTruthy()
  })

  /**
   * A later step still waiting out its delay has reached nobody. Rendering that as "0 delivered of
   * 0" puts three numbers on screen that look like a result and are the absence of one — and on a
   * multi-step journey that is most of the screen.
   */
  it('says a step has reached nobody rather than printing zeroes', () => {
    renderFlow()

    expect(screen.getByText(/nobody yet/)).toBeTruthy()
    // …and exactly one step reports a real result, so the zero row cannot be mistaken for one.
    expect(screen.getAllByText(/delivered of/)).toHaveLength(1)
  })

  /**
   * A campaign that has not run yet must say so. An empty funnel rendered as bars reads as "nobody
   * matched", which is a business conclusion, and the opposite of the truth.
   */
  it('says nothing has run rather than drawing an empty funnel', () => {
    renderFlow([], null)

    expect(screen.getByText(/has not reached anyone yet/)).toBeTruthy()
  })

  /** The wait between steps is part of the design; seconds are not a unit anyone reads. */
  it('renders the delay between steps in human units', () => {
    renderFlow()

    expect(screen.getByText(/after 2 d/)).toBeTruthy()
    expect(screen.queryByText(/172800/)).toBeNull()
  })

  /**
   * The audience number is stated as pre-consent, pre-suppression. Without that it reads as the
   * number of people who got the campaign, which is the single most expensive misreading here.
   */
  it('qualifies the audience number so it cannot be read as reach', () => {
    renderFlow()

    expect(screen.getByText(/before consent checks and suppression/)).toBeTruthy()
  })
})
