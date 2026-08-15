// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import React from 'react'
import { render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { CampaignPlanningBoard } from '@/components/campaigns/CampaignPlanningBoard'

function renderBoard(items: React.ComponentProps<typeof CampaignPlanningBoard>['items'], state: 'loading' | 'ok' | 'unavailable' = 'ok') {
  return render(React.createElement(LanguageProvider, null, React.createElement(CampaignPlanningBoard, { items, state })))
}

describe('CampaignPlanningBoard', () => {
  it('shows only an active declared cadence as a next window and calls it a window, not delivery', () => {
    renderBoard([
      {
        campaignId: 'live', name: 'Savings pulse', state: 'ACTIVE', entry: 'SCHEDULED',
        cadence: 'DAILY_MORNING', cadenceHumanForm: 'every day at 09:00', zone: 'Europe/Prague',
        nextScheduledWindowAt: '2026-02-04T08:00:00Z',
      },
      {
        campaignId: 'review', name: 'Card offer', state: 'PENDING_APPROVAL', entry: 'SCHEDULED',
        cadence: 'WEEKLY_MONDAY_MORNING', cadenceHumanForm: 'every Monday at 09:00', zone: 'Europe/Prague',
      },
      { campaignId: 'event', name: 'Welcome', state: 'ACTIVE', entry: 'EVENT', trigger: 'ACCOUNT_OPENED' },
    ])

    expect(screen.getByTestId('campaign-planning-board').textContent).toMatch(/Savings pulse.*every day at 09:00/)
    expect(screen.getByTestId('campaign-planning-board').textContent).toMatch(/Not live yet.*1/)
    expect(screen.getByTestId('campaign-planning-board').textContent).toMatch(/declared cadence windows.*not promised sends/i)
    expect(document.querySelector('[data-plan-campaign="review"]')).toBeNull()
  })

  it('renders an unavailable planning source as unknown rather than an empty schedule', () => {
    renderBoard([], 'unavailable')
    expect(screen.getByTestId('campaign-plan-unavailable').textContent).toMatch(/does not mean nothing is running/i)
  })

  it('does not call an active schedule without another window not live yet', () => {
    renderBoard([{
      campaignId: 'ended', name: 'Closed cadence', state: 'ACTIVE', entry: 'SCHEDULED',
      cadence: 'DAILY_MORNING', cadenceHumanForm: 'every day at 09:00', endAt: '2026-02-03T08:00:00Z',
    }])

    expect(screen.getByTestId('campaign-plan-no-next-window').textContent).toMatch(/no next declared window/i)
    expect(screen.getByTestId('campaign-planning-board').textContent).toMatch(/Not live yet.*0/)
  })
})
