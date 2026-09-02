// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const page = readFileSync(path.resolve(__dirname, '../app/sanctions/page.tsx'), 'utf8')

describe('sanctions checker decision review', () => {
  it('reviews the exact queued request before calling the protected decision mutation', () => {
    expect(page).toContain('role="alertdialog"')
    expect(page).toContain('intent.approval.makerId')
    expect(page).toContain('intent.approval.action')
    expect(page).toContain('intent.approval.id')
    expect(page).toContain("body: JSON.stringify({ approve })")
  })

  it('preserves the dialog on failure and only closes after a successful decision', () => {
    expect(page).toContain('const succeeded = await decideApproval')
    expect(page).toContain('if (succeeded) setDecisionIntent(null)')
    expect(page).toContain('trapDialogFocus(event, dialogRef.current)')
  })
})
