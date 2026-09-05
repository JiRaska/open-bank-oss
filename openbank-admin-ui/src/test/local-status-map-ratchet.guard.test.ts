// SPDX-License-Identifier: Apache-2.0
import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const APP_ROOT = path.resolve(__dirname, '../app')

// Intentionally narrow: this detects the named, presentational status maps that duplicate
// components/ui/tone.ts. It does not treat arbitrary Record<string, string> data structures as UI
// debt, because that would make the ratchet noisy and encourage renaming instead of migration.
//
// KNOWN BLIND SPOT — do not read this count as a census of page-local status styling.
// The regex below matches a *named map declaration* only. The other way a page hand-rolls status
// colour is an inline ternary in a style prop:
//
//     background: isHit ? 'var(--danger-bg)' : isPending ? 'var(--warning-bg)' : 'var(--success-bg)'
//
// That shape declares nothing, so it matches this regex never, and this ratchet has always been
// structurally unable to see it. It is the shape that produced the ESCALATED-renders-green defect
// in src/app/sanctions/page.tsx, where a five-value SanctionsCheckStatus was discriminated by two
// branches and every unlisted value fell through to success. statusTone() cannot fail that way:
// an unrecognised value resolves to 'neutral', never to 'success'.
//
// The blind spot is documented rather than closed on purpose. A sweep on 2026-08-31 found six
// occurrences of that ternary shape across five pages, and only the sanctions ones were defects —
// day-end, system/agent, system/health and system/inventory all discriminate a genuine boolean or
// count (or, in system/health's case, exhaust a three-value `boolean | 'unknown'` domain with the
// unknown case landing on warning). A count-based ratchet over a shape that is usually CORRECT
// would be a false-debt metric: it cannot distinguish `isUp ? success : danger` from a truncated
// enum, so it would push authors to launder correct code through statusTone() to satisfy a number.
// The property that actually decides it is semantic — "does the discriminator have more values
// than the branches enumerate" — and that needs the producing enum or openapi schema, which this
// file cannot read. If this is ever closed, close it with that check, not with a wider regex.
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
    // Re-baselined on main 2026-08-31: the real count is 1 (src/app/swift/[id]/page.tsx). The
    // previous bound of 11 was the historical debt, left untightened when the assertion moved from
    // toHaveLength(11) to toBeLessThanOrEqual(11) — so migrations paid the debt from 11 down to 1
    // and the ratchet kept ten free slots. Measured: appending a new named status map to an
    // unrelated page still passed at <=11 (exit 0). A ratchet with slack is green about the
    // regression it exists to catch, so the bound has to follow the count down.
    // Migrations reduce this number; adding a new page-local STATUS_COLOR/STATUS_STYLES map is a
    // regression because StatusBadge + tone.ts own this decision.
    expect(pagesWithLocalStatusMaps().length).toBeLessThanOrEqual(1)
  })
})
