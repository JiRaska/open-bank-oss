// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Unmatched contributions (ADR-0334 lifecycle step 4): incoming payments the contribution matcher
// could not attach to a contract (unknown variable symbol, closed contract, amount outside the
// pack's limits). pension-service GET /funding/operations/unmatched (backend slice S3, #12350); the
// panel degrades through DataUnavailable until it is deployed.

'use client'

import { FileSearch } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { PENSION, pensionUrl } from '@/components/pension/api'
import { PensionQueue } from '@/components/pension/PensionQueue'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionUnmatchedContributionsPage() {
  return (
    <AuthGuard permission="pension:view">
      <Unmatched />
    </AuthGuard>
  )
}

function Unmatched() {
  const { t } = useLanguage()
  return (
    <div>
      <PageHeader
        title={t('Nespárované příspěvky', 'Unmatched contributions')}
        subtitle={t('Platby, které se nepodařilo přiřadit ke smlouvě.', 'Payments that could not be attached to a contract.')}
        icon={<FileSearch size={20} aria-hidden="true" />}
      />
      <PensionQueue
        title={t('K vyřešení', 'To resolve')}
        url={pensionUrl('/funding/operations/unmatched')}
        service={PENSION}
        feature={t('nespárované příspěvky', 'unmatched contributions')}
        columns={[
          { key: 'amount', cs: 'Částka', en: 'Amount' },
          { key: 'currency', cs: 'Měna', en: 'Currency' },
          { key: 'valueDate', cs: 'Valuta', en: 'Value date' },
          { key: 'reference', cs: 'Reference', en: 'Reference' },
          { key: 'reason', cs: 'Důvod', en: 'Reason' },
        ]}
      />
    </div>
  )
}
