// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { Users, ArrowLeft, Save } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard } from '@/components/auth/AuthGuard'
import styles from './page.module.css'

const PARTY_SERVICE = '/api/svc/party-service'

export default function NewPartyPage() {
  const router = useRouter()
  const { t } = useLanguage()
  const [saving, setSaving]   = useState(false)
  const [error, setError]     = useState<string | null>(null)
  // State updates are asynchronous, so `saving` alone cannot stop two submit events in the
  // same event turn. Creating a party is externally visible and the current service only
  // de-duplicates by email, so take a synchronous client-side single-flight lock as well.
  const createInFlight = useRef(false)
  // A retry after a lost/failed response must replay the same idempotency key: the server may
  // already have committed the first request. Editing any field starts a genuinely new command.
  const idempotencyKeyRef = useRef<string | null>(null)
  const [form, setForm] = useState({
    partyType: 'INDIVIDUAL',
    legalName: '', tradingName: '', email: '', phone: '',
    taxId: '', registrationNumber: '', nationality: '', dateOfBirth: '',
    addressLine1: '', addressCity: '', addressPostal: '', addressCountry: 'CZ',
  })

  const set = (k: string, v: string) => {
    idempotencyKeyRef.current = null
    setForm(f => ({ ...f, [k]: v }))
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (createInFlight.current) return
    createInFlight.current = true
    setSaving(true); setError(null)
    try {
      const idempotencyKey = idempotencyKeyRef.current ?? crypto.randomUUID()
      idempotencyKeyRef.current = idempotencyKey
      const body = {
        partyType: form.partyType,
        legalName: form.legalName,
        tradingName: form.tradingName || undefined,
        email: form.email,
        phone: form.phone || undefined,
        taxId: form.taxId || undefined,
        registrationNumber: form.registrationNumber || undefined,
        nationality: form.nationality || undefined,
        dateOfBirth: form.dateOfBirth || undefined,
        address: form.addressLine1 ? {
          line1: form.addressLine1, line2: undefined,
          city: form.addressCity, postalCode: form.addressPostal, countryCode: form.addressCountry,
        } : undefined,
      }
      const res = await fetch(`${PARTY_SERVICE}/api/v1/parties`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      })
      if (!res.ok) {
        // User-initiated write: show a calm, human inline message rather than
        // leaking the raw backend status (admin-ui graceful-state rule).
        setError(t('Vytvoření strany selhalo. Zkuste to prosím znovu.', 'Failed to create party. Please try again.'))
        return
      }
      const party = await res.json()
      router.push(`/parties/${party.id}`)
    } catch {
      setError(t('Vytvoření strany selhalo. Zkuste to prosím znovu.', 'Failed to create party. Please try again.'))
    } finally {
      createInFlight.current = false
      setSaving(false)
    }
  }

  return (
    <AuthGuard permission="parties:create">
    <div>
      <PageHeader
        icon={<Users size={18} aria-hidden="true" />}
        title={t('Registrovat nový subjekt', 'Register New Party')}
        subtitle={t('Vytvořte nového zákazníka nebo společnost v platformě', 'Create a new customer or company in the platform')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/parties" style={{ color: 'var(--text-secondary)', textDecoration: 'none' }}>{t('Subjekty', 'Parties')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Nový subjekt', 'New Party')}</span></div>}
        actions={<Link href="/parties" className="btn btn-secondary" style={{ textDecoration: 'none', display: 'flex', alignItems: 'center', gap: '6px' }}><ArrowLeft size={13} aria-hidden="true" /> {t('Zpět', 'Back')}</Link>}
      />

      <form onSubmit={submit} aria-busy={saving} aria-describedby="party-form-guidance">
        <p id="party-form-guidance" className={styles.guidance}>
          {t('Pole označená * jsou povinná. Údaje před uložením zkontrolujte — stanou se součástí klientského profilu.', 'Fields marked * are required. Review the details before saving — they become part of the customer profile.')}
        </p>
        <div className={styles.formGrid}>
          {/* Identity */}
          <div className="card" style={{ padding: '20px' }}>
            <div style={{ fontWeight: 600, fontSize: '13px', marginBottom: '16px' }}>{t('Identita', 'Identity')}</div>
            <div className="field">
              <label className="field-label" htmlFor="party-type">{t('Typ subjektu *', 'Party Type *')}</label>
              <select id="party-type" className="input" value={form.partyType} onChange={e => set('partyType', e.target.value)}>
                <option value="INDIVIDUAL">{t('Fyzická osoba', 'Individual')}</option>
                <option value="COMPANY">{t('Společnost', 'Company')}</option>
                <option value="SOLE_TRADER">{t('Živnostník', 'Sole Trader')}</option>
              </select>
            </div>
            <div className="field">
              <label className="field-label" htmlFor="party-legal-name">{t('Obchodní jméno *', 'Legal Name *')}</label>
              <input id="party-legal-name" className="input" required autoComplete="name" value={form.legalName} onChange={e => set('legalName', e.target.value)} placeholder={t('Celé právní jméno', 'Full legal name')} />
            </div>
            <div className="field">
              <label className="field-label" htmlFor="party-trading-name">{t('Obchodní název', 'Trading Name')}</label>
              <input id="party-trading-name" className="input" value={form.tradingName} onChange={e => set('tradingName', e.target.value)} placeholder={t('Nepovinné', 'Optional')} />
            </div>
            <div className="field">
              <label className="field-label" htmlFor="party-tax-id">{t('DIČ', 'Tax ID')}</label>
              <input id="party-tax-id" className="input" value={form.taxId} onChange={e => set('taxId', e.target.value)} placeholder="e.g. CZ12345678" />
            </div>
            <div className="field">
              <label className="field-label" htmlFor="party-registration-number">{t('Registrační číslo', 'Registration Number')}</label>
              <input id="party-registration-number" className="input" value={form.registrationNumber} onChange={e => set('registrationNumber', e.target.value)} placeholder={t('IČO firmy', 'Company reg. number')} />
            </div>
            {form.partyType === 'INDIVIDUAL' && <>
              <div className="field">
                <label className="field-label" htmlFor="party-nationality">{t('Státní příslušnost', 'Nationality')}</label>
                <input id="party-nationality" className="input" value={form.nationality} onChange={e => set('nationality', e.target.value)} placeholder="e.g. CZ" maxLength={2} />
              </div>
              <div className="field">
                <label className="field-label" htmlFor="party-date-of-birth">{t('Datum narození', 'Date of Birth')}</label>
                <input id="party-date-of-birth" className="input" type="date" autoComplete="bday" value={form.dateOfBirth} onChange={e => set('dateOfBirth', e.target.value)} />
              </div>
            </>}
          </div>

          {/* Contact & Address */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div className="card" style={{ padding: '20px' }}>
              <div style={{ fontWeight: 600, fontSize: '13px', marginBottom: '16px' }}>{t('Kontakt', 'Contact')}</div>
              <div className="field">
                <label className="field-label" htmlFor="party-email">{t('E-mail *', 'Email *')}</label>
                <input id="party-email" className="input" type="email" required autoComplete="email" value={form.email} onChange={e => set('email', e.target.value)} placeholder="email@example.com" />
              </div>
              <div className="field">
                <label className="field-label" htmlFor="party-phone">{t('Telefon', 'Phone')}</label>
                <input id="party-phone" className="input" autoComplete="tel" value={form.phone} onChange={e => set('phone', e.target.value)} placeholder="+420 123 456 789" />
              </div>
            </div>

            <div className="card" style={{ padding: '20px' }}>
              <div style={{ fontWeight: 600, fontSize: '13px', marginBottom: '16px' }}>{t('Adresa', 'Address')}</div>
              <div className="field">
                <label className="field-label" htmlFor="party-address-line1">{t('Ulice', 'Street')}</label>
                <input id="party-address-line1" className="input" autoComplete="street-address" value={form.addressLine1} onChange={e => set('addressLine1', e.target.value)} placeholder={t('Ulice a číslo popisné', 'Street and number')} />
              </div>
              <div className={styles.addressGrid}>
                <div className="field">
                  <label className="field-label" htmlFor="party-address-city">{t('Město', 'City')}</label>
                  <input id="party-address-city" className="input" autoComplete="address-level2" value={form.addressCity} onChange={e => set('addressCity', e.target.value)} />
                </div>
                <div className="field">
                  <label className="field-label" htmlFor="party-address-postal">{t('PSČ', 'Postal Code')}</label>
                  <input id="party-address-postal" className="input" autoComplete="postal-code" value={form.addressPostal} onChange={e => set('addressPostal', e.target.value)} />
                </div>
              </div>
              <div className="field">
                <label className="field-label" htmlFor="party-address-country">{t('Kód země', 'Country Code')}</label>
                <input id="party-address-country" className="input" autoComplete="country" value={form.addressCountry} onChange={e => set('addressCountry', e.target.value)} maxLength={2} placeholder="CZ" />
              </div>
            </div>
          </div>
        </div>

        {error && (
          <div className="card" role="alert" style={{ padding: '12px 16px', color: 'var(--red)', marginTop: '16px', fontSize: '13px' }}>
            {error}
          </div>
        )}

        <div className={styles.actions}>
          <Link href="/parties" className="btn btn-secondary" style={{ textDecoration: 'none', display: 'flex', alignItems: 'center', gap: '6px' }}>
            {t('Zrušit', 'Cancel')}
          </Link>
          <button type="submit" className="btn btn-primary" disabled={saving} aria-busy={saving}>
            <Save size={13} aria-hidden="true" />
            {saving ? t('Vytvářím…', 'Creating…') : t('Vytvořit subjekt', 'Create Party')}
          </button>
        </div>
      </form>
    </div>
    </AuthGuard>
  )
}
