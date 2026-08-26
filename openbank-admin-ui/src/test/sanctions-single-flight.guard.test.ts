// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/sanctions/page.tsx'), 'utf8')

describe('sanctions four-eyes mutation single-flight contract', () => {
  it('guards maker disposition and checker decision before network dispatch', () => {
    expect(page).toContain('claimSingleFlight(reviewInFlight)')
    expect(page).toContain('releaseSingleFlight(reviewInFlight)')
    expect(page).toContain('claimSingleFlight(decisionInFlight)')
    expect(page).toContain('releaseSingleFlight(decisionInFlight)')
    expect(page.match(/aria-busy=\{reviewBusy\}/g)).toHaveLength(2)
    expect(page.match(/aria-busy=\{decideBusy\}/g)).toHaveLength(2)
  })
})
