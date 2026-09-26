// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury limit utilisation (ADR-0315 D4, #10896): a read-only view of the SAME limit-check the
// server enforces at booking — limit, utilised principal, available headroom, utilisation % and a
// breached flag, plus how many senior overrides are currently in force per counterparty/currency.
// Server-computed only: this page never derives utilised/breached itself (that duplication is
// exactly what treasury-service's shared domain rule exists to prevent, see
// Deal.LIMIT_CONSUMING_STATES).

'use client'

import { useCallback, useEffect, useState } from 'react'
import { Gauge, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { getJson, treasuryUrl } from '@/components/treasury/api'
import { limitUtilisationSchema, type LimitUtilisationEntry } from '@/components/treasury/contracts'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function TreasuryLimitUtilisationPage() {
  return (
    <AuthGuard permission="treasury:view">
      <LimitUtilisation />
    </AuthGuard>
  )
}

function LimitUtilisation() {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const [rows, setRows] = useState<LimitUtilisationEntry[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const load = useCallback(async () => {
    const res = await getJson(treasuryUrl('/limits/utilisation'), limitUtilisationSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setRows(null); return }
    setUnavailable(null)
    setRows(res.data.limits)
  }, [])

  useEffect(() => { void load() }, [load])

  return (
    <div>
      <PageHeader
        title={t('Čerpání limitů', 'Limit utilisation')}
        subtitle={t(
          'Čerpání úvěrového limitu podle protistrany a měny — stejné pravidlo jako kontrola při zaúčtování obchodu.',
          'Credit-limit utilisation per counterparty and currency — the exact same rule the booking-time check uses.',
        )}
        icon={<Gauge size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />
      <div className="card" style={{ overflowX: 'auto' }}>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service="treasury-service" feature={t('čerpání limitů', 'limit utilisation')} lang={language} dense />
        ) : rows === null ? null : rows.length === 0 ? (
          <DataUnavailable kind="no_data" service="treasury-service" feature={t('čerpání limitů', 'limit utilisation')} lang={language} dense />
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Protistrana', 'Counterparty')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Limit', 'Limit')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Čerpáno', 'Utilised')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Volno', 'Available')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Čerpání', 'Utilisation')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Aktivní výjimky', 'Active overrides')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(r => {
                const pct = Math.round(r.utilisationPercent)
                return (
                  <tr key={`${r.counterpartyId}-${r.currency}`}>
                    <td>{`${r.name} (${r.counterpartyId})`} <SyntheticBadge synthetic={r.synthetic} /></td>
                    <td>{r.currency}</td>
                    <td style={{ textAlign: 'right' }}>{money(r.limit)}</td>
                    <td style={{ textAlign: 'right' }}>{money(r.utilised)}</td>
                    <td style={{ textAlign: 'right', color: r.available < 0 ? 'var(--danger-text)' : undefined }}>{money(r.available)}</td>
                    <td style={{ minWidth: 120 }}>
                      <div
                        role="meter"
                        aria-valuemin={0}
                        aria-valuemax={100}
                        aria-valuenow={Math.min(100, Math.max(0, pct))}
                        aria-label={t(`Čerpání limitu ${r.name} ${r.currency}`, `Limit utilisation ${r.name} ${r.currency}`)}
                        style={{ height: 8, background: 'var(--bg-tertiary, #e5e7eb)', borderRadius: 4, overflow: 'hidden' }}
                      >
                        <div style={{ width: `${Math.min(100, Math.max(0, pct))}%`, height: '100%', background: r.breached ? 'var(--danger)' : pct >= 75 ? 'var(--warning)' : 'var(--success)' }} />
                      </div>
                      <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{`${pct} %`}</span>
                    </td>
                    <td>
                      {r.breached ? (
                        <StatusBadge status="BREACHED" tone="danger" label={t('Překročeno', 'Breached')} />
                      ) : (
                        <StatusBadge status="WITHIN_LIMIT" tone="success" label={t('V limitu', 'Within limit')} />
                      )}
                    </td>
                    <td style={{ textAlign: 'right' }}>{r.activeOverrides}</td>
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
