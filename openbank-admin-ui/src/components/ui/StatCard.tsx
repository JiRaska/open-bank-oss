// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { TONE_TEXT_CLASS, type Tone } from './tone'

type StatCardProps = {
  label: string
  value: ReactNode
  /** Secondary line under the value — a delta, a unit, a timestamp. */
  hint?: string
  /**
   * Tints the label AND the value when the metric itself carries a verdict (a GO count, a failure
   * count) — the number is the thing being read, so tinting only the label loses the signal.
   * Omit for a plain metric: colouring every tile makes none of them stand out.
   */
  tone?: Tone
  /** Leading icon in the label row. */
  icon?: ReactNode
  className?: string
}

/** Single-metric tile (ADR-0208 D1), on the existing `.stat-card` class. */
export function StatCard({ label, value, hint, tone, icon, className }: StatCardProps) {
  return (
    <div className={cn('stat-card', className)}>
      <div className={cn('stat-label flex items-center gap-1.5', tone && TONE_TEXT_CLASS[tone])}>
        {icon}
        {label}
      </div>
      <div className={cn('stat-value', tone && TONE_TEXT_CLASS[tone])}>{value}</div>
      {hint && <div className="stat-hint">{hint}</div>}
    </div>
  )
}
