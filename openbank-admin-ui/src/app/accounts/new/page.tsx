// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, Save, AlertCircle } from 'lucide-react'
import { accountApi } from '@/lib/api'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui/PageHeader'


const ACCOUNT_TYPES = ['CURRENT', 'SAVINGS', 'NOSTRO', 'GL_ASSET', 'GL_LIABILITY', 'GL_INCOME', 'GL_EXPENSE']
const CURRENCIES    = ['CZK', 'EUR', 'USD', 'GBP', 'CHF', 'PLN']

export default function NewAccountPage() {
  const router = useRouter()
  const { t } = useLanguage()
  const [form, setForm] = useState({
    partyId:     '',
    productId:   '',
    accountType: 'CURRENT',
    currencyCode: 'CZK',
    legalName:   '',
  })
  const [errors, setErrors]   = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)
  const [apiError, setApiError]     = useState<string | null>(null)

  function validate() {
    const e: Record<string, string> = {}
    if (!form.partyId.trim())   e.partyId   = t('Party ID je povinné', 'Party ID is required')
    else if (!/^[0-9a-f-]{36}$/i.test(form.partyId.trim())) e.partyId = t('Musí být platné UUID', 'Must be a valid UUID')
    if (!form.productId.trim()) e.productId = t('Product ID je povinné', 'Product ID is required')
    else if (!/^[0-9a-f-]{36}$/i.test(form.productId.trim())) e.productId = t('Musí být platné UUID', 'Must be a valid UUID')
    if (!form.legalName.trim()) e.legalName = t('Právní název je povinný pro sankční screening', 'Legal name is required for sanctions screening')
    return e
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    const errs = validate()
    if (Object.keys(errs).length) { setErrors(errs); return }
    setErrors({}); setSubmitting(true); setApiError(null)
    try {
      const idempotencyKey = crypto.randomUUID()
      const account = await accountApi.open({
        partyId:     form.partyId.trim(),
        productId:   form.productId.trim(),
        accountType: form.accountType,
        currencyCode: form.currencyCode,
        legalName:   form.legalName.trim(),
      }, idempotencyKey)
      router.push(`/accounts/${account.id}`)
    } catch (err: unknown) {
      setApiError(err instanceof Error ? err.message : t('Otevření účtu selhalo', 'Failed to open account'))
    } finally {
      setSubmitting(false)
    }
  }

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(p => ({ ...p, [k]: e.target.value }))

  return (
    <div>
      <PageHeader
        title={t('Otevřít nový účet', 'Open New Account')}
        subtitle={t('Vytvořte nový bankovní účet pro zákazníka', 'Create a new bank account for a customer party')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/accounts" style={{ color: 'var(--text-tertiary)', textDecoration: 'none' }}>{t('Účty', 'Accounts')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Otevřít účet', 'Open Account')}</span></div>}
        actions={<Link href="/accounts" className="btn btn-secondary"><ArrowLeft size={13} aria-hidden="true"/> {t('Zpět', 'Back')}</Link>}
      />

      <div style={{ maxWidth: '560px' }}>
        <form onSubmit={submit}>
          <div className="card">
            <div className="card-header"><span className="card-header-title">{t('Detaily účtu', 'Account Details')}</span></div>
            <div style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '14px' }}>

              {apiError && (
                <div style={{
                  padding: '10px 14px',
                  background: 'var(--danger-bg)', border: '1px solid var(--danger-border)',
                  borderRadius: 'var(--r-md)', display: 'flex', gap: '8px', alignItems: 'flex-start',
                }}>
                  <AlertCircle size={14} style={{ color: 'var(--danger)', flexShrink: 0, marginTop: '1px' }}/>
                  <span style={{ fontSize: '13px', color: 'var(--danger)' }}>{apiError}</span>
                </div>
              )}

              <div className="field">
                <label>{t('Party ID', 'Party ID')} <span style={{ color: 'var(--danger)' }}>*</span></label>
                <input
                  className="input"
                  placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                  value={form.partyId}
                  onChange={set('partyId')}
                  style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', borderColor: errors.partyId ? 'var(--danger)' : undefined }}
                />
                {errors.partyId && <span style={{ fontSize: '11px', color: 'var(--danger)' }}>{errors.partyId}</span>}
              </div>

              <div className="field">
                <label>{t('Product ID', 'Product ID')} <span style={{ color: 'var(--danger)' }}>*</span></label>
                <input
                  className="input"
                  placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                  value={form.productId}
                  onChange={set('productId')}
                  style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', borderColor: errors.productId ? 'var(--danger)' : undefined }}
                />
                {errors.productId && <span style={{ fontSize: '11px', color: 'var(--danger)' }}>{errors.productId}</span>}
              </div>

              <div className="field">
                <label>{t('Právní název', 'Legal name')} <span style={{ color: 'var(--danger)' }}>*</span></label>
                <input
                  className="input"
                  placeholder={t('Celé jméno nebo název společnosti', 'Full name or company name')}
                  value={form.legalName}
                  onChange={set('legalName')}
                  style={{ borderColor: errors.legalName ? 'var(--danger)' : undefined }}
                />
                {errors.legalName
                  ? <span style={{ fontSize: '11px', color: 'var(--danger)' }}>{errors.legalName}</span>
                  : <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>{t('Použito pro sankční screening (ADR-0032)', 'Used for sanctions screening (ADR-0032)')}</span>
                }
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
                <div className="field">
                  <label>{t('Typ účtu', 'Account type')}</label>
                  <select className="input" value={form.accountType} onChange={set('accountType')}>
                    {ACCOUNT_TYPES.map(at => <option key={at}>{at}</option>)}
                  </select>
                </div>
                <div className="field">
                  <label>{t('Měna', 'Currency')}</label>
                  <select className="input" value={form.currencyCode} onChange={set('currencyCode')}>
                    {CURRENCIES.map(c => <option key={c}>{c}</option>)}
                  </select>
                </div>
              </div>

              <div style={{
                padding: '10px 12px',
                background: 'var(--info-bg)', border: '1px solid var(--info-border)',
                borderRadius: 'var(--r-md)', fontSize: '12px', color: 'var(--info)',
              }}>
                {t('Idempotency klíč bude vygenerován automaticky, aby se zabránilo duplicitním odesláním.', 'An idempotency key will be generated automatically to prevent duplicate submissions.')}
              </div>
            </div>

            <div style={{
              padding: '14px 20px', borderTop: '1px solid var(--border)',
              display: 'flex', justifyContent: 'flex-end', gap: '8px',
              background: 'var(--surface-2)', borderRadius: '0 0 var(--r-lg) var(--r-lg)',
            }}>
              <Link href="/accounts" className="btn btn-secondary">{t('Zrušit', 'Cancel')}</Link>
              <button type="submit" className="btn btn-primary" disabled={submitting}>
                <Save size={13}/>
                {submitting ? t('Otevírám…', 'Opening…') : t('Otevřít účet', 'Open Account')}
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  )
}
