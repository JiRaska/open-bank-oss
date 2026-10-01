// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { HumanReference } from '@/components/ui'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/** An item row's label: the shared `HumanReference` when there is an id, plus an optional second line. */
export function ItemLabel({ label, sublabel, reference }: { label: string; sublabel?: string; reference?: string | null }) {
  const { t } = useLanguage()
  return (
    <span style={{ display: 'inline-flex', flexDirection: 'column', gap: 2 }}>
      {reference
        ? <HumanReference label={label} reference={reference} copyLabel={t('Kopírovat identifikátor', 'Copy identifier')} />
        : <span>{label}</span>}
      {sublabel && <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{sublabel}</span>}
    </span>
  )
}
