// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared band layout for the topology pages. A "band" is a full-width region whose
// items (pills) wrap into centred rows; `layoutBands` stacks several bands top→bottom.
// The service map (infra/external tiers), the infra topology, and the data-lineage
// page all used a byte-identical copy of this — extracted here, parameterised by a
// per-page config (dimensions + a label→width measurer) so each page keeps its own
// look while sharing the maths.

export type BandItem = { id: string; label: string }
export type BandPos = { cx: number; cy: number; w: number }
export type BandBox<K extends string = string> = { key: K; y: number; h: number; count: number }
export type BandLayoutCfg = {
  width: number
  pad: number
  pillH: number
  bandHead: number
  bandPadY: number
  bandGap: number
  pillGap: number
  measure: (label: string) => number
}

// One band: wrap items into centred rows starting at `startY + bandHead`. Returns
// each item's centre/width (keyed by id) and the band's total height.
export function layoutBand(items: BandItem[], startY: number, cfg: BandLayoutCfg): { pos: Record<string, BandPos>; height: number } {
  const availW = cfg.width - cfg.pad * 2
  const rows: { id: string; w: number }[][] = [[]]
  let rowW = 0
  for (const it of items) {
    const w = cfg.measure(it.label)
    const cur = rows[rows.length - 1]
    if (cur.length && rowW + cfg.pillGap + w > availW) { rows.push([]); rowW = 0 }
    rows[rows.length - 1].push({ id: it.id, w })
    rowW += (cur.length ? cfg.pillGap : 0) + w
  }
  const pos: Record<string, BandPos> = {}
  let y = startY + cfg.bandHead
  for (const row of rows) {
    const total = row.reduce((s, r) => s + r.w, 0) + cfg.pillGap * Math.max(0, row.length - 1)
    let x = cfg.pad + Math.max(0, (availW - total) / 2)
    for (const r of row) { pos[r.id] = { cx: x + r.w / 2, cy: y + cfg.pillH / 2, w: r.w }; x += r.w + cfg.pillGap }
    y += cfg.pillH + cfg.pillGap
  }
  const height = cfg.bandHead + rows.length * (cfg.pillH + cfg.pillGap) - cfg.pillGap + cfg.bandPadY
  return { pos, height }
}

// Several bands stacked top→bottom (empty groups skipped). Returns the merged
// positions, per-band boxes (with item count), and the total stacked height.
export function layoutBands<K extends string>(groups: { key: K; items: BandItem[] }[], cfg: BandLayoutCfg): { pos: Record<string, BandPos>; bands: BandBox<K>[]; height: number } {
  const pos: Record<string, BandPos> = {}
  const bands: BandBox<K>[] = []
  let y = cfg.pad
  for (const g of groups) {
    if (!g.items.length) continue
    const b = layoutBand(g.items, y, cfg)
    Object.assign(pos, b.pos)
    bands.push({ key: g.key, y, h: b.height, count: g.items.length })
    y += b.height + cfg.bandGap
  }
  return { pos, bands, height: bands.length ? y - cfg.bandGap + cfg.pad : cfg.pad }
}
