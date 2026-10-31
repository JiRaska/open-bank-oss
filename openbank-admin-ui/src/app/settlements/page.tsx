// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { z } from 'zod'
import styles from '@/components/settlement/settlement.module.css'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function SettlementLookupPage() {
  const { t } = useLanguage()
  const router = useRouter()
  const [id, setId] = useState('')
  const [invalid, setInvalid] = useState(false)
  return <AuthGuard permission="settlements:view">
    <PageHeader title={t('Stav settlementu', 'Settlement status')}
      subtitle={t('Vložte referenci převodu z potvrzení platby (ne referenci schválení). Platby i jejich stav najdete také v přehledu Platby.', 'Paste the transfer reference from the payment confirmation (not the approval reference). Payments and their status are also listed under Payments.')} />
    <form className={`card ${styles.form}`} onSubmit={event => {
      event.preventDefault()
      const parsed = z.uuid().safeParse(id.trim())
      setInvalid(!parsed.success)
      if (parsed.success) router.push(`/settlements/${encodeURIComponent(parsed.data)}`)
    }}>
      <label className="block font-medium" htmlFor="settlement-lookup-id">{t('Identifikátor převodu', 'Transfer reference')}</label>
      <input id="settlement-lookup-id" className="input w-full" value={id} required aria-invalid={invalid}
        onChange={event => { setId(event.target.value); setInvalid(false) }} />
      {invalid && <p role="alert">{t('Reference převodu nemá platný formát — zkopírujte ji celou z potvrzení platby.', 'That transfer reference is not in a valid format — copy it whole from the payment confirmation.')}</p>}
      <button type="submit" className={`btn btn-primary ${styles.action}`}>{t('Zobrazit stav', 'Show status')}</button>
    </form>
  </AuthGuard>
}
