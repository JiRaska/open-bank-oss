// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/security/excellence/page.tsx'), 'utf8')
const rawColour = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])|rgba?\(/
const legacyFallback = /var\(--[^,)]+,\s*#[0-9a-fA-F]{3,6}\)/

describe('Security Excellence presentation and snapshot contract', () => {
  it('uses shared semantics for grades and domain states', () => {
    expect(source).not.toMatch(rawColour)
    expect(source).not.toMatch(legacyFallback)
    for (const tone of ['success', 'warning', 'danger', 'info']) {
      expect(source).toContain(`var(--${tone}-text)`)
      expect(source).toContain(`var(--${tone}-bg)`)
      expect(source).toContain(`var(--${tone}-border)`)
    }
  })

  it('shares one KPI snapshot across every KPI-derived domain per refresh', () => {
    expect(source.match(/fetchKpis\(\)/g)).toHaveLength(2) // declaration + one request per refresh
    expect(source.match(/const kpisSnapshot = fetchKpis\(\)/g)).toHaveLength(1)
    expect(source).not.toMatch(/const snap = await fetchKpis\(\)/)
    for (const domain of ['segmentation', 'freshness', 'credentials', 'fuzz', 'threatmodels', 'mttr']) {
      expect(source).toContain(`${domain}: async () =>`)
    }
  })
})
