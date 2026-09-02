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

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
  SessionProvider: ({ children }: { children: React.ReactNode }) => children,
}))

let searchParams = ''
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(searchParams),
}))

const TEMPLATES = {
  state: 'ok',
  items: [
    { template: 'MARKETING_PRODUCT_OFFER', channel: 'EMAIL', variables: ['offerTitle', 'offerText', 'ctaText'] },
    { template: 'MARKETING_PRODUCT_OFFER_PUSH', channel: 'PUSH', variables: ['offerTitle'] },
    { template: 'MARKETING_PRODUCT_OFFER_BANNER', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'HOME_BANNER' },
    { template: 'MARKETING_PRODUCT_OFFER_CAROUSEL', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'HOME_CAROUSEL' },
    { template: 'MARKETING_PRODUCT_OFFER_STORY', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'STORIES' },
    { template: 'MARKETING_PRODUCT_OFFER_PRODUCT_FEED', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'PRODUCT_FEED' },
    { template: 'MARKETING_PRODUCT_OFFER_REWARDS_HUB', channel: 'BANNER', variables: ['offerTitle', 'offerText', 'ctaText'], inAppSurface: 'REWARDS_HUB' },
  ],
}

const stub = () => vi.stubGlobal('fetch', vi.fn(async (u: string) =>
  String(u).includes('/templates')
    ? { ok: true, json: async () => TEMPLATES }
    : String(u).includes('/preview')
    ? { ok: true, json: async () => ({ size: 1240, state: 'ok' }) }
    : { ok: true, json: async () => ({ state: 'ok', items: [
        { name: 'active-clients', version: 1, rules: ['party status is ACTIVE'] },
        { name: 'savers', version: 2, rules: ['has a savings account', 'balance over 50 000 CZK'] }] }) }))

describe('campaign builder interaction', () => {
  it('turns an app-first recipe into an explicit, editable multi-surface journey', async () => {
    stub()
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('savers'), { timeout: 8000 })
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())

    fireEvent.click(container.querySelector('[data-journey-recipe="IN_APP_DISCOVERY"]')!)

    expect(container.querySelectorAll('[data-step]').length).toBe(2)
    expect(container.querySelector('[data-step="0"]')!.getAttribute('data-channel')).toBe('BANNER')
    expect(container.querySelector('[data-step="1"]')!.getAttribute('data-channel')).toBe('BANNER')
    expect(container.querySelector('[data-journey-recipe="IN_APP_DISCOVERY"]')!.getAttribute('data-selected')).toBe('true')
    // A recipe must reveal its first node for immediate editing, rather than act as a hidden
    // black-box automation.
    expect(container.querySelector('[data-step-editor="0"]')).toBeTruthy()
  }, 25000)

  it('adds steps up to the domain cap, and the add affordance then disappears', async () => {
    stub()
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('savers'), { timeout: 8000 })
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())
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
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())
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
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())
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

/**
 * The channel picker (ADR-0200 D7 as it now stands: EMAIL + PUSH).
 *
 * The rule worth protecting is not that a picker exists — it is that choosing PUSH changes what the
 * panel can offer. A push renders a title plus a fixed generic body, so offering an email's body
 * fields on a push step would promise a delivery the platform refuses to make (#1182).
 */
describe('campaign builder channels', () => {
  it('starts app-first and switching channels exposes only fields that channel can carry', async () => {
    vi.stubGlobal('fetch', vi.fn(async (u: string) =>
      String(u).includes('/templates')
        ? { ok: true, json: async () => TEMPLATES }
        : String(u).includes('/preview')
        ? { ok: true, json: async () => ({ size: 10, state: 'ok' }) }
        : { ok: true, json: async () => ({ state: 'ok', items: [
            { name: 'active-clients', version: 1, rules: ['party status is ACTIVE'] }] }) }))
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('active-clients'), { timeout: 8000 })
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())

    // App-first: a new campaign opens on push with only its headline field.
    expect(container.querySelector('[data-step="0"]')!.getAttribute('data-channel')).toBe('PUSH')
    expect(container.querySelectorAll('[id^="var-0-"]').length).toBe(1)
    expect(document.getElementById('var-0-offerTitle')).toBeTruthy()
    expect(document.getElementById('var-0-offerText')).toBeNull()

    // Email remains available and exposes the template's three declared variables.
    fireEvent.click(container.querySelector('[data-channel-pick="EMAIL"]')!)
    expect(container.querySelectorAll('[id^="var-0-"]').length).toBe(3)

    fireEvent.click(container.querySelector('[data-channel-pick="PUSH"]')!)

    // Push: the headline, and nothing that would become body copy.
    expect(container.querySelectorAll('[id^="var-0-"]').length).toBe(1)
    expect(document.getElementById('var-0-offerTitle')).toBeTruthy()
    expect(document.getElementById('var-0-offerText')).toBeNull()
    // The canvas node reports the channel, so the journey is legible without opening each step.
    expect(container.querySelector('[data-step="0"]')!.getAttribute('data-channel')).toBe('PUSH')
  }, 25000)
})

/**
 * Conditions, which the domain has had since #3585 and the console could not author.
 *
 * The rules worth protecting are the honest ones: a condition names a delivery status and nothing
 * richer, the first step is warned about because it has no predecessor, and the cap is stated on the
 * canvas rather than only in a form field a marketer has scrolled past.
 */
describe('campaign builder conditions', () => {
  const stub = () => vi.stubGlobal('fetch', vi.fn(async (u: string) =>
    String(u).includes('/templates')
      ? { ok: true, json: async () => TEMPLATES }
      : String(u).includes('/preview')
      ? { ok: true, json: async () => ({ size: 10, state: 'ok' }) }
      : { ok: true, json: async () => ({ state: 'ok', items: [
          { name: 'active-clients', version: 1, rules: ['party status is ACTIVE'] }] }) }))

  it('a step condition is offered and lands on the connector, not inside the node', async () => {
    stub()
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('active-clients'), { timeout: 8000 })
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())
    fireEvent.click(container.querySelector('[data-add-step]')!)

    expect(container.querySelector('[data-edge-condition]')).toBeNull()
    fireEvent.click(container.querySelector('[data-condition-pick="IF_PREVIOUS_CONFIRMED"]')!)
    // The gate belongs to the hop, so it renders on the edge the journey may not cross.
    expect(container.querySelector('[data-edge-condition="IF_PREVIOUS_CONFIRMED"]')).toBeTruthy()
  }, 25000)

  it('makes a real delivery decision node, not two manually guessed linear gates', async () => {
    stub()
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('active-clients'), { timeout: 8000 })
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())

    fireEvent.click(container.querySelector('[data-add-decision="delivery"]')!)

    expect(container.querySelectorAll('[data-step]')).toHaveLength(3)
    expect(container.querySelector('[data-step-editor="1"]')).toBeTruthy()
    expect(container.querySelector('[data-decision-node="0"]')).toBeTruthy()
    expect(container.querySelector('[data-edge-condition]')).toBeNull()
    expect(container.textContent).toMatch(/delivered\?|doručeno\?/)
  }, 25000)

  it('hydrates a graph draft and saves its exact reviewed edges back to the API', async () => {
    searchParams = 'draft=11111111-1111-1111-1111-111111111111'
    let saved: Record<string, unknown> | undefined
    vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => {
      if (String(u).includes('/templates')) return { ok: true, json: async () => TEMPLATES }
      if (String(u).includes('/api/campaigns/11111111-1111-1111-1111-111111111111')) {
        if (init?.method === 'PUT') {
          saved = JSON.parse(String(init.body))
          return { ok: true, json: async () => ({ state: 'ok', campaign: { id: '11111111-1111-1111-1111-111111111111' } }) }
        }
        return {
          ok: true,
          json: async () => ({
            state: 'ok', sources: { campaign: 'ok' }, campaign: {
              state: 'DRAFT', name: 'Return to app', goal: 'Open savings',
              segmentRef: { name: 'active-clients', version: 1 },
              steps: [
                { order: 0, template: 'MARKETING_PRODUCT_OFFER_PUSH', channel: 'PUSH', variables: { offerTitle: 'Savings' }, delaySeconds: 0 },
                { order: 4, template: 'MARKETING_PRODUCT_OFFER_BANNER', channel: 'BANNER', variables: { offerTitle: 'Yes', offerText: 'Yes', ctaText: 'Open' }, delaySeconds: 0 },
                { order: 7, template: 'MARKETING_PRODUCT_OFFER_BANNER', channel: 'BANNER', variables: { offerTitle: 'No', offerText: 'No', ctaText: 'Learn' }, delaySeconds: 0 },
              ],
              decisions: [{ sourceStepOrder: 0, evaluationDelaySeconds: 86_400, confirmedStepOrder: 4, notConfirmedStepOrder: 7 }],
            },
          }),
        }
      }
      return { ok: true, json: async () => ({ state: 'ok', items: [{ name: 'active-clients', version: 1, rules: ['party status is ACTIVE'] }] }) }
    }))

    try {
      const { container, getByText } = render(
        React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
      await waitFor(() => expect(container.querySelector('[data-decision-node="0"]')).toBeTruthy())
      await waitFor(() => expect(getByText('Save draft')).toBeTruthy())

      fireEvent.click(getByText('Save draft'))
      await waitFor(() => expect(saved).toBeDefined())

      expect(saved?.decisions).toEqual([
        { sourceStepOrder: 1, evaluationDelaySeconds: 86_400, confirmedStepOrder: 2, notConfirmedStepOrder: 3 },
      ])
      expect(saved?.steps).toEqual(expect.arrayContaining([
        expect.objectContaining({ order: 1, template: 'MARKETING_PRODUCT_OFFER_PUSH' }),
      ]))
    } finally {
      searchParams = ''
    }
  }, 25000)

  it('warns that a condition on the first step has nothing to test', async () => {
    stub()
    const { container, getByText, queryByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('active-clients'), { timeout: 8000 })
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())

    expect(queryByText(/has no predecessor/)).toBeNull()
    fireEvent.click(container.querySelector('[data-condition-pick="IF_PREVIOUS_CONFIRMED"]')!)
    // Silently accepting it would let someone build a step that can never run.
    expect(getByText(/has no predecessor/)).toBeTruthy()
  }, 25000)

  it('the stop cap is stated on the canvas, where it changes the outcome', async () => {
    stub()
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('active-clients'), { timeout: 8000 })
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())

    expect(container.querySelector('[data-stop-after]')).toBeNull()
    fireEvent.click(container.querySelector('[data-stop-enabled]')!)
    expect(container.querySelector('svg [data-stop-after]')).toBeTruthy()
    expect(getByText(/Stops after 2 messages per person/)).toBeTruthy()
  }, 25000)
})

/**
 * Conversion (ADR-0245) at authoring time.
 *
 * Two properties matter more than the picker existing: the options are a closed catalogue, and the
 * screen says what is NOT measured. A marketer who assumes "success" includes an email open would
 * read every number here wrong.
 */
describe('campaign builder conversion', () => {
  it('offers only catalogue rules, and says engagement is not tracked', async () => {
    vi.stubGlobal('fetch', vi.fn(async (u: string) =>
      String(u).includes('/templates')
        ? { ok: true, json: async () => TEMPLATES }
        : String(u).includes('/preview')
        ? { ok: true, json: async () => ({ size: 10, state: 'ok' }) }
        : { ok: true, json: async () => ({ state: 'ok', items: [
            { name: 'active-clients', version: 1, rules: ['party status is ACTIVE'] }] }) }))
    const { container, getByText } = render(
      React.createElement(LanguageProvider, null, React.createElement(NewCampaignPage)))
    await waitFor(() => getByText('active-clients'), { timeout: 8000 })
    await waitFor(() => expect(container.querySelector('[data-step="0"]')).toBeTruthy())

    const picks = Array.from(container.querySelectorAll('[data-conversion-pick]'))
      .map(e => e.getAttribute('data-conversion-pick'))
    expect(picks).toEqual(['NONE', 'ACCOUNT_OPENED', 'CARD_ISSUED'])

    // Not measuring is the default: a rule chosen after the fact measures nothing retroactively,
    // so the screen must not imply one was set.
    expect(container.querySelector('[data-conversion-pick="NONE"]')!.getAttribute('data-selected'))
      .toBe('true')

    expect(getByText(/never an email open or a click/)).toBeTruthy()

    fireEvent.click(container.querySelector('[data-conversion-pick="CARD_ISSUED"]')!)
    expect(container.querySelector('[data-conversion-pick="CARD_ISSUED"]')!.getAttribute('data-selected'))
      .toBe('true')
  }, 25000)
})
