// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury daily position (ADR-0315): per currency, what the desk has placed with banks, borrowed
// from them and holds at the ČNB deposit facility as of a chosen day, and the net of the three.

'use client'

import { useCallback, useEffect, useState } from 'react'
import { RefreshCw, Wallet } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { EmptyState, PageHeader, StatusBadge } from '@/components/ui'
import { getJson, treasuryUrl } from '@/components/treasury/api'
import { positionsSchema, type Positions } from '@/components/treasury/contracts'
import { DATE_SHORTCUTS, addDays, basisOf, isEmptyPosition, longDate } from '@/components/treasury/positions'
import { bankToday, isIsoDate } from '@/components/balance-sheet/model'
import { formatCurrency } from '@/lib/utils'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function TreasuryPositionsPage() {
  return (
    <AuthGuard permission="treasury:view">
      <DailyPosition />
    </AuthGuard>
  )
}

function DailyPosition() {
  const { t, language } = useLanguage()
  const [today] = useState(bankToday())
  const [asOf, setAsOf] = useState(today)
  const [data, setData] = useState<Positions | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const load = useCallback(async () => {
    if (!isIsoDate(asOf)) return
    const res = await getJson(treasuryUrl('/positions', { asOf }), positionsSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setData(null); return }
    setUnavailable(null)
    setData(res.data)
  }, [asOf])

  useEffect(() => { void load() }, [load])

  const basis = data ? basisOf(data, today) : null

  return (
    <div>
      <PageHeader
        title={t('Denní pozice treasury', 'Treasury daily position')}
        subtitle={t('Umístěno, přijato a uloženo u ČNB podle měny k vybranému dni — skutečnost do dneška, projekce do budoucna.', 'Placed, borrowed and held at ČNB per currency as of the chosen day — actual up to today, projected after it.')}
        icon={<Wallet size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />
      <div className="card" style={{ marginBottom: 16, display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'flex-end' }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, maxWidth: 220 }}>
          {t('Ke dni', 'As of')}
          <input type="date" lang={language === 'cs' ? 'cs-CZ' : 'en-GB'} className="input" value={asOf} onChange={e => setAsOf(e.target.value)} aria-label={t('Ke dni', 'As of date')} />
        </label>
        <div role="group" aria-label={t('Rychlá volba dne', 'Quick date choice')} style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {DATE_SHORTCUTS.map(s => {
            const target = addDays(today, s.days)
            return (
              <button key={s.days} type="button" className={`btn btn-sm ${asOf === target ? 'btn-primary' : 'btn-secondary'}`} aria-pressed={asOf === target} onClick={() => setAsOf(target)}>
                {t(s.cs, s.en)}
              </button>
            )
          })}
        </div>
        {isIsoDate(asOf) && (
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }} data-testid="as-of-long">{longDate(asOf, language === 'cs' ? 'cs' : 'en')}</span>
        )}
      </div>
      <div className="card" style={{ overflowX: 'auto' }}>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service="treasury-service" feature={t('denní pozice', 'daily position')} lang={language} dense />
        ) : data === null || basis === null ? null : (
          <>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 10 }}>
              {basis === 'PROJECTED' ? (
                <StatusBadge status="PROJECTED" tone="warning" label={t('Projekce', 'Projection')} withDot />
              ) : (
                <StatusBadge status="ACTUAL" tone="success" label={t('Skutečnost', 'Actual')} withDot />
              )}
              <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                {basis === 'PROJECTED'
                  ? t('Projekce — jen sjednané obchody, bez nových: vypořádané i rezervované a potvrzené obchody podle smluvních dat valuty a splatnosti.', 'Projection — concluded deals only, no new ones: settled plus booked and confirmed deals on their contracted value and maturity dates.')
                  : t('Skutečnost — jen vypořádané obchody, které ten den byly otevřené (valuta ≤ den < splatnost).', 'Actual — settled deals only, outstanding that day (value date ≤ day < maturity).')}
              </span>
            </div>
            {data.positions.length === 0 || isEmptyPosition(data) ? (
              <EmptyState
                title={t('K tomuto dni nejsou otevřené žádné obchody', 'No deals are outstanding on this day')}
                description={t(`Pro ${data.asOf} je pozice ve všech měnách nulová.`, `On ${data.asOf} the position is zero in every currency.`)}
              />
            ) : (
              <table
                data-basis={basis}
                style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, fontStyle: basis === 'PROJECTED' ? 'italic' : undefined }}
              >
                <caption style={{ textAlign: 'left', fontSize: 12, color: 'var(--text-secondary)', marginBottom: 6 }}>
                  {basis === 'PROJECTED'
                    ? t(`Projekce ke dni ${data.asOf}`, `Projection as of ${data.asOf}`)
                    : t(`Stav ke dni ${data.asOf}`, `Position as of ${data.asOf}`)}
                </caption>
                <thead>
                  <tr>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Umístěno', 'Placed')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Přijato', 'Borrowed')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('U ČNB', 'At ČNB')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Netto', 'Net')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Obchodů', 'Deals')}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.positions.map(p => (
                    <tr key={p.currency}>
                      <th scope="row" style={{ textAlign: 'left', fontWeight: 600 }}>{p.currency}</th>
                      <td style={{ textAlign: 'right' }}>{formatCurrency(p.placed, p.currency)}</td>
                      <td style={{ textAlign: 'right' }}>{formatCurrency(p.borrowed, p.currency)}</td>
                      <td style={{ textAlign: 'right' }}>{formatCurrency(p.atCnb, p.currency)}</td>
                      <td style={{ textAlign: 'right', fontWeight: 600, color: p.net < 0 ? 'var(--danger-text)' : undefined }}>{formatCurrency(p.net, p.currency)}</td>
                      <td style={{ textAlign: 'right' }}>{p.dealCount ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </div>
    </div>
  )
}
