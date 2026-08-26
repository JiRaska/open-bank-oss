// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/day-end/page.tsx'), 'utf8')

describe('month-end catch-up trigger contract', () => {
  it('claims a synchronous lock before the close POST and releases it in finally', () => {
    const trigger = page.slice(page.indexOf('const trigger ='), page.indexOf('const toggleFailures ='))
    expect(trigger.indexOf('claimSingleFlight(triggerInFlight)')).toBeLessThan(trigger.indexOf("fetch('/api/closings/runs'"))
    expect(trigger).toContain('releaseSingleFlight(triggerInFlight)')
    expect(page).toContain('aria-busy={triggering}')
    expect(page).toContain('disabled={triggering || running || unavailable !== null}')
  })
})
