// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * The single semantic status vocabulary for admin-ui (ADR-0208 D2).
 *
 * Before this existed, 35 of 86 pages carried their own `Record<string, string>` status map and 46
 * carried raw hex literals — 1 133 of them, the same red/amber/green triple re-typed per page while
 * `globals.css` already defined `.badge-*` and `.status-dot-*` for exactly this. A colour decided
 * per page cannot be reviewed, themed, or kept consistent.
 *
 * A [Tone] maps to the CSS classes already in `globals.css`; it never carries a colour value itself.
 * That indirection is the point: dark mode, contrast tuning and rebranding happen in one stylesheet.
 */
export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'accent'

export const BADGE_CLASS: Record<Tone, string> = {
  success: 'badge badge-success',
  warning: 'badge badge-warning',
  danger: 'badge badge-danger',
  info: 'badge badge-info',
  neutral: 'badge badge-neutral',
  accent: 'badge badge-accent',
}

/**
 * Fixed-size square swatch classes, for a score or level tile.
 *
 * Composes `.tone-swatch` (geometry) with the `.badge-*` colour rule — which is safe precisely
 * because `.badge-*` carries ONLY background/colour/border, while padding and border-radius live on
 * `.badge`. Reusing `BADGE_CLASS` here and overriding width/height inline does not work: `.badge`
 * sets `padding: 4px 10px; border-radius: 20px`, and under the global `box-sizing: border-box` an
 * 18px-wide box then has zero content width and renders as a circle.
 */
export const SWATCH_CLASS: Record<Tone, string> = {
  success: 'tone-swatch badge-success',
  warning: 'tone-swatch badge-warning',
  danger: 'tone-swatch badge-danger',
  info: 'tone-swatch badge-info',
  neutral: 'tone-swatch badge-neutral',
  accent: 'tone-swatch badge-accent',
}

/**
 * Text-colour classes for a tone, for the cases where a badge is too heavy — a tinted metric label,
 * an inline verdict. Defined in `globals.css` alongside the badge classes so the token stays the
 * single place a colour is chosen.
 */
export const TONE_TEXT_CLASS: Record<Tone, string> = {
  success: 'tone-text-success',
  warning: 'tone-text-warning',
  danger: 'tone-text-danger',
  info: 'tone-text-info',
  neutral: 'tone-text-neutral',
  accent: 'tone-text-accent',
}

export const DOT_CLASS: Record<Tone, string> = {
  success: 'status-dot status-dot-green',
  warning: 'status-dot status-dot-yellow',
  danger: 'status-dot status-dot-red',
  info: 'status-dot status-dot-blue',
  neutral: 'status-dot status-dot-gray',
  accent: 'status-dot status-dot-blue',
}

/**
 * Domain status values grouped by tone. Derived from the values that actually appear across
 * `src/app/**\/page.tsx` — not an invented taxonomy, which is why several near-synonyms
 * (`SETTLED`/`COMPLETED`/`CLEARED`) appear separately rather than being normalised away.
 *
 * Matching is case-insensitive and exact. A value NOT listed here resolves to `neutral`, and that
 * is deliberate: an unrecognised status must render as "no opinion", never as green. Guessing by
 * substring ("does it contain 'FAIL'") is how `PENDING_FAILOVER` ends up red.
 */
const TONE_BY_STATUS: Record<string, Tone> = {
  // Terminal-good
  ACTIVE: 'success',
  APPROVED: 'success',
  CLEARED: 'success',
  COMPLETED: 'success',
  DELIVERED: 'success',
  ENABLED: 'success',
  HEALTHY: 'success',
  OK: 'success',
  PASS: 'success',
  RESOLVED: 'success',
  SENT: 'success',
  SETTLED: 'success',
  VERIFIED: 'success',

  // In-flight / needs attention but not wrong
  DEGRADED: 'warning',
  DRAFT: 'warning',
  IN_PROGRESS: 'warning',
  PENDING: 'warning',
  PENDING_APPROVAL: 'warning',
  PENDING_CONFIRMATION: 'warning',
  PENDING_EVIDENCE: 'warning',
  PENDING_REVIEW: 'warning',
  PENDING_SCA: 'warning',
  PROCESSING: 'warning',
  QUEUED: 'warning',
  WARN: 'warning',
  WARNING: 'warning',

  // Terminal-bad or actively blocking
  BLOCKED: 'danger',
  BOUNCED: 'danger',
  CRITICAL: 'danger',
  ERROR: 'danger',
  ESCALATED: 'danger',
  FAIL: 'danger',
  FAILED: 'danger',
  REJECTED: 'danger',
  SUSPENDED: 'danger',

  // Terminal-neutral: over, but not a failure.
  //
  // EXPIRED and REVOKED are DOMAIN-AMBIGUOUS and the default here is the consent reading, where a
  // revocation is the customer exercising Art. 7(3) normally and an expiry is the clock doing its
  // job — neither is an incident, and colouring them red on a screen operators scan for real
  // problems is noise. Other domains legitimately disagree: `src/app/pid/page.tsx` renders EXPIRED
  // as warning (an unusable credential the holder must renew) and REVOKED as danger (a credential
  // withdrawn for cause). Those pages must pass an explicit `tone` to StatusBadge when they migrate
  // — see its `tone` prop. Do not "fix" this map to match one domain; that just moves the conflict.
  CANCELLED: 'neutral',
  ARCHIVED: 'neutral',
  CLOSED: 'neutral',
  CLEAN: 'neutral',
  INACTIVE: 'neutral',
  DISABLED: 'neutral',
  EXPIRED: 'neutral',
  MERGED: 'neutral',
  REVOKED: 'neutral',
  SUPERSEDED: 'neutral',
  SUPPRESSED: 'neutral',
  UNKNOWN: 'neutral',

  // Retained in the catalog for auditability, but intentionally not offered to customers.
  DEPRECATED: 'danger',

  // Severity scales, which share the vocabulary
  HIGH: 'danger',
  MEDIUM: 'warning',
  LOW: 'info',
  INFO: 'info',

  // Go/no-go gates (production readiness, deploy gates)
  GO: 'success',
  'NO-GO': 'danger',
  NOGO: 'danger',
  // A released component with no gitops workload at all (#5760). Neutral on purpose: it is not a
  // failed control (danger) and emphatically not a pass (success) — there is nothing running to
  // judge. Reached only in place of NO-GO; see the collectors' computeGate.
  'NOT-DEPLOYED': 'neutral',
}

/**
 * Resolves a domain status or severity to a [Tone]. Unrecognised values resolve to `neutral` —
 * never `success`, so a status this helper has not been taught can never render as a green tick.
 */
export function statusTone(value: string | null | undefined): Tone {
  if (!value) return 'neutral'
  return TONE_BY_STATUS[value.trim().toUpperCase()] ?? 'neutral'
}

/** Convenience: the badge classes for a domain status in one call. */
export function statusBadgeClass(value: string | null | undefined): string {
  return BADGE_CLASS[statusTone(value)]
}

/** Convenience: the status-dot classes for a domain status in one call. */
export function statusDotClass(value: string | null | undefined): string {
  return DOT_CLASS[statusTone(value)]
}
