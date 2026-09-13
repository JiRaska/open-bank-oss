// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const source = (page: 'ledger' | 'transactions') =>
  readFileSync(new URL(`../app/${page}/page.tsx`, import.meta.url), 'utf8')

describe('operational insights use a live window', () => {
  it.each(['ledger', 'transactions'] as const)('%s does not inherit business-search dates', page => {
    const component = source(page).match(/<ContextualInsights[\s\S]*?\/>/)?.[0]

    expect(component).toBeDefined()
    expect(component).not.toMatch(/\bfrom=/)
    expect(component).not.toMatch(/\bto=/)
  })
})
