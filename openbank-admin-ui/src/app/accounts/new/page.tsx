// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, Save, AlertCircle } from 'lucide-react'
import { accountApi } from '@/lib/api'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PartySearch, type PartyHit } from '@/components/party/PartySearch'
import { accountPartySelection } from '@/lib/accounts/partySelection'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'


const ACCOUNT_TYPES = ['CURRENT', 'SAVINGS', 'TERM_DEPOSIT', 'NOSTRO', 'GL_ASSET', 'GL_LIABILITY', 'GL_INCOME', 'GL_EXPENSE']
const CUSTOMER_ACCOUNT_TYPES = new Set(['CURRENT', 'SAVINGS', 'TERM_DEPOSIT'])
const CURRENCIES    = ['CZK', 'EUR', 'USD', 'GBP', 'CHF', 'PLN']

interface CatalogProduct {
  id: string
  code: string
  name: string
  type: string
  currency: string
  status: string
}

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
  // A double submit can arrive before React renders `submitting`. Keep the request key stable
  // for this account-opening attempt so the account service can return a safe replay after an
  // interrupted response instead of opening a second account.
  const openingInFlight = useRef(false)
  const idempotencyKey = useRef<string | null>(null)
  const [products, setProducts] = useState<CatalogProduct[]>([])
  const [productsLoading, setProductsLoading] = useState(true)
  const [productsUnavailable, setProductsUnavailable] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    void fetch(svcUrl('product-catalog', '/api/v1/products?status=ACTIVE'), {
      cache: 'no-store', signal: controller.signal,
    }).then(async response => {
      if (!response.ok) {
        await classifyBffFailure(response.clone())
        throw new Error('catalog unavailable')
      }
      const body = await response.json() as CatalogProduct[] | { products?: CatalogProduct[]; items?: CatalogProduct[] }
      const rows = Array.isArray(body) ? body : body.products ?? body.items ?? []
      setProducts(rows.filter(product => product.status === 'ACTIVE' && CUSTOMER_ACCOUNT_TYPES.has(product.type)))
    }).catch(error => {
      if (error instanceof DOMException && error.name === 'AbortError') return
      setProductsUnavailable(true)
    }).finally(() => setProductsLoading(false))
    return () => controller.abort()
  }, [])

  function selectProduct(productId: string) {
    const product = products.find(item => item.id === productId)
    setForm(current => ({
      ...current,
      productId,
      ...(product ? { accountType: product.type, currencyCode: product.currency } : {}),
    }))
  }

  function validate() {
    const e: Record<string, string> = {}
    if (!form.partyId.trim())   e.partyId   = t('Party ID je povinné', 'Party ID is required')
    else if (!/^[0-9a-f-]{36}$/i.test(form.partyId.trim())) e.partyId = t('Musí být platné UUID', 'Must be a valid UUID')
    if (!form.productId.trim()) e.productId = t('Product ID je povinné', 'Product ID is required')
    else if (!/^[0-9a-f-]{36}$/i.test(form.productId.trim())) e.productId = t('Musí být platné UUID', 'Must be a valid UUID')
    if (!form.legalName.trim()) e.legalName = t('Právní název je povinný pro sankční screening', 'Legal name is required for sanctions screening')
    return e
  }

  function selectParty(party: PartyHit) {
    const selection = accountPartySelection(party)
    setForm(prev => ({
      ...prev,
      partyId: selection.partyId,
      // The selected party is the source of truth for sanctions screening. Keep
      // the field editable for exceptional legal-name corrections, but never
      // never retain a name from a different party after an id-only selection.
      legalName: selection.legalName,
    }))
    setErrors(prev => ({ ...prev, partyId: '', legalName: '' }))
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    const errs = validate()
    if (Object.keys(errs).length) { setErrors(errs); return }
    if (openingInFlight.current) return
    openingInFlight.current = true
    setErrors({}); setSubmitting(true); setApiError(null)
    try {
      const stableIdempotencyKey = idempotencyKey.current ??= crypto.randomUUID()
      const account = await accountApi.open({
        partyId:     form.partyId.trim(),
        productId:   form.productId.trim(),
        accountType: form.accountType,
        currencyCode: form.currencyCode,
        legalName:   form.legalName.trim(),
      }, stableIdempotencyKey)
      router.push(`/accounts/${account.id}`)
    } catch (err: unknown) {
      setApiError(err instanceof Error ? err.message : t('Otevření účtu selhalo', 'Failed to open account'))
    } finally {
      openingInFlight.current = false
      setSubmitting(false)
    }
  }

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm(p => ({ ...p, [k]: e.target.value }))

  return (
    <AuthGuard permission="accounts:create">
      <div>
      <PageHeader
        title={t('Otevřít nový účet', 'Open New Account')}
        subtitle={t('Vytvořte nový bankovní účet pro zákazníka', 'Create a new bank account for a customer party')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/accounts" style={{ color: 'var(--text-tertiary)', textDecoration: 'none' }}>{t('Účty', 'Accounts')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Otevřít účet', 'Open Account')}</span></div>}
        actions={<Link href="/accounts" className="btn btn-secondary"><ArrowLeft size={13} aria-hidden="true"/> {t('Zpět', 'Back')}</Link>}
      />

      <div style={{ maxWidth: '560px' }}>
        <PartySearch
          onSelect={selectParty}
          selectedId={form.partyId || undefined}
          busy={submitting}
          placeholder={t('Vyhledejte existující party podle jména nebo UUID', 'Find an existing party by name or UUID')}
        />
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
                  <AlertCircle size={14} aria-hidden="true" style={{ color: 'var(--danger)', flexShrink: 0, marginTop: '1px' }}/>
                  <span role="alert" style={{ fontSize: '13px', color: 'var(--danger)' }}>{apiError}</span>
                </div>
              )}

              <div className="field">
                <label htmlFor="account-party-id">{t('Party ID', 'Party ID')} <span aria-hidden="true" style={{ color: 'var(--danger)' }}>*</span></label>
                <input
                  id="account-party-id"
                  className="input"
                  placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                  value={form.partyId}
                  onChange={set('partyId')}
                  aria-invalid={Boolean(errors.partyId)}
                  aria-describedby={errors.partyId ? 'account-party-id-error' : undefined}
                  style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', borderColor: errors.partyId ? 'var(--danger)' : undefined }}
                />
                {errors.partyId && <span id="account-party-id-error" role="alert" style={{ fontSize: '11px', color: 'var(--danger)' }}>{errors.partyId}</span>}
              </div>

              <div className="field">
                <label htmlFor="account-product-id">{t('Product ID', 'Product ID')} <span aria-hidden="true" style={{ color: 'var(--danger)' }}>*</span></label>
                {!productsUnavailable ? <select
                  id="account-product-id"
                  className="input"
                  required
                  disabled={productsLoading}
                  value={form.productId}
                  onChange={event => selectProduct(event.target.value)}
                  aria-invalid={Boolean(errors.productId)}
                  aria-describedby={errors.productId ? 'account-product-id-error' : 'account-product-id-help'}
                  style={{ borderColor: errors.productId ? 'var(--danger)' : undefined }}
                >
                  <option value="">{productsLoading ? t('Načítám produkty…', 'Loading products…') : t('Vyberte aktivní produkt', 'Select an active product')}</option>
                  {products.map(product => <option key={product.id} value={product.id}>{product.name} ({product.code}) · {product.currency}</option>)}
                </select> : <input
                  id="account-product-id"
                  className="input"
                  placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                  value={form.productId}
                  onChange={set('productId')}
                  aria-invalid={Boolean(errors.productId)}
                  aria-describedby={errors.productId ? 'account-product-id-error' : 'account-product-id-help'}
                  style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', borderColor: errors.productId ? 'var(--danger)' : undefined }}
                />}
                {errors.productId && <span id="account-product-id-error" role="alert" style={{ fontSize: '11px', color: 'var(--danger)' }}>{errors.productId}</span>}
                {!errors.productId && <span id="account-product-id-help" style={{ fontSize: '11px', color: 'var(--text-muted)' }}>{productsUnavailable ? t('Katalog není dostupný; Product ID lze zadat ručně.', 'Catalog is unavailable; enter the Product ID manually.') : t('Typ účtu a měna se převezmou z produktu.', 'Account type and currency follow the selected product.')}</span>}
              </div>

              <div className="field">
                <label htmlFor="account-legal-name">{t('Právní název', 'Legal name')} <span aria-hidden="true" style={{ color: 'var(--danger)' }}>*</span></label>
                <input
                  id="account-legal-name"
                  className="input"
                  placeholder={t('Celé jméno nebo název společnosti', 'Full name or company name')}
                  value={form.legalName}
                  onChange={set('legalName')}
                  aria-invalid={Boolean(errors.legalName)}
                  aria-describedby={errors.legalName ? 'account-legal-name-error' : 'account-legal-name-help'}
                  style={{ borderColor: errors.legalName ? 'var(--danger)' : undefined }}
                />
                {errors.legalName
                  ? <span id="account-legal-name-error" role="alert" style={{ fontSize: '11px', color: 'var(--danger)' }}>{errors.legalName}</span>
                  : <span id="account-legal-name-help" style={{ fontSize: '11px', color: 'var(--text-muted)' }}>{t('Použito pro sankční screening (ADR-0032)', 'Used for sanctions screening (ADR-0032)')}</span>
                }
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
                <div className="field">
                  <label htmlFor="account-type">{t('Typ účtu', 'Account type')}</label>
                  <select id="account-type" className="input" value={form.accountType} onChange={set('accountType')}>
                    {ACCOUNT_TYPES.map(at => <option key={at}>{at}</option>)}
                  </select>
                </div>
                <div className="field">
                  <label htmlFor="account-currency">{t('Měna', 'Currency')}</label>
                  <select id="account-currency" className="input" value={form.currencyCode} onChange={set('currencyCode')}>
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
                <Save size={13} aria-hidden="true"/>
                {submitting ? t('Otevírám…', 'Opening…') : t('Otevřít účet', 'Open Account')}
              </button>
            </div>
          </div>
        </form>
      </div>
      </div>
    </AuthGuard>
  )
}
