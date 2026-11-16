// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { KEY_CATALOG } from './catalog'
import type { Measurement } from './prometheus'
import { allStatuses, matches, statusForDays, type EntryStatus, type KeyStatus } from './status'

export interface ExpertCert {
  entryId: string | null
  source: 'cert-manager' | 'strimzi' | 'seal'
  namespace: string
  name: string
  issuer: string
  /** null when the Certificate has no valid certificate at all. */
  daysLeft: number | null
  status: KeyStatus
}

export interface KeysReport {
  measuredAt: string
  reachable: Measurement['reachable']
  entries: EntryStatus[]
  /** Present only for keys:expert. */
  expert?: ExpertCert[]
}

export function buildKeysReport(m: Measurement, nowSeconds: number, expert: boolean): KeysReport {
  const report: KeysReport = {
    measuredAt: new Date(nowSeconds * 1000).toISOString(),
    reachable: m.reachable,
    entries: allStatuses(m.certs, nowSeconds),
  }
  if (expert) {
    report.expert = m.certs
      .map((c) => {
        const missing = c.notAfter === 0
        const days = missing ? Number.NaN : Math.floor((c.notAfter - nowSeconds) / 86400)
        const entry = KEY_CATALOG.find((e) => matches(e.expiry, c))
        return {
          entryId: entry?.id ?? null,
          source: c.source,
          namespace: c.namespace,
          name: c.name,
          issuer: c.issuer,
          daysLeft: missing ? null : days,
          status: c.source === 'seal' && c.name === 'ephemeral'
            ? 'dev-only' as const
            : missing ? 'expired' as const : statusForDays((c.notAfter - nowSeconds) / 86400),
        }
      })
      .sort((a, b) => (a.daysLeft ?? -Infinity) - (b.daysLeft ?? -Infinity))
  }
  return report
}
