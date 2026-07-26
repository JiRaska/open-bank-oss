// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { TONE_TEXT_CLASS, type Tone } from './tone'

type CardProps = {
  /** Optional heading, rendered with the existing `.section-title` treatment. */
  title?: string
  /** Right-aligned header slot (a filter, a link, a count). */
  aside?: ReactNode
  children: ReactNode
  className?: string
}

/** Content container (ADR-0208 D1), on the existing `.card` class. */
export function Card({ title, aside, children, className }: CardProps) {
  return (
    <div className={cn('card', className)}>
      {(title || aside) && (
        <div className="flex items-center justify-between mb-4">
          {title && <h2 className="section-title">{title}</h2>}
          {aside}
        </div>
      )}
      {children}
    </div>
  )
}

type StatCardProps = {
  label: string
  value: ReactNode
  /** Secondary line under the value — a delta, a unit, a timestamp. */
  hint?: string
  /**
   * Tints the label when the metric itself carries a verdict (a GO count, a failure count).
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
      <div className="stat-value">{value}</div>
      {hint && <div className="stat-hint">{hint}</div>}
    </div>
  )
}
