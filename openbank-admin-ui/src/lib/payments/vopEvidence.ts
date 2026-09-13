// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export type VopVerdict = 'match' | 'close_match' | 'no_match' | 'no_data'

export interface VopEvidence {
  status: VopVerdict
  matchedName: string | null
  verifiedAt: string
}

const VERDICTS = new Set<VopVerdict>(['match', 'close_match', 'no_match', 'no_data'])
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/

export function parseVopEvidence(value: unknown): VopEvidence | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const candidate = value as Record<string, unknown>
  if (typeof candidate.status !== 'string' || !VERDICTS.has(candidate.status as VopVerdict)) return null
  if (typeof candidate.verifiedAt !== 'string' || !RFC3339.test(candidate.verifiedAt) || !Number.isFinite(Date.parse(candidate.verifiedAt))) return null

  const status = candidate.status as VopVerdict
  const matchedName = candidate.matchedName
  if (status === 'close_match') {
    if (typeof matchedName !== 'string' || matchedName.trim().length === 0 || matchedName.length > 140) return null
  } else if (matchedName !== undefined && matchedName !== null) {
    // Never let an anomalous response turn MATCH/NO_MATCH into an account-name disclosure.
    return null
  }

  return { status, matchedName: status === 'close_match' ? matchedName as string : null, verifiedAt: candidate.verifiedAt }
}
