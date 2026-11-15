// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Reads certificate expiry from operator metrics (podmonitor-cert-expiry.yaml, #16294) and
 * projects each series onto a CLOSED set of fields. Nothing else in a Prometheus response,
 * whatever it contains, can reach the browser: projectSeries builds a new object from named
 * labels and drops the rest.
 */

import type { MeasuredCert } from './status'

const CERT_MANAGER = 'certmanager_certificate_expiration_timestamp_seconds'
const STRIMZI = 'strimzi_certificate_expiration_timestamp_ms'

interface PromSeries { metric?: Record<string, unknown>; value?: [number, string] }

const LABEL = /^[A-Za-z0-9._-]{1,253}$/

function label(metric: Record<string, unknown>, key: string): string | null {
  const v = metric[key]
  return typeof v === 'string' && LABEL.test(v) ? v : null
}

export function projectCertManager(series: PromSeries[]): MeasuredCert[] {
  const out: MeasuredCert[] = []
  for (const s of series) {
    const m = s.metric ?? {}
    const namespace = label(m, 'namespace')
    const name = label(m, 'name')
    const v = Number(s.value?.[1])
    // cert-manager reports 0 for a Certificate with NO current certificate (never issued, failed
    // renewal, deleted Secret). Keep it: dropping it would let the rest of a group read healthy.
    if (!namespace || !name || !Number.isFinite(v) || v < 0) continue
    out.push({ source: 'cert-manager', namespace, name, issuer: label(m, 'issuer_name') ?? '', notAfter: v })
  }
  return out
}

/**
 * Strimzi labels the CA by its type. The exact label key is not pinned here (not measured
 * against a live operator yet), so both known spellings are accepted and anything that does
 * not resolve to clients/cluster is DROPPED, which surfaces as `unknown`, never as healthy.
 */
export function projectStrimzi(series: PromSeries[]): MeasuredCert[] {
  const out: MeasuredCert[] = []
  for (const s of series) {
    const m = s.metric ?? {}
    const type = (label(m, 'type') ?? label(m, 'ca_type') ?? '').toLowerCase()
    const ca = type.includes('client') ? 'clients' : type.includes('cluster') ? 'cluster' : null
    const v = Number(s.value?.[1])
    if (!ca || !Number.isFinite(v) || v < 0) continue
    out.push({
      source: 'strimzi',
      namespace: label(m, 'resource_namespace') ?? label(m, 'namespace') ?? '',
      name: ca,
      issuer: label(m, 'cluster') ?? '',
      notAfter: Math.floor(v / 1000),
    })
  }
  return out
}

export function certKey(c: MeasuredCert): string {
  return `${c.source}/${c.namespace}/${c.name}/${c.issuer}`
}

export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>

async function instant(base: string, query: string, fetchImpl: FetchLike): Promise<PromSeries[] | null> {
  try {
    const res = await fetchImpl(`${base}/api/v1/query?query=${encodeURIComponent(query)}`, {
      cache: 'no-store',
      signal: AbortSignal.timeout(4000),
    })
    if (!res.ok) return null
    const body = await res.json() as { status?: string; data?: { result?: PromSeries[] } }
    if (body.status !== 'success' || !Array.isArray(body.data?.result)) return null
    return body.data.result
  } catch {
    return null
  }
}

export interface Measurement {
  certs: MeasuredCert[]
  /** Which metric families answered. A family that did not answer is not "empty". */
  reachable: { certManager: boolean; strimzi: boolean }
}

export async function measure(base: string | null, fetchImpl: FetchLike = fetch): Promise<Measurement> {
  if (!base) return { certs: [], reachable: { certManager: false, strimzi: false } }
  const [cm, sz] = await Promise.all([instant(base, CERT_MANAGER, fetchImpl), instant(base, STRIMZI, fetchImpl)])
  // The same target scraped by two configs yields duplicate series; one certificate counts once.
  const seen = new Set<string>()
  const certs = [...(cm ? projectCertManager(cm) : []), ...(sz ? projectStrimzi(sz) : [])].filter((c) => {
    const k = certKey(c)
    if (seen.has(k)) return false
    seen.add(k)
    return true
  })
  return {
    certs,
    reachable: { certManager: cm !== null && cm.length > 0, strimzi: sz !== null && sz.length > 0 },
  }
}

export function prometheusBase(): string | null {
  if (process.env.PROMETHEUS_URL) return process.env.PROMETHEUS_URL.replace(/\/$/, '')
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return null
}
