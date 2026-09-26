// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
'use client'

import Link from 'next/link'
import { useParams } from 'next/navigation'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { ScaApprovalWorkbench } from '@/components/sca/ScaApprovalWorkbench'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function ScaApprovalPage() {
  const { t } = useLanguage()
  const { id } = useParams<{ id: string }>()
  return <AuthGuard permission="approvals:view">
    <Link href="/approvals" className="btn btn-secondary">{t('Zpět do fronty', 'Back to queue')}</Link>
    <h1>{t('Posouzení operace SCA', 'SCA operation review')}</h1>
    <p>{t('Zkontrolujte konkrétní požadavek jiného operátora.', 'Review another operator’s exact request.')}</p>
    <ScaApprovalWorkbench key={id} id={id} />
  </AuthGuard>
}
