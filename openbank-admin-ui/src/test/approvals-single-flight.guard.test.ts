// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/approvals/page.tsx'), 'utf8')

describe('agent approval decision single-flight contract', () => {
  it('serializes approve/reject before their decision POST and disables competing decisions', () => {
    const decide = page.slice(page.indexOf('const decide ='), page.indexOf('const pending ='))
    expect(decide.indexOf('claimSingleFlight(decisionInFlight)')).toBeLessThan(decide.indexOf("fetch('/api/agent/proposals'"))
    expect(decide).toContain('releaseSingleFlight(decisionInFlight)')
    expect(page.match(/disabled=\{busyId !== null\}/g)).toHaveLength(2)
  })
})
