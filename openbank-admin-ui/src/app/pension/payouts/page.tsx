// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Payouts and death claims (ADR-0334 lifecycle steps 6-8): payout requests from regular and early
// termination, and death claims paid to beneficiaries or the estate. Read from pension-service's
// staff list routes (API 1.1.0): GET /operator/payouts and GET /death-claims. Read-only: no
// operator route writes a payout account, by design (S8).

'use client'

import { Wallet } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { PENSION, pensionUrl } from '@/components/pension/api'
import { PAGE_SIZE, PensionQueue } from '@/components/pension/PensionQueue'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionPayoutsPage() {
  return (
    <AuthGuard permission="pension:view">
      <Payouts />
    </AuthGuard>
  )
}

function Payouts() {
  const { t } = useLanguage()
  const limit = String(PAGE_SIZE * 4)
  return (
    <div>
      <PageHeader
        title={t('Výplaty a úmrtí', 'Payouts & death claims')}
        subtitle={t('Žádosti o výplatu a pojistné události úmrtí účastníka.', 'Payout requests and participant death claims.')}
        icon={<Wallet size={20} aria-hidden="true" />}
      />
      <PensionQueue
        title={t('Výplaty', 'Payouts')}
        url={pensionUrl('/operator/payouts', { limit })}
        service={PENSION}
        feature={t('výplaty penzijních smluv', 'pension payouts')}
        columns={[
          { key: 'form', cs: 'Forma', en: 'Form' },
          { key: 'grossAmount', cs: 'Hrubá částka', en: 'Gross amount' },
          { key: 'taxWithheld', cs: 'Sražená daň', en: 'Tax withheld' },
          { key: 'netAmount', cs: 'Čistá částka', en: 'Net amount' },
          { key: 'currency', cs: 'Měna', en: 'Currency' },
          { key: 'payoutAccountLast4', cs: 'Účet (konec)', en: 'Account (last 4)' },
          { key: 'pendingAccountLast4', cs: 'Čekající změna účtu', en: 'Held account change' },
        ]}
      />
      <PensionQueue
        title={t('Úmrtí účastníka', 'Death claims')}
        url={pensionUrl('/death-claims', { limit })}
        service={PENSION}
        feature={t('pojistné události úmrtí', 'death claims')}
        columns={[
          { key: 'dateOfDeath', cs: 'Datum úmrtí', en: 'Date of death' },
          { key: 'notifiedBy', cs: 'Nahlásil', en: 'Notified by' },
          { key: 'approvedBy', cs: 'Schválil', en: 'Approved by' },
          { key: 'valuation', cs: 'Ocenění', en: 'Valuation' },
          { key: 'incentiveReturn', cs: 'Vratka podpory', en: 'Incentive return' },
        ]}
      />
    </div>
  )
}
