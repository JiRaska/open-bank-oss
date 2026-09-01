// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import Link from 'next/link'
import { Map, Plus, Search, RefreshCw, Fingerprint, Clock, CheckCircle2, AlertTriangle, ShieldCheck } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { PageHeader, StatusBadge, statusTone, type Tone } from '@/components/ui'

const PID_SERVICE = '/api/svc/pid-service'

interface PidRecord {
  id: string
  personId: string
  identifierType: string
  identifierValue: string
  issuingCountry: string
  status: string
  verified: boolean
  createdAt: string
  validUntil?: string
}

// PID lifecycle deliberately treats expired credentials as renewal work and a
// revoked credential as a security concern. Those meanings are stricter than
// the shared consent-oriented defaults for the same words.
function pidStatusTone(status: string): Tone {
  if (status === 'EXPIRED') return 'warning'
  if (status === 'REVOKED') return 'danger'
  return statusTone(status)
}

export default function PidPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [records, setRecords] = useState<PidRecord[]>([])
  const [loading, setLoading] = useState(true)
  // Inline error is reserved for user-initiated writes (the quick-create form);
  // a failed list load degrades to the calm <DataUnavailable> panel instead of a
  // raw "HTTP 404" leak (admin-ui graceful-state rule).
  const [error, setError] = useState<string | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [search, setSearch] = useState('')
  const [showNewForm, setShowNewForm] = useState(false)
  const [formData, setFormData] = useState({ 
    givenName: '', 
    familyName: '', 
    birthdate: '',
    bankIdSub: '',
    nationalities: 'CZ',
    gender: '',
    birthplace: '',
    email: '',
    phone: '',
    idDocType: 'NATIONAL_ID',
    idDocNumber: '',
    idDocCountry: 'CZ',
    idDocIssuedAt: '',
    idDocExpiresAt: ''
  })
  const [formSubmitting, setFormSubmitting] = useState(false)
  const [successMsg, setSuccessMsg] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setError(null); setUnavailable(null)
    try {
      const res = await fetch(`${PID_SERVICE}/api/v1/pids`, { signal: AbortSignal.timeout(5000) })
      if (!res.ok) {
        // 404/405 usually mean the list endpoint isn't implemented yet, so treat
        // those as an empty list; everything else is classified for the panel.
        if (res.status === 404 || res.status === 405) {
          setRecords([])
        } else {
          setRecords([])
          setUnavailable({ kind: await classifyBffFailure(res) })
        }
        return
      }
      const data = await res.json()
      setRecords(Array.isArray(data) ? data : data.items ?? data.content ?? [])
    } catch {
      // Timeout / abort / network — the BFF or pid-service didn't answer.
      setRecords([])
      setUnavailable({ kind: 'unreachable' })
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const filtered = records.filter(r =>
    !search || r.identifierValue?.toLowerCase().includes(search.toLowerCase()) ||
    r.personId?.toLowerCase().includes(search.toLowerCase()) ||
    r.id?.toLowerCase().includes(search.toLowerCase())
  )

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setFormSubmitting(true)
    setError(null)
    setSuccessMsg(null)
    
    const requiredFields = [
      'givenName', 'familyName', 'birthdate', 'bankIdSub', 'nationalities', 
      'gender', 'birthplace', 'idDocType', 'idDocNumber', 'idDocCountry', 
      'idDocIssuedAt', 'idDocExpiresAt'
    ];
    
    for (const field of requiredFields) {
      if (!formData[field as keyof typeof formData]) {
        setError(t(`Prosím vyplňte všechna povinná pole (${field}).`, `Please fill all required fields (${field}).`));
        setFormSubmitting(false);
        return;
      }
    }
    
    try {
      const nationalitiesArray = formData.nationalities.split(',').map(s => s.trim()).filter(Boolean)
      
      const createPayload = {
        partyType: 'NATURAL_PERSON',
        givenName: formData.givenName,
        familyName: formData.familyName,
        birthdate: formData.birthdate,
        bankIdSub: formData.bankIdSub,
        nationalities: nationalitiesArray.length > 0 ? nationalitiesArray : ['CZ'],
        verificationSource: 'BANKID',
        initialRole: 'CUSTOMER',
        onboardingChannel: 'BANKID'
      }
      
      const res = await fetch(`${PID_SERVICE}/api/v1/parties`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(createPayload)
      })
      if (!res.ok) throw new Error(t('Vytvoření strany selhalo. Zkuste to prosím znovu.', 'Failed to create party. Please try again.'))

      const party = await res.json()
      const partyId = party.id || party.partyId

      if (formData.bankIdSub) {
        const syncPayload = {
          bankIdSub: formData.bankIdSub,
          givenName: formData.givenName,
          familyName: formData.familyName,
          birthdate: formData.birthdate,
          gender: formData.gender,
          birthplace: formData.birthplace,
          nationalities: nationalitiesArray,
          idDocuments: [{
            type: formData.idDocType,
            number: formData.idDocNumber,
            issuingCountry: formData.idDocCountry,
            issuedAt: formData.idDocIssuedAt,
            expiresAt: formData.idDocExpiresAt
          }],
          email: formData.email || undefined,
          phone: formData.phone || undefined
        }

        const syncRes = await fetch(`${PID_SERVICE}/api/v1/parties/${partyId}/sync/bankid`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(syncPayload)
        })

        if (!syncRes.ok) {
          throw new Error(t(`Záznam vytvořen (ID: ${partyId}), ale synchronizace BankID selhala. Zkuste to prosím znovu.`, `Record created (ID: ${partyId}), but BankID sync failed. Please try again.`))
        }
      }
      
      setShowNewForm(false)
      setFormData({ 
        givenName: '', familyName: '', birthdate: '', bankIdSub: '', nationalities: 'CZ', 
        gender: '', birthplace: '', email: '', phone: '', idDocType: 'NATIONAL_ID', idDocNumber: '', idDocCountry: 'CZ', idDocIssuedAt: '', idDocExpiresAt: '' 
      })
      load()
      setSuccessMsg(t('Záznam úspěšně vytvořen a synchronizován.', 'Record created and synchronized successfully.'))
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Failed to create record'
      setError(msg)
    } finally {
      setFormSubmitting(false)
    }
  }

  return (
    <AuthGuard permission="pid:view">
      <div style={{ animation: 'fadeIn 0.2s ease-out', maxWidth: '1400px', margin: '0 auto' }}>
        <PageHeader
          icon={<Map size={18} aria-hidden="true" />}
          title={t('Osobní identifikační údaje (PID)', 'Personal Identification Data (PID)')}
          subtitle={t('Správa identit, dokladů a identifikátorů klientů', 'Management of identities, documents, and client identifiers')}
          breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">PID</span></div>}
          actions={<div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '4px' }}>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                className="btn btn-secondary"
                type="button"
                onClick={load}
                disabled={loading}
                aria-busy={loading}
                aria-label={t('Obnovit PID záznamy', 'Refresh PID records')}
              >
                <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
                {t('Obnovit', 'Refresh')}
              </button>
              <button type="button" className="btn btn-secondary" onClick={() => setShowNewForm(true)} aria-label={t('Otevřít rychlé vytvoření PID záznamu', 'Open PID quick create')}>
                {t('Rychlé vytvoření', 'Quick Create')}
              </button>
              <Can permission="parties:create">
                <Link href="/parties/new" className="btn btn-primary" style={{ display: 'flex', alignItems: 'center', gap: '6px', textDecoration: 'none' }}>
                  <Plus size={13} aria-hidden="true" /> {t('Nový záznam', 'New Record')}
                </Link>
              </Can>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-secondary)', textAlign: 'right' }}>
              {t('Chcete vytvořit záznam rychle?', 'Want to create quickly?')}
            </div>
          </div>}
        />

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Záznamů celkem', 'Total Records'), value: records.length, icon: <Fingerprint size={16} />, color: 'var(--accent)' },
            { label: t('Aktivní', 'Active'), value: records.filter(r => r.status === 'ACTIVE').length, icon: <CheckCircle2 size={16} />, color: 'var(--success)' },
            { label: t('Ověřeno', 'Verified'), value: records.filter(r => r.verified).length, icon: <ShieldCheck size={16} />, color: 'var(--info)' },
            { label: t('Expirované/Zrušené', 'Expired/Revoked'), value: records.filter(r => r.status === 'EXPIRED' || r.status === 'REVOKED').length, icon: <AlertTriangle size={16} />, color: 'var(--danger)' },
          ].map(k => (
            <div key={k.label} className="stat-card">
              <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`,
                display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color, marginBottom: '10px' }}>{k.icon}</div>
              <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{k.value}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
            </div>
          ))}
        </div>

        <div className="card" style={{ marginBottom: '24px', padding: '20px' }}>
          <h2 style={{ fontSize: '16px', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Map size={16} style={{ color: 'var(--accent)' }} />
            {t('Jak funguje Case Management', 'How Case Management works')}
          </h2>
          <div style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: '1.6' }}>
            <p style={{ marginBottom: '8px' }}>
              <strong>Flow:</strong> Create &rarr; Verify &rarr; Review &rarr; Decision &rarr; Close
            </p>
            <ul style={{ paddingLeft: '20px', marginBottom: '12px' }}>
              <li><strong>Employee:</strong> {t('Vytváří a ověřuje záznamy', 'Creates and verifies records')}</li>
              <li><strong>Admin:</strong> {t('Schvaluje a spravuje životní cyklus', 'Approves and manages lifecycle')}</li>
              <li><strong>Customer:</strong> {t('Vidí pouze své vlastní záznamy', 'Can only see their own records')}</li>
            </ul>
            <p style={{ marginBottom: '0' }}>
              <strong>{t('Životní cyklus:', 'Lifecycle:')}</strong> {t('Akce probíhají v detailu případu. API řídí přechody mezi stavy podle rolí oprávnění.', 'Actions take place in the case detail. API drives state transitions based on role permissions.')}
            </p>
          </div>
        </div>

        {showNewForm && (
          <div className="card" style={{ padding: '20px', marginBottom: '24px', border: '1px solid var(--accent)' }}>
            <h3 style={{ fontSize: '16px', marginBottom: '8px' }}>{t('Nový záznam (Party)', 'New Record (Party)')}</h3>
            <div style={{ padding: '12px', marginBottom: '16px', background: 'var(--warning-bg, #fff3cd)', color: 'var(--warning-text, #664d03)', border: '1px solid var(--warning-border, #ffecb5)', borderRadius: '6px', fontSize: '13px' }}>
              <AlertTriangle size={14} style={{ display: 'inline-block', verticalAlign: 'text-bottom', marginRight: '6px' }} />
              <strong>{t('Rychlé vytvoření je pouze předvyplnění. Právně závazná AML identifikace vyžaduje plný onboarding a ověření.', 'Quick create is only pre-filling. Legally binding AML identification requires full onboarding and verification.')}</strong>
            </div>
            <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                <div style={{ gridColumn: '1 / -1' }}>
                  <h4 style={{ fontSize: '13px', margin: '0 0 8px 0', borderBottom: '1px solid var(--border-color)', paddingBottom: '4px' }}>{t('Základní údaje', 'Basic Info')}</h4>
                </div>
                <div>
                  <label htmlFor="pid-given-name" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Jméno', 'Given Name')} *</label>
                  <input 
                    id="pid-given-name"
                    className="input" 
                    required 
                    style={{ width: '100%' }}
                    value={formData.givenName} 
                    onChange={e => setFormData({ ...formData, givenName: e.target.value })} 
                  />
                </div>
                <div>
                  <label htmlFor="pid-family-name" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Příjmení', 'Family Name')} *</label>
                  <input 
                    id="pid-family-name"
                    className="input" 
                    required 
                    style={{ width: '100%' }}
                    value={formData.familyName} 
                    onChange={e => setFormData({ ...formData, familyName: e.target.value })} 
                  />
                </div>
                <div>
                  <label htmlFor="pid-birthdate" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Datum narození', 'Birthdate')} *</label>
                  <input 
                    id="pid-birthdate"
                    type="date"
                    className="input" 
                    required 
                    style={{ width: '100%' }}
                    value={formData.birthdate} 
                    onChange={e => setFormData({ ...formData, birthdate: e.target.value })} 
                  />
                </div>
                <div>
                  <label htmlFor="pid-gender" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Pohlaví', 'Gender')} *</label>
                  <select 
                    id="pid-gender"
                    className="input" 
                    required
                    style={{ width: '100%' }}
                    value={formData.gender} 
                    onChange={e => setFormData({ ...formData, gender: e.target.value })} 
                  >
                    <option value="">{t('Neurčeno', 'Not specified')}</option>
                    <option value="MALE">{t('Muž', 'Male')}</option>
                    <option value="FEMALE">{t('Žena', 'Female')}</option>
                  </select>
                </div>
                <div>
                  <label htmlFor="pid-birthplace" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Místo narození', 'Birthplace')} *</label>
                  <input 
                    id="pid-birthplace"
                    className="input" 
                    required
                    style={{ width: '100%' }}
                    value={formData.birthplace} 
                    onChange={e => setFormData({ ...formData, birthplace: e.target.value })} 
                  />
                </div>
                <div>
                  <label htmlFor="pid-nationalities" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Občanství (čárkou oddělené)', 'Nationalities (comma separated)')} *</label>
                  <input 
                    id="pid-nationalities"
                    className="input" 
                    required 
                    style={{ width: '100%' }}
                    value={formData.nationalities} 
                    onChange={e => setFormData({ ...formData, nationalities: e.target.value })} 
                  />
                </div>

                <div style={{ gridColumn: '1 / -1', marginTop: '8px' }}>
                  <h4 style={{ fontSize: '13px', margin: '0 0 8px 0', borderBottom: '1px solid var(--border-color)', paddingBottom: '4px' }}>{t('BankID a Kontakt', 'BankID & Contact')}</h4>
                </div>
                <div>
                  <label htmlFor="pid-bankid-sub" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('BankID SUB', 'BankID SUB')} *</label>
                  <input 
                    id="pid-bankid-sub"
                    className="input" 
                    required
                    style={{ width: '100%' }}
                    value={formData.bankIdSub} 
                    onChange={e => setFormData({ ...formData, bankIdSub: e.target.value })} 
                  />
                </div>
                <div>
                  <label htmlFor="pid-email" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Email', 'Email')}</label>
                  <input 
                    id="pid-email"
                    type="email"
                    className="input" 
                    style={{ width: '100%' }}
                    value={formData.email} 
                    onChange={e => setFormData({ ...formData, email: e.target.value })} 
                  />
                </div>
                <div>
                  <label htmlFor="pid-phone" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Telefon', 'Phone')}</label>
                  <input 
                    id="pid-phone"
                    type="tel"
                    className="input" 
                    style={{ width: '100%' }}
                    value={formData.phone} 
                    onChange={e => setFormData({ ...formData, phone: e.target.value })} 
                  />
                </div>

                <div style={{ gridColumn: '1 / -1', marginTop: '8px' }}>
                  <h4 style={{ fontSize: '13px', margin: '0 0 8px 0', borderBottom: '1px solid var(--border-color)', paddingBottom: '4px' }}>{t('Primární doklad totožnosti', 'Primary ID Document')}</h4>
                </div>
                <div>
                  <label htmlFor="pid-document-type" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Typ dokladu', 'Document Type')} *</label>
                  <select 
                    id="pid-document-type"
                    className="input" 
                    required
                    style={{ width: '100%' }}
                    value={formData.idDocType} 
                    onChange={e => setFormData({ ...formData, idDocType: e.target.value })} 
                  >
                    <option value="NATIONAL_ID">{t('Občanský průkaz', 'National ID')}</option>
                    <option value="PASSPORT">{t('Cestovní pas', 'Passport')}</option>
                    <option value="RESIDENCE_PERMIT">{t('Povolení k pobytu', 'Residence Permit')}</option>
                  </select>
                </div>
                <div>
                  <label htmlFor="pid-document-number" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Číslo dokladu', 'Document Number')} *</label>
                  <input 
                    id="pid-document-number"
                    className="input" 
                    required
                    style={{ width: '100%' }}
                    value={formData.idDocNumber} 
                    onChange={e => setFormData({ ...formData, idDocNumber: e.target.value })} 
                  />
                </div>
                <div>
                  <label htmlFor="pid-document-country" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Vydávající stát', 'Issuing Country')} *</label>
                  <input 
                    id="pid-document-country"
                    className="input" 
                    required
                    style={{ width: '100%' }}
                    value={formData.idDocCountry} 
                    onChange={e => setFormData({ ...formData, idDocCountry: e.target.value })} 
                  />
                </div>
                <div>
                  <label htmlFor="pid-document-issued-at" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Datum vydání', 'Issued At')} *</label>
                  <input 
                    id="pid-document-issued-at"
                    type="date"
                    className="input" 
                    required
                    style={{ width: '100%' }}
                    value={formData.idDocIssuedAt} 
                    onChange={e => setFormData({ ...formData, idDocIssuedAt: e.target.value })} 
                  />
                </div>
                <div>
                  <label htmlFor="pid-document-expires-at" style={{ display: 'block', fontSize: '12px', marginBottom: '6px', color: 'var(--text-secondary)' }}>{t('Platnost do', 'Expires At')} *</label>
                  <input 
                    id="pid-document-expires-at"
                    type="date"
                    className="input" 
                    required
                    style={{ width: '100%' }}
                    value={formData.idDocExpiresAt} 
                    onChange={e => setFormData({ ...formData, idDocExpiresAt: e.target.value })} 
                  />
                </div>
              </div>
              <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end', marginTop: '8px' }}>
                <button type="button" className="btn btn-secondary" onClick={() => setShowNewForm(false)} disabled={formSubmitting}>
                  {t('Zrušit', 'Cancel')}
                </button>
                <button type="submit" className="btn btn-primary" disabled={formSubmitting}>
                  {formSubmitting ? t('Vytvářím...', 'Creating...') : t('Vytvořit', 'Create')}
                </button>
              </div>
            </form>
          </div>
        )}

        {successMsg && (
          <div className="card" style={{ padding: '16px', color: 'var(--success-text, #0f5132)', background: 'var(--success-bg, #d1e7dd)', border: '1px solid var(--success-border, #badbcc)', marginBottom: '16px', fontSize: '13px', borderRadius: '8px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <CheckCircle2 size={16} /> {successMsg}
          </div>
        )}

        <div style={{ display: 'flex', gap: '10px', marginBottom: '16px' }}>
          <div style={{ position: 'relative', flex: 1, maxWidth: '360px' }}>
            <Search size={14} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
            <input
              className="input"
              style={{ paddingLeft: '32px', width: '100%' }}
              placeholder={t('Hledat hodnotu, ID osoby...', 'Search value, person ID...')}
              aria-label={t('Hledat PID případy', 'Search PID cases')}
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
        </div>

        {error && (
          <div className="card" style={{ padding: '16px', color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', marginBottom: '16px', fontSize: '13px', borderRadius: '8px' }}>
            {error}
          </div>
        )}

        {unavailable && (
          <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
            <DataUnavailable
              kind={unavailable.kind}
              service={t('PID-service', 'PID-service')}
              feature={t('PID záznamy', 'PID records')}
              lang={language}
              dense
            />
          </div>
        )}

        {!unavailable && (
        <div className="card" style={{ overflow: 'hidden' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>{t('Typ', 'Type')}</th>
                <th>{t('Hodnota', 'Value')}</th>
                <th>{t('Země', 'Country')}</th>
                <th>{t('Osoba', 'Person ID')}</th>
                <th>{t('Status', 'Status')}</th>
                <th>{t('Ověřeno', 'Verified')}</th>
                <th>{t('Vytvořeno', 'Created')}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {loading && Array.from({ length: 4 }).map((_, i) => (
                <tr key={i}>
                  {Array.from({ length: 8 }).map((_, j) => (
                    <td key={j}><div className="skeleton" style={{ height: '14px', width: j === 1 ? '140px' : '80px', borderRadius: '4px' }} /></td>
                  ))}
                </tr>
              ))}
              {!loading && filtered.length === 0 && (
                <tr>
                  <td colSpan={8} style={{ textAlign: 'center', padding: '48px' }}>
                    <Map size={32} style={{ color: 'var(--text-tertiary)', marginBottom: '12px', margin: '0 auto' }} />
                    <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '4px' }}>
                      {search ? t('Nenalezeny žádné záznamy', 'No records found matching search') : t('Žádné PID záznamy', 'No PID records found')}
                    </div>
                    <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                      {t('Služba pid-service aktuálně nevrací žádná data.', 'The pid-service is currently returning no data.')}
                    </div>
                  </td>
                </tr>
              )}
              {!loading && filtered.map(r => (
                <tr key={r.id}>
                  <td><span className="tag">{r.identifierType}</span></td>
                  <td style={{ fontWeight: 600, fontFamily: 'var(--font-mono)' }}>{r.identifierValue}</td>
                  <td style={{ color: 'var(--text-secondary)' }}>{r.issuingCountry}</td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>
                    <Can permission="parties:view">
                      <Link href={`/parties/${r.personId}`} style={{ color: 'var(--accent)', textDecoration: 'none' }}>
                        {r.personId?.slice(0, 8) || r.personId}...
                      </Link>
                    </Can>
                  </td>
                  <td>
                    <StatusBadge status={r.status} tone={pidStatusTone(r.status)} />
                  </td>
                  <td>
                    {r.verified ? <CheckCircle2 size={14} color="var(--success)" /> : <Clock size={14} color="var(--warning)" />}
                  </td>
                  <td style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{new Date(r.createdAt).toLocaleDateString(dateLocale)}</td>
                  <td>
                    <span role="status" style={{ color: 'var(--text-tertiary)', fontSize: '12px' }}>
                      {t('Detail není dostupný', 'Details unavailable')}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        )}
      </div>
    </AuthGuard>
  )
}
