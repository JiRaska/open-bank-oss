// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

describe('account lifecycle dialog contract', () => {
  it('uses a localized accessible reason dialog instead of a browser prompt', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/accounts/[id]/page.tsx'), 'utf8')

    expect(source).not.toContain('window.prompt')
    expect(source).toContain('<section')
    expect(source).not.toContain('aria-modal="true"')
    expect(source).toContain('aria-labelledby="account-action-title"')
    expect(source).toContain('aria-describedby="account-action-description"')
    expect(source).toContain('htmlFor="account-action-reason"')
    expect(source).toContain('reason: string')
    expect(source).toContain('doAction(actionIntent, actionReason)')
  })
})
