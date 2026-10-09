// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Onboarding and transfer queues (ADR-0334 lifecycle steps 2-3): contracts signed and waiting for
// activation, and transfer requests to or from another provider. Transfers are backend slice S2
// (#12350); until that route ships the panel degrades through DataUnavailable.

'use client'

import { Inbox } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { PENSION, pensionUrl } from '@/components/pension/api'
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
        title={t('Čeká na aktivaci', 'Waiting for activation')}
        url={pensionUrl('/contracts', { status: 'PENDING_ACTIVATION', limit })}
        service={PENSION}
        feature={t('smlouvy čekající na aktivaci', 'contracts waiting for activation')}
        columns={[
          { key: 'productLine', cs: 'Produkt', en: 'Product' },
          { key: 'createdAt', cs: 'Založeno', en: 'Created' },
        ]}
      />
      <PensionQueue
        title={t('Převody', 'Transfers')}
        url={pensionUrl('/transfers', { status: 'OPEN', limit })}
        service={PENSION}
        feature={t('převody penzijních smluv', 'pension transfers')}
        columns={[
          { key: 'direction', cs: 'Směr', en: 'Direction' },
          { key: 'counterpartyProvider', cs: 'Druhý poskytovatel', en: 'Other provider' },
          { key: 'requestedAt', cs: 'Požádáno', en: 'Requested' },
          { key: 'deadline', cs: 'Lhůta', en: 'Deadline' },
        ]}
      />
    </div>
  )
}
