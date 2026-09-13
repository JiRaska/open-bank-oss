// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { parseVopEvidence } from '@/lib/payments/vopEvidence'

const verifiedAt = '2026-09-10T08:15:30Z'

describe('VoP evidence boundary', () => {
  it.each(['match', 'no_match', 'no_data'] as const)('accepts %s without disclosing a name', status => {
    expect(parseVopEvidence({ status, matchedName: null, verifiedAt })).toEqual({ status, matchedName: null, verifiedAt })
  })

  it('accepts the bounded account name only for close_match', () => {
    expect(parseVopEvidence({ status: 'close_match', matchedName: 'Verified Supplier GmbH', verifiedAt }))
      .toEqual({ status: 'close_match', matchedName: 'Verified Supplier GmbH', verifiedAt })
  })

  it.each([
    null,
    [],
    { status: 'MATCH', verifiedAt },
    { status: 'match' },
    { status: 'match', verifiedAt: 'not-a-date' },
    { status: 'match', matchedName: 'must-not-leak', verifiedAt },
    { status: 'no_match', matchedName: 'must-not-leak', verifiedAt },
    { status: 'close_match', matchedName: '', verifiedAt },
    { status: 'close_match', matchedName: 'x'.repeat(141), verifiedAt },
  ])('rejects malformed or disclosure-unsafe evidence %#', value => {
    expect(parseVopEvidence(value)).toBeNull()
  })
})
