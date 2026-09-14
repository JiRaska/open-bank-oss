// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(path.resolve(__dirname, '../app/auth/login/login.module.css'), 'utf8')

describe('pre-auth login semantic theme', () => {
  it('separates the stable Explorer story from the adaptive access palette', () => {
    expect(css).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    expect(css).not.toMatch(/rgba?\(/u)
    expect(css).toContain('var(--login-story-bg)')
    expect(css).toContain('var(--privacy-bg)')
    expect(css).toContain('var(--login-error-bg)')
  })
})
