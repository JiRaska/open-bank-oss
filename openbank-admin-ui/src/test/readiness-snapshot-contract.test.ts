// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { isReadinessSnapshot, readinessRosterDigest, READINESS_DIMENSION_CODES, type ReadinessSnapshotReport } from '../lib/evidence/readinessSnapshot'

const codes = READINESS_DIMENSION_CODES
const now = Date.parse('2026-10-19T12:00:00Z')

function makeSnapshot(moneyPath = false): ReadinessSnapshotReport {
  const scores = Object.fromEntries(codes.map(code => [code, 2])) as ReadinessSnapshotReport['services'][number]['scores']
  if (moneyPath) scores.C1 = scores.C5 = scores.C7 = 3
  const services = [{
    service: 'ledger', money_path: moneyPath, scores,
    evidence: Object.fromEntries(codes.map(code => [code, `evidence ${code}`])) as ReadinessSnapshotReport['services'][number]['evidence'],
    gate: 'GO' as const,
  }]
  return {
    schema_version: 1,
    generated_for: '2026-10-19',
    collected_at: '2026-10-19T11:59:00Z',
    source_sha: 'a'.repeat(40),
    scope: {
      kind: 'service-module-matrix-v1', service_count: 1,
      money_path_count: Number(moneyPath), roster_sha256: readinessRosterDigest(services),
    },
    dimensions: codes.map(code => ({ code, name: code })),
    services,
  }
}

describe('readiness snapshot validation', () => {
  it('accepts a complete non-money-path service-module matrix snapshot', () => {
    expect(isReadinessSnapshot(makeSnapshot(), now)).toBe(true)
  })

  it('accepts a money-path GO only when the existing critical thresholds are met', () => {
    const value = makeSnapshot(true)
    expect(isReadinessSnapshot(value, now)).toBe(true)
    value.services[0].scores.C7 = 2
    expect(isReadinessSnapshot(value, now)).toBe(false)
  })

  it('rejects a false GO and a roster/count mismatch', () => {
    const falseGo = makeSnapshot()
    falseGo.services[0].scores.C8 = 1
    expect(isReadinessSnapshot(falseGo, now)).toBe(false)

    const wrongScope = makeSnapshot()
    wrongScope.scope.service_count = 55
    expect(isReadinessSnapshot(wrongScope, now)).toBe(false)
  })

  it('rejects duplicate rows, incomplete dimensions, and invalid timestamps', () => {
    const duplicate = makeSnapshot()
    duplicate.services.push({ ...duplicate.services[0] })
    duplicate.scope.service_count = 2
    duplicate.scope.roster_sha256 = readinessRosterDigest(duplicate.services)
    expect(isReadinessSnapshot(duplicate, now)).toBe(false)

    const incomplete = makeSnapshot()
    incomplete.dimensions.pop()
    expect(isReadinessSnapshot(incomplete, now)).toBe(false)

    const future = makeSnapshot()
    future.collected_at = '2026-10-19T13:00:00Z'
    expect(isReadinessSnapshot(future, now)).toBe(false)
  })
})
