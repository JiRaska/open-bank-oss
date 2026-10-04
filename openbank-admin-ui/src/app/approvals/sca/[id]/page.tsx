// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { useParams } from 'next/navigation'
import { ArrowLeft, FileCheck2 } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { OperatorApprovalWorkbench } from '@/components/approvals/OperatorApprovalWorkbench'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function OperatorApprovalPage() {
  const { t } = useLanguage()
  const params = useParams<{ id: string }>()
  const id = params?.id ?? ''
  return <AuthGuard permission="operator-approvals:decide">
    <div>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><Link href="/approvals">{t('Schvalování', 'Approvals')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Posouzení operace SCA', 'SCA operation review')}</span></div>}
        icon={<FileCheck2 size={18} aria-hidden="true" />}
        title={t('Posouzení operace SCA', 'SCA operation review')}
        subtitle={t('Zkontrolujte konkrétní požadavek jiného operátora.', 'Review another operator’s exact request.')}
        actions={<Link href="/approvals" className="btn btn-secondary"><ArrowLeft size={14} aria-hidden="true" />{t('Zpět do fronty', 'Back to queue')}</Link>}
      />
      <OperatorApprovalWorkbench key={id} domain="sca" id={id} />
    </div>
  </AuthGuard>
}
