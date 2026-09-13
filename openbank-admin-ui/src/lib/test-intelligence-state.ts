// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { EvidenceState } from '@/lib/types/test-intelligence'

const STATE_PRIORITY: Record<EvidenceState, number> = {
  passed: 0,
  skipped: 1,
  stale: 2,
  'not-run': 3,
  unknown: 4,
  blocked: 5,
  failed: 6,
}

/** Return the strongest unresolved verdict without manufacturing a green aggregate. */
export function aggregateEvidenceState(states: EvidenceState[], empty: EvidenceState = 'unknown'): EvidenceState {
  return states.reduce(
    (aggregate, state) => STATE_PRIORITY[state] > STATE_PRIORITY[aggregate] ? state : aggregate,
    states.length > 0 ? 'passed' : empty,
  )
}
