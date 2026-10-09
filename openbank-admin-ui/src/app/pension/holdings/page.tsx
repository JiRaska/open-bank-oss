// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Unit register lookup (ADR-0334, openbank-pension-fund-service): the unit holdings of one
// contract per fund, valued at each fund's latest published NAV, and the orders still waiting for
// a forward price. The contract id is validated as a UUID before any call (search rule #2).

'use client'

import { useState, type FormEvent } from 'react'
import Link from 'next/link'
import { Layers } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { fundUrl, getJson, PENSION_FUND } from '@/components/pension/api'
import { contractValuationSchema, fundListSchema, type ContractValuation } from '@/components/pension/contracts'
import { FundRef } from '@/components/pension/FundRef'
import { isUuid } from '@/components/pension/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionHoldingsPage() {
  return (
    <AuthGuard permission="pension:view">
      <Holdings />
    </AuthGuard>
  )
}

function Holdings() {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const num = (v: number, digits = 2) => v.toLocaleString(locale, { minimumFractionDigits: digits, maximumFractionDigits: Math.max(digits, 6) })
  const [query, setQuery] = useState('')
  const [hint, setHint] = useState<string | null>(null)
  const [valuation, setValuation] = useState<ContractValuation | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [fundNames, setFundNames] = useState<ReadonlyMap<string, string>>(new Map())

  const lookUp = async (e: FormEvent) => {
    e.preventDefault()
    if (!isUuid(query)) { setHint(t('Zadejte ID smlouvy ve formátu UUID.', 'Enter the contract id as a UUID.')); return }
    setHint(null)
    const [res, funds] = await Promise.all([
      getJson(fundUrl(`/contracts/${encodeURIComponent(query.trim())}/holdings`), contractValuationSchema),
      getJson(fundUrl('/funds'), fundListSchema),
    ])
    if (funds.ok) setFundNames(new Map(funds.data.map(f => [f.id, f.name])))
    if (!res.ok) { setUnavailable({ kind: res.kind }); setValuation(null); return }
    setUnavailable(null)
    setValuation(res.data)
  }

  return (
    <div>
      <PageHeader
        title={t('Podílové jednotky', 'Unit holdings')}
        subtitle={t('Evidence podílových jednotek smlouvy oceněná posledním zveřejněným NAV.', 'A contract’s unit register valued at the latest published NAV.')}
        icon={<Layers size={20} aria-hidden="true" />}
      />

      <form className="card" onSubmit={lookUp} style={{ marginBottom: 16, display: 'flex', gap: 8, alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 320px' }}>
          <input className="input" value={query} onChange={e => setQuery(e.target.value)} placeholder={t('ID smlouvy (UUID)', 'Contract id (UUID)')} aria-label={t('ID smlouvy', 'Contract id')} style={{ width: '100%' }} />
          {hint && <div role="alert" style={{ fontSize: 12, color: 'var(--danger-text)', marginTop: 4 }}>{hint}</div>}
        </div>
        <button type="submit" className="btn btn-primary btn-sm">{t('Vyhledat', 'Look up')}</button>
      </form>

      <div className="card" style={{ overflowX: 'auto' }}>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service={PENSION_FUND} feature={t('podílové jednotky', 'unit holdings')} lang={language} dense />
        ) : valuation === null ? null : valuation.holdings.length === 0 ? (
          <DataUnavailable kind="no_data" service={PENSION_FUND} feature={t('podílové jednotky', 'unit holdings')} lang={language} dense />
        ) : (
          <>
            <p style={{ fontSize: 13, marginTop: 0 }}>
              <Link href={`/pension/contracts/${encodeURIComponent(valuation.contractId)}`}>{t('Otevřít smlouvu', 'Open contract')}</Link>
              {valuation.pendingOrders.length > 0 && ` · ${t(`${valuation.pendingOrders.length} pokynů čeká na NAV`, `${valuation.pendingOrders.length} orders awaiting NAV`)}`}
            </p>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Fond', 'Fund')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Jednotky', 'Units')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('NAV / jednotku', 'NAV / unit')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Datum NAV', 'NAV date')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Hodnota', 'Value')}</th>
                </tr>
              </thead>
              <tbody>
                {valuation.holdings.map(h => (
                  <tr key={h.fundId}>
                    <td><FundRef id={h.fundId} names={fundNames} /></td>
                    <td style={{ textAlign: 'right' }}>{num(h.units, 4)}</td>
                    <td style={{ textAlign: 'right' }}>{h.navPerUnit === null ? t('bez NAV', 'no NAV') : num(h.navPerUnit, 6)}</td>
                    <td>{h.navDate ?? '—'}</td>
                    <td style={{ textAlign: 'right' }}>{h.value === null ? '—' : `${num(h.value)} ${h.currency}`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </div>
    </div>
  )
}
