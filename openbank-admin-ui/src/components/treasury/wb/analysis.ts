// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Aggregation of cash-flow analysis rows for display: per date (or per month), with the running
// total when the service did not send one. Display arithmetic only — the figures are the service's.
import type { AnalysisFlow } from './types'

export const monthKey = (iso: string): string => iso.slice(0, 7)

export type DateRow = { key: string; total: number; cumulative: number | null; flows: AnalysisFlow[] }

export function groupByDate(flows: AnalysisFlow[], monthly: boolean): DateRow[] {
  const map = new Map<string, AnalysisFlow[]>()
  for (const f of [...flows].sort((a, b) => a.date.localeCompare(b.date))) {
    const k = monthly ? monthKey(f.date) : f.date
    map.set(k, [...(map.get(k) ?? []), f])
  }
  let running = 0
  return [...map.entries()].map(([key, list]) => {
    const total = Math.round(list.reduce((s, f) => s + f.amount, 0) * 100) / 100
    running = Math.round((running + total) * 100) / 100
    const stated = list[list.length - 1].cumulative
    return { key, total, cumulative: stated ?? running, flows: list }
  })
}
