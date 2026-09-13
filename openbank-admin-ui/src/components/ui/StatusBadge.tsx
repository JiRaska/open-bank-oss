// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { BADGE_CLASS, DOT_CLASS, type Tone, statusTone } from './tone'

type StatusBadgeProps = {
  /** Domain status or severity, e.g. `ACTIVE`, `PENDING_SCA`, `CRITICAL`. Tone is derived from it. */
  status: string | null | undefined
  /** Overrides the derived tone. Use only when the same value means different things per domain. */
  tone?: Tone
  /** Visible text. Defaults to `status`; pass a translated label for a user-facing surface. */
  label?: string
  /** Renders a leading status dot alongside the text. */
  withDot?: boolean
  /** Decorative leading icon when the status has a recognizable domain symbol. */
  leading?: ReactNode
  className?: string
}

/**
 * The one way admin-ui renders a status (ADR-0208 D2). Colour comes from [statusTone] and the
 * `globals.css` `.badge-*` classes — never from a literal in a page.
 *
 * `label` exists so a page can pass a translated string while the tone still derives from the raw
 * domain value: translating the value first and then colouring it is how a Czech-locale page ends up
 * with every badge neutral.
 */
export function StatusBadge({ status, tone, label, withDot = false, leading, className }: StatusBadgeProps) {
  const resolved = tone ?? statusTone(status)
  return (
    <span className={cn(BADGE_CLASS[resolved], className)}>
      {withDot && <span className={DOT_CLASS[resolved]} aria-hidden="true" />}
      {leading && <span aria-hidden="true">{leading}</span>}
      {label ?? status ?? '—'}
    </span>
  )
}
