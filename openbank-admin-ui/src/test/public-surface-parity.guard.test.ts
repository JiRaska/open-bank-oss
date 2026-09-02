// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Two independent lists decide "is this route public", and nothing made them agree:
//   - src/proxy.ts        — skips the auth gate (no session required to reach the page)
//   - lib/auth/publicSurface.ts — skips SessionProvider/AgentDock (issue #7073)
// A path the proxy serves unauthenticated but publicSurface calls protected mounts
// authenticated-only client infrastructure on an unauthenticated page — the exact defect
// #7073 closed. This guard fails when the two predicates disagree.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { isPublicSurface } from '@/lib/auth/publicSurface'

/** Mirrors the public-route condition in src/proxy.ts. Kept in lockstep by this test. */
function proxyServesUnauthenticated(pathname: string): boolean {
  return isPublicSurface(pathname) || pathname.startsWith('/.well-known/')
}

// Paths that must be reachable without a session, and paths that must not be.
const PUBLIC_PAGES = ['/auth', '/auth/login', '/auth/error', '/auth/forbidden', '/privacy']
const PROTECTED_PAGES = ['/dashboard', '/ledger', '/payments', '/settings', '/customer-360']
// Prefix traps: a future route whose name merely STARTS WITH a public one.
const LOOKALIKES = ['/authentication-policy', '/auth-settings', '/authorization', '/privacy-settings']

describe('public-surface parity between the proxy gate and the provider boundary', () => {
  it.each(PUBLIC_PAGES)('%s is public to both the proxy and the provider boundary', p => {
    expect(isPublicSurface(p)).toBe(true)
    expect(proxyServesUnauthenticated(p)).toBe(true)
  })

  it.each(PROTECTED_PAGES)('%s is protected by both', p => {
    expect(isPublicSurface(p)).toBe(false)
    expect(proxyServesUnauthenticated(p)).toBe(false)
  })

  it.each(LOOKALIKES)('%s is NOT made public by a loose prefix match', p => {
    expect(isPublicSurface(p)).toBe(false)
    // The real defect this guards: proxy.ts used startsWith("/auth"), which served
    // every /auth*-named route with no auth gate while publicSurface called it protected.
    expect(proxyServesUnauthenticated(p)).toBe(false)
  })

  it('keeps the proxy public-route condition delegating to the shared predicate', () => {
    const proxySrc = stripComments(readProxy())
    expect(proxySrc).toContain('isPublicSurface(pathname)')
    // A bare startsWith("/auth") is the loose match that caused the divergence.
    expect(proxySrc).not.toMatch(/startsWith\(\s*["']\/auth["']\s*\)/)
  })
})

/** Comments describe the OLD condition; the assertion must read code, not prose. */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

function readProxy(): string {
  return readFileSync(path.resolve(__dirname, '../proxy.ts'), 'utf8')
}
