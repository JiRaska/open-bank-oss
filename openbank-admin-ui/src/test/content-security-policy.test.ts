// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { scriptSourceDirective } from '@/lib/security/contentSecurityPolicy'

describe('admin UI script CSP', () => {
  it('does not let the card capability page override nonce-bearing dynamic rendering', () => {
    const page = readFileSync('src/app/cards/capabilities/page.tsx', 'utf8')
    expect(page).not.toContain('force-static')
  })

  it('permits the evaluated React Refresh runtime only in development', () => {
    expect(scriptSourceDirective('test-nonce', true)).toContain("'unsafe-eval'")
    expect(scriptSourceDirective('test-nonce', false)).not.toContain("'unsafe-eval'")
  })

  it('keeps nonce trust and never permits inline scripts in either mode', () => {
    for (const development of [true, false]) {
      const directive = scriptSourceDirective('test-nonce', development)
      expect(directive).toContain("'nonce-test-nonce'")
      expect(directive).toContain("'strict-dynamic'")
      expect(directive).not.toContain("'unsafe-inline'")
    }
  })
})
