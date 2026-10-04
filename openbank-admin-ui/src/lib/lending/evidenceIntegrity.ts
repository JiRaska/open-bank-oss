// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

/**
 * What lending-service's evidence bundle says about its own integrity (ADR-0214 D3, #11900).
 *
 * Since the bundle is read from audit-service's tamper-evident chain, the response carries
 * `attestation`, a bundle-level `tampered` / `truncated`, and a per-event `hashStatus`. Every field is
 * OPTIONAL here on purpose: an older lending-service (`attestation: local-outbox`) sends none of them,
 * and the page must render exactly as before rather than inventing a verdict. Absent is "unknown",
 * never "verified".
 */
export type HashStatus = 'VERIFIED' | 'MISMATCH' | 'LEGACY_UNVERIFIABLE' | 'UNCHAINED'

export type EvidenceIntegrity = {
  /** `audit-chain` when the trail comes from the tamper-evident chain; null when the backend did not say. */
  source: string | null
  /** True only when the backend said so. A bundle-level flag: it covers events the table does not show. */
  tampered: boolean
  truncated: boolean
}

const HASH_STATUSES: readonly HashStatus[] = ['VERIFIED', 'MISMATCH', 'LEGACY_UNVERIFIABLE', 'UNCHAINED']

export function evidenceIntegrity(body: unknown): EvidenceIntegrity {
  const b = (body ?? {}) as Record<string, unknown>
  return {
    source: typeof b.attestation === 'string' ? b.attestation : null,
    tampered: b.tampered === true,
    truncated: b.truncated === true,
  }
}

/** An unknown or missing value is null — rendered as nothing, not as a pass. */
export function hashStatusOf(value: unknown): HashStatus | null {
  return typeof value === 'string' && (HASH_STATUSES as readonly string[]).includes(value) ? (value as HashStatus) : null
}

/** How a row's status is shown: an altered row is an alarm, an unverifiable one is a caveat, a verified one is quiet. */
export function hashBadge(status: HashStatus | null): 'altered' | 'unverifiable' | null {
  if (status === 'MISMATCH') return 'altered'
  if (status === 'LEGACY_UNVERIFIABLE' || status === 'UNCHAINED') return 'unverifiable'
  return null
}
