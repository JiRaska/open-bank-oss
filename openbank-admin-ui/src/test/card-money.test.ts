// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The card limit editor shows major units and the API takes minor units. A factor
// of 100 on a spending limit is the kind of back-office slip that reaches a
// customer, so the conversion is pinned here — including the float trap
// (`19.99 * 100 === 1998.9999999999998`) that makes the naive version lose a
// haléř on every edit.

import { describe, it, expect } from 'vitest'
import {
  currencyExponent, formatMinor, minorToMajorString, parseMajorToMinor,
} from '@/lib/cards/money'

describe('currency exponent', () => {
  it('is 2 for the ordinary currencies and 0 for the zero-decimal ones', () => {
    expect(currencyExponent('CZK')).toBe(2)
    expect(currencyExponent('eur')).toBe(2)
    expect(currencyExponent('JPY')).toBe(0)
    expect(currencyExponent('ISK')).toBe(0)
  })

  it('assumes 2 for an unknown or missing code rather than throwing', () => {
    expect(currencyExponent('ZZZ')).toBe(2)
    expect(currencyExponent(null)).toBe(2)
    expect(currencyExponent(undefined)).toBe(2)
  })
})

describe('minor → major', () => {
  it('renders the API default limits as the amounts an operator recognises', () => {
    expect(minorToMajorString(500_000, 'CZK')).toBe('5000.00')
    expect(minorToMajorString(5_000_000, 'CZK')).toBe('50000.00')
  })

  it('pads sub-unit amounts instead of dropping the leading zero', () => {
    expect(minorToMajorString(5, 'EUR')).toBe('0.05')
    expect(minorToMajorString(0, 'EUR')).toBe('0.00')
  })

  it('leaves a zero-decimal currency alone', () => {
    expect(minorToMajorString(5000, 'JPY')).toBe('5000')
  })
})

describe('major → minor', () => {
  it('round-trips the amounts the form produces', () => {
    expect(parseMajorToMinor('5000.00', 'CZK')).toBe(500_000)
    expect(parseMajorToMinor('5000', 'CZK')).toBe(500_000)
    expect(parseMajorToMinor('0.05', 'EUR')).toBe(5)
  })

  it('does not lose a minor unit to floating point', () => {
    expect(parseMajorToMinor('19.99', 'EUR')).toBe(1999)
    expect(parseMajorToMinor('1234.56', 'CZK')).toBe(123456)
  })

  it('accepts the separators a Czech operator actually types', () => {
    expect(parseMajorToMinor('5 000,50', 'CZK')).toBe(500_050)
    expect(parseMajorToMinor('5 000,50', 'CZK')).toBe(500_050)
  })

  it('refuses text that is not an amount this currency can hold', () => {
    expect(parseMajorToMinor('', 'CZK')).toBeNull()
    expect(parseMajorToMinor('abc', 'CZK')).toBeNull()
    expect(parseMajorToMinor('1.2.3', 'CZK')).toBeNull()
    // More decimals than the currency has minor units — rounding would change intent.
    expect(parseMajorToMinor('10.999', 'CZK')).toBeNull()
    expect(parseMajorToMinor('10.5', 'JPY')).toBeNull()
  })

  it('refuses a negative amount outright (Card.withLimits requires >= 0)', () => {
    expect(parseMajorToMinor('-1', 'CZK')).toBeNull()
    expect(parseMajorToMinor('-0.01', 'EUR')).toBeNull()
  })
})

describe('display formatting', () => {
  it('formats with the currency and the right number of decimals', () => {
    expect(formatMinor(500_000, 'CZK', 'en-US')).toContain('5,000.00')
    expect(formatMinor(5000, 'JPY', 'en-US')).not.toContain('.')
  })

  it('degrades to "<amount> <CODE>" for a code Intl does not know', () => {
    const out = formatMinor(12_345, 'XX', 'en-US')
    expect(out).toContain('123.45')
    expect(out).toContain('XX')
  })
})
