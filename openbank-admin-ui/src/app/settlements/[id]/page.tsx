// SPDX-License-Identifier: Apache-2.0
'use client'

import Link from 'next/link'
import styles from '@/components/settlement/settlement.module.css'
import { useParams } from 'next/navigation'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { SettlementStatusPanel } from '@/components/settlement/SettlementStatusPanel'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function SettlementDetailPage() {
  const { t } = useLanguage()
  const { id } = useParams<{ id: string }>()
  return <AuthGuard permission="settlements:view">
    <PageHeader title={t('Stav settlementu', 'Settlement state')}
      actions={<Link href="/settlements" className={`btn btn-secondary ${styles.action}`}>{t('Vyhledat jiný převod', 'Find another transfer')}</Link>} />
    <SettlementStatusPanel key={id} id={id} />
  </AuthGuard>
}
