// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Unit register lookup (ADR-0334, openbank-pension-fund-service): the unit holdings of one
// contract per fund, valued at each fund's latest published NAV, and the orders still waiting for
// a forward price. The operator finds the client by name, then picks one of the client's contracts.

'use client'

import { useState } from 'react'
import Link from 'next/link'
import { Layers } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { fundUrl, getJson, PENSION, PENSION_FUND, pensionUrl } from '@/components/pension/api'
import { contractValuationSchema, fundListSchema, pensionContractListSchema, type ContractValuation, type PensionContract } from '@/components/pension/contracts'
import { FundRef } from '@/components/pension/FundRef'
import { statusLabel } from '@/components/pension/model'
import { PartySearch, partyDisplayName, type PartyHit } from '@/components/party/PartySearch'
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
  const [party, setParty] = useState<PartyHit | null>(null)
  const [contracts, setContracts] = useState<PensionContract[] | null>(null)
  const [contractsKind, setContractsKind] = useState<UnavailableKind | null>(null)
  const [valuation, setValuation] = useState<ContractValuation | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [fundNames, setFundNames] = useState<ReadonlyMap<string, string>>(new Map())

  const lookUp = async (contractId: string) => {
    const [res, funds] = await Promise.all([
      getJson(fundUrl(`/contracts/${encodeURIComponent(contractId)}/holdings`), contractValuationSchema),
      getJson(fundUrl('/funds'), fundListSchema),
    ])
    if (funds.ok) setFundNames(new Map(funds.data.map(f => [f.id, f.name])))
    if (!res.ok) { setUnavailable({ kind: res.kind }); setValuation(null); return }
    setUnavailable(null)
    setValuation(res.data)
  }

  const choose = async (hit: PartyHit) => {
    setParty(hit); setContracts(null); setContractsKind(null); setValuation(null); setUnavailable(null)
    const res = await getJson(pensionUrl('/contracts', { q: hit.id, limit: '100' }), pensionContractListSchema)
    if (!res.ok) { setContractsKind(res.kind); return }
    const own = res.data.filter(c => c.participantPartyId === hit.id)
    // A pasted contract id is not a party id: when nothing belongs to it as a party, read it as a contract.
    if (own.length === 0 && !hit.legalName) { void lookUp(hit.id); return }
    setContracts(own)
  }

  return (
    <div>
      <PageHeader
        title={t('Podílové jednotky', 'Unit holdings')}
        subtitle={t('Evidence podílových jednotek smlouvy oceněná posledním zveřejněným NAV.', 'A contract’s unit register valued at the latest published NAV.')}
        icon={<Layers size={20} aria-hidden="true" />}
      />

      <PartySearch onSelect={hit => void choose(hit)} selectedId={party?.id} />

      {party && (
        <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
          <div style={{ fontSize: 13, marginBottom: 8 }}>{t('Klient', 'Client')}: <strong>{party.legalName || party.tradingName || partyDisplayName(party)}</strong></div>
          {contractsKind ? <DataUnavailable kind={contractsKind} service={PENSION} feature={t('smlouvy klienta', 'client contracts')} lang={language} dense />
            : contracts === null ? null
            : contracts.length === 0 ? <DataUnavailable kind="no_data" service={PENSION} feature={t('smlouvy klienta', 'client contracts')} lang={language} dense />
            : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead><tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Produkt', 'Product')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Počátek', 'Start')}</th>
                  <th scope="col" />
                </tr></thead>
                <tbody>
                  {contracts.slice(0, 25).map(c => (
                    <tr key={c.contractId}>
                      <td>{`${c.productLine} · ${c.jurisdiction}`}</td>
                      <td>{statusLabel(c.status, t)}</td>
                      <td>{c.startDate ?? '—'}</td>
                      <td><button type="button" className="btn btn-secondary btn-sm" onClick={() => void lookUp(c.contractId)}>{t('Zobrazit jednotky', 'Show holdings')}</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
        </div>
      )}

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
