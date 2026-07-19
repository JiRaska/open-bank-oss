// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect } from 'vitest'
import { layoutBand, layoutBands, type BandLayoutCfg } from '@/components/topology/layout'

const CFG: BandLayoutCfg = {
  width: 1000, pad: 50, pillH: 30, bandHead: 20, bandPadY: 10, bandGap: 20, pillGap: 10,
  measure: () => 100, // fixed 100px pills → availW 900 fits 9 per row
}

describe('topology band layout', () => {
  it('layoutBand centres a single row and reports height', () => {
    const { pos, height } = layoutBand([{ id: 'a', label: 'a' }, { id: 'b', label: 'b' }], 0, CFG)
    // two 100px pills + 10 gap = 210, centred in availW 900 → startX = 50 + (900-210)/2 = 395
    expect(pos.a.cx).toBe(395 + 50) // first pill centre = startX + w/2
    expect(pos.b.cx).toBe(pos.a.cx + 110)
    expect(pos.a.cy).toBe(0 + 20 + 30 / 2) // startY + bandHead + pillH/2
    // one row: bandHead + 1*(pillH+pillGap) - pillGap + bandPadY
    expect(height).toBe(20 + (30 + 10) - 10 + 10)
  })

  it('layoutBand wraps to a second row past availW', () => {
    const items = Array.from({ length: 10 }, (_, i) => ({ id: `n${i}`, label: 'x' }))
    const { pos, height } = layoutBand(items, 0, CFG) // 9 fit per row → 2 rows
    expect(pos.n0.cy).not.toBe(pos.n9.cy) // n9 dropped to row 2
    expect(height).toBe(20 + 2 * (30 + 10) - 10 + 10)
  })

  it('layoutBands stacks bands, skips empty groups, counts items', () => {
    const r = layoutBands([
      { key: 'g1', items: [{ id: 'a', label: 'a' }] },
      { key: 'g2', items: [] }, // skipped
      { key: 'g3', items: [{ id: 'b', label: 'b' }] },
    ], CFG)
    expect(r.bands.map(b => b.key)).toEqual(['g1', 'g3'])
    expect(r.bands[0].count).toBe(1)
    expect(r.bands[1].y).toBeGreaterThan(r.bands[0].y) // stacked below
    expect(r.pos.a).toBeDefined()
    expect(r.pos.b).toBeDefined()
  })

  it('layoutBands returns just the pad height when everything is empty', () => {
    const r = layoutBands([{ key: 'g', items: [] }], CFG)
    expect(r.bands).toHaveLength(0)
    expect(r.height).toBe(CFG.pad)
  })
})
