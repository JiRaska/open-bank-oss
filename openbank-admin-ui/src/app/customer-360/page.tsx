// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useRef, useState } from 'react'
import { Users } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import type { Customer360 } from '@/app/api/customer-360/[partyId]/route'
import { PartySearch, partyDisplayName, type PartyHit } from '@/components/party/PartySearch'
import { AdverseStatePanel } from '@/components/party/AdverseStatePanel'
import { DevicesPanel } from '@/components/party/DevicesPanel'
import { DocumentsPanel } from '@/components/party/DocumentsPanel'
import { CustomerPortfolioPanel } from '@/components/party/CustomerPortfolioPanel'
import { ExplorerGuide } from '@/components/brand/ExplorerGuide'

// ADR-0210: a lookup over the analytics silver layer, not a customer list. There is no
// crm-service and no "list all customers" surface here — party-service owns that.
//
// The lookup is keyed by party id because the silver layer is keyed by aggregate id. That key is NOT
// the search box: PartySearch resolves a name to an id against party-service (ADR-0055), because an
// operator has a name in hand, not a UUID. The first version exposed the raw id and was unusable
// without a second tab open on the Parties page — the data model had been allowed to dictate the UX.
//
// Everything shown is DERIVED from an event projection and is deliberately non-authoritative
// (ADR-0210 D3 / ADR-0089): counts, recency and lifecycle state, never balances or transaction
// rows. The `asOf` chip is not decoration — it is how an operator sees the view's staleness
// instead of assuming it is live.
export default function Customer360Page() {
  const { t, language } = useLanguage()
  const [selected, setSelected] = useState<PartyHit | null>(null)
  const [data, setData] = useState<Customer360 | null>(null)
  const [failure, setFailure] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(false)

  // Only the newest selection may commit. Today the Select buttons are disabled while a load is in
  // flight, which happens to serialise clicks — but that is a UI accident, not a guarantee, and the
  // failure it would hide is the worst one this page has: another party's data under this party's
  // name. Guard it at the source instead of relying on the button state.
  const generation = useRef(0)

  const load360 = async (party: PartyHit) => {
    const gen = ++generation.current
    setSelected(party)
    setLoading(true)
    setFailure(null)
    setData(null)
    try {
      const res = await fetch(`/api/customer-360/${encodeURIComponent(party.id)}`, { cache: 'no-store' })
      const body = (await res.json()) as Customer360
      if (gen !== generation.current) return // a newer selection won; this answer is about someone else
      if (res.status === 400) {
        setFailure('error')
        return
      }
      // `available: false` means one thing only: ClickHouse did not answer. A party that simply has
      // no projected events comes back available with empty domains, and is rendered as such below.
      if (!body.available) {
        setFailure(body.error === 'unauthorized' ? 'unauthorized' : 'unreachable')
        return
      }
      setData(body)
    } catch {
      if (gen === generation.current) setFailure('unreachable')
    } finally {
      if (gen === generation.current) setLoading(false)
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

      {!selected && (
        <ExplorerGuide compact title={t('Začněte člověkem, ne UUID', 'Start with a person, not a UUID')}>
          {t(
            'Hledejte přirozeně podle jména nebo e-mailu a vyberte správnou party. Explorer pak poskládá odvozený pohled napříč doménami — autoritativní detail vždy zůstává ve zdrojové službě.',
            'Search naturally by name or email, then select the right party. Explorer will assemble the derived cross-domain view — authoritative detail always remains in the source service.',
          )}
        </ExplorerGuide>
      )}

      <PartySearch onSelect={load360} selectedId={selected?.id} busy={loading} />

      {/* Issue #4265. Deliberately OUTSIDE every `data`/`loading`/`failure` branch below: this panel
          reads engagement-service, not ClickHouse, so a silver layer that is down or a party with no
          projected events must not hide an active fraud hold. Those are independent sources and the
          page now degrades independently for each. */}
      {selected && <AdverseStatePanel key={`adverse:${selected.id}`} partyId={selected.id} />}
      {selected && <CustomerPortfolioPanel key={`portfolio:${selected.id}`} partyId={selected.id} />}
      {selected && <DevicesPanel key={`devices:${selected.id}`} partyId={selected.id} />}
      {selected && <DocumentsPanel key={`documents:${selected.id}`} partyId={selected.id} />}

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

      {/* A party that exists but has no projected events. Stated as exactly that — the source is up,
          this party simply has no analytics history yet. The earlier copy said the source held no
          records at all, which an operator cannot distinguish from a broken page. */}
      {!loading && data && data.domains.length === 0 && (
        <div className="card" style={{ padding: '32px', textAlign: 'center' }}>
          <p style={{ margin: '0 0 6px', fontWeight: 600, color: 'var(--text-primary)' }}>
            {t('Tato party nemá žádné analytické události', 'This party has no analytics events')}
          </p>
          <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-secondary)' }}>
            {t(
              'Silver vrstva je dostupná a odpověděla — pro tuto party v ní zatím není žádná událost. Nejde o chybu stránky ani o prázdný zdroj.',
              'The silver layer is available and answered — it holds no event for this party yet. This is not a page error, nor an empty source.',
            )}
          </p>
          {selected && (
            <p style={{ margin: '10px 0 0', fontSize: '11px', fontFamily: 'var(--font-mono, monospace)', color: 'var(--text-tertiary)' }}>
              {partyDisplayName(selected)} · {selected.id}
            </p>
          )}
        </div>
      )}

      {!loading && data && data.domains.length > 0 && (
        <>
          {selected && (
            <h2 className="section-title" style={{ marginBottom: '12px' }}>
              {partyDisplayName(selected)}
            </h2>
          )}

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

          <div className="card" style={{ marginBottom: '20px', overflowX: 'auto', padding: '20px' }}>
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
            <div className="card" style={{ overflowX: 'auto', padding: '20px' }}>
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
