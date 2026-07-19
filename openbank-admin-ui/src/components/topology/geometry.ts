// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared geometry primitives for the animated topology pages (service map,
// infra topology, lineage, …). Extracted verbatim so every page draws edges
// and mixes colours identically — the layout stays per-page, only the maths is shared.

export type Pt = { cx: number; cy: number }
export type Half = { hw: number; hh: number }

// Curved, box-trimmed quadratic edge path between two node centres. Each endpoint
// is trimmed to the node's bounding half-extent (hw/hh) so the line meets the node
// edge cleanly; the control point is offset perpendicular so parallel edges fan out.
// Returns the path `d` plus a label anchor (lx,ly) ~62% toward the target.
export function edgeGeometry(a: Pt, b: Pt, aHalf: Half, bHalf: Half) {
  const dx = b.cx - a.cx, dy = b.cy - a.cy
  const dist = Math.hypot(dx, dy) || 1
  const ux = dx / dist, uy = dy / dist
  const boxT = (hw: number, hh: number) => {
    const tx = Math.abs(ux) < 1e-6 ? Infinity : hw / Math.abs(ux)
    const ty = Math.abs(uy) < 1e-6 ? Infinity : hh / Math.abs(uy)
    return Math.min(tx, ty)
  }
  const ta = boxT(aHalf.hw, aHalf.hh) + 2
  const tb = boxT(bHalf.hw, bHalf.hh) + 9 // extra gap for the arrowhead
  const sx = a.cx + ux * ta, sy = a.cy + uy * ta
  const ex = b.cx - ux * tb, ey = b.cy - uy * tb
  const mx = (sx + ex) / 2, my = (sy + ey) / 2
  const curve = Math.min(46, dist * 0.14)
  const cpx = mx - uy * curve, cpy = my + ux * curve
  const t = 0.62, mt = 1 - t
  const lx = mt * mt * sx + 2 * mt * t * cpx + t * t * ex
  const ly = mt * mt * sy + 2 * mt * t * cpy + t * t * ey
  return { d: `M ${sx} ${sy} Q ${cpx} ${cpy} ${ex} ${ey}`, lx, ly }
}

// Linearly interpolate a hex colour toward a target hex by ratio r (0..1).
export function mixHex(hex: string, target: string, r: number): string {
  const a = parseInt(hex.slice(1), 16), b = parseInt(target.slice(1), 16)
  const ch = (s: number) => Math.round(((a >> s) & 255) + (((b >> s) & 255) - ((a >> s) & 255)) * r)
  return '#' + ((1 << 24) + (ch(16) << 16) + (ch(8) << 8) + ch(0)).toString(16).slice(1)
}

// Stable, SVG-safe id for an edge <path> (so <mpath href> can reference it). The
// `prefix` namespaces ids per page so two topologies on one document never collide.
export function pathId(prefix: string, a: string, b: string, i: number): string {
  return `${prefix}-${a}-${b}-${i}`.replace(/[^a-zA-Z0-9_-]/g, '_')
}
