// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const source = readFileSync(path.resolve(__dirname, '../app/segments/page.tsx'), 'utf8')

describe('audience approval review', () => {
  it('shows the immutable audience evidence before approval', () => {
    expect(source).toContain('role="alertdialog"')
    expect(source).toContain('audience.createdBy')
    expect(source).toContain('audience.rules.map')
    expect(source).toContain('audience.name} · v{audience.version')
  })

  it('preserves the protected endpoint and closes only after success', () => {
    expect(source).toContain("action: 'submit' | 'approve'")
    expect(source).toContain("method: 'POST'")
    expect(source).toContain("if (await lifecycle(approvalIntent, 'approve')) setApprovalIntent(null)")
    expect(source).toContain('trapDialogFocus(event, dialogRef.current)')
  })
})
