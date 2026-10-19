// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury reference data and configuration (ADR-0344 D3): every code list the treasury service
// keeps, edited by ONE generic effective-dated editor driven from the kind catalogue. Versions are
// proposed, then approved or rejected by a DIFFERENT person; a record carries a verified badge and
// shows what depends on it before a change is approved.

'use client'

import { BookOpen } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { ReferenceWorkbench } from '@/components/treasury/wb/ReferenceWorkbench'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function TreasuryReferencePage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <Reference />
    </AuthGuard>
  )
}

function Reference() {
  const { t } = useLanguage()
  return (
    <div>
      <PageHeader
        title={t('Referenční data a konfigurace', 'Reference data and configuration')}
        subtitle={t('Číselníky s platností v čase, verzováním, schvalováním čtyř očí a příznakem ověření.', 'Effective-dated, versioned, four-eyes-approved code lists with a verified flag.')}
        icon={<BookOpen size={20} aria-hidden="true" />}
      />
      <ReferenceWorkbench />
    </div>
  )
}
