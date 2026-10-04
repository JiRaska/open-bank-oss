// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { useParams } from 'next/navigation'
import styles from '@/components/settlement/settlement.module.css'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { SettlementStatusPanel } from '@/components/settlement/SettlementStatusPanel'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function SettlementDetailPage() {
  const { t } = useLanguage()
  const { id } = useParams<{ id: string }>()
  return <AuthGuard permission="settlements:view">
    <PageHeader title={t('Stav settlementu', 'Settlement status')}
      actions={<Link href="/settlements" className={`btn btn-secondary ${styles.action}`}>{t('Vyhledat jiný převod', 'Find another transfer')}</Link>} />
    <SettlementStatusPanel key={id} id={id} />
  </AuthGuard>
}
