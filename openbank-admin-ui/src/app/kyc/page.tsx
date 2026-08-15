// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { ShieldCheck, Search, RefreshCw, ChevronRight } from 'lucide-react'
import Link from 'next/link'
import { PageHeader, StatusBadge } from '@/components/ui'

const KYC_SERVICE = '/api/svc/kyc-service'

interface KycCase {
  id: string; partyId: string; status: string
  checks: { checkType: string; status: string }[]
  reviewedBy?: string; createdAt: string; updatedAt: string
}

export default function KycPage() {
  const [cases, setCases]     = useState<KycCase[]>([])
  const { t, language } = useLanguage()
  const [loading, setLoading] = useState(true)
  // Typed unavailable reason → renders the calm <DataUnavailable> panel instead
  // of leaking a raw "HTTP 404" string (admin-ui graceful-state rule).
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [search, setSearch]   = useState('')
  const [partyId, setPartyId] = useState('')

  const load = useCallback(async () => {
    setLoading(true); setUnavailable(null)
    try {
      const url = partyId
        ? `${KYC_SERVICE}/api/v1/kyc/cases/party/${partyId}`
        : `${KYC_SERVICE}/api/v1/kyc/cases`
      const res = await fetch(url, { signal: AbortSignal.timeout(5000) })
      if (!res.ok) {
        const kind = await classifyBffFailure(res)
        setCases([])
        // A genuine 404/405 on the cases endpoint means "no case for this party",
        // not a broken app — degrade to the calm empty state rather than an error.
        setUnavailable({ kind: res.status === 405 || kind === 'not_found' ? 'no_data' : kind })
        return
      }
      const data = await res.json()
      setCases(Array.isArray(data) ? data : data.items ?? [data].filter(Boolean))
    } catch {
      // Timeout / abort / network — the BFF or kyc-service didn't answer.
      setCases([])
      setUnavailable({ kind: 'unreachable' })
    } finally { setLoading(false) }
  }, [partyId])

  useEffect(() => { load() }, [load])

  const filtered = cases.filter(c =>
    !search || c.id.includes(search) || c.partyId.includes(search) || c.status.includes(search.toUpperCase())
  )

  return (
    <div>
      <PageHeader
        title={t('KYC Případy', 'KYC Cases')}
        subtitle={t('Ověření totožnosti zákazníka — přehled případů', 'Know Your Customer verification cases')}
        icon={<ShieldCheck size={20} aria-hidden="true" />}
        actions={<button className="btn btn-secondary" onClick={load} disabled={loading}>
          <RefreshCw size={13} style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
          {t('Obnovit', 'Refresh')}
        </button>}
      />

      {/* Toolbar */}
      <div style={{ display: 'flex', gap: '10px', marginBottom: '16px' }}>
        <div style={{ position: 'relative', flex: 1, maxWidth: '300px' }}>
          <Search size={14} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
          <input className="input" style={{ paddingLeft: '32px', width: '100%' }} placeholder={t('Hledat podle ID nebo stavu…', 'Search by ID or status…')} value={search} onChange={e => setSearch(e.target.value)} />
        </div>
        <input className="input" style={{ width: '280px', fontFamily: 'var(--font-mono)', fontSize: '12px' }} placeholder={t('Filtrovat podle Party ID (UUID)…', 'Filter by Party ID (UUID)…')} value={partyId} onChange={e => setPartyId(e.target.value)} />
        <button className="btn btn-secondary" onClick={load}>{t('Hledat', 'Search')}</button>
      </div>

      {unavailable && (
        <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
          <DataUnavailable
            kind={unavailable.kind}
            service={t('KYC-service', 'KYC-service')}
            feature={t('KYC případy', 'KYC cases')}
            lang={language}
            detail={unavailable.kind === 'no_data' && partyId
              ? t('Pro tuto party nebyl nalezen žádný KYC případ.', 'No KYC case was found for this party.')
              : undefined}
            dense
          />
        </div>
      )}

      <div className="card" style={{ overflow: 'hidden' }}>
        <table className="data-table">
          <thead>
            <tr>
              <th>{t('ID případu', 'Case ID')}</th>
              <th>{t('Party ID', 'Party ID')}</th>
              <th>{t('Stav', 'Status')}</th>
              <th>{t('Ověření', 'Checks')}</th>
              <th>{t('Zkontroloval', 'Reviewed By')}</th>
              <th>{t('Aktualizováno', 'Updated')}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {loading && Array.from({ length: 5 }).map((_, i) => (
              <tr key={i}>{Array.from({ length: 7 }).map((_, j) => <td key={j}><div className="skeleton" style={{ height: '14px', width: j === 0 ? '200px' : '80px' }} /></td>)}</tr>
            ))}
            {!loading && !unavailable && filtered.length === 0 && (
              <tr><td colSpan={7} style={{ padding: 0 }}>
                <DataUnavailable
                  kind="no_data"
                  feature={t('KYC případy', 'KYC cases')}
                  lang={language}
                  detail={partyId
                    ? t('Pro tuto party nebyl nalezen žádný KYC případ.', 'No KYC case was found for this party.')
                    : t('Žádné KYC případy nenalezeny.', 'No KYC cases found.')}
                  dense
                />
              </td></tr>
            )}
            {!loading && filtered.map(c => (
              <tr key={c.id}>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>{c.id.slice(0, 8)}…</td>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>
                  <Link href={`/parties/${c.partyId}`} style={{ color: 'var(--accent)', textDecoration: 'none' }}>{c.partyId.slice(0, 8)}…</Link>
                </td>
                <td>
                  <StatusBadge status={c.status} />
                </td>
                <td>
                  <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
                    {c.checks?.map(ch => (
                      <StatusBadge key={ch.checkType} status={ch.status} label={ch.checkType?.replace(/_/g, ' ') ?? ch.checkType} />
                    ))}
                  </div>
                </td>
                <td style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{c.reviewedBy ?? '—'}</td>
                <td style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{new Date(c.updatedAt).toLocaleDateString()}</td>
                <td>
                  <Link href={`/parties/${c.partyId}`} style={{ color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: '2px', fontSize: '12px', textDecoration: 'none' }}>
                    {t('Klient', 'Party')} <ChevronRight size={12} />
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
