// SPDX-License-Identifier: Apache-2.0

import type { Control } from './schema'

// Keep this pure client-safe derivation separate from schema.ts. The manifest loader validates
// with Zod on the server; the interactive view must not ship that validator merely to calculate
// a weighted percentage from already-validated controls.
export function overallScore(controls: Control[]): number {
  const weightSum = controls.reduce((sum, control) => sum + control.weight, 0)
  if (weightSum === 0) return 0
  return Math.round(
    controls.reduce((sum, control) => sum + control.weight * control.pct, 0) / weightSum,
  )
}
