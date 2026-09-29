// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury counterparty limits (ADR-0315): per counterparty and currency, the credit limit, the
// exposure open against it (asset deals pending, booked or settled) and the headroom left. The
// sandbox's interbank counterparties are simulated and labelled so.

'use client'

import { useCallback, useEffect, useState } from 'react'
import { Handshake, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { getJson, treasuryUrl } from '@/components/treasury/api'
import { counterpartyListSchema, type Counterparty } from '@/components/treasury/contracts'
import { utilisation } from '@/components/treasury/model'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function TreasuryCounterpartiesPage() {
  return (
    <AuthGuard permission="treasury:view">
      <CounterpartyLimits />
    </AuthGuard>
  )
}

function CounterpartyLimits() {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const [rows, setRows] = useState<Counterparty[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const load = useCallback(async () => {
    const res = await getJson(treasuryUrl('/counterparties'), counterpartyListSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setRows(null); return }
    setUnavailable(null)
    setRows(res.data)
  }, [])

  useEffect(() => { void load() }, [load])

  return (
    <div>
      <PageHeader
        title={t('Limity protistran', 'Counterparty limits')}
        subtitle={t('Úvěrový limit, expozice a volný limit podle protistrany a měny.', 'Credit limit, exposure and headroom per counterparty and currency.')}
        icon={<Handshake size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />
      <div className="card" style={{ overflowX: 'auto' }}>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service="treasury-service" feature={t('limity protistran', 'counterparty limits')} lang={language} dense />
        ) : rows === null ? null : rows.length === 0 ? (
          <DataUnavailable kind="no_data" service="treasury-service" feature={t('limity protistran', 'counterparty limits')} lang={language} dense />
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Protistrana', 'Counterparty')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Druh', 'Kind')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Limit', 'Limit')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Expozice', 'Exposure')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Volný limit', 'Headroom')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Čerpání', 'Utilisation')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(r => {
                const u = utilisation(r)
                const pct = Math.round(u * 100)
                return (
                  <tr key={`${r.counterpartyId}-${r.currency}`}>
                    <td>{`${r.name} (${r.counterpartyId})`} <SyntheticBadge synthetic={r.synthetic} /></td>
                    <td>
                      <StatusBadge status={r.kind} tone="neutral" label={r.kind === 'CENTRAL_BANK' ? t('Centrální banka', 'Central bank') : t('Banka', 'Bank')} />
                    </td>
                    <td>{r.currency}</td>
                    <td style={{ textAlign: 'right' }}>{money(r.limit)}</td>
                    <td style={{ textAlign: 'right' }}>{money(r.exposure)}</td>
                    <td style={{ textAlign: 'right', color: r.headroom < 0 ? 'var(--danger-text)' : undefined }}>{money(r.headroom)}</td>
                    <td style={{ minWidth: 120 }}>
                      <div
                        role="meter"
                        aria-valuemin={0}
                        aria-valuemax={100}
                        aria-valuenow={pct}
                        aria-label={t(`Čerpání limitu ${r.name} ${r.currency}`, `Limit utilisation ${r.name} ${r.currency}`)}
                        style={{ height: 8, background: 'var(--bg-tertiary, #e5e7eb)', borderRadius: 4, overflow: 'hidden' }}
                      >
                        <div style={{ width: `${pct}%`, height: '100%', background: u >= 0.9 ? 'var(--danger)' : u >= 0.75 ? 'var(--warning)' : 'var(--success)' }} />
                      </div>
                      <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{`${pct} %`}</span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
