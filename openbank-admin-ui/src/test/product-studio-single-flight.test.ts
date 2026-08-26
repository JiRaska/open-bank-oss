// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { claimSingleFlight, releaseSingleFlight, type SingleFlightLock } from '@/lib/single-flight'

describe('Product Studio single-flight mutation lock', () => {
  it('rejects a repeated activation until the active operation releases the lock', () => {
    const lock: SingleFlightLock = { current: false }

    expect(claimSingleFlight(lock)).toBe(true)
    expect(claimSingleFlight(lock)).toBe(false)

    releaseSingleFlight(lock)
    expect(claimSingleFlight(lock)).toBe(true)
  })
})
