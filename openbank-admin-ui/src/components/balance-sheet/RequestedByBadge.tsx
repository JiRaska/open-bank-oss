// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { StatusBadge } from '@/components/ui'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const SYSTEM_PREFIX = 'system:'

/**
 * Who asked for this snapshot run (risk-engine openapi 1.9.0, requestedBy on SnapshotRun /
 * SnapshotRunSummary, #11016). Three cases, never guessed:
 *   - a human principal — rendered as-is;
 *   - a `system:<job>` principal (the EOD scheduler, #11010, records
 *     `system:risk-engine-eod-snapshot`) — rendered as a "Scheduled run" badge naming the job;
 *   - missing/null (a historic run recorded before the field existed, or an older backend that
 *     never sends it) — rendered as "—" with a title explaining why, never invented.
 */
export function RequestedByBadge({ requestedBy }: { requestedBy?: string | null }) {
  const { t } = useLanguage()

  if (requestedBy == null) {
    return <span title={t('nezaznamenáno', 'not recorded')}>—</span>
  }

  if (requestedBy.startsWith(SYSTEM_PREFIX)) {
    const job = requestedBy.slice(SYSTEM_PREFIX.length) || requestedBy
    return (
      <StatusBadge
        status="SCHEDULED"
        tone="neutral"
        label={t(`Plánovaný běh (${job})`, `Scheduled run (${job})`)}
      />
    )
  }

  return <span>{requestedBy}</span>
}
