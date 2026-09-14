// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/devops/RemediationReviewDialog.tsx'), 'utf8')

describe('remediation review semantic theme contract', () => {
  it('uses shared severity, link and overlay semantics', () => {
    // The component's issue reference (#7895) is content, not a CSS colour.
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    expect(source).not.toMatch(/rgba?\(/i)
    for (const token of ['--warning-text', '--danger-text', '--link', '--overlay-scrim']) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('keeps the human confirmation boundary intact', () => {
    expect(source).toContain('role="alertdialog"')
    expect(source).toContain('onConfirm')
    expect(source).toContain('onRestoreFocus')
    expect(source).toContain('aria-busy={busy}')
  })
})
