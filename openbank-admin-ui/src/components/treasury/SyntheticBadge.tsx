// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { StatusBadge } from '@/components/ui'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * ADR-0315 D9: the sandbox's interbank counterparties are simulated. A deal against one must never
 * read like a trade with a real bank, so the word is spelled out wherever the counterparty appears.
 */
export function SyntheticBadge({ synthetic }: { synthetic: boolean | undefined }) {
  const { t } = useLanguage()
  if (!synthetic) return null
  return <StatusBadge status="SYNTHETIC" tone="warning" label={t('Simulovaná protistrana', 'Synthetic counterparty')} />
}
