// SPDX-License-Identifier: Apache-2.0
import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const APP_ROOT = path.resolve(__dirname, '../app')

// Intentionally narrow: this detects the named, presentational status maps that duplicate
// components/ui/tone.ts. It does not treat arbitrary Record<string, string> data structures as UI
// debt, because that would make the ratchet noisy and encourage renaming instead of migration.
const LOCAL_STATUS_COLOUR_MAP = /(?:const|let)\s+\w*(?:STATUS|Status)(?:_?COLORS?|_?STYLES?)\s*:\s*Record</

function pageFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name)
    if (entry.isDirectory()) return pageFiles(file)
    return entry.name === 'page.tsx' ? [file] : []
  })
}

function pagesWithLocalStatusMaps(): string[] {
  return pageFiles(APP_ROOT).filter(file => LOCAL_STATUS_COLOUR_MAP.test(readFileSync(file, 'utf8')))
}

describe('ADR-0208 local status-map migration', () => {
  it('does not increase page-local named status colour maps', () => {
    // Baseline captured on main 2026-08-31. Migrations reduce this number; adding a new page-local
    // STATUS_COLOR/STATUS_STYLES map is a regression because StatusBadge + tone.ts own this decision.
    expect(pagesWithLocalStatusMaps()).toHaveLength(2)
  })
})
