// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { CampaignExperiencePreview } from '@/components/campaigns/CampaignExperiencePreview'
import { CampaignLaunchReadiness } from '@/components/campaigns/CampaignLaunchReadiness'
import type { EditorStep } from '@/components/campaigns/JourneyEditor'

const push: EditorStep = {
  channel: 'PUSH',
  template: 'MARKETING_PRODUCT_OFFER_PUSH',
  variables: { offerTitle: 'Savings at 4%' },
  delaySeconds: 0,
  mobileDestination: 'SAVINGS',
}

describe('campaign customer experience preview', () => {
  it('renders a push with its selected closed app destination', () => {
    const { container } = render(
      <LanguageProvider><CampaignExperiencePreview step={push} campaignName="Summer saving" /></LanguageProvider>,
    )

    // The headline intentionally appears in the notification and again in the authenticated app
    // destination. One occurrence would test only half of the customer sequence.
    expect(screen.getAllByText('Savings at 4%')).toHaveLength(2)
    expect(screen.getByText(/Summer saving → Savings/)).toBeTruthy()
    expect(container.querySelector('[data-preview-channel="PUSH"]')).toBeTruthy()
    expect(screen.getByText('openbank://savings')).toBeTruthy()
  })

  it('does not pretend an email is a push notification', () => {
    const { container } = render(
      <LanguageProvider><CampaignExperiencePreview step={{ ...push, channel: 'EMAIL', mobileDestination: undefined }} campaignName="" /></LanguageProvider>,
    )

    expect(container.querySelector('[data-preview-channel="EMAIL"]')).toBeTruthy()
    expect(screen.getByText(/Choose a push step/)).toBeTruthy()
  })

  it('shows a banner as an authenticated app surface, not as a notification', () => {
    const { container } = render(
      <LanguageProvider>
        <CampaignExperiencePreview
          step={{ ...push, channel: 'BANNER', template: 'MARKETING_PRODUCT_OFFER_BANNER' }}
          campaignName="Summer saving"
        />
      </LanguageProvider>,
    )

    expect(container.querySelector('[data-preview-channel="BANNER"]')).toBeTruthy()
    expect(screen.getByText(/Banner in the signed-in app/)).toBeTruthy()
    expect(screen.getByText(/No interruption, no lock screen/)).toBeTruthy()
  })
})

describe('campaign launch readiness', () => {
  it('names missing inputs and preserves policy as a non-optional guardrail', () => {
    const { container } = render(
      <LanguageProvider>
        <CampaignLaunchReadiness
          audienceChosen={false}
          audienceSize={null}
          entryConfigured={false}
          incomplete
          conversionRule={null}
          contentExperiment={false}
          steps={[{ ...push, channel: 'EMAIL', mobileDestination: undefined }]}
        />
      </LanguageProvider>,
    )

    expect(container.querySelector('[data-readiness="audience"][data-state="waiting"]')).toBeTruthy()
    expect(container.querySelector('[data-readiness="content"][data-state="waiting"]')).toBeTruthy()
    expect(container.querySelector('[data-readiness="policy"][data-state="ready"]')).toBeTruthy()
    expect(screen.getByText(/Journey has no in-app step/)).toBeTruthy()
  })
})
