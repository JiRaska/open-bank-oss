// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Recharts writes fill/stroke as SVG attributes, where CSS variables do not resolve (same
// constraint as components/lending/risk/palette.ts). Same green/red as the credit-risk console so
// "money in" and "money out" read identically across the two risk surfaces.
export const C_INFLOW = '#22c55e'
export const C_OUTFLOW = '#ef4444'
export const C_GAP = '#6366f1'
