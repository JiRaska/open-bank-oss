// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CampaignOutcomeBrief } from '@/components/campaigns/CampaignOutcomeBrief'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

describe('campaign outcome brief', () => {
  it('distinguishes handed-off sends from a measured outcome', () => {
    render(
      <LanguageProvider><CampaignOutcomeBrief
          state="ACTIVE"
          audience={1240}
          handedOff={1110}
          suppressed={96}
          conversion={48}
          conversionLabel="Account opened"
          nextAction="Follow the journey and outcome"
          nextActionDetail="Delivery and conversion are different facts."
        /></LanguageProvider>,
    )

    expect(screen.getByText('Handed off')).toBeTruthy()
    expect(screen.getByText('to notification service')).toBeTruthy()
    expect(screen.getByText('Account opened')).toBeTruthy()
    expect(screen.getByText(/no in-app engagement is attributed/)).toBeTruthy()
  })

  it('does not show a zero outcome when the campaign measures nothing', () => {
    const { container } = render(
      <LanguageProvider><CampaignOutcomeBrief
          state="ACTIVE"
          audience={12}
          handedOff={10}
          suppressed={2}
          conversion={null}
          conversionLabel="Outcome is not measured"
          nextAction="Consider a measurable outcome"
          nextActionDetail="The journey can still run."
        /></LanguageProvider>,
    )

    expect(container.querySelector('[data-conversion="unmeasured"]')).toBeTruthy()
    expect(screen.getByText('not measured')).toBeTruthy()
  })
})
