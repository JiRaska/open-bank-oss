// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

const source = readFileSync(path.resolve(__dirname, '../app/auth/login/login.module.css'), 'utf8')
const globals = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')

describe('login semantic theme contract', () => {
  it('keeps local presentation free of an independent colour system', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--surface', '--surface-2', '--border', '--border-strong',
      '--text-primary', '--text-secondary', '--text-tertiary',
      '--danger-bg', '--danger-border', '--danger-text',
      '--success-text', '--info', '--link',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('owns one compact branded trust-boundary palette centrally', () => {
    for (const token of [
      '--auth-story-bg', '--auth-story-text', '--auth-story-muted', '--auth-story-subtle',
      '--auth-story-accent', '--auth-story-success', '--auth-action-start', '--auth-action-end',
    ]) {
      expect(globals).toContain(`${token}:`)
      expect(source).toContain(`var(${token})`)
    }
  })
})
