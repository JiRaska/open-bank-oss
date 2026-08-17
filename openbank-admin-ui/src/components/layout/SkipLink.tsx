// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'

export function SkipLink() {
  const { t } = useLanguage()

  return (
    <a className="ob-skip-link" href="#main-content">
      {t('Přeskočit na hlavní obsah', 'Skip to main content')}
    </a>
  )
}
