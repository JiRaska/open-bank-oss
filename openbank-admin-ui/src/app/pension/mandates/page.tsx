// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Contribution mandates (ADR-0334 S3): the standing orders and direct debits participants set up
// to fund their contracts. pension-service GET /operator/mandates (staff only — OPA
// `operator-pension-mandate-read` admits human operator/admin/compliance, never a service
// account). Read-only: a mandate is set up or cancelled only by the participant, under SCA.

'use client'

import { Repeat } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { PENSION, pensionUrl } from '@/components/pension/api'
import { fieldOf } from '@/components/pension/contracts'
import { PAGE_SIZE, PensionQueue } from '@/components/pension/PensionQueue'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionMandatesPage() {
  return (
    <AuthGuard permission="pension:view">
      <Mandates />
    </AuthGuard>
  )
}

function Mandates() {
  const { t } = useLanguage()
  const limit = String(PAGE_SIZE * 4)
  return (
    <div>
      <PageHeader
        title={t('Příkazy k úhradě příspěvků', 'Contribution mandates')}
        subtitle={t(
          'Trvalé příkazy a inkasa, kterými účastníci platí příspěvky.',
          'Standing orders and direct debits participants fund their contracts with.',
        )}
        icon={<Repeat size={20} aria-hidden="true" />}
      />
      <PensionQueue
        title={t('Aktivní', 'Active')}
        url={pensionUrl('/operator/mandates', { status: 'ACTIVE', limit })}
        service={PENSION}
        feature={t('příkazy k úhradě příspěvků', 'contribution mandates')}
        hrefOf={row => {
          const id = fieldOf(row, 'contractId')
          return typeof id === 'string' ? `/pension/contracts/${encodeURIComponent(id)}` : null
        }}
        columns={[
          { key: 'kind', cs: 'Druh', en: 'Kind' },
          { key: 'externalId', cs: 'Externí ID', en: 'External id' },
          { key: 'createdAt', cs: 'Založeno', en: 'Created' },
        ]}
      />
      <PensionQueue
        title={t('Zrušené', 'Cancelled')}
        url={pensionUrl('/operator/mandates', { status: 'CANCELLED', limit })}
        service={PENSION}
        feature={t('zrušené příkazy', 'cancelled mandates')}
        hrefOf={row => {
          const id = fieldOf(row, 'contractId')
          return typeof id === 'string' ? `/pension/contracts/${encodeURIComponent(id)}` : null
        }}
        columns={[
          { key: 'kind', cs: 'Druh', en: 'Kind' },
          { key: 'externalId', cs: 'Externí ID', en: 'External id' },
          { key: 'updatedAt', cs: 'Zrušeno', en: 'Cancelled' },
        ]}
      />
    </div>
  )
}
