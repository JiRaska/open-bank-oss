// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Drill-down of a cash-flow analysis bucket: the flows behind one currency's day (or month). The
// figures are the service's — the page never sums, converts or guesses a rate.
import type { AnalysedFlow } from './types'

export const monthKey = (iso: string): string => iso.slice(0, 7)

/** The flows of [currency] in the bucket starting [bucketDate] (a day, or a month's first day when monthly). */
export function flowsOfBucket(flows: AnalysedFlow[], currency: string, bucketDate: string, monthly: boolean): AnalysedFlow[] {
  return flows.filter(f => f.currency === currency && (monthly ? monthKey(f.date) === monthKey(bucketDate) : f.date === bucketDate))
}
