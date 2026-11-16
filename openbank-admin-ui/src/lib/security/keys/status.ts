// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Pure status logic for the Keys & Certificates section (#16292). No I/O, so every branch is
 * unit-tested, including the ones that must NOT read as healthy.
 *
 * The rule that matters: absence of evidence is never green. A certificate whose metric is
 * missing is `unknown`; a key nothing can observe is `untracked`. Only a measured expiry far
 * enough away is `ok`.
 */

import { KEY_CATALOG, type ExpirySource, type KeyEntry } from './catalog'

export type KeyStatus =
  | 'ok'          // measured, > ATTENTION_DAYS left
  | 'attention'   // measured, <= ATTENTION_DAYS
  | 'critical'    // measured, <= CRITICAL_DAYS
  | 'expired'     // measured, in the past
  | 'short-lived' // minutes-long certificates issued on demand; nothing to expire
  | 'rotation'    // no expiry by design; rotation is the control
  | 'dev-only'    // only a DEV-ONLY stand-in exists (ephemeral seal); not evidence, not healthy
  | 'revocation'  // does not expire by itself; revocation is the risk
  | 'untracked'   // exists, expiry not observable today
  | 'unknown'     // should be observable, but the measurement is missing

export const ATTENTION_DAYS = 30
export const CRITICAL_DAYS = 7

/** One measured certificate, as projected from operator metrics. Public metadata only. */
export interface MeasuredCert {
  source: 'cert-manager' | 'strimzi' | 'seal'
  namespace: string
  name: string
  issuer: string
  /** Unix seconds. */
  notAfter: number
}

export interface EntryStatus {
  id: string
  status: KeyStatus
  /** Days until the EARLIEST expiry among matched certificates; null when not measured. */
  daysLeft: number | null
  /** How many measured certificates this entry covers. */
  count: number
}

export function statusForDays(days: number): KeyStatus {
  if (days < 0) return 'expired'
  if (days <= CRITICAL_DAYS) return 'critical'
  if (days <= ATTENTION_DAYS) return 'attention'
  return 'ok'
}

export function matches(source: ExpirySource, cert: MeasuredCert): boolean {
  if (source.type === 'cert-manager') {
    if (cert.source !== 'cert-manager') return false
    if (source.namespace && cert.namespace !== source.namespace) return false
    if (source.name && cert.name !== source.name) return false
    if (source.issuer && cert.issuer !== source.issuer) return false
    if (source.excludeNames?.includes(cert.name)) return false
    return true
  }
  if (source.type === 'strimzi') {
    return cert.source === 'strimzi' && cert.name === source.ca
  }
  if (source.type === 'seal') return cert.source === 'seal'
  return false
}

export function entryStatus(entry: KeyEntry, certs: readonly MeasuredCert[], nowSeconds: number): EntryStatus {
  const src = entry.expiry
  if (src.type === 'short-lived') return { id: entry.id, status: 'short-lived', daysLeft: null, count: 0 }
  if (src.type === 'no-expiry') return { id: entry.id, status: 'rotation', daysLeft: null, count: 0 }
  if (src.type === 'revocation') return { id: entry.id, status: 'revocation', daysLeft: null, count: 0 }
  if (src.type === 'untracked') return { id: entry.id, status: 'untracked', daysLeft: null, count: 0 }
  let matched = certs.filter((c) => matches(src, c))
  if (matched.length === 0) return { id: entry.id, status: 'unknown', daysLeft: null, count: 0 }
  if (src.type === 'seal') {
    // An ephemeral seal's expiry is meaningless as evidence: it must never read as a healthy
    // countdown. Only organizational-keystore certificates are measured.
    const real = matched.filter((c) => c.name === 'keystore')
    if (real.length === 0) return { id: entry.id, status: 'dev-only', daysLeft: null, count: matched.length }
    matched = real
  }
  // A matched Certificate with notAfter 0 has no valid certificate at all: that is the worst
  // state, so it decides the entry no matter how healthy its siblings are.
  if (matched.some((c) => c.notAfter === 0)) return { id: entry.id, status: 'expired', daysLeft: null, count: matched.length }
  const earliest = Math.min(...matched.map((c) => c.notAfter))
  const daysLeft = (earliest - nowSeconds) / 86400
  return { id: entry.id, status: statusForDays(daysLeft), daysLeft: Math.floor(daysLeft), count: matched.length }
}

export function allStatuses(certs: readonly MeasuredCert[], nowSeconds: number): EntryStatus[] {
  return KEY_CATALOG.map((e) => entryStatus(e, certs, nowSeconds))
}

/** Order for the "needs attention" list: worst first, then soonest. Unknown ranks with critical. */
const SEVERITY: Record<KeyStatus, number> = {
  expired: 0, critical: 1, unknown: 2, attention: 3, 'dev-only': 4, untracked: 5, revocation: 6, ok: 7, rotation: 8, 'short-lived': 9,
}

export function byUrgency(a: EntryStatus, b: EntryStatus): number {
  const s = SEVERITY[a.status] - SEVERITY[b.status]
  if (s !== 0) return s
  return (a.daysLeft ?? Number.MAX_SAFE_INTEGER) - (b.daysLeft ?? Number.MAX_SAFE_INTEGER)
}

/** Blast radius: every catalogue entry that chains to `id`, transitively. */
export function dependents(id: string): KeyEntry[] {
  const out: KeyEntry[] = []
  const walk = (parent: string) => {
    for (const e of KEY_CATALOG) {
      if (e.parent === parent) { out.push(e); walk(e.id) }
    }
  }
  walk(id)
  return out
}
