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
import { PageHeader } from '@/components/ui'
import { getJson, treasuryUrl } from '@/components/treasury/api'
import { positionsSchema, type Positions } from '@/components/treasury/contracts'
import { bankToday, isIsoDate } from '@/components/balance-sheet/model'
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
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const [asOf, setAsOf] = useState(bankToday())
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

  return (
    <div>
      <PageHeader
        title={t('Denní pozice treasury', 'Treasury daily position')}
        subtitle={t('Umístěno, přijato a uloženo u ČNB podle měny k vybranému dni.', 'Placed, borrowed and held at ČNB per currency as of the chosen day.')}
        icon={<Wallet size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />
      <div className="card" style={{ marginBottom: 16 }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, maxWidth: 220 }}>
          {t('Ke dni', 'As of')}
          <input type="date" className="input" value={asOf} onChange={e => setAsOf(e.target.value)} aria-label={t('Ke dni', 'As of date')} />
        </label>
      </div>
      <div className="card" style={{ overflowX: 'auto' }}>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service="treasury-service" feature={t('denní pozice', 'daily position')} lang={language} dense />
        ) : data === null ? null : data.positions.length === 0 ? (
          <DataUnavailable kind="no_data" service="treasury-service" feature={t('denní pozice', 'daily position')} lang={language} dense />
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <caption style={{ textAlign: 'left', fontSize: 12, color: 'var(--text-secondary)', marginBottom: 6 }}>
              {t(`Stav ke dni ${data.asOf}`, `Position as of ${data.asOf}`)}
            </caption>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Umístěno', 'Placed')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Přijato', 'Borrowed')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('U ČNB', 'At ČNB')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Netto', 'Net')}</th>
              </tr>
            </thead>
            <tbody>
              {data.positions.map(p => (
                <tr key={p.currency}>
                  <td>{p.currency}</td>
                  <td style={{ textAlign: 'right' }}>{money(p.placed)}</td>
                  <td style={{ textAlign: 'right' }}>{money(p.borrowed)}</td>
                  <td style={{ textAlign: 'right' }}>{money(p.atCnb)}</td>
                  <td style={{ textAlign: 'right', fontWeight: 600, color: p.net < 0 ? 'var(--danger-text)' : undefined }}>{money(p.net)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
