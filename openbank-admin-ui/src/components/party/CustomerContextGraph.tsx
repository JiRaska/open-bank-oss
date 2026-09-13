// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useId, useMemo, useState } from 'react'
import { Network } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Customer360Evidence } from '@/lib/customer360/evidence'

type Kind = 'domain' | 'account' | 'consent'
type ContextNode = { id: string; kind: Kind; label: string; facts: string[] }
const PAGE_SIZE = 12
const OBSERVATION_LIMIT = 10

/** A rendering of the existing authorized projection, with no independent data access or inference. */
export function CustomerContextGraph({ evidence, partyName }: {
  evidence: Customer360Evidence
  partyName: string
}) {
  const { t } = useLanguage()
  const headingId = useId()
  const [kind, setKind] = useState<Kind | 'all'>('all')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [zoom, setZoom] = useState(1)

  // All facts come from the same validated, party-scoped response that powers the 360 cards.
  // An account association is not proof of current ownership; a domain summary is not an entity.
  const nodes = useMemo((): ContextNode[] => {
    const consentObservations = new Map<string, Customer360Evidence['consents']>()
    for (const observation of evidence.consents) {
      const existing = consentObservations.get(observation.consentId)
      if (existing) existing.push(observation)
      else consentObservations.set(observation.consentId, [observation])
    }
    return [
    ...evidence.domains.map(domain => ({
      id: `domain:${domain.aggregateType}`,
      kind: 'domain' as const,
      label: domain.aggregateType,
      facts: [
        t('Souhrn domény', 'Domain summary'),
        `${t('Události', 'Events')}: ${domain.events}`,
        `${t('Poslední událost', 'Latest event')}: ${domain.lastEventType}`,
        `${t('Čas události', 'Event time')}: ${domain.lastOccurredAt}`,
      ],
    })),
    ...Array.from(new Set(evidence.accountIds)).map(id => ({
      id: `account:${id}`, kind: 'account' as const, label: id,
      facts: [t('Účet v projekci klienta', 'Account in the customer projection'), id],
    })),
    ...Array.from(consentObservations, ([id, observations]) => ({
      id: `consent:${id}`, kind: 'consent' as const, label: id,
      facts: [
        t('Souhlas v projekci klienta', 'Consent in the customer projection'), id,
        ...observations.slice(0, OBSERVATION_LIMIT).flatMap((observation, index) => [
          `${observations.length === 1 ? t('Projektovaný stav', 'Projected state') : `${t('Pozorování', 'Observation')} ${index + 1}`}: ${observation.status}`,
          `${t('Rozsahy', 'Scopes')}: ${observation.scopes.join(', ') || '—'}`,
        ]),
        ...(observations.length > 1 ? [t(
          `Zobrazeno ${Math.min(observations.length, OBSERVATION_LIMIT)} z ${observations.length} pozorování. Aktuální stav a úplnou historii ověřte ve zdrojové službě.`,
          `Showing ${Math.min(observations.length, OBSERVATION_LIMIT)} of ${observations.length} observations. Verify current state and complete history with the source service.`,
        )] : []),
      ],
    })),
    ]
  }, [evidence, t])
  const filtered = nodes.filter(node => (kind === 'all' || node.kind === kind)
    && node.label.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
  const lastPage = Math.max(0, Math.ceil(filtered.length / PAGE_SIZE) - 1)
  const currentPage = Math.min(page, lastPage)
  const visible = filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)
  const selected = visible.find(node => node.id === selectedId)
  const colors: Record<Kind, string> = {
    domain: 'var(--accent)', account: 'var(--success-text)', consent: 'var(--warning-text)',
  }
  const labels: Record<Kind, string> = {
    domain: t('Doména', 'Domain'), account: t('Účet', 'Account'), consent: t('Souhlas', 'Consent'),
  }
  const positions = visible.map((node, index) => {
    const angle = (index / visible.length) * Math.PI * 2 - Math.PI / 2
    return { ...node, x: 400 + Math.cos(angle) * 285, y: 240 + Math.sin(angle) * 177 }
  })

  if (!evidence.available) return null

  return (
    <section aria-labelledby={headingId} className="card" style={{ padding: 24, marginBottom: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
        <Network size={22} aria-hidden="true" className="tone-text-accent" />
        <h2 id={headingId} style={{ margin: 0, fontSize: 20 }}>{t('Kontextový graf', 'Context graph')}</h2>
        <span className="tag">{t('Klientská 360', 'Customer 360')}</span>
      </div>
      <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginTop: 0 }}>
        {t('Prozkoumejte souvislosti v událostech tohoto klienta. Vyberte uzel a zobrazte jeho podklad.',
          'Explore connections in this customer’s events. Select a node to inspect its evidence.')}
      </p>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end', marginBottom: 16 }}>
        <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
          {t('Typ uzlu', 'Node type')}
          <select className="input" value={kind} onChange={event => {
            setKind(event.target.value as Kind | 'all'); setPage(0); setSelectedId(null)
          }}>
            <option value="all">{t('Všechny typy', 'All types')}</option>
            {Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <label style={{ display: 'grid', gap: 4, fontSize: 12, flex: '1 1 180px' }}>
          {t('Najít v grafu', 'Find in graph')}
          <input className="input" value={query} onChange={event => {
            setQuery(event.target.value); setPage(0); setSelectedId(null)
          }} placeholder={t('Název domény nebo identifikátor', 'Domain name or identifier')} />
        </label>
        <button type="button" className="btn btn-secondary" style={{ minWidth: 36 }} disabled={zoom <= 1} aria-label={t('Oddálit graf', 'Zoom out graph')}
          onClick={() => setZoom(value => Math.max(1, value - 0.25))}>−</button>
        <button type="button" className="btn btn-secondary" style={{ minWidth: 36 }} disabled={zoom >= 2} aria-label={t('Přiblížit graf', 'Zoom in graph')}
          onClick={() => setZoom(value => Math.min(2, value + 0.25))}>+</button>
        <button type="button" className="btn btn-secondary" onClick={() => setZoom(1)}>{t('Celý graf', 'Fit graph')}</button>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
        <div style={{ flex: '3 1 480px', minWidth: 0 }}>
          <div tabIndex={0} role="region" aria-label={t('Mapa vztahů — posuvná oblast', 'Relationship map — scrollable area')}
            style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--surface-2)' }}>
            <svg viewBox="0 0 800 480" role="group" aria-label={t('Vztahy v projekci klienta', 'Relationships in the customer projection')}
              style={{ display: 'block', width: `${zoom * 100}%`, minWidth: 560 }}>
              {positions.map(node => <line key={node.id} x1={400} y1={240} x2={node.x} y2={node.y}
                stroke={colors[node.kind]} strokeOpacity={selected?.id === node.id ? 1 : 0.3}
                strokeWidth={selected?.id === node.id ? 3 : 1.5} strokeDasharray={node.kind === 'domain' ? '5 5' : undefined} />)}
              <circle cx={400} cy={240} r={44} fill="var(--surface)" stroke="var(--accent)" strokeWidth={2} />
              <text x={400} y={235} textAnchor="middle" fill="var(--text-primary)" fontSize={14} fontWeight={600}>{t('Klient', 'Customer')}</text>
              <text x={400} y={256} textAnchor="middle" fill="var(--text-secondary)" fontSize={14}>{t('Výchozí bod', 'Starting point')}</text>
              {positions.map(node => (
                <g key={node.id} role="button" tabIndex={0} aria-pressed={selected?.id === node.id}
                  aria-label={`${labels[node.kind]}: ${node.label}`} style={{ cursor: 'pointer' }}
                  onClick={() => setSelectedId(node.id)} onKeyDown={event => {
                    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setSelectedId(node.id) }
                  }}>
                  <title>{node.label}</title>
                  <circle cx={node.x} cy={node.y} r={28} fill="var(--surface)" stroke={colors[node.kind]}
                    strokeWidth={selected?.id === node.id ? 4 : 2} />
                  <text x={node.x} y={node.y + 4} textAnchor="middle" fill="var(--text-primary)" fontSize={14}>{labels[node.kind]}</text>
                  <text x={node.x} y={node.y + 40} textAnchor="middle" fill="var(--text-secondary)" fontSize={14}>
                    {node.label.length > 20 ? `${node.label.slice(0, 17)}…` : node.label}
                  </text>
                </g>
              ))}
            </svg>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center', marginTop: 12 }}>
            <span role="status" style={{ color: 'var(--text-secondary)', fontSize: 12 }}>
              {t('Zobrazené uzly', 'Visible nodes')}: {visible.length} / {filtered.length}
            </span>
            <button type="button" className="btn btn-secondary" disabled={currentPage === 0}
              onClick={() => { setPage(currentPage - 1); setSelectedId(null) }}>{t('Předchozí', 'Previous')}</button>
            <span style={{ fontSize: 12 }}>{currentPage + 1} / {lastPage + 1}</span>
            <button type="button" className="btn btn-secondary" disabled={currentPage === lastPage}
              onClick={() => { setPage(currentPage + 1); setSelectedId(null) }}>{t('Další', 'Next')}</button>
          </div>
          {filtered.length === 0 && <p role="status">{nodes.length === 0
            ? t('Pro tohoto klienta nejsou k dispozici projektované vztahy.', 'No projected relationships are available for this customer.')
            : t('Žádný uzel neodpovídá filtru.', 'No nodes match this filter.')}</p>}
        </div>
        <aside aria-label={t('Podklad vybraného uzlu', 'Selected node evidence')}
          style={{ flex: '1 1 220px', padding: 16, border: '1px solid var(--border)', borderRadius: 12, overflowWrap: 'anywhere' }}>
          <p style={{ marginTop: 0, fontWeight: 600 }}>{partyName}</p>
          <p style={{ color: 'var(--text-secondary)', fontSize: 12 }}>{evidence.partyId}</p>
          {selected ? <ul style={{ paddingLeft: 18, fontSize: 13, lineHeight: 1.8 }}>
            {selected.facts.map((fact, index) => <li key={index}>{fact}</li>)}
          </ul> : <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>
            {t('Vyberte uzel v grafu. Každá spojnice znamená výskyt v projekci tohoto klienta.',
              'Select a node in the graph. Each connection means inclusion in this customer’s projection.')}
          </p>}
          <p style={{ fontSize: 12 }}>{t('Zdroj: událostní projekce Customer 360', 'Source: Customer 360 event projection')}</p>
          <p style={{ fontSize: 12 }}>{t('Nejnovější událost ve výřezu', 'Newest event in this slice')}: {evidence.asOf ?? '—'}</p>
          <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
            {t('Výřez může být neúplný nebo opožděný. Spojnice nepotvrzuje aktuální vlastnictví, platnost souhlasu ani podezřelé jednání. Aktuální stav ověřte ve zdrojové službě.',
              'This slice may be incomplete or delayed. A connection does not establish current ownership, consent validity or suspicious activity. Verify current state with the source service.')}
          </p>
          {evidence.excludedCount > 0 && <p role="status" style={{ fontSize: 12, color: 'var(--warning-text)' }}>
            {t('Neplatné záznamy vynechány', 'Invalid records omitted')}: {evidence.excludedCount}
          </p>}
        </aside>
      </div>
    </section>
  )
}
