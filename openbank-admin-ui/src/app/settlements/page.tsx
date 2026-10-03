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
      subtitle={t('Použijte identifikátor převodu z odpovědi na jeho vytvoření, nikoli identifikátor schválení.', 'Use the transfer reference from the origination response, not the approval reference.')} />
    <form className={`card ${styles.form}`} onSubmit={event => {
      event.preventDefault()
      const parsed = z.uuid().safeParse(id.trim())
      setInvalid(!parsed.success)
      if (parsed.success) router.push(`/settlements/${encodeURIComponent(parsed.data)}`)
    }}>
      <label className="block font-medium" htmlFor="settlement-lookup-id">{t('Identifikátor převodu', 'Transfer reference')}</label>
      <input id="settlement-lookup-id" className="input w-full" value={id} required aria-invalid={invalid}
        onChange={event => { setId(event.target.value); setInvalid(false) }} />
      {invalid && <p role="alert">{t('Zadejte platný identifikátor UUID.', 'Enter a valid UUID.')}</p>}
      <button type="submit" className={`btn btn-primary ${styles.action}`}>{t('Zobrazit stav', 'Show status')}</button>
    </form>
  </AuthGuard>
}
