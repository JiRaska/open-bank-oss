// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { consolidateEgressTargets } from '@/lib/governance/security'

describe('Zero Trust effective egress targets', () => {
  it('keeps target order while merging and sorting unique ports', () => {
    expect(consolidateEgressTargets([
      { target: 'messaging', ports: [9093, 9092] },
      { target: 'iam', ports: [8080] },
      { target: 'messaging', ports: [9092, 443] },
    ])).toEqual([
      { target: 'messaging', ports: [443, 9092, 9093] },
      { target: 'iam', ports: [8080] },
    ])
  })
})
