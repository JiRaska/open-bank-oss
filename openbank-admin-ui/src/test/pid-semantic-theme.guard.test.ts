// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/pid/page.tsx'), 'utf8')

describe('PID semantic theme contract', () => {
  it('uses mandatory shared semantics for legal warning and success feedback', () => {
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    for (const token of [
      '--warning-bg', '--warning-text', '--warning-border',
      '--success-bg', '--success-text', '--success-border',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('preserves the AML disclaimer and same-party BankID retry invariant', () => {
    expect(source).toContain('Quick create is only pre-filling. Legally binding AML identification requires full onboarding and verification.')
    expect(source).toContain('the next attempt retries BankID sync against the same party and originally submitted identity only')
    expect(source).toContain('pendingBankIdSync.partyId')
  })
})
