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
    expect(source).toContain("if (await lifecycle(approvalIntent, 'approve')) {")
  })

  it('uses the shared Radix dialog primitive, not a hand-rolled focus trap', () => {
    expect(source).toContain("import * as Dialog from '@radix-ui/react-dialog'")
    expect(source).toContain('<Dialog.Root open onOpenChange={open => { if (!open && !busy) onCancel() }}>')
    expect(source).not.toContain('trapDialogFocus')
  })

  it('lands initial focus on the safe Back action, never the destructive confirm', () => {
    expect(source).toContain('onOpenAutoFocus={event => {')
    expect(source).toContain('backRef.current?.focus()')
  })

  it('restores focus to a stable landmark when success removes the trigger, and to the trigger otherwise', () => {
    expect(source).toContain('approvalCloseFocusOverrideRef.current = audienceWorkspaceRef.current')
    expect(source).toContain('const target = override?.isConnected ? override : triggerRef.current')
  })
})
