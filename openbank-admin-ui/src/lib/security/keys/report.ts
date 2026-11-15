// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { KEY_CATALOG } from './catalog'
import type { Measurement } from './prometheus'
import { allStatuses, matches, statusForDays, type EntryStatus, type KeyStatus } from './status'

export interface ExpertCert {
  entryId: string | null
  source: 'cert-manager' | 'strimzi'
  namespace: string
  name: string
  issuer: string
  daysLeft: number
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
        const days = Math.floor((c.notAfter - nowSeconds) / 86400)
        const entry = KEY_CATALOG.find((e) => matches(e.expiry, c))
        return {
          entryId: entry?.id ?? null,
          source: c.source,
          namespace: c.namespace,
          name: c.name,
          issuer: c.issuer,
          daysLeft: days,
          status: statusForDays((c.notAfter - nowSeconds) / 86400),
        }
      })
      .sort((a, b) => a.daysLeft - b.daysLeft)
  }
  return report
}
