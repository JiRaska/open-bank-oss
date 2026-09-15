// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/devops/page.tsx'), 'utf8')
const reviewDialog = readFileSync(path.resolve(__dirname, '../components/devops/RemediationReviewDialog.tsx'), 'utf8')

describe('DevOps cockpit semantic theme', () => {
  it('maps DORA meaning to shared adaptive status tokens', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const tone of ['success', 'info', 'warning', 'danger']) {
      expect(source).toContain(`var(--${tone}-text)`)
      expect(source).toContain(`var(--${tone}-bg)`)
      expect(source).toContain(`var(--${tone}-border)`)
    }
  })

  it('keeps the HITL checkpoint adaptive and rejects executable proposal URLs', () => {
    expect(reviewDialog).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const token of ['warning-text', 'danger-text', 'accent-text']) {
      expect(reviewDialog).toContain(`var(--${token})`)
    }
    expect(reviewDialog).toContain("url.protocol === 'https:'")
    expect(reviewDialog).toContain('rel="noopener noreferrer"')
  })
})
