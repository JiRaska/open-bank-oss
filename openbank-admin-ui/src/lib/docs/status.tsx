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

export type Status = 'live' | 'partial' | 'planned'

export interface StatusLabel {
  cs: string
  en: string
}

export interface StatusMeta {
  label: StatusLabel
  color: string
  bg: string
  border: string
  Icon: React.ElementType
}

export const STATUS_META: Record<Status, StatusMeta> = {
  live:    { label: { cs: 'Live (běží dnes)',             en: 'Live (running today)' },         color: '#059669', bg: '#ecfdf5', border: '#6ee7b7', Icon: CheckCircle2 },
  partial: { label: { cs: 'Částečně (nasazeno, neúplné)', en: 'Partial (deployed, incomplete)' }, color: '#d97706', bg: '#fffbeb', border: '#fcd34d', Icon: CircleDashed },
  planned: { label: { cs: 'Plánováno',                    en: 'Planned' },                      color: '#94a3b8', bg: '#f8fafc', border: '#cbd5e1', Icon: Circle },
}

export function StatusDot({ status, size = 13 }: { status: Status; size?: number }) {
  const m = STATUS_META[status]
  return <m.Icon size={size} style={{ color: m.color, flexShrink: 0 }} />
}
