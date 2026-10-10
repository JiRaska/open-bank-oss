// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One pension contract (ADR-0334): the contract and its pinned jurisdiction-pack version, the
// lifecycle timeline as the contract records it, unit holdings at the latest published NAV and the
// priced unit transactions (pension-fund-service). Read-only: a contract is activated only by its
// signed onboarding application once the cooling-off period ends and the first contribution arrives
// (pension-service API 1.1.0 retired the operator activation route), so no button can skip that.

'use client'

import { use, useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, PiggyBank } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { fundUrl, getJson, PENSION, PENSION_FUND, pensionUrl } from '@/components/pension/api'
import {
  contractValuationSchema, fundListSchema, pensionContractSchema, unitTransactionListSchema,
  type ContractValuation, type PensionContract, type UnitTransaction,
} from '@/components/pension/contracts'
import { contractTimeline, isUuid, statusLabel } from '@/components/pension/model'
import { ContractChanges } from '@/components/pension/ContractChanges'
import { FundRef } from '@/components/pension/FundRef'
import { PAGE_SIZE } from '@/components/pension/PensionQueue'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionContractDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="pension:view">
      <ContractDetail id={id} />
    </AuthGuard>
  )
}

function ContractDetail({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const num = (v: number, digits = 2) => v.toLocaleString(locale, { minimumFractionDigits: digits, maximumFractionDigits: Math.max(digits, 6) })

  const [contract, setContract] = useState<PensionContract | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [valuation, setValuation] = useState<ContractValuation | null>(null)
  const [valuationKind, setValuationKind] = useState<UnavailableKind | null>(null)
  const [transactions, setTransactions] = useState<UnitTransaction[]>([])
  const [fundNames, setFundNames] = useState<ReadonlyMap<string, string>>(new Map())
  const [shown, setShown] = useState(PAGE_SIZE)

  const valid = isUuid(id)

  const load = useCallback(async () => {
    if (!valid) return
    const res = await getJson(pensionUrl(`/contracts/${encodeURIComponent(id)}`), pensionContractSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setContract(null); return }
    setUnavailable(null)
    setContract(res.data)
    const [holdings, txs, funds] = await Promise.all([
      getJson(fundUrl(`/contracts/${encodeURIComponent(id)}/holdings`), contractValuationSchema),
      getJson(fundUrl(`/contracts/${encodeURIComponent(id)}/transactions`), unitTransactionListSchema),
      getJson(fundUrl('/funds'), fundListSchema),
    ])
    if (funds.ok) setFundNames(new Map(funds.data.map(f => [f.id, f.name])))
    if (holdings.ok) { setValuation(holdings.data); setValuationKind(null) } else { setValuation(null); setValuationKind(holdings.kind) }
    setTransactions(txs.ok ? txs.data : [])
  }, [id, valid])

  useEffect(() => { void load() }, [load])

  const totals = useMemo(() => {
    const sums = new Map<string, number>()
    for (const h of valuation?.holdings ?? []) if (h.value !== null) sums.set(h.currency, (sums.get(h.currency) ?? 0) + h.value)
    return [...sums.entries()]
  }, [valuation])

  if (!valid) {
    return <DataUnavailable kind="not_found" service={PENSION} feature={t('penzijní smlouva', 'pension contract')} lang={language} />
  }

  return (
    <div>
      <PageHeader
        title={t('Penzijní smlouva', 'Pension contract')}
        subtitle={id}
        icon={<PiggyBank size={20} aria-hidden="true" />}
        actions={<Link href="/pension" className="btn btn-secondary btn-sm"><ArrowLeft size={14} aria-hidden="true" /> {t('Zpět', 'Back')}</Link>}
      />

      {unavailable ? (
        <DataUnavailable kind={unavailable.kind} service={PENSION} feature={t('penzijní smlouva', 'pension contract')} lang={language} />
      ) : contract === null ? null : (
        <>
          <section className="card" style={{ marginBottom: 16 }}>
            <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '4px 16px', fontSize: 13, margin: 0 }}>
              <dt>{t('Stav', 'Status')}</dt><dd>{statusLabel(contract.status, t)}</dd>
              <dt>{t('Produkt', 'Product')}</dt><dd>{`${contract.productLine} · ${contract.jurisdiction}`}</dd>
              <dt>{t('Verze pravidel', 'Pack version')}</dt><dd>{contract.packVersion ?? '—'}</dd>
              <dt>{t('Účastník', 'Participant')}</dt><dd>{contract.participantPartyId ?? '—'}</dd>
              <dt>{t('Poskytovatel', 'Provider')}</dt><dd>{`${contract.providerType ?? '—'} · ${contract.providerEntityId ?? '—'}`}</dd>
              <dt>{t('Příspěvek', 'Contribution')}</dt>
              <dd>{contract.schedule ? `${num(contract.schedule.amount)} ${contract.schedule.currency} · ${contract.schedule.frequency}` : '—'}</dd>
              <dt>{t('Strategie', 'Strategy')}</dt><dd>{contract.currentStrategy?.strategyCode ?? '—'}</dd>
              <dt>{t('Obmyšlení', 'Beneficiaries')}</dt>
              <dd>{contract.beneficiaries.length ? contract.beneficiaries.map(b => `${b.name} ${b.sharePercent} %`).join(', ') : '—'}</dd>
            </dl>
            {contract.status === 'PENDING_ACTIVATION' && (
              <p style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
                {t('Smlouva se aktivuje sama po uplynutí lhůty na rozmyšlenou a připsání prvního příspěvku (fronta žádostí).', 'The contract activates itself once the cooling-off period has ended and the first contribution has arrived (applications queue).')}
              </p>
            )}
          </section>

          <section className="card" style={{ marginBottom: 16 }}>
            <h2 style={{ fontSize: 15, marginTop: 0 }}>{t('Průběh smlouvy', 'Lifecycle timeline')}</h2>
            <ol style={{ margin: 0, paddingLeft: 18, fontSize: 13 }}>
              {contractTimeline(contract, t).map(e => <li key={`${e.at}-${e.label}`}><code>{e.at}</code> — {e.label}</li>)}
            </ol>
          </section>

          <ContractChanges contractId={id} />

          <section className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
            <h2 style={{ fontSize: 15, marginTop: 0 }}>{t('Podílové jednotky', 'Unit holdings')}</h2>
            {valuationKind ? (
              <DataUnavailable kind={valuationKind} service={PENSION_FUND} feature={t('podílové jednotky', 'unit holdings')} lang={language} dense />
            ) : valuation === null ? null : valuation.holdings.length === 0 ? (
              <DataUnavailable kind="no_data" service={PENSION_FUND} feature={t('podílové jednotky', 'unit holdings')} lang={language} dense />
            ) : (
              <>
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
                        <td style={{ textAlign: 'right' }}>{h.navPerUnit === null ? t('bez NAV', 'no NAV') : num(h.navPerUnit, 4)}</td>
                        <td>{h.navDate ?? '—'}</td>
                        <td style={{ textAlign: 'right' }}>{h.value === null ? '—' : `${num(h.value)} ${h.currency}`}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p style={{ fontSize: 13, marginBottom: 0 }}>
                  {t('Celkem', 'Total')}: {totals.map(([ccy, v]) => `${num(v)} ${ccy}`).join(', ') || '—'}
                  {valuation.pendingOrders.length > 0 && ` · ${t(`${valuation.pendingOrders.length} pokynů čeká na NAV`, `${valuation.pendingOrders.length} orders awaiting NAV`)}`}
                </p>
              </>
            )}
          </section>

          <section className="card" style={{ overflowX: 'auto' }}>
            <h2 style={{ fontSize: 15, marginTop: 0 }}>{t('Transakce s jednotkami', 'Unit transactions')}</h2>
            {transactions.length === 0 ? (
              <DataUnavailable kind="no_data" service={PENSION_FUND} feature={t('transakce s jednotkami', 'unit transactions')} lang={language} dense />
            ) : (
              <>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead>
                    <tr>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Oceněno', 'Priced at')}</th>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Typ', 'Type')}</th>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Fond', 'Fund')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('Jednotky', 'Units')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('Částka', 'Amount')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('NAV', 'NAV')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {transactions.slice(0, shown).map(tx => (
                      <tr key={tx.id}>
                        <td>{tx.pricedAt}</td>
                        <td>{tx.type}</td>
                        <td><FundRef id={tx.fundId} names={fundNames} /></td>
                        <td style={{ textAlign: 'right' }}>{num(tx.units, 4)}</td>
                        <td style={{ textAlign: 'right' }}>{num(tx.amount)}</td>
                        <td style={{ textAlign: 'right' }}>{num(tx.navPerUnit, 4)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {transactions.length > shown && (
                  <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 12 }} onClick={() => setShown(s => s + PAGE_SIZE)}>
                    {t('Načíst další', 'Load more')}
                  </button>
                )}
              </>
            )}
          </section>
        </>
      )}
    </div>
  )
}
