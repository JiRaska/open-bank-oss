// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export interface CveSummary {
  id: string
  summary: string | null
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN'
  score: number | null
  references: string[]
}

export type CveEvidence =
  | { state: 'loading'; vulns: CveSummary[] }
  | { state: 'verified'; vulns: CveSummary[] }
  | { state: 'unavailable'; vulns: CveSummary[] }

const CVE_REQUEST_TIMEOUT_MS = 10_000

/** Map only components whose package identity is unambiguous in OSV. */
export function osvCoordinates(component: string): { ecosystem: string; pkg: string } | null {
  switch (component) {
    case 'Quarkus': return { ecosystem: 'Maven', pkg: 'io.quarkus:quarkus-core' }
    case 'Kotlin': return { ecosystem: 'Maven', pkg: 'org.jetbrains.kotlin:kotlin-stdlib' }
    default: return null
  }
}

function isCveSummary(value: unknown): value is CveSummary {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const cve = value as Record<string, unknown>
  return typeof cve.id === 'string' && cve.id.length > 0 &&
    (cve.summary === null || typeof cve.summary === 'string') &&
    ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'].includes(String(cve.severity)) &&
    (cve.score === null || (typeof cve.score === 'number' && Number.isFinite(cve.score))) &&
    Array.isArray(cve.references) && cve.references.every(reference => typeof reference === 'string')
}

export async function fetchCves(component: string, version: string): Promise<CveEvidence> {
  const coord = osvCoordinates(component)
  if (!coord) return { state: 'verified', vulns: [] }
  try {
    const res = await fetch(
      `/api/sbom/cve?ecosystem=${coord.ecosystem}&pkg=${encodeURIComponent(coord.pkg)}&version=${encodeURIComponent(version)}`,
      { cache: 'no-store', signal: AbortSignal.timeout(CVE_REQUEST_TIMEOUT_MS) },
    )
    if (!res.ok) return { state: 'unavailable', vulns: [] }
    const body = await res.json().catch(() => null) as { vulns?: unknown } | null
    if (!body || !Array.isArray(body.vulns) || !body.vulns.every(isCveSummary)) {
      return { state: 'unavailable', vulns: [] }
    }
    return { state: 'verified', vulns: body.vulns }
  } catch {
    return { state: 'unavailable', vulns: [] }
  }
}
