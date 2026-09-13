// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared plan-vs-reality status vocabulary (ADR-0072 follow-up).
//
// One source of truth for the live / partial / planned status primitives used by
// the docs "plan vs reality" views — /docs/cloud-architecture (monolingual EN)
// and /docs/customer-app (bilingual cs/en). The labels are bilingual so both
// callers are served: a monolingual page reads `label.en`, a bilingual page
// picks `label.cs` / `label.en` off the language toggle. Colours, backgrounds,
// borders and the dot icon are identical across both pages — that visual
// encoding is what this module exists to keep from drifting.

import { CheckCircle2, CircleDashed, Circle } from 'lucide-react'
import type { Tone } from '@/components/ui/tone'

export type Status = 'live' | 'partial' | 'planned'

export interface StatusLabel {
  cs: string
  en: string
}

export interface StatusMeta {
  label: StatusLabel
  tone: Tone
  text: string
  background: string
  border: string
  Icon: React.ElementType
}

export const STATUS_META: Record<Status, StatusMeta> = {
  live:    { label: { cs: 'Live (běží dnes)',             en: 'Live (running today)' },           tone: 'success', text: 'var(--success-text)', background: 'var(--success-bg)', border: 'var(--success-border)', Icon: CheckCircle2 },
  partial: { label: { cs: 'Částečně (nasazeno, neúplné)', en: 'Partial (deployed, incomplete)' }, tone: 'warning', text: 'var(--warning-text)', background: 'var(--warning-bg)', border: 'var(--warning-border)', Icon: CircleDashed },
  planned: { label: { cs: 'Plánováno',                    en: 'Planned' },                        tone: 'neutral', text: 'var(--text-secondary)', background: 'var(--surface-2)', border: 'var(--border-strong)', Icon: Circle },
}

export function StatusDot({ status, size = 13 }: { status: Status; size?: number }) {
  const m = STATUS_META[status]
  return <m.Icon size={size} aria-hidden="true" style={{ color: m.text, flexShrink: 0 }} />
}
