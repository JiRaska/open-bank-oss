// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// State-incentive claim batches (ADR-0334 lifecycle step 4, incentive rules per jurisdiction pack):
// each batch is one claim run to the state agency, with what was claimed, received and returned.
// Backend slice S3 (#12350); the panel degrades through DataUnavailable until then.

'use client'

import { Gift } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { PENSION, pensionUrl } from '@/components/pension/api'
import { PAGE_SIZE, PensionQueue } from '@/components/pension/PensionQueue'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionIncentiveBatchesPage() {
  return (
    <AuthGuard permission="pension:view">
      <Batches />
    </AuthGuard>
  )
}

function Batches() {
  const { t } = useLanguage()
  return (
    <div>
      <PageHeader
        title={t('Státní příspěvky', 'State incentives')}
        subtitle={t('Dávky žádostí o státní příspěvek a jejich vypořádání.', 'State-incentive claim batches and their settlement.')}
        icon={<Gift size={20} aria-hidden="true" />}
      />
      <PensionQueue
        title={t('Dávky žádostí', 'Claim batches')}
        url={pensionUrl('/incentive-claims/batches', { limit: String(PAGE_SIZE * 4) })}
        service={PENSION}
        feature={t('dávky státních příspěvků', 'state-incentive claim batches')}
        columns={[
          { key: 'period', cs: 'Období', en: 'Period' },
          { key: 'claimChannel', cs: 'Kanál', en: 'Channel' },
          { key: 'claimCount', cs: 'Žádostí', en: 'Claims' },
          { key: 'claimedAmount', cs: 'Nárokováno', en: 'Claimed' },
          { key: 'receivedAmount', cs: 'Přijato', en: 'Received' },
          { key: 'returnedAmount', cs: 'Vráceno', en: 'Returned' },
        ]}
      />
    </div>
  )
}
