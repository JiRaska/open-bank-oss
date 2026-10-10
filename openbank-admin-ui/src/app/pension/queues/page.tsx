// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Onboarding and transfer queues (ADR-0334 lifecycle steps 2-3): onboarding applications and
// transfer requests to or from another provider, from pension-service's operator routes (backend
// slice S2, #12350 — GET /operator/onboarding/applications and /operator/transfers). Until that
// slice is deployed the panels degrade through DataUnavailable.

'use client'

import { Inbox } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { PENSION, pensionUrl } from '@/components/pension/api'
import { fieldOf } from '@/components/pension/contracts'
import { PAGE_SIZE, PensionQueue } from '@/components/pension/PensionQueue'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionQueuesPage() {
  return (
    <AuthGuard permission="pension:view">
      <Queues />
    </AuthGuard>
  )
}

function Queues() {
  const { t } = useLanguage()
  const limit = String(PAGE_SIZE * 4)
  return (
    <div>
      <PageHeader
        title={t('Sjednání a převody', 'Onboarding & transfers')}
        subtitle={t('Smlouvy čekající na aktivaci a převody od jiných poskytovatelů a k nim.', 'Contracts waiting for activation, and transfers from and to other providers.')}
        icon={<Inbox size={20} aria-hidden="true" />}
      />
      <PensionQueue
        title={t('Žádosti o sjednání', 'Onboarding applications')}
        url={pensionUrl('/operator/onboarding/applications', { limit })}
        service={PENSION}
        feature={t('žádosti o sjednání', 'onboarding applications')}
        statusPath="application.status"
        hrefOf={row => {
          const id = fieldOf(row, 'application.applicationId')
          return typeof id === 'string' ? `/pension/applications/${encodeURIComponent(id)}` : null
        }}
        columns={[
          { key: 'application.kind', cs: 'Druh', en: 'Kind' },
          { key: 'application.productLine', cs: 'Produkt', en: 'Product' },
          { key: 'application.chosenStrategy', cs: 'Strategie', en: 'Strategy' },
          { key: 'application.signedAt', cs: 'Podepsáno', en: 'Signed' },
        ]}
      />
      <PensionQueue
        title={t('Převody', 'Transfers')}
        url={pensionUrl('/operator/transfers', { limit })}
        service={PENSION}
        feature={t('převody penzijních smluv', 'pension transfers')}
        columns={[
          { key: 'direction', cs: 'Směr', en: 'Direction' },
          { key: 'counterpartyProviderName', cs: 'Druhý poskytovatel', en: 'Other provider' },
          { key: 'deadline', cs: 'Lhůta', en: 'Deadline' },
          { key: 'netAmount', cs: 'Čistá částka', en: 'Net amount' },
          { key: 'currency', cs: 'Měna', en: 'Currency' },
        ]}
      />
    </div>
  )
}
