// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { HumanReference } from '@/components/ui'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/** A fund shown by its name with the id as a copyable secondary reference, never the bare id. */
export function FundRef({ id, names }: { id: string; names: ReadonlyMap<string, string> }) {
  const { t } = useLanguage()
  return <HumanReference label={names.get(id) ?? t('Fond', 'Fund')} reference={id} copyLabel={t('Kopírovat ID fondu', 'Copy fund id')} />
}
