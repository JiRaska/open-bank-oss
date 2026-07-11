// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// A small, honest status pill for the "<service> :<port>" badge that several
// operator pages show in their header. The legacy pages drove it off a bare
// `serviceUp` boolean, so a service that was merely *idle* (KEDA scale-to-zero,
// ADR-0057) rendered an alarming red "down" badge identical to a real outage.
// This component maps the graceful `unavailable` kind onto four honest visual
// states — up / idle(waking) / down / checking — so the badge never lies.

'use client'

import { CheckCircle2, XCircle, Clock, Moon } from 'lucide-react'
import type { ServiceUnavailable } from '@/lib/services/useServiceResource'

interface Props {
  /** e.g. "card-issuance :8118" — kept as-is (technical token, not translated). */
  label: string
  loading?: boolean
  /** True while an automatic wake-retry is in flight. */
  waking?: boolean
  unavailable?: ServiceUnavailable | null
  /** Bilingual labels for the state, supplied by the page (it owns the language). */
  copy: { up: string; idle: string; down: string; checking: string }
}

export function ServiceStatusBadge({ label, loading, waking, unavailable, copy }: Props) {
  // idle = deployed but asleep (scale-to-zero) or actively waking; visually calm
  // (blue moon), never the red "down" treatment.
  const idle = waking || unavailable?.kind === 'scaled_to_zero'
  const down = !idle && !!unavailable && unavailable.kind !== 'no_data'
  const checking = loading && !unavailable

  const state = checking ? 'checking' : idle ? 'idle' : down ? 'down' : 'up'
  const style = {
    up:       { bg: 'var(--success-bg)', fg: 'var(--success-text)', bd: 'var(--success-border)', icon: <CheckCircle2 size={10} /> },
    idle:     { bg: 'var(--info-bg, var(--surface-3))', fg: 'var(--info-text, var(--text-secondary))', bd: 'var(--info-border, var(--border))', icon: <Moon size={10} /> },
    down:     { bg: 'var(--danger-bg)', fg: 'var(--danger-text)', bd: 'var(--danger-border)', icon: <XCircle size={10} /> },
    checking: { bg: 'var(--surface-3)', fg: 'var(--text-tertiary)', bd: 'var(--border)', icon: <Clock size={10} /> },
  }[state]

  const title = copy[state]

  return (
    <span
      title={title}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: '5px', fontSize: '11px', fontWeight: 600,
        padding: '4px 10px', borderRadius: '20px',
        background: style.bg, color: style.fg, border: `1px solid ${style.bd}`,
      }}
    >
      {style.icon}
      {label}
    </span>
  )
}
