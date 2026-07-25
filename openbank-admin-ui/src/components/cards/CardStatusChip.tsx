// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One status chip for the whole card surface (list, detail, lifecycle map,
// confirmation dialog). A card's status is the single most-read thing on the
// screen; two screens rendering it in different colours is how an operator stops
// trusting the colour at all.
//
// The status token itself (ACTIVE, BLOCKED, …) is the service's own enum value and
// is shown verbatim in both languages on purpose: it is the string that appears in
// the API, the audit trail and the alerts, so translating it would make the console
// harder to correlate, not easier to read.

'use client'

export const CARD_STATUS_COLORS: Record<string, { bg: string; text: string; border: string }> = {
  ACTIVE: { bg: 'var(--success-bg)', text: 'var(--success-text)', border: 'var(--success-border)' },
  SUSPENDED: { bg: 'var(--accent-bg)', text: 'var(--accent-text)', border: 'var(--accent-border)' },
  BLOCKED: { bg: 'var(--danger-bg)', text: 'var(--danger-text)', border: 'var(--danger-border)' },
  EXPIRED: { bg: 'var(--surface-3)', text: 'var(--text-tertiary)', border: 'var(--border)' },
  CANCELLED: { bg: 'var(--surface-3)', text: 'var(--text-tertiary)', border: 'var(--border)' },
  PENDING: { bg: 'var(--warning-bg)', text: 'var(--warning-text)', border: 'var(--warning-border)' },
}

export function cardStatusColor(status: string | null | undefined) {
  return CARD_STATUS_COLORS[status ?? ''] ?? CARD_STATUS_COLORS.PENDING
}

export function CardStatusChip({
  status, current, small,
}: {
  status: string
  /** Ring the chip — "this is the state of the card you are looking at". */
  current?: boolean
  small?: boolean
}) {
  const c = cardStatusColor(status)
  return (
    <span style={{
      padding: small ? '1px 7px' : '3px 10px',
      borderRadius: '10px',
      fontSize: small ? '10px' : '11px',
      fontWeight: 700,
      whiteSpace: 'nowrap',
      background: c.bg,
      color: c.text,
      border: `1px solid ${current ? 'var(--accent)' : c.border}`,
      boxShadow: current ? '0 0 0 3px var(--accent-bg)' : 'none',
    }}>{status}</span>
  )
}
