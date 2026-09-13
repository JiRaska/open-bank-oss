// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (relative: string) => fs.readFileSync(path.join(process.cwd(), relative), 'utf8')

describe('EmptyState primitive contract', () => {
  const primitive = read('src/components/ui/EmptyState.tsx')
  const styles = read('src/app/globals.css')
  const transactions = read('src/app/transactions/page.tsx')

  it('announces explanatory copy and supports a caller-owned recovery action', () => {
    expect(primitive).toContain('role="status"')
    expect(primitive).toContain('aria-live="polite"')
    expect(primitive).toContain("className=\"ui-empty-state-action\"")
    expect(transactions).toContain("t('Vymazat kritéria', 'Clear search criteria')")
    expect(transactions).toContain('Prázdný výsledek znamená úspěšně dokončené hledání')
  })

  it('uses design tokens instead of a light-only local palette', () => {
    const start = styles.indexOf('.ui-empty-state {')
    const emptyStateStyles = styles.slice(start, styles.indexOf('@media (max-width: 840px)', start))
    expect(emptyStateStyles).toContain('var(--surface)')
    expect(emptyStateStyles).toContain('var(--surface-2)')
    expect(emptyStateStyles).toContain('var(--text-primary)')
    expect(emptyStateStyles).not.toMatch(/#[0-9a-f]{3,8}/i)
  })
})
