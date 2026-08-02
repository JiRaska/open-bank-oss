// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { PeopleSummary } from '@/components/campaigns/PeopleSummary'

const ENROLMENTS = [
  { id: 'a', partyId: '05a02ef1-381c-40e7-b73f-d6855eead42e', state: 'TERMINATED_SUPPRESSED', currentStep: 0 },
  { id: 'b', partyId: '026289e3-0b80-452a-be01-e69034838549', state: 'TERMINATED_SUPPRESSED', currentStep: 0 },
  { id: 'c', partyId: '5e3d6411-cb5b-4e94-83f8-de95495ef5dd', state: 'TERMINATED_CONSENT_REVOKED', currentStep: 1 },
  { id: 'd', partyId: '7a1b2c3d-0000-0000-0000-000000000000', state: 'ACTIVE', currentStep: 1 },
]

const show = (rows = ENROLMENTS) =>
  render(React.createElement(LanguageProvider, null, React.createElement(PeopleSummary, { enrolments: rows })))

describe('people summary', () => {
  /**
   * The reason this component exists. A per-person table of party UUIDs answers "what happened to
   * 05a02ef1" — a lookup you do when someone complains. The screen is opened to answer "how many
   * are still running, how many stopped, and why", and showing the lookup by default made the page
   * read as a database dump.
   */
  it('leads with counts by state, in words', () => {
    show()

    // The label appears twice on purpose — once on the count tile, once on the row badge in the
    // collapsed detail — so assert the TILE, which is what leads the section.
    expect(screen.getByText('2')).toBeTruthy()
    expect(screen.getAllByText(/Stopped by a rule/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Withdrew consent/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Still in the journey/).length).toBeGreaterThan(0)
  })

  it('never shows a state enum as visible text', () => {
    const { container } = show()

    expect(container.textContent).not.toMatch(/TERMINATED_/)
    expect(container.textContent).not.toMatch(/\bACTIVE\b/)
  })

  /**
   * …but the enum stays queryable, so the screen and the API cannot drift apart unnoticed — the
   * same rule the journey canvas follows with `data-outcome`.
   */
  it('keeps the raw state reachable for whoever is debugging', () => {
    const { container } = show()

    expect(container.querySelector('[data-state="TERMINATED_CONSENT_REVOKED"]')).toBeTruthy()
    expect(container.querySelector('[data-state="ACTIVE"]')).toBeTruthy()
  })

  /**
   * The per-person rows are the only place an individual case can be traced, so they are one click
   * away rather than gone — and named for what they are, so nobody mistakes them for the overview.
   */
  it('keeps the per-person rows behind a disclosure, labelled as debugging', () => {
    const { container } = show()

    const details = container.querySelector('details')
    expect(details).toBeTruthy()
    expect(details?.hasAttribute('open')).toBe(false)
    expect(screen.getByText(/for debugging/)).toBeTruthy()
  })

  it('says nobody is enrolled rather than rendering an empty table', () => {
    show([])

    expect(screen.getByText(/Nobody has been enrolled yet/)).toBeTruthy()
  })
})
