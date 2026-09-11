// SPDX-License-Identifier: Apache-2.0

export const ONBOARDING_STEPS = ['WELCOME', 'IDENTITY', 'EMAIL', 'AGREEMENT', 'PASSKEY', 'SIGN'] as const

export interface FunnelStep { step: string; stepOrdinal: number; viewed: number; completed: number; holdAbandons: number; dropOffPct: number; medianSeconds: number | null }
export interface SignOutcome { day: string; attempts: number; successes: number; failures: number }
export interface FailReason { reason: string; failures: number }
export interface KycMethod { method: string; sessions: number }
export interface FunnelAnalytics { available: boolean; from: string; to: string; steps: FunnelStep[]; signOutcomes: SignOutcome[]; failReasons: FailReason[]; kycMethods: KycMethod[] }

const DATE = /^\d{4}-\d{2}-\d{2}$/
function record(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected object'); return value as Record<string, unknown> }
function text(value: unknown, max = 200): string { if (typeof value !== 'string' || value.trim() === '' || value.length > max) throw new Error('Invalid text'); return value }
function count(value: unknown): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('Invalid count'); return value }
function finite(value: unknown): number { if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) throw new Error('Invalid metric'); return value }
function day(value: unknown): string { const result = text(value, 10); if (!DATE.test(result) || Number.isNaN(Date.parse(`${result}T00:00:00Z`))) throw new Error('Invalid day'); return result }
function list(value: unknown, max: number): unknown[] { if (!Array.isArray(value) || value.length > max) throw new Error('Invalid collection'); return value }

export function parseFunnelAnalytics(value: unknown, expectedFrom: string, expectedTo: string): FunnelAnalytics {
  const body = record(value)
  if (typeof body.available !== 'boolean' || body.from !== expectedFrom || body.to !== expectedTo) throw new Error('Invalid analytics identity')
  const steps = list(body.steps, ONBOARDING_STEPS.length).map((value, index): FunnelStep => {
    const item = record(value); const viewed = count(item.viewed); const completed = count(item.completed)
    const step = ONBOARDING_STEPS[index]
    if (!step || item.step !== step || item.stepOrdinal !== index + 1 || completed > viewed) throw new Error('Invalid funnel step')
    const medianSeconds = item.medianSeconds === null ? null : finite(item.medianSeconds)
    return { step, stepOrdinal: index + 1, viewed, completed, holdAbandons: count(item.holdAbandons), dropOffPct: finite(item.dropOffPct), medianSeconds }
  })
  const signOutcomes = list(body.signOutcomes, 366).map((value): SignOutcome => { const item = record(value); const attempts = count(item.attempts); const successes = count(item.successes); const failures = count(item.failures); if (successes + failures > attempts) throw new Error('Invalid signature outcome'); return { day: day(item.day), attempts, successes, failures } })
  const failReasons = list(body.failReasons, 10).map((value): FailReason => { const item = record(value); return { reason: text(item.reason), failures: count(item.failures) } })
  const kycMethods = list(body.kycMethods, 100).map((value): KycMethod => { const item = record(value); return { method: text(item.method), sessions: count(item.sessions) } })
  if (body.available ? steps.length !== ONBOARDING_STEPS.length : steps.length || signOutcomes.length || failReasons.length || kycMethods.length) throw new Error('Contradictory availability')
  return { available: body.available, from: expectedFrom, to: expectedTo, steps, signOutcomes, failReasons, kycMethods }
}
