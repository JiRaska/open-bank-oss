// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState } from 'react'
import { Users, Search } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import type { Customer360 } from '@/app/api/customer-360/[partyId]/route'

// ADR-0210: a lookup over the analytics silver layer, not a customer list. There is no
// crm-service and no "list all customers" surface here — party-service owns that.
//
// Everything shown is DERIVED from an event projection and is deliberately non-authoritative
// (ADR-0210 D3 / ADR-0089): counts, recency and lifecycle state, never balances or transaction
// rows. The `asOf` chip is not decoration — it is how an operator sees the view's staleness
// instead of assuming it is live.
export default function Customer360Page() {
  const { t, language } = useLanguage()
  const [term, setTerm] = useState('')
  const [data, setData] = useState<Customer360 | null>(null)
  const [failure, setFailure] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(false)

  const lookup = async () => {
    const q = term.trim()
    if (!q) return
    setLoading(true)
    setFailure(null)
    setData(null)
    try {
      const res = await fetch(`/api/customer-360/${encodeURIComponent(q)}`, { cache: 'no-store' })
      const body = (await res.json()) as Customer360
      if (res.status === 400) {
        setFailure('error')
        return
      }
      if (!body.available) {
        setFailure(body.error ? 'unreachable' : 'no_data')
        return
      }
      setData(body)
    } catch {
      setFailure('unreachable')
    } finally {
      setLoading(false)
    }
  }

  const fmt = (iso: string) =>
    new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'medium', timeStyle: 'short' })
      .format(new Date(iso.replace(' ', 'T') + 'Z'))

  const totalEvents = data?.domains.reduce((n, d) => n + d.events, 0) ?? 0

  return (
    <div style={{ padding: '32px', maxWidth: '1280px', margin: '0 auto' }}>
      <PageHeader
        icon={<Users size={28} className="tone-text-accent" />}
        title={t('Customer 360', 'Customer 360')}
        subtitle={t(
          'Odvozený pohled ze silver vrstvy analytiky (ADR-0210) — žádná crm-service, žádná druhá databáze. Čísla NEJSOU autoritativní: zůstatky a jednotlivé transakce vlastní příslušné služby (ADR-0089). Zde jsou počty, aktuálnost a stav.',
          'A derived view over the analytics silver layer (ADR-0210) — no crm-service, no second database. Figures are NOT authoritative: balances and individual transactions belong to the owning services (ADR-0089). This shows counts, recency and state.',
        )}
      />

      <div className="card" style={{ marginBottom: '20px' }}>
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
          <input
            value={term}
            onChange={e => setTerm(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') lookup() }}
            placeholder={t('UUID party', 'Party UUID')}
            style={{
              flex: '1 1 340px', padding: '8px 12px', borderRadius: '8px', border: '1px solid var(--border)',
              background: 'var(--surface)', color: 'var(--text-primary)', fontSize: '13px',
              fontFamily: 'var(--font-mono, monospace)',
            }}
          />
          <button
            onClick={lookup}
            disabled={loading || !term.trim()}
            style={{
              display: 'flex', alignItems: 'center', gap: '6px', padding: '8px 16px',
              cursor: loading || !term.trim() ? 'not-allowed' : 'pointer', borderRadius: '8px',
              border: '1px solid var(--border)', background: 'var(--surface)',
              color: 'var(--text-secondary)', fontSize: '13px', fontWeight: 600,
              opacity: loading || !term.trim() ? 0.6 : 1,
            }}
          >
            <Search size={15} /> {t('Vyhledat', 'Look up')}
          </button>
        </div>
      </div>

      {loading && (
        <div style={{ color: 'var(--text-secondary)', padding: '40px', textAlign: 'center' }}>
          {t('Načítám…', 'Loading…')}
        </div>
      )}

      {!loading && failure && (
        <DataUnavailable
          kind={failure}
          service="ClickHouse (analytics)"
          feature={t('Customer 360', 'Customer 360')}
          lang={language === 'cs' ? 'cs' : 'en'}
        />
      )}

      {!loading && data && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '12px', marginBottom: '20px' }}>
            <StatCard label={t('Domén', 'Domains')} value={data.domains.length} />
            <StatCard label={t('Událostí', 'Events')} value={totalEvents} />
            <StatCard label={t('Účtů', 'Accounts')} value={data.accountIds.length} tone="info" />
            <StatCard
              label={t('Souhlasů', 'Consents')}
              value={data.consents.length}
              tone={data.consents.length ? 'success' : undefined}
            />
          </div>

          {data.asOf && (
            <div style={{ marginBottom: '20px' }}>
              {/* Staleness, stated rather than assumed (ADR-0210 D3). */}
              <span className="tag" style={{ fontSize: '11px' }}>
                {t('Stav k', 'As of')}: {fmt(data.asOf)}
              </span>
            </div>
          )}

          <div className="card" style={{ marginBottom: '20px', overflowX: 'auto' }}>
            <h2 className="section-title" style={{ marginBottom: '12px' }}>
              {t('Domény a aktuálnost', 'Domains and recency')}
            </h2>
            <table className="table">
              <thead>
                <tr>
                  <th>{t('Doména', 'Domain')}</th>
                  <th style={{ textAlign: 'right' }}>{t('Událostí', 'Events')}</th>
                  <th>{t('Poslední událost', 'Last event')}</th>
                  <th>{t('Kdy', 'When')}</th>
                </tr>
              </thead>
              <tbody>
                {data.domains.map(d => (
                  <tr key={d.aggregateType}>
                    <td style={{ fontWeight: 600 }}>{d.aggregateType}</td>
                    <td style={{ textAlign: 'right' }}>{d.events}</td>
                    <td><span className="tag" style={{ fontSize: '10px' }}>{d.lastEventType}</span></td>
                    <td>{fmt(d.lastOccurredAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {data.consents.length > 0 && (
            <div className="card" style={{ overflowX: 'auto' }}>
              <h2 className="section-title" style={{ marginBottom: '12px' }}>
                {t('Souhlasy (consent-service zůstává autoritativní)', 'Consents (consent-service stays authoritative)')}
              </h2>
              <table className="table">
                <thead>
                  <tr>
                    <th>{t('Souhlas', 'Consent')}</th>
                    <th>{t('Stav', 'Status')}</th>
                    <th>{t('Rozsahy', 'Scopes')}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.consents.map(c => (
                    <tr key={c.consentId}>
                      <td style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: '11px' }}>{c.consentId}</td>
                      <td><StatusBadge status={c.status} withDot /></td>
                      <td>
                        <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
                          {c.scopes.map(s => (
                            <span key={s} className="tag" style={{ fontSize: '10px' }}>{s}</span>
                          ))}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  )
}
