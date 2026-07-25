// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The two operator-parity editors, mounted for real.
//
// The pure rules are pinned in card-rules.test.ts; what these assert is the WIRING,
// which is where a limits editor actually goes wrong: that the operator's major
// units reach the API as minor units, that all four channel flags are sent (the
// endpoint takes the complete set), and that a card the aggregate would refuse
// cannot be saved at all.

import { describe, it, expect, vi, afterEach } from 'vitest'
import React from 'react'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { CardLimitsPanel } from '@/components/cards/CardLimitsPanel'
import { CardControlsPanel } from '@/components/cards/CardControlsPanel'
import type { Card } from '@/lib/cards/types'

const card = (over: Partial<Card> = {}): Card => ({
  id: 'c-1',
  partyId: 'p-1',
  accountId: 'a-1',
  productCode: 'CURRENT_CZK',
  cardType: 'DEBIT',
  network: 'VISA',
  maskedPan: '**** **** **** 4242',
  cardholderName: 'Bohuslava Cermakova',
  embossedName: 'BOHUSLAVA CERMAKOVA',
  expiryDate: '2029-07-31',
  status: 'ACTIVE',
  dailyLimitMinorUnits: 500_000,
  monthlyLimitMinorUnits: 5_000_000,
  currency: 'CZK',
  createdAt: '2026-01-01T10:00:00Z',
  contactlessEnabled: true,
  onlineEnabled: true,
  atmEnabled: true,
  abroadEnabled: true,
  ...over,
})

const mount = (ui: React.ReactNode) => render(React.createElement(LanguageProvider, null, ui))

afterEach(cleanup)

describe('limits editor', () => {
  it('sends what the operator typed in MAJOR units as minor units', () => {
    const onSave = vi.fn().mockResolvedValue(true)
    mount(<CardLimitsPanel card={card()} busy={null} onSave={onSave} />)

    const daily = screen.getByLabelText('Daily limit')
    expect((daily as HTMLInputElement).value).toBe('5000.00') // 500000 minor units
    fireEvent.change(daily, { target: { value: '2500.50' } })
    fireEvent.click(screen.getByText('Save limits'))

    expect(onSave).toHaveBeenCalledWith(250_050, 5_000_000)
  })

  it('will not save an edit the aggregate would refuse (daily > monthly)', () => {
    const onSave = vi.fn()
    mount(<CardLimitsPanel card={card()} busy={null} onSave={onSave} />)

    fireEvent.change(screen.getByLabelText('Daily limit'), { target: { value: '90000' } })
    expect(screen.getByText('The daily limit cannot exceed the monthly one.')).toBeTruthy()
    fireEvent.click(screen.getByText('Save limits'))
    expect(onSave).not.toHaveBeenCalled()
  })

  it('will not save an unchanged card — no no-op writes into the audit trail', () => {
    const onSave = vi.fn()
    mount(<CardLimitsPanel card={card()} busy={null} onSave={onSave} />)
    fireEvent.click(screen.getByText('Save limits'))
    expect(onSave).not.toHaveBeenCalled()
  })

  it('explains WHY a blocked card cannot be re-limited instead of greying out silently', () => {
    const onSave = vi.fn()
    mount(<CardLimitsPanel card={card({ status: 'BLOCKED' })} busy={null} onSave={onSave} />)
    expect(screen.getByText(/A blocked card has nothing left to spend/)).toBeTruthy()
    expect((screen.getByLabelText('Daily limit') as HTMLInputElement).disabled).toBe(true)
  })
})

describe('channel controls', () => {
  it('sends the COMPLETE set of four flags, not just the one that changed', () => {
    const onSave = vi.fn().mockResolvedValue(true)
    mount(<CardControlsPanel card={card()} busy={null} onSave={onSave} />)

    fireEvent.click(screen.getByLabelText('Online payments'))
    fireEvent.click(screen.getByText('Save channels'))

    expect(onSave).toHaveBeenCalledWith({
      contactlessEnabled: true,
      onlineEnabled: false,
      atmEnabled: true,
      abroadEnabled: true,
    })
  })

  it('defaults a card that predates the controls columns to all-on', () => {
    const onSave = vi.fn().mockResolvedValue(true)
    const legacy = card({ contactlessEnabled: undefined, onlineEnabled: undefined, atmEnabled: undefined, abroadEnabled: undefined })
    mount(<CardControlsPanel card={legacy} busy={null} onSave={onSave} />)
    expect(screen.getByLabelText('Use abroad').getAttribute('aria-checked')).toBe('true')
  })

  it('is read-only on a terminal card, with the reason on screen', () => {
    mount(<CardControlsPanel card={card({ status: 'CANCELLED' })} busy={null} onSave={vi.fn()} />)
    expect(screen.getByText(/terminal state/)).toBeTruthy()
    expect((screen.getByLabelText('ATM withdrawals') as HTMLButtonElement).disabled).toBe(true)
  })
})
