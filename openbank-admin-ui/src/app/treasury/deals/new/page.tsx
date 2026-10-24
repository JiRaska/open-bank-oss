// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// New treasury deal (dealer only, ADR-0315 / ADR-0344): the deal ticket. Creates a DRAFT — nothing
// posts — and previews the cash-flow ledger on demand; Submit sends it to a DIFFERENT person for
// approval. The counterparty's limit headroom is shown up front; the control is the server's limit
// check at submit and approve (LIMIT_BREACHED / PRODUCT_LIMIT_BREACHED, 422).

'use client'

import { useMemo } from 'react'
import { useSession } from 'next-auth/react'
import { FilePlus } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { principalNameFromToken } from '@/lib/auth/principal'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DealTicket } from '@/components/treasury/wb/DealTicket'

export default function NewTreasuryDealPage() {
  return (
    <AuthGuard permission="treasury:deal:create">
      <NewDeal />
    </AuthGuard>
  )
}

function NewDeal() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  return (
    <div>
      <PageHeader
        title={t('Nový obchod treasury', 'New treasury deal')}
        subtitle={t('Vytvoří koncept (DRAFT); zaúčtuje se až po schválení druhou osobou.', 'Creates a DRAFT; it posts only after a second person approves it.')}
        icon={<FilePlus size={20} aria-hidden="true" />}
      />
      <DealTicket
        mode="create"
        canCreate={hasPermission(roles, 'treasury:deal:create')}
        canBackOffice={hasPermission(roles, 'treasury:confirmation:send')}
        actor={principalNameFromToken(session?.user?.accessToken)}
      />
    </div>
  )
}
