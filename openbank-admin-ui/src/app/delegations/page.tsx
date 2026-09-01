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

import { useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { Search, Share2, RefreshCw, Activity } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { EntityChip } from '@/components/entities/EntityChip'
import { PageHeader } from '@/components/ui/PageHeader'
import { RoleCatalog } from '@/components/delegations/RoleCatalog'
import {
  EffectiveAccess,
  effectiveResourceDetails,
  grantConditions,
  grantResourcePresentation,
  isEffectiveAccessPayload,
  matchedRoleName,
  type EffectiveAccessPayload,
} from '@/components/delegations/EffectiveAccess'
import { capabilityLabel } from '@/lib/delegations/rolePresets'
import {
  DelegationStatusBadge,
  counterpartyLabel,
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
  const [searching, setSearching] = useState(false)

  const [party, setParty] = useState<EntityRef | null>(null)
  const [grants, setGrants] = useState<GrantsPayload | null>(null)
  const [effectiveAccess, setEffectiveAccess] = useState<EffectiveAccessPayload | null>(null)
  const [grantsUnavail, setGrantsUnavail] = useState<UnavailableKind | null>(null)
  const [effectiveUnavail, setEffectiveUnavail] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(false)

  const [consumers, setConsumers] = useState<ProjectionConsumer[] | null>(null)
  const [projectionKnown, setProjectionKnown] = useState(true)
  const [projectionLoading, setProjectionLoading] = useState(false)
  const searchGeneration = useRef(0)
  const searchRequest = useRef<AbortController | null>(null)
  const accessGeneration = useRef(0)
  const accessRequest = useRef<AbortController | null>(null)

  useEffect(() => () => {
    searchGeneration.current += 1
    accessGeneration.current += 1
    searchRequest.current?.abort()
    accessRequest.current?.abort()
  }, [])

  const search = useCallback(async () => {
    const q = term.trim()
    if (q.length < SEARCH_MIN) return
    const generation = ++searchGeneration.current
    searchRequest.current?.abort()
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 6000)
    searchRequest.current = controller
    setSearchFailed(false)
    setSearched(true)
    setSearching(true)
    setResults([])
    try {
      const res = await fetch(`/api/entities/resolve?q=${encodeURIComponent(q)}`, {
        cache: 'no-store',
        signal: controller.signal,
      })
      if (generation !== searchGeneration.current) return
      if (!res.ok) { setSearchFailed(true); setResults([]); return }
      const body = (await res.json()) as { results?: EntityRef[] }
      if (generation !== searchGeneration.current) return
      setResults((body.results ?? []).filter(r => r.type === 'party'))
    } catch {
      if (generation === searchGeneration.current) {
        setSearchFailed(true)
        setResults([])
      }
    } finally {
      window.clearTimeout(timeout)
      if (searchRequest.current === controller) searchRequest.current = null
      if (generation === searchGeneration.current) setSearching(false)
    }
  }, [term])

  const loadGrants = useCallback(async (target: EntityRef) => {
    const generation = ++accessGeneration.current
    accessRequest.current?.abort()
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 8000)
    accessRequest.current = controller
    setParty(target)
    setLoading(true)
    setGrantsUnavail(null)
    setEffectiveUnavail(null)
    setGrants(null)
    setEffectiveAccess(null)
    setConsumers(null)
    setProjectionKnown(false)
    setProjectionLoading(true)

    const grantsRequest = fetch(`/api/delegations/party/${target.id}`, {
      cache: 'no-store',
      signal: controller.signal,
    }).then(async res => {
      if (!res.ok) return { data: null, unavailable: await classifyBffFailure(res) }
      return { data: (await res.json()) as GrantsPayload, unavailable: null }
    })
    const effectiveRequest = fetch(`/api/delegations/effective-access/${target.id}`, {
      cache: 'no-store',
      signal: controller.signal,
    }).then(async res => {
      if (!res.ok) return { data: null, unavailable: await classifyBffFailure(res) }
      const payload: unknown = await res.json()
      return isEffectiveAccessPayload(payload)
        ? { data: payload, unavailable: null }
        : { data: null, unavailable: 'error' as const }
    })
    const projectionRequest = fetch('/api/delegations/projection-health', {
      cache: 'no-store',
      signal: controller.signal,
    }).then(async res => {
      if (!res.ok) return null
      return (await res.json()) as { consumers?: ProjectionConsumer[]; state?: string }
    }).catch(() => null)

    try {
      const [grantResult, effectiveResult] = await Promise.allSettled([grantsRequest, effectiveRequest])
      if (generation === accessGeneration.current) {
        if (grantResult.status === 'fulfilled') {
          setGrants(grantResult.value.data)
          setGrantsUnavail(grantResult.value.unavailable)
        } else {
          setGrantsUnavail('unreachable')
        }
        if (effectiveResult.status === 'fulfilled') {
          setEffectiveAccess(effectiveResult.value.data)
          setEffectiveUnavail(effectiveResult.value.unavailable)
        } else {
          setEffectiveUnavail('unreachable')
        }
      }
    } finally {
      if (generation === accessGeneration.current) setLoading(false)
    }

    const projection = await projectionRequest
    if (generation === accessGeneration.current) {
      setProjectionKnown(projection?.state === 'ok')
      setConsumers(projection?.consumers ?? null)
      setProjectionLoading(false)
    }
    window.clearTimeout(timeout)
    if (accessRequest.current === controller) {
      accessRequest.current = null
    }
  }, [])

  useEffect(() => {
    if (!party || !effectiveAccess?.nextChangeAt || effectiveAccess.refreshAfterMs === null) return
    // The BFF computes this remaining duration immediately before returning the snapshot. This
    // avoids both the workstation clock and extending validity by time spent resolving details.
    const delay = Math.min(Math.max(effectiveAccess.refreshAfterMs, 0), 2_147_000_000)
    const timer = window.setTimeout(() => { void loadGrants(party) }, delay)
    return () => window.clearTimeout(timer)
  }, [effectiveAccess?.nextChangeAt, effectiveAccess?.refreshAfterMs, loadGrants, party])

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
          <button type="button" className="btn btn-primary" onClick={search} disabled={term.trim().length < SEARCH_MIN} aria-busy={searching} aria-label={t('Vyhledat delegující stranu', 'Search delegating party')}>
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

        {searched && !searching && !searchFailed && results.length === 0 && (
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

      {party && loading && (
        <div role="status" aria-live="polite" className="card" style={{ padding: '20px', marginBottom: '20px', color: 'var(--text-tertiary)', fontSize: '13px' }}>
          {t(`Načítám přístup klienta ${party.label}…`, `Loading access for ${party.label}…`)}
        </div>
      )}

      {party && effectiveAccess && <EffectiveAccess data={effectiveAccess} />}

      {party && !loading && effectiveUnavail && grants && (
        <div role="status" aria-live="polite" style={{ padding: '12px', marginBottom: '20px', borderRadius: '10px', border: '1px solid var(--warning-border)', background: 'var(--warning-bg)', fontSize: '12px' }}>
          <strong>{t('Souhrn efektivního přístupu je dočasně neúplný.', 'The effective-access summary is temporarily incomplete.')}</strong>{' '}
          {t('Níže zůstávají zobrazené úspěšně načtené delegace; vlastnictví, názvy rolí nebo detaily zdrojů mohou chybět.', 'Successfully loaded grants remain visible below; ownership, role names, or resource details may be missing.')}
        </div>
      )}

      {party && !grantsUnavail && grants && (
        <>
          <GrantTable
            title={t('Sdíleno touto stranou', 'Shared by this party')}
            subtitle={t('Práva, která tato strana udělila jiným.', 'Rights this party has granted to others.')}
            grants={grants.granted}
            state={grants.sources.granted}
            direction="granted"
            effectiveAccess={effectiveAccess}
          />
          <GrantTable
            title={t('Sdíleno s touto stranou', 'Shared with this party')}
            subtitle={t('Práva, která tato strana drží nad cizími zdroji.', 'Rights this party holds over other people’s resources.')}
            grants={grants.received}
            state={grants.sources.received}
            direction="received"
            effectiveAccess={effectiveAccess}
          />
          <ProjectionHealth consumers={consumers} known={projectionKnown} loading={projectionLoading} />
        </>
      )}
    </div>
  )
}

function GrantTable({
  title, subtitle, grants, state, direction, effectiveAccess,
}: { title: string; subtitle: string; grants: Grant[]; state: DirectionState; direction: 'granted' | 'received'; effectiveAccess: EffectiveAccessPayload | null }) {
  const { t, language } = useLanguage()
  const details = effectiveAccess ? effectiveResourceDetails(effectiveAccess) : []

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
                <th>{t('Role', 'Role')}</th>
                <th>{t('Zdroj', 'Resource')}</th>
                <th>{t('Práva', 'Rights')}</th>
                <th>{t('Podmínky', 'Conditions')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {grants.map(g => {
                const counterparty = grantCounterparty(g, direction)
                const resource = grantResourcePresentation(g, details, language)
                const role = effectiveAccess?.sources.presets === 'ok'
                  ? matchedRoleName(g, effectiveAccess.presets, language)
                  : t('Role není dostupná', 'Role unavailable')
                return <tr key={g.id}>
                  <td><DelegationStatusBadge status={g.status} /></td>
                  <td><EntityChip type="party" id={counterparty.id} label={counterparty.name} /></td>
                  <td style={{ fontSize: '12px', fontWeight: 650 }}>{role}</td>
                  <td style={{ fontSize: '12px' }}><strong style={{ display: 'block' }}>{resource.label}</strong>{resource.meta && <span style={{ color: 'var(--text-tertiary)' }}>{resource.meta}</span>}</td>
                  <td><div aria-label={t('Práva', 'Rights')} style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>{g.capabilities.map(capability => <span key={capability} title={capability} style={{ borderRadius: 999, padding: '3px 7px', fontSize: 10, background: 'var(--surface-3)', border: '1px solid var(--border)' }}>{capabilityLabel(capability, language)}</span>)}</div></td>
                  <td style={{ fontSize: '11px' }}>{grantConditions(g, language).map(condition => <div key={condition.label}><span style={{ color: 'var(--text-tertiary)' }}>{condition.label}:</span> <strong>{condition.value}</strong></div>)}</td>
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

function ProjectionHealth({ consumers, known, loading }: { consumers: ProjectionConsumer[] | null; known: boolean; loading: boolean }) {
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

      {loading ? (
        <div role="status" style={{ padding: '12px', fontSize: '13px', color: 'var(--text-tertiary)' }}>
          {t('Načítám zdraví projekcí…', 'Loading projection health…')}
        </div>
      ) : !known || consumers === null ? (
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
