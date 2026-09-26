// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { StatusBadge } from '@/components/ui'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Provenance } from './contracts'

/**
 * ADR-0313 D13: every figure states its provenance, and SYNTHETIC data must be unmistakable — a
 * risk number from a synthetic run that reads like a production one is a misstatement. Synthetic
 * renders as a warning badge with the word spelled out; production as a neutral one.
 */
export function ProvenanceBadge({ provenance }: { provenance: Provenance }) {
  const { t } = useLanguage()
  return provenance === 'synthetic'
    ? <StatusBadge status="SYNTHETIC" tone="warning" label={t('Syntetická data', 'Synthetic data')} />
    : <StatusBadge status="PRODUCTION" tone="neutral" label={t('Produkční data', 'Production data')} />
}
