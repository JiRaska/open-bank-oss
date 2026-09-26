// SPDX-License-Identifier: Apache-2.0
'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import styles from '@/components/settlement/settlement.module.css'
import { z } from 'zod'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function SettlementLookupPage() {
  const { t } = useLanguage()
  const router = useRouter()
  const [id, setId] = useState('')
  const [invalid, setInvalid] = useState(false)
  return <AuthGuard permission="settlements:view">
    <PageHeader title={t('Ověřit stav settlementu', 'Check settlement state')}
      subtitle={t('Použijte identifikátor převodu z odpovědi na jeho vytvoření, nikoli identifikátor schválení.', 'Use the transfer ID from the origination response, not the approval ID.')}
      actions={<Link href="/approvals" className={`btn btn-secondary ${styles.action}`}>{t('Zpět do fronty', 'Back to queue')}</Link>} />
    <form className={`card ${styles.form}`} onSubmit={event => {
      event.preventDefault()
      const parsed = z.uuid().safeParse(id.trim())
      setInvalid(!parsed.success)
      if (parsed.success) router.push(`/settlements/${encodeURIComponent(parsed.data)}`)
    }}>
      <label className="block font-medium" htmlFor="settlement-lookup-id">{t('Identifikátor převodu', 'Transfer ID')}</label>
      <input id="settlement-lookup-id" className="input w-full" value={id} onChange={event => { setId(event.target.value); setInvalid(false) }} required aria-invalid={invalid} />
      {invalid && <p role="alert">{t('Zadejte platný identifikátor UUID.', 'Enter a valid UUID.')}</p>}
      <button type="submit" className={`btn btn-primary ${styles.action}`}>{t('Zobrazit stav', 'Show state')}</button>
    </form>
  </AuthGuard>
}
