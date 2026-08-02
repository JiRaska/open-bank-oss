// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * The campaign builder driven the way a marketer drives it.
 *
 * The rest of the suite asserts what the screen RENDERS. Everything here is behaviour only a click
 * reaches — and two of these were written wrong first: `addStep` selects the step it just added, so
 * a follow-up click on that node CLOSES the panel instead of opening it. That toggle is the
 * intended design, so it is asserted here rather than left as a thing the next edit can lose.
 */

import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, waitFor, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import NewCampaignPage from '@/app/campaigns/new/page'
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const stub = () => vi.stubGlobal('fetch', vi.fn(async (u: string) =>
  String(u).includes('/preview')
    ? { ok: true, json: async () => ({ size: 1240, state: 'ok' }) }
    : { ok: true, json: async () => ({ state: 'ok', items: [
        { name: 'active-clients', version: 1, rules: ['party status is ACTIVE'] },
        { name: 'savers', version: 2, rules: ['has a savings account', 'balance over 50 000 CZK'] }] }) }))

describe('campaign builder interaction', () => {
  it('adds steps up to the domain cap, and the add affordance then disappears', async () => {
    stub()
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('savers'), { timeout: 8000 })
    expect(container.querySelectorAll('[data-step]').length).toBe(1)
    for (let i = 0; i < 4; i++) fireEvent.click(container.querySelector('[data-add-step]')!)
    expect(container.querySelectorAll('[data-step]').length).toBe(5)
    // The cap is a domain rule (Campaign.MAX_STEPS); the control must be gone, not merely inert.
    expect(container.querySelector('[data-add-step]')).toBeNull()
    expect(getByText(/5 steps is the maximum/)).toBeTruthy()
  }, 20000)

  it('removing a step closes the editor rather than leaving it on a node that is gone', async () => {
    stub()
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('savers'), { timeout: 8000 })
    fireEvent.click(container.querySelector('[data-add-step]')!)
    // A newly added step opens itself — you added it to fill it in.
    expect(container.querySelector('[data-step-editor="1"]')).toBeTruthy()
    fireEvent.click(container.querySelector('[data-remove-step="1"]')!)
    expect(container.querySelectorAll('[data-step]').length).toBe(1)
    expect(container.querySelector('[data-step-editor="1"]')).toBeNull()
  }, 20000)

  it('selecting a node opens that node, and the reach follows the chosen audience', async () => {
    stub()
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('savers'), { timeout: 8000 })
    fireEvent.click(container.querySelector('[data-segment="savers@2"]')!)
    await waitFor(() => getByText(/1240 people/), { timeout: 8000 })
    fireEvent.click(container.querySelector('[data-add-step]')!)
    fireEvent.click(container.querySelector('[data-add-step]')!)
    expect(container.querySelector('[data-step-editor="2"]')).toBeTruthy()
    // Clicking the open node closes it — the same control both ways, not a separate close button.
    fireEvent.click(container.querySelector('[data-step="2"]')!)
    expect(container.querySelector('[data-step-editor="2"]')).toBeNull()
    fireEvent.click(container.querySelector('[data-step="2"]')!)
    expect(container.querySelector('[data-step-editor="2"]')).toBeTruthy()
    expect(container.querySelector('[data-step="2"]')!.getAttribute('data-selected')).toBe('true')
    expect(container.querySelector('[data-step="0"]')!.getAttribute('data-selected')).toBe('false')
  }, 25000)
})
