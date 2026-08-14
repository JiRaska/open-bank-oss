// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { JourneyCanvas } from '@/components/campaigns/JourneyCanvas'

/**
 * The journey is drawn as a flow because the send-log table is unreadable for the person deciding
 * whether a campaign is working. These pin the properties that make it readable — each easy to lose
 * in a later edit, and each one the table breaks on purpose.
 */

const STEPS = [
  { order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0, channel: 'PUSH' as const },
  {
    order: 2, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 172800, channel: 'BANNER' as const,
    condition: 'IF_PREVIOUS_CONFIRMED' as const, conditionSourceOrder: 0,
  },
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
  { stepOrder: 2, reached: 600, delivered: 420, failed: 0, skipped: 180, suppressed: [] },
]

function renderFlow(funnel = FUNNEL, audience: number | null = 1000) {
  return render(
    React.createElement(
      LanguageProvider,
      null,
      React.createElement(JourneyCanvas, { steps: STEPS, funnel, audienceSize: audience }),
    ),
  )
}

describe('journey canvas', () => {
  /**
   * The whole point. `SUPPRESSED_QUIET_HOURS` is a value the API returns; a marketer needs
   * "quiet hours". If an enum reaches the visible text this has become the send-log table with
   * extra steps.
   */
  it('never puts a raw enum on the screen', () => {
    const { container } = renderFlow()

    expect(container.textContent).not.toMatch(/SUPPRESSED_/)
    expect(container.textContent).not.toMatch(/\bFAILED\b/)
    expect(screen.getByText(/No consent/)).toBeTruthy()
    expect(screen.getByText(/Quiet hours/)).toBeTruthy()
    expect(screen.getByText(/Delivery failed/)).toBeTruthy()
  })

  /**
   * …but the raw value must stay reachable, or the screen and the API can drift apart with nobody
   * noticing. It lives in a data attribute rather than an SVG <title>, because a <title> inside
   * <text> IS text content and would defeat the assertion above.
   */
  it('keeps the raw outcome reachable for whoever is debugging', () => {
    const { container } = renderFlow()

    expect(container.querySelector('[data-outcome="SUPPRESSED_CONSENT"]')).toBeTruthy()
    expect(container.querySelector('[data-outcome="SUPPRESSED_QUIET_HOURS"]')).toBeTruthy()
    expect(container.querySelector('[data-outcome="FAILED"]')).toBeTruthy()
  })

  it('states what entered, what was delivered, and what each branch lost', () => {
    renderFlow()

    expect(screen.getAllByText('1,000').length).toBeGreaterThan(0)
    expect(screen.getByText('600')).toBeTruthy()
    expect(screen.getByText('300')).toBeTruthy()
  })

  /**
   * A campaign that has not run yet must say so. An empty flow drawn with zeroes reads as "nobody
   * matched", which is a business conclusion and the opposite of the truth.
   */
  it('says nothing has entered rather than drawing zeroes', () => {
    renderFlow([], null)

    expect(screen.getByText(/Nobody has entered yet/)).toBeTruthy()
  })

  /** The wait between steps is part of the design; seconds are not a unit anyone reads. */
  it('renders the delay between steps in human units', () => {
    renderFlow()

    expect(screen.getByText(/wait 2 d/)).toBeTruthy()
    expect(screen.queryByText(/172800/)).toBeNull()
  })

  /**
   * The entry number is the segment size, stated as such. Without that it reads as the number of
   * people who got the campaign — the single most expensive misreading on this screen.
   */
  it('labels the entry number as a segment, not as reach', () => {
    renderFlow()

    expect(screen.getByText(/people in segment/)).toBeTruthy()
  })

  it('shows the reviewed decision and separates its other path from delivery loss', () => {
    const { container } = renderFlow()

    expect(screen.getByText(/step 1 delivered/)).toBeTruthy()
    expect(screen.getByText(/180 took another path/)).toBeTruthy()
    expect(container.querySelector('[data-branch="IF_PREVIOUS_CONFIRMED"]')).toBeTruthy()
    expect(screen.getByText(/STEP 1 · push/)).toBeTruthy()
    expect(screen.getByText(/STEP 2 · banner/)).toBeTruthy()
  })

  it('draws declared graph edges rather than inventing a linear path, and keeps missing evidence unknown', () => {
    const graphSteps = [
      { order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0, channel: 'PUSH' as const },
      { order: 2, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0, channel: 'BANNER' as const },
      { order: 3, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0, channel: 'BANNER' as const },
    ]
    const { container } = render(
      React.createElement(
        LanguageProvider,
        null,
        React.createElement(JourneyCanvas, {
          steps: graphSteps,
          funnel: [],
          audienceSize: null,
          decisions: [{ sourceStepOrder: 1, evaluationDelaySeconds: 86_400, confirmedStepOrder: 2, notConfirmedStepOrder: 3 }],
          decisionPaths: [],
          decisionPathsKnown: false,
        }),
      ),
    )

    expect(container.querySelector('[data-graph-edge="1-confirmed"]')).toBeTruthy()
    expect(container.querySelector('[data-graph-edge="1-not-confirmed"]')).toBeTruthy()
    expect(container.querySelector('[data-linear-edge]')).toBeNull()
    expect(screen.getByText(/Branch outcomes are unavailable right now/)).toBeTruthy()
    expect(screen.queryByText(/0× confirmed/)).toBeNull()
  })
})
