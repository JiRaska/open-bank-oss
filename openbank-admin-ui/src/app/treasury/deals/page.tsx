// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury deal blotter (openbank-treasury-service, ADR-0315; console #10618). Every money-market
// deal the desk has drafted, filterable by lifecycle state. Reads go through the BFF with the
// signed-in person's own token.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { FilePlus, Landmark, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { getJson, treasuryUrl } from '@/components/treasury/api'
import {
  counterpartyListSchema, DEAL_STATES, dealListSchema,
  type Counterparty, type Deal, type DealState,
} from '@/components/treasury/contracts'
import { distinctCounterparties, productLabel, STATE_TONE, stateLabel } from '@/components/treasury/model'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const PAGE_SIZE = 25

export default function TreasuryDealsPage() {
  return (
    <AuthGuard permission="treasury:view">
      <DealBlotter />
    </AuthGuard>
  )
}

function DealBlotter() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const canCreate = hasPermission(roles, 'treasury:deal:create')
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  const [stateFilter, setStateFilter] = useState<DealState | ''>('')
  const [deals, setDeals] = useState<Deal[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [counterparties, setCounterparties] = useState<Counterparty[]>([])
  const [shown, setShown] = useState(PAGE_SIZE)

  const load = useCallback(async () => {
    const [res, cps] = await Promise.all([
      getJson(treasuryUrl('/deals', stateFilter ? { state: stateFilter } : undefined), dealListSchema),
      getJson(treasuryUrl('/counterparties'), counterpartyListSchema),
    ])
    if (cps.ok) setCounterparties(distinctCounterparties(cps.data))
    if (!res.ok) { setUnavailable({ kind: res.kind }); setDeals(null); return }
    setUnavailable(null)
    setDeals(res.data)
    setShown(PAGE_SIZE)
  }, [stateFilter])

  useEffect(() => { void load() }, [load])

  const byId = useMemo(() => new Map(counterparties.map(c => [c.counterpartyId, c])), [counterparties])

  return (
    <div>
      <PageHeader
        title={t('Obchody treasury', 'Treasury deals')}
        subtitle={t('Obchody peněžního trhu a depozitní facilita ČNB — pravidlo čtyř očí (ADR-0315).', 'Money-market deals and the ČNB deposit facility — four-eyes booking (ADR-0315).')}
        icon={<Landmark size={20} aria-hidden="true" />}
        actions={
          <div style={{ display: 'flex', gap: 8 }}>
            {canCreate && (
              <Link href="/treasury/deals/new" className="btn btn-primary btn-sm">
                <FilePlus size={14} aria-hidden="true" /> {t('Nový obchod', 'New deal')}
              </Link>
            )}
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
              <RefreshCw size={14} aria-hidden="true" />
            </button>
          </div>
        }
      />

      <div className="card" style={{ marginBottom: 16, display: 'flex', gap: 12, alignItems: 'end', flexWrap: 'wrap' }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
          {t('Stav', 'State')}
          <select className="input" value={stateFilter} onChange={e => setStateFilter(e.target.value as DealState | '')} aria-label={t('Filtr podle stavu', 'Filter by state')}>
            <option value="">{t('Všechny stavy', 'All states')}</option>
            {DEAL_STATES.map(s => <option key={s} value={s}>{stateLabel(s, t)}</option>)}
          </select>
        </label>
      </div>

      <div className="card" style={{ overflowX: 'auto' }}>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service="treasury-service" feature={t('obchody treasury', 'treasury deals')} lang={language} dense />
        ) : deals === null ? null : deals.length === 0 ? (
          <DataUnavailable kind="no_data" service="treasury-service" feature={t('obchody treasury', 'treasury deals')} lang={language} dense />
        ) : (
          <>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Produkt', 'Product')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Protistrana', 'Counterparty')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Ccy')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Jistina', 'Principal')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Sazba % p.a.', 'Rate % p.a.')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Valuta / splatnost', 'Value / maturity')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'State')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Vytvořil', 'Created by')}</th>
                </tr>
              </thead>
              <tbody>
                {deals.slice(0, shown).map(d => {
                  const cp = byId.get(d.counterpartyId)
                  return (
                    <tr key={d.dealId}>
                      <td><Link href={`/treasury/deals/${encodeURIComponent(d.dealId)}`}>{productLabel(d.product, t)}</Link></td>
                      <td>
                        {cp ? cp.name : d.counterpartyId} <SyntheticBadge synthetic={cp?.synthetic} />
                      </td>
                      <td>{d.currency}</td>
                      <td style={{ textAlign: 'right' }}>{money(d.principal)}</td>
                      <td style={{ textAlign: 'right' }}>{d.rate.toLocaleString(locale, { maximumFractionDigits: 4 })}</td>
                      <td>{`${d.valueDate} → ${d.maturityDate}`}</td>
                      <td><StatusBadge status={d.state} tone={STATE_TONE[d.state]} label={stateLabel(d.state, t)} /></td>
                      <td>{d.createdBy}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {deals.length > shown && (
              <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 12 }} onClick={() => setShown(s => s + PAGE_SIZE)}>
                {t('Načíst další', 'Load more')}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  )
}
