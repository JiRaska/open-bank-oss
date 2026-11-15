// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { existsSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { KEY_CATALOG, catalogEntry } from '@/lib/security/keys/catalog'
import { allStatuses, byUrgency, dependents, entryStatus, statusForDays, type MeasuredCert } from '@/lib/security/keys/status'
import { measure, projectCertManager, projectStrimzi } from '@/lib/security/keys/prometheus'
import { buildKeysReport } from '@/lib/security/keys/report'
import { hasPermission, permissionForPath, ROLES } from '@/lib/auth/roles'

const NOW = 1_800_000_000
const DAY = 86400
const cm = (namespace: string, name: string, issuer: string, days: number): MeasuredCert =>
  ({ source: 'cert-manager', namespace, name, issuer, notAfter: NOW + days * DAY })

describe('keys catalogue', () => {
  const repoRoot = path.resolve(__dirname, '../../..')

  it('every entry points at a file that exists in the repo', () => {
    for (const e of KEY_CATALOG) {
      expect(existsSync(path.join(repoRoot, e.definedIn)), `${e.id}: ${e.definedIn}`).toBe(true)
      if (e.runbook) expect(existsSync(path.join(repoRoot, e.runbook)), `${e.id}: ${e.runbook}`).toBe(true)
    }
  })

  it('ids are unique and every parent exists', () => {
    const ids = KEY_CATALOG.map((e) => e.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const e of KEY_CATALOG) if (e.parent) expect(catalogEntry(e.parent), e.id).toBeDefined()
  })

  it('carries no key material or secret-looking text', () => {
    const text = JSON.stringify(KEY_CATALOG)
    expect(text).not.toMatch(/BEGIN [A-Z ]*PRIVATE KEY|BEGIN CERTIFICATE|KEYSTORE_PASSWORD|KEYSTORE_P12/)
  })
})

describe('status', () => {
  it('thresholds', () => {
    expect(statusForDays(-1)).toBe('expired')
    expect(statusForDays(7)).toBe('critical')
    expect(statusForDays(30)).toBe('attention')
    expect(statusForDays(31)).toBe('ok')
  })

  it('a missing measurement is unknown, never ok', () => {
    const root = catalogEntry('root-ca')!
    expect(entryStatus(root, [], NOW).status).toBe('unknown')
    // a measurement for a DIFFERENT certificate does not count for this one
    expect(entryStatus(root, [cm('accounts', 'account-service-internal-tls', 'openbank-ca', 80)], NOW).status).toBe('unknown')
  })

  it('a group reports its EARLIEST expiry and its count', () => {
    const leaves = catalogEntry('internal-mtls')!
    const s = entryStatus(leaves, [
      cm('accounts', 'account-service-internal-tls', 'openbank-ca', 80),
      cm('ledger', 'ledger-internal-tls', 'openbank-ca', 5),
      cm('vault', 'openbao-tls', 'openbank-ca', 1), // excluded: has its own entry
    ], NOW)
    expect(s).toEqual({ id: 'internal-mtls', status: 'critical', daysLeft: 5, count: 2 })
  })

  it('a Certificate with NO valid certificate (cert-manager reports 0) makes its group expired', () => {
    const leaves = catalogEntry('internal-mtls')!
    const s = entryStatus(leaves, [
      cm('accounts', 'account-service-internal-tls', 'openbank-ca', 40),
      { source: 'cert-manager', namespace: 'ledger', name: 'ledger-internal-tls', issuer: 'openbank-ca', notAfter: 0 },
    ], NOW)
    expect(s.status).toBe('expired')
    expect(s.daysLeft).toBeNull() // not a fabricated "20 000 days ago"
    expect(s.count).toBe(2)
  })

  it('the zero series survives projection instead of being dropped', () => {
    expect(projectCertManager([{ metric: { namespace: 'ledger', name: 'l-tls', issuer_name: 'openbank-ca' }, value: [0, '0'] }]))
      .toEqual([{ source: 'cert-manager', namespace: 'ledger', name: 'l-tls', issuer: 'openbank-ca', notAfter: 0 }])
  })

  it('non-measurable kinds are explicit, not healthy', () => {
    const statuses = Object.fromEntries(allStatuses([], NOW).map((s) => [s.id, s.status]))
    expect(statuses['pades-seal']).toBe('untracked')
    expect(statuses['cosign-kms']).toBe('rotation')
    expect(statuses['pki-agent']).toBe('short-lived')
    expect(Object.values(statuses)).not.toContain('ok')
  })

  it('urgency puts unknown before attention', () => {
    const order = [
      { id: 'a', status: 'attention' as const, daysLeft: 20, count: 1 },
      { id: 'b', status: 'unknown' as const, daysLeft: null, count: 0 },
      { id: 'c', status: 'expired' as const, daysLeft: -2, count: 1 },
    ].sort(byUrgency).map((s) => s.id)
    expect(order).toEqual(['c', 'b', 'a'])
  })

  it('blast radius of the root CA covers the internal leaves and the vault certificate', () => {
    expect(dependents('root-ca').map((e) => e.id).sort()).toEqual(['internal-mtls', 'openbao-tls'])
  })
})

describe('prometheus projection', () => {
  it('keeps only the closed field set', () => {
    const out = projectCertManager([{
      metric: { namespace: 'accounts', name: 'x-tls', issuer_name: 'openbank-ca', secret_data: 'LEAK', tls_key: 'LEAK' },
      value: [0, String(NOW)],
    }])
    expect(out).toEqual([{ source: 'cert-manager', namespace: 'accounts', name: 'x-tls', issuer: 'openbank-ca', notAfter: NOW }])
    expect(JSON.stringify(out)).not.toContain('LEAK')
  })

  it('drops series with malformed labels or values', () => {
    expect(projectCertManager([
      { metric: { namespace: 'a b', name: 'x' }, value: [0, '1'] },
      { metric: { namespace: 'a', name: 'x' }, value: [0, 'NaN'] },
      { metric: { namespace: 'a', name: 'x' }, value: [0, '-1'] },
    ])).toEqual([])
  })

  it('an unrecognised Strimzi CA type is dropped (so it reads unknown), not guessed', () => {
    expect(projectStrimzi([{ metric: { type: 'mystery' }, value: [0, '1'] }])).toEqual([])
    expect(projectStrimzi([{ metric: { type: 'clientsCa' }, value: [0, String(NOW * 1000)] }])[0])
      .toMatchObject({ name: 'clients', notAfter: NOW })
  })

  it('a target scraped twice counts once', async () => {
    const series = { metric: { namespace: 'a', name: 'x-tls', issuer_name: 'openbank-ca' }, value: [0, String(NOW)] }
    const body = { status: 'success', data: { result: [series, series] } }
    const m = await measure('http://prom', async (url) => new Response(JSON.stringify(
      String(url).includes('certmanager') ? body : { status: 'success', data: { result: [] } })))
    expect(m.certs).toHaveLength(1)
  })

  it('an unreachable Prometheus reports unreachable, not empty-and-fine', async () => {
    const m = await measure('http://prom', async () => { throw new Error('down') })
    expect(m).toEqual({ certs: [], reachable: { certManager: false, strimzi: false } })
    expect((await measure(null)).reachable.certManager).toBe(false)
  })
})

describe('report and permissions', () => {
  const m = { certs: [cm('accounts', 'account-service-internal-tls', 'openbank-ca', 40)], reachable: { certManager: true, strimzi: false } }

  it('the per-certificate list is built only for keys:expert', () => {
    expect(buildKeysReport(m, NOW, false).expert).toBeUndefined()
    expect(JSON.stringify(buildKeysReport(m, NOW, false))).not.toContain('account-service-internal-tls')
    expect(buildKeysReport(m, NOW, true).expert?.[0]).toMatchObject({ entryId: 'internal-mtls', name: 'account-service-internal-tls', daysLeft: 40, status: 'ok' })
  })

  it('every department can read the overview; only operators get expert; demo gets neither', () => {
    for (const r of [ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS, ROLES.AUDITOR, ROLES.SUPERVISOR]) {
      expect(hasPermission([r], 'keys:view'), r).toBe(true)
      expect(hasPermission([r], 'keys:expert'), r).toBe(false)
    }
    expect(hasPermission([ROLES.OPERATOR], 'keys:expert')).toBe(true)
    expect(hasPermission([ROLES.DEMO], 'keys:view')).toBe(false)
  })

  it('the route prefix beats the generic /security system:view guard', () => {
    expect(permissionForPath('/security/keys')).toBe('keys:view')
    expect(permissionForPath('/api/security/keys')).toBe('keys:view')
    expect(permissionForPath('/security')).toBe('system:view')
  })
})
