// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const styles = readFileSync(
  path.resolve(__dirname, '../app/docs/release-notes/[service]/ReleaseNotes.module.css'),
  'utf8',
)

describe('release notes theme contract', () => {
  it('keeps release cards on theme-adaptive surfaces', () => {
    const card = styles.match(/\.releaseCard\s*\{([\s\S]*?)\n\}/)?.[1]
    expect(card).toContain('var(--surface)')
    expect(card).toContain('var(--accent-bg)')
    expect(card).not.toMatch(/#[\da-f]{3,8}\b/i)
  })

  it('uses the shared accent palette for the release marker', () => {
    const marker = styles.match(/\.releaseCard::before\s*\{([\s\S]*?)\n\}/)?.[1]
    expect(marker).toContain('var(--accent-border)')
    expect(marker).toContain('var(--accent)')
    expect(marker).not.toMatch(/#[\da-f]{3,8}\b/i)
  })
})
