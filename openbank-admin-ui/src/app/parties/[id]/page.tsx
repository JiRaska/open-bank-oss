// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { useParams, useRouter } from 'next/navigation'
import Link from 'next/link'
import { Users, ArrowLeft, ShieldCheck, FileText, RefreshCw } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const PARTY_SERVICE = '/api/svc/party-service'
const KYC_SERVICE   = '/api/svc/kyc-service'

interface Party {
  id: string; partyType: string; status: string; legalName: string; tradingName?: string
  email: string; phone?: string; kycStatus: string; taxId?: string; registrationNumber?: string
  nationality?: string; dateOfBirth?: string; address?: { line1: string; city: string; postalCode: string; countryCode: string }
  createdAt: string; updatedAt: string
}

interface KycCase {
  id: string; status: string; checks: { checkType: string; status: string; result?: string }[]
  reviewedBy?: string; createdAt: string; updatedAt: string
}

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'var(--green)', INACTIVE: 'var(--text-muted)', BLOCKED: 'var(--red)',
}
const KYC_COLOR: Record<string, string> = {
  APPROVED: 'var(--green)', PENDING: 'var(--yellow)', REJECTED: 'var(--red)', NOT_STARTED: 'var(--text-muted)',
}

export default function PartyDetailPage() {
  const { id } = useParams<{ id: string }>()
  const router = useRouter()
  const { t } = useLanguage()
  const [party, setParty]     = useState<Party | null>(null)
  const [kyc, setKyc]         = useState<KycCase | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setError(null)
    try {
      const [partyRes, kycRes] = await Promise.allSettled([
        fetch(`${PARTY_SERVICE}/api/v1/parties/${id}`).then(r => r.ok ? r.json() : Promise.reject(r.status)),
        fetch(`${KYC_SERVICE}/api/v1/kyc/cases/party/${id}`).then(r => r.ok ? r.json() : null),
      ])
      if (partyRes.status === 'rejected') throw new Error(`Party not found (${partyRes.reason})`)
      setParty(partyRes.value)
      if (kycRes.status === 'fulfilled') setKyc(kycRes.value)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load party')
    } finally { setLoading(false) }
  }, [id])

  useEffect(() => { load() }, [load])

  if (loading) return (
    <div>
      <div className="page-header"><div><div className="skeleton" style={{ height: '24px', width: '200px' }} /></div></div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
        {[1,2].map(i => <div key={i} className="card" style={{ padding: '20px', height: '200px' }}><div className="skeleton" style={{ height: '100%' }} /></div>)}
      </div>
    </div>
  )

  if (error) return (
    <div>
      <div className="page-header">
        <button className="btn btn-secondary" onClick={() => router.back()}><ArrowLeft size={13} /> {t('Zpět', 'Back')}</button>
      </div>
      <div className="card" style={{ padding: '24px', color: 'var(--red)' }}>{error}</div>
    </div>
  )

  if (!party) return null

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <Link href="/parties" style={{ color: 'var(--text-secondary)', textDecoration: 'none' }}>{t('Subjekty', 'Parties')}</Link>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{party.legalName}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Users size={18} style={{ color: 'var(--accent)' }} />
            {party.legalName}
          </h1>
          <p className="page-subtitle" style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{party.id}</p>
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          <button className="btn btn-secondary" onClick={load}><RefreshCw size={13} /> {t('Obnovit', 'Refresh')}</button>
          <Link href="/parties" className="btn btn-secondary" style={{ textDecoration: 'none', display: 'flex', alignItems: 'center', gap: '6px' }}>
            <ArrowLeft size={13} /> {t('Zpět', 'Back')}
          </Link>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
        {/* Party Details */}
        <div className="card" style={{ padding: '20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '16px' }}>
            <Users size={15} style={{ color: 'var(--accent)' }} />
            <span style={{ fontWeight: 600, fontSize: '13px' }}>{t('Detaily subjektu', 'Party Details')}</span>
            <span className="pill" style={{ marginLeft: 'auto', background: `${STATUS_COLOR[party.status] ?? 'var(--text-muted)'}22`, color: STATUS_COLOR[party.status] ?? 'var(--text-muted)' }}>
              {party.status}
            </span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            {[
              [t('Typ', 'Type'),                        party.partyType],
              [t('Obchodní jméno', 'Legal Name'),       party.legalName],
              [t('Obchodní název', 'Trading Name'),     party.tradingName ?? '—'],
              [t('E-mail', 'Email'),                    party.email],
              [t('Telefon', 'Phone'),                   party.phone ?? '—'],
              ['Tax ID',                                 party.taxId ?? '—'],
              [t('Reg. číslo', 'Reg. Number'),          party.registrationNumber ?? '—'],
              [t('Státní příslušnost', 'Nationality'),  party.nationality ?? '—'],
              [t('Datum narození', 'Date of Birth'),    party.dateOfBirth ?? '—'],
              [t('Vytvořeno', 'Created'),               new Date(party.createdAt).toLocaleString()],
              [t('Aktualizováno', 'Updated'),           new Date(party.updatedAt).toLocaleString()],
            ].map(([label, value]) => (
              <div key={label} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px', borderBottom: '1px solid var(--border)', paddingBottom: '8px' }}>
                <span style={{ color: 'var(--text-muted)' }}>{label}</span>
                <span style={{ fontWeight: 500, textAlign: 'right', maxWidth: '200px', wordBreak: 'break-all' }}>{value}</span>
              </div>
            ))}
          </div>
        </div>

        {/* KYC Status */}
        <div className="card" style={{ padding: '20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '16px' }}>
            <ShieldCheck size={15} style={{ color: 'var(--accent)' }} />
            <span style={{ fontWeight: 600, fontSize: '13px' }}>{t('Stav KYC', 'KYC Status')}</span>
            <span className="pill" style={{ marginLeft: 'auto', background: `${KYC_COLOR[party.kycStatus] ?? 'var(--text-muted)'}22`, color: KYC_COLOR[party.kycStatus] ?? 'var(--text-muted)' }}>
              {party.kycStatus?.replace('_', ' ')}
            </span>
          </div>
          {kyc ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '8px' }}>
                {t('ID případu:', 'Case ID:')} <span style={{ fontFamily: 'var(--font-mono)' }}>{kyc.id}</span>
              </div>
              {kyc.checks?.map(check => (
                <div key={check.checkType} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px', background: 'var(--surface-2)', borderRadius: '6px' }}>
                  <span style={{ fontSize: '13px' }}>{check.checkType?.replace(/_/g, ' ') ?? check.checkType}</span>
                  <span className="pill" style={{ background: `${KYC_COLOR[check.status] ?? 'var(--text-muted)'}22`, color: KYC_COLOR[check.status] ?? 'var(--text-muted)' }}>
                    {check.status}
                  </span>
                </div>
              ))}
              {kyc.reviewedBy && (
                <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '8px' }}>
                  {t('Kontroloval:', 'Reviewed by:')} {kyc.reviewedBy}
                </div>
              )}
            </div>
          ) : (
            <div style={{ color: 'var(--text-muted)', fontSize: '13px', padding: '20px 0', textAlign: 'center' }}>
              {t('Pro tento subjekt nebyl nalezen žádný případ KYC', 'No KYC case found for this party')}
            </div>
          )}

          {/* Address */}
          {party.address && (
            <div style={{ marginTop: '20px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
                <FileText size={13} style={{ color: 'var(--accent)' }} />
                <span style={{ fontWeight: 600, fontSize: '13px' }}>{t('Adresa', 'Address')}</span>
              </div>
              <div style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: '1.6' }}>
                {party.address.line1}<br />
                {party.address.city}, {party.address.postalCode}<br />
                {party.address.countryCode}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
