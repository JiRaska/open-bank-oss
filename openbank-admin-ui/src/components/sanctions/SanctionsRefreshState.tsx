// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { Clock } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * #14780: a manual refresh is QUEUED, not done. The service answers 202 and flags the list
 * (`refreshRequestedAt`); the scheduler imports it on a later tick and clears the flag. While the
 * flag is set, say so — the entry count and date beside it are still the PREVIOUS import, and
 * showing them alone would read as "the refresh you just asked for finished".
 */
export function SanctionsRefreshState({ refreshRequestedAt, dateLocale }: {
  refreshRequestedAt?: string | null
  dateLocale: string
}) {
  const { t } = useLanguage()
  if (!refreshRequestedAt) return null
  const at = new Date(refreshRequestedAt)
  const when = Number.isNaN(at.getTime()) ? '' : at.toLocaleString(dateLocale)
  return (
    <div role="status" data-testid="sanctions-refresh-queued"
      style={{ color: 'var(--warning-text)', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '4px', justifyContent: 'flex-end' }}>
      <Clock size={11} aria-hidden="true" />
      {t(
        `Stažení ve frontě${when ? ` od ${when}` : ''} — proběhne při dalším běhu plánovače; údaje níže jsou z předchozího stažení`,
        `Download queued${when ? ` since ${when}` : ''} — runs on the next scheduler pass; the figures below are from the previous download`,
      )}
    </div>
  )
}
