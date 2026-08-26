// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/segments/page.tsx'), 'utf8')
const lifecycle = page.slice(page.indexOf('const lifecycle ='), page.indexOf('const key ='))

describe('audience lifecycle mutation contract', () => {
  it('serializes lifecycle writes and keeps action failures local and visible', () => {
    expect(page).toContain('claimSingleFlight(lifecycleInFlight)')
    expect(page).toContain('releaseSingleFlight(lifecycleInFlight)')
    expect(page).toContain('setLifecycleError(error instanceof Error')
    expect(page).toContain('role="alert"')
    expect(lifecycle).not.toContain('setUnavailable')
  })
})
