// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/identity-cases/page.tsx'), 'utf8')

describe('identity case mutation single-flight contract', () => {
  it('guards both four-eyes writes before network dispatch and reports progress', () => {
    expect(page.match(/claimSingleFlight\(mutationInFlight\)/g)).toHaveLength(2)
    expect(page.match(/releaseSingleFlight\(mutationInFlight\)/g)).toHaveLength(2)
    expect(page.match(/aria-busy=\{busy\}/g)).toHaveLength(2)
    expect(page).toContain('/decision`')
    expect(page).toContain('/reopen`')
  })
})
