// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { aggregateEvidenceState } from '@/lib/test-intelligence-state'

describe('aggregateEvidenceState', () => {
  it('does not turn partial client execution into a green aggregate', () => {
    expect(aggregateEvidenceState(['passed', 'not-run'])).toBe('not-run')
    expect(aggregateEvidenceState(['passed', 'stale'])).toBe('stale')
  })

  it('preserves the strongest unresolved verdict', () => {
    expect(aggregateEvidenceState(['unknown', 'blocked', 'failed'])).toBe('failed')
    expect(aggregateEvidenceState(['stale', 'blocked'])).toBe('blocked')
  })

  it('uses an explicit empty-state verdict', () => {
    expect(aggregateEvidenceState([])).toBe('unknown')
    expect(aggregateEvidenceState([], 'not-run')).toBe('not-run')
  })
})
