// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 (hybrid console) + ADR-0232 (delegated access): "grants by party".
//
// Party lookup reuses the ADR-0228 entity-resolution facade (/api/entities/resolve) rather than
// asking the operator for a UUID — ADR-0231 D3 bans raw-UUID navigation, and the facade already
// resolves parties by business key through party-service's /api/v1/parties/search. This screen
// therefore never needs its own search endpoint; it consumes the shared one and renders results
// as entity chips, so a party found here deep-links to the same /parties/{id} page as everywhere
// else.
//
// Read-only by construction. delegation-service's bank-side mutations (suspend / reinstate /
// revoke) are NOT reachable from this console — there is no maker-checker store for them to land
// in, and ADR-0230 forbids a direct write. The grant detail page states that in place rather
// than rendering a button that cannot work.

'use client'

import { useCallback, useState } from 'react'
import Link from 'next/link'
import { Search, Share2, RefreshCw, Activity } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { EntityChip } from '@/components/entities/EntityChip'
import { PageHeader } from '@/components/ui/PageHeader'
import { RoleCatalog } from '@/components/delegations/RoleCatalog'
import { EffectiveAccess, type EffectiveAccessPayload } from '@/components/delegations/EffectiveAccess'
import {
  DelegationStatusBadge,
  capabilityLabels,
  counterpartyLabel,
  formatCeiling,
  grantCounterparty,
  type Grant,
} from '@/components/delegations/GrantView'

type EntityRef = { type: string; id: string; label: string; sublabel?: string }

type DirectionState = 'ok' | 'forbidden' | 'unavailable'

type GrantsPayload = {
  granted: Grant[]
  received: Grant[]
  sources: { granted: DirectionState; received: DirectionState }
}

type ProjectionConsumer = { groupId: string; state: string | null; lag: number | null; members: number | null }

const SEARCH_MIN = 2

export default function DelegationsPage() {
  const { t, language } = useLanguage()

  const [term, setTerm] = useState('')
  const [results, setResults] = useState<EntityRef[]>([])
  const [searched, setSearched] = useState(false)
  const [searchFailed, setSearchFailed] = useState(false)

  const [party, setParty] = useState<EntityRef | null>(null)
  const [grants, setGrants] = useState<GrantsPayload | null>(null)
  const [effectiveAccess, setEffectiveAccess] = useState<EffectiveAccessPayload | null>(null)
  const [grantsUnavail, setGrantsUnavail] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(false)

  const [consumers, setConsumers] = useState<ProjectionConsumer[] | null>(null)
  const [projectionKnown, setProjectionKnown] = useState(true)

  const search = useCallback(async () => {
    const q = term.trim()
    if (q.length < SEARCH_MIN) return
    setSearchFailed(false)
    setSearched(true)
    try {
      const res = await fetch(`/api/entities/resolve?q=${encodeURIComponent(q)}`, {
        cache: 'no-store',
        signal: AbortSignal.timeout(6000),
      })
      if (!res.ok) { setSearchFailed(true); setResults([]); return }
      const body = (await res.json()) as { results?: EntityRef[] }
      setResults((body.results ?? []).filter(r => r.type === 'party'))
    } catch {
      setSearchFailed(true)
      setResults([])
    }
  }, [term])

  const loadGrants = useCallback(async (target: EntityRef) => {
    setParty(target)
    setLoading(true)
    setGrantsUnavail(null)
    setGrants(null)
    setEffectiveAccess(null)
    try {
      const [res, effectiveRes] = await Promise.all([
        fetch(`/api/delegations/party/${target.id}`, { cache: 'no-store', signal: AbortSignal.timeout(8000) }),
        fetch(`/api/delegations/effective-access/${target.id}`, { cache: 'no-store', signal: AbortSignal.timeout(8000) }),
      ])
      if (effectiveRes.ok) setEffectiveAccess((await effectiveRes.json()) as EffectiveAccessPayload)
      if (!res.ok) {
        setGrantsUnavail((await classifyBffFailure(res)) as BffFailure)
        return
      }
      setGrants((await res.json()) as GrantsPayload)
    } catch {
      setGrantsUnavail('unreachable')
    } finally {
      setLoading(false)
    }

    try {
      const res = await fetch('/api/delegations/projection-health', {
        cache: 'no-store',
        signal: AbortSignal.timeout(6000),
      })
      if (!res.ok) { setProjectionKnown(false); setConsumers(null); return }
      const body = (await res.json()) as { consumers?: ProjectionConsumer[]; state?: string }
      setProjectionKnown(body.state === 'ok')
      setConsumers(body.consumers ?? [])
    } catch {
      setProjectionKnown(false)
      setConsumers(null)
    }
  }, [])

  return (
    <div>
      <PageHeader
        icon={<Share2 size={18} aria-hidden="true" />}
        title={t('Delegovaný přístup', 'Delegated Access')}
        subtitle={t('Kdo komu udělil práva k účtu, kartě nebo spoření (ADR-0232). Konzole je pouze pro čtení.', 'Who granted whom rights over an account, card or savings goal (ADR-0232). This console is read-only.')}
        actions={party && (
          <button type="button" className="btn btn-secondary" onClick={() => loadGrants(party)} disabled={loading} aria-busy={loading} aria-label={t('Obnovit delegovaný přístup', 'Refresh delegated access')}>
            <RefreshCw size={14} aria-hidden="true" />
            {t('Obnovit', 'Refresh')}
          </button>
        )}
      />

      <RoleCatalog />

      {/* ---- party lookup (ADR-0228 facade, never a raw UUID field) ---- */}
      <div className="card" style={{ padding: '16px', marginBottom: '20px' }}>
        <label htmlFor="party-search" style={{ display: 'block', fontSize: '13px', fontWeight: 600, marginBottom: '8px' }}>
          {t('Najít stranu podle jména nebo obchodního klíče', 'Find a party by name or business key')}
        </label>
        <div style={{ display: 'flex', gap: '8px' }}>
          <input
            id="party-search"
            className="input"
            style={{ flex: 1 }}
            value={term}
            onChange={e => setTerm(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') search() }}
            placeholder={t('Jméno strany…', 'Party name…')}
            aria-label={t('Hledat stranu', 'Search party')}
          />
          <button type="button" className="btn btn-primary" onClick={search} disabled={term.trim().length < SEARCH_MIN} aria-busy={loading} aria-label={t('Vyhledat delegující stranu', 'Search delegating party')}>
            <Search size={14} aria-hidden="true" />
            {t('Vyhledat', 'Search')}
          </button>
        </div>

        {searchFailed && (
          <p style={{ marginTop: '10px', fontSize: '13px', color: 'var(--text-tertiary)' }}>
            {t(
              'Vyhledávání stran teď neodpovídá. Zkuste to prosím za chvíli.',
              'Party search is not responding right now. Please try again shortly.',
            )}
          </p>
        )}

        {searched && !searchFailed && results.length === 0 && (
          <p style={{ marginTop: '10px', fontSize: '13px', color: 'var(--text-tertiary)' }}>
            {t('Žádná strana neodpovídá dotazu.', 'No party matches that query.')}
          </p>
        )}

        {results.length > 0 && (
          <div style={{ marginTop: '12px', display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
            {results.map(r => (
              <button
                key={r.id}
                type="button"
                className="btn btn-secondary"
                aria-pressed={party?.id === r.id}
                aria-label={t(`Vybrat stranu ${r.label}`, `Select party ${r.label}`)}
                onClick={() => loadGrants(r)}
                style={{ fontWeight: party?.id === r.id ? 700 : 500 }}
              >
                {r.label}
                {r.sublabel && (
                  <span style={{ color: 'var(--text-tertiary)', fontWeight: 400, marginLeft: '6px' }}>{r.sublabel}</span>
                )}
              </button>
            ))}
          </div>
        )}
      </div>

      {!party && (
        <div className="card" style={{ padding: '40px', textAlign: 'center', color: 'var(--text-tertiary)' }}>
          {t('Vyberte stranu a zobrazte její delegace.', 'Pick a party to see its delegations.')}
        </div>
      )}

      {party && grantsUnavail && (
        <DataUnavailable
          kind={grantsUnavail}
          service="delegation-service"
          feature={t('delegovaný přístup', 'delegated access')}
          lang={language}
        />
      )}

      {party && effectiveAccess && <EffectiveAccess data={effectiveAccess} />}

      {party && !grantsUnavail && grants && (
        <>
          <GrantTable
            title={t('Sdíleno touto stranou', 'Shared by this party')}
            subtitle={t('Práva, která tato strana udělila jiným.', 'Rights this party has granted to others.')}
            grants={grants.granted}
            state={grants.sources.granted}
            direction="granted"
          />
          <GrantTable
            title={t('Sdíleno s touto stranou', 'Shared with this party')}
            subtitle={t('Práva, která tato strana drží nad cizími zdroji.', 'Rights this party holds over other people’s resources.')}
            grants={grants.received}
            state={grants.sources.received}
            direction="received"
          />
          <ProjectionHealth consumers={consumers} known={projectionKnown} />
        </>
      )}
    </div>
  )
}

function GrantTable({
  title, subtitle, grants, state, direction,
}: { title: string; subtitle: string; grants: Grant[]; state: DirectionState; direction: 'granted' | 'received' }) {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'

  return (
    <div className="card" style={{ padding: '16px', marginBottom: '20px' }}>
      <h2 style={{ fontSize: '15px', fontWeight: 700, marginBottom: '2px' }}>{title}</h2>
      <p style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>{subtitle}</p>

      {state !== 'ok' ? (
        <div style={{ padding: '16px', borderRadius: '8px', background: 'var(--surface-3)', fontSize: '13px' }}>
          {state === 'forbidden'
            ? t(
                'Tento pohled vám nebyl povolen — nezaměňujte za „žádné delegace“.',
                'This view was refused for your role — do not read it as “no delegations”.',
              )
            : t(
                'Tento pohled se teď nepodařilo načíst — nezaměňujte za „žádné delegace“.',
                'This view could not be loaded right now — do not read it as “no delegations”.',
              )}
        </div>
      ) : grants.length === 0 ? (
        <div style={{ padding: '16px', color: 'var(--text-tertiary)', fontSize: '13px' }}>
          {t('Žádné delegace.', 'No delegations.')}
        </div>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table className="table" style={{ width: '100%' }}>
            <thead>
              <tr>
                <th>{t('Stav', 'Status')}</th>
                <th>{t('Protistrana', 'Counterparty')}</th>
                <th>{t('Zdroj', 'Resource')}</th>
                <th>{t('Oprávnění', 'Capabilities')}</th>
                <th>{t('Strop na transakci', 'Per-transaction cap')}</th>
                <th>{t('Platnost do', 'Valid until')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {grants.map(g => {
                const counterparty = grantCounterparty(g, direction)
                return <tr key={g.id}>
                  <td><DelegationStatusBadge status={g.status} /></td>
                  <td><EntityChip type="party" id={counterparty.id} label={counterparty.name} /></td>
                  <td style={{ fontSize: '12px' }}>{g.resourceType}</td>
                  <td style={{ fontSize: '12px' }}>{capabilityLabels(g.capabilities)}</td>
                  <td style={{ fontSize: '12px' }}>{formatCeiling(g.perTransactionLimit, numberLocale)}</td>
                  <td style={{ fontSize: '12px' }}>{g.validTo ? g.validTo.slice(0, 10) : '—'}</td>
                  <td>
                    <Link href={`/delegations/${g.id}`} className="btn btn-secondary" style={{ fontSize: '12px' }}>
                      {t('Detail', 'Detail')}
                    </Link>
                  </td>
                </tr>
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function ProjectionHealth({ consumers, known }: { consumers: ProjectionConsumer[] | null; known: boolean }) {
  const { t } = useLanguage()

  return (
    <div className="card" style={{ padding: '16px' }}>
      <h2 style={{ fontSize: '15px', fontWeight: 700, marginBottom: '2px' }}>
        <Activity size={15} color="var(--accent)" style={{ verticalAlign: 'middle', marginRight: '6px' }} />
        {t('Zdraví projekcí', 'Projection health')}
      </h2>
      <p style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>
        {t(
          'Práva vynucují produktové služby z vlastní projekce. Zpoždění konzumenta znamená, že odvolané právo může být ještě chvíli platné.',
          'Rights are enforced by each product service from its own projection. Consumer lag means a revoked right may still be honoured for a while.',
        )}
      </p>

      {!known || consumers === null ? (
        <div style={{ padding: '12px', fontSize: '13px', color: 'var(--text-tertiary)' }}>
          {t(
            'Zpoždění konzumentů teď není známé — nepovažujte to za nulové zpoždění.',
            'Consumer lag is not known right now — do not read that as zero lag.',
          )}
        </div>
      ) : consumers.length === 0 ? (
        <div style={{ padding: '12px', fontSize: '13px', color: 'var(--text-tertiary)' }}>
          {t('Toto téma zatím nemá žádnou konzumentskou skupinu.', 'This topic has no consumer group yet.')}
        </div>
      ) : (
        <table className="table" style={{ width: '100%' }}>
          <thead>
            <tr>
              <th>{t('Skupina', 'Group')}</th>
              <th>{t('Stav', 'State')}</th>
              <th>{t('Zpoždění', 'Lag')}</th>
            </tr>
          </thead>
          <tbody>
            {consumers.map(c => (
              <tr key={c.groupId}>
                <td style={{ fontSize: '12px' }}>{c.groupId}</td>
                <td style={{ fontSize: '12px' }}>{c.state ?? '—'}</td>
                <td style={{ fontSize: '12px' }}>{c.lag === null ? '—' : c.lag}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
