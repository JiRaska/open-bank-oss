// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Payouts and death claims (ADR-0334 lifecycle steps 6-8): payout requests from regular and early
// termination, and death claims paid to beneficiaries or the estate. Backend slice S5 (#12350)
// serves payouts and death claims by id only; the two list routes read here are not in it yet, so
// both panels degrade through DataUnavailable until a list route ships.

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
        url={pensionUrl('/payouts', { limit })}
        service={PENSION}
        feature={t('výplaty penzijních smluv', 'pension payouts')}
        columns={[
          { key: 'form', cs: 'Forma', en: 'Form' },
          { key: 'grossAmount', cs: 'Hrubá částka', en: 'Gross amount' },
          { key: 'taxWithheld', cs: 'Sražená daň', en: 'Tax withheld' },
          { key: 'requestedAt', cs: 'Požádáno', en: 'Requested' },
        ]}
      />
      <PensionQueue
        title={t('Úmrtí účastníka', 'Death claims')}
        url={pensionUrl('/death-claims', { limit })}
        service={PENSION}
        feature={t('pojistné události úmrtí', 'death claims')}
        columns={[
          { key: 'reportedAt', cs: 'Nahlášeno', en: 'Reported' },
          { key: 'payee', cs: 'Příjemce', en: 'Payee' },
          { key: 'amount', cs: 'Částka', en: 'Amount' },
        ]}
      />
    </div>
  )
}
