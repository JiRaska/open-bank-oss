// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect } from 'vitest'
import { edgeGeometry, mixHex, pathId } from '@/components/topology/geometry'

describe('topology geometry primitives', () => {
  it('edgeGeometry returns a quadratic path + a label anchor between the nodes', () => {
    const g = edgeGeometry({ cx: 0, cy: 0 }, { cx: 100, cy: 0 }, { hw: 10, hh: 10 }, { hw: 10, hh: 10 })
    expect(g.d).toMatch(/^M [\d.-]+ [\d.-]+ Q [\d.-]+ [\d.-]+ [\d.-]+ [\d.-]+$/)
    // horizontal edge → the trimmed start is past node A's half-width, end before B's
    expect(g.lx).toBeGreaterThan(10)
    expect(g.lx).toBeLessThan(100)
    expect(Number.isFinite(g.ly)).toBe(true)
  })

  it('edgeGeometry handles a vertical edge (ux≈0) with finite output', () => {
    const g = edgeGeometry({ cx: 0, cy: 0 }, { cx: 0, cy: 100 }, { hw: 10, hh: 10 }, { hw: 10, hh: 10 })
    expect(g.d.startsWith('M')).toBe(true)
    expect(Number.isFinite(g.lx)).toBe(true)
    expect(Number.isFinite(g.ly)).toBe(true)
  })

  it('mixHex interpolates hex colours', () => {
    expect(mixHex('#000000', '#ffffff', 0)).toBe('#000000')
    expect(mixHex('#000000', '#ffffff', 1)).toBe('#ffffff')
    expect(mixHex('#000000', '#ffffff', 0.5)).toBe('#808080')
  })

  it('pathId namespaces by prefix and sanitises SVG-unsafe chars', () => {
    expect(pathId('fx', 'account', 'ledger', 3)).toBe('fx-account-ledger-3')
    // colons (tier ids like infra:kafka) and other chars → underscore
    expect(pathId('ix', 'infra:kafka', 'ext:s3', 0)).toBe('ix-infra_kafka-ext_s3-0')
  })
})
