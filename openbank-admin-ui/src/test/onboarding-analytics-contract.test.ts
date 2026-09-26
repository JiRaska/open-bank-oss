// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { ONBOARDING_STEPS, parseFunnelAnalytics } from '@/lib/onboarding/funnelAnalyticsContract'

const from = '2026-08-01'; const to = '2026-09-01'
const payload = {
  available: true, from, to,
  steps: ONBOARDING_STEPS.map((step, index) => ({ step, stepOrdinal: index + 1, viewed: 10, completed: 8, holdAbandons: 1, dropOffPct: 20, medianSeconds: 12 })),
  signOutcomes: [{ day: '2026-08-31', attempts: 10, successes: 8, failures: 2 }],
  failReasons: [{ reason: 'OTP_EXPIRED', failures: 2 }],
  kycMethods: [{ method: 'BANK_ID', sessions: 8 }],
}

describe('onboarding analytics client contract', () => {
  it('accepts a complete internally consistent analytics snapshot', () => expect(parseFunnelAnalytics(payload, from, to)).toEqual(payload))
  it('rejects mismatched ranges and incomplete funnels', () => {
    expect(() => parseFunnelAnalytics({ ...payload, from: '2026-07-01' }, from, to)).toThrow()
    expect(() => parseFunnelAnalytics({ ...payload, steps: payload.steps.slice(1) }, from, to)).toThrow()
  })
  it('rejects impossible counts and unknown step identities', () => {
    expect(() => parseFunnelAnalytics({ ...payload, steps: payload.steps.map((step, index) => index ? step : { ...step, completed: 11 }) }, from, to)).toThrow()
    expect(() => parseFunnelAnalytics({ ...payload, steps: payload.steps.map((step, index) => index ? step : { ...step, step: 'START' }) }, from, to)).toThrow()
    expect(() => parseFunnelAnalytics({ ...payload, signOutcomes: [{ day: '2026-08-31', attempts: 1, successes: 1, failures: 1 }] }, from, to)).toThrow()
  })
  it('accepts only an actually empty unavailable result', () => {
    expect(parseFunnelAnalytics({ available: false, from, to, steps: [], signOutcomes: [], failReasons: [], kycMethods: [] }, from, to).available).toBe(false)
    expect(() => parseFunnelAnalytics({ ...payload, available: false }, from, to)).toThrow()
  })
})
