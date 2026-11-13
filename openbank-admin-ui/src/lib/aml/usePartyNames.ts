// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Resolve AML case parties to the names an officer actually triages by.
//
// aml-service stores only `partyId` and a machine `customerReference` (`onboarding-<uuid>`), so
// the register used to show UUIDs where an AML officer needs "Jan Novák". The name lives in
// party-service; this is the same cross-service lookup the card and campaign screens make, with
// the same three properties: deduplicated (a party with several cases is asked for once),
// bounded (a fixed cap and a small concurrency window, so a page of 50 cases cannot burst
// party-service), and non-fatal (an unresolved name stays `undefined` and the UI keeps the
// reference — party-service masks or refuses names for roles without directory access, and
// that must read as "name not available", never as an empty register).

import { useEffect, useState } from 'react'
import { svcUrl } from '@/lib/services/bff'
import { partyDisplayName } from '@/lib/aml/amlCaseContract'

export const MAX_PARTY_LOOKUPS = 60
const CONCURRENCY = 6

export interface AmlPartySummary {
  name: string | null
  kycStatus: string | null
  partyType: string | null
  status: string | null
  /** party-service masked the record for this role (`piiMasked`), so `name` may be a mask. */
  masked: boolean
}

export function parsePartySummary(raw: unknown): AmlPartySummary | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const o = raw as Record<string, unknown>
  const text = (v: unknown) => (typeof v === 'string' && v.trim() ? v : null)
  return {
    name: partyDisplayName({ legalName: text(o.legalName), tradingName: text(o.tradingName) }),
    kycStatus: text(o.kycStatus),
    partyType: text(o.partyType),
    status: text(o.status),
    masked: o.piiMasked === true,
  }
}

export async function fetchPartySummary(partyId: string, signal?: AbortSignal): Promise<AmlPartySummary | null> {
  try {
    const res = await fetch(svcUrl('party-service', `/api/v1/parties/${encodeURIComponent(partyId)}`), { cache: 'no-store', signal })
    if (!res.ok) return null
    return parsePartySummary(await res.json())
  } catch {
    return null
  }
}

/** Map partyId -> summary; ids not (yet) resolved are absent, ids that failed map to null. */
export function usePartySummaries(partyIds: string[]): Record<string, AmlPartySummary | null> {
  const [byId, setById] = useState<Record<string, AmlPartySummary | null>>({})
  const key = Array.from(new Set(partyIds.filter(Boolean))).slice(0, MAX_PARTY_LOOKUPS).sort().join(',')

  useEffect(() => {
    if (!key) return
    const controller = new AbortController()
    const queue = key.split(',')
    const worker = async () => {
      for (let id = queue.shift(); id; id = queue.shift()) {
        const summary = await fetchPartySummary(id, controller.signal)
        if (controller.signal.aborted) return
        setById(prev => ({ ...prev, [id as string]: summary }))
      }
    }
    void Promise.all(Array.from({ length: CONCURRENCY }, worker))
    return () => controller.abort()
  }, [key])

  return byId
}
