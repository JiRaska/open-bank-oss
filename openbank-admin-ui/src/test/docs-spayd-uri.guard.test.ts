// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (file: string) => fs.readFileSync(path.resolve(process.cwd(), 'src', file), 'utf8')
const page = read('app/docs/spayd-uri/page.tsx')

describe('Camera QR (SPAYD-URI) docs page', () => {
  it('is reachable from the docs hub, the sidebar and the QRlessPay page', () => {
    expect(read('components/docs/DocsHub.tsx')).toContain("href: '/docs/spayd-uri'")
    expect(read('components/layout/Sidebar.tsx')).toContain("href: '/docs/spayd-uri'")
    expect(read('app/docs/qrlesspay/page.tsx')).toContain('href="/docs/spayd-uri"')
  })

  it('uses theme tokens, not fixed presentation colours', () => {
    expect(page).not.toMatch(/["']#[0-9a-f]{3,8}\b/i)
    expect(page).not.toMatch(/rgba?\(\s*\d/i)
  })

  it('offers the shared print/PDF export', () => {
    expect(page).toContain('window.print()')
    expect(page).toContain('docs-print-action')
    expect(page).toContain('docs-printable')
  })

  it('states the non-breaking rule: bare SPAYD stays the default', () => {
    // The page is the operator-facing record of the rollout order. If someone flips the app
    // default, this sentence has to change in the same PR — that is the point of pinning it.
    expect(page).toContain('Výchozí zůstává holý SPAYD')
    expect(page).toContain("state: 'done'")
    expect(page).toContain("state: 'now'")
    expect(page).toContain("state: 'later'")
  })
})
