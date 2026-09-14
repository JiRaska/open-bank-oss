// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useEffect, useId, useMemo, useState } from 'react'
import { Network } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Customer360Evidence } from '@/lib/customer360/evidence'
import {
  buildCustomerGraph,
  emptyLiveCustomerFacts,
  type CustomerGraphKind,
  type LiveCustomerFacts,
} from '@/lib/context/customerGraph'
import { loadCustomerGraphFacts } from '@/lib/context/customerGraphClient'

const EMPTY_LIVE: LiveCustomerFacts = emptyLiveCustomerFacts()
const MAX_VISIBLE = 48
const GRAPH_FEEDS = 7

export function CustomerContextGraph({ evidence, partyName }: {
  evidence: Customer360Evidence
  partyName: string
}) {
  const { t } = useLanguage()
  const headingId = useId()
  const [kind, setKind] = useState<CustomerGraphKind | 'all'>('all')
  const [query, setQuery] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [zoom, setZoom] = useState(1)
  const [live, setLive] = useState<LiveCustomerFacts>(EMPTY_LIVE)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!evidence.available) return
    let current = true
    void loadCustomerGraphFacts(evidence.partyId)
      .then(facts => {
        if (!current) return
        setLive(facts)
        setLoading(false)
      })
      .catch(() => {
        if (!current) return
        setLive(emptyLiveCustomerFacts(['graph']))
        setLoading(false)
      })
    return () => { current = false }
  }, [evidence.available, evidence.partyId])

  const graph = useMemo(() => buildCustomerGraph(evidence, live), [evidence, live])
  const filtered = graph.nodes.filter(node =>
    (kind === 'all' || node.kind === kind)
    && `${node.label} ${node.facts.join(' ')}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()),
  )
  const visible = filtered.slice(0, MAX_VISIBLE)
  const selected = graph.nodes.find(node => node.id === selectedId) ?? null
  const positions = new Map(visible.map((node, index) => {
    const ring = index < 16 ? 0 : 1
    const ringStart = ring === 0 ? 0 : 16
    const ringCount = ring === 0 ? Math.min(16, visible.length) : visible.length - 16
    const angle = ((index - ringStart) / Math.max(1, ringCount)) * Math.PI * 2 - Math.PI / 2
    const radiusX = ring === 0 ? 210 : 330
    const radiusY = ring === 0 ? 135 : 205
    return [node.id, { x: 400 + Math.cos(angle) * radiusX, y: 250 + Math.sin(angle) * radiusY }] as const
  }))
  const colors: Record<CustomerGraphKind, string> = {
    domain: '#7c3aed', account: '#0f766e', product: '#2563eb', card: '#dc2626',
    notification: '#d97706', consent: '#9333ea', application: '#0891b2', case: '#be123c',
    device: '#4f46e5', document: '#15803d',
  }
  const labels: Record<CustomerGraphKind, string> = {
    domain: t('Doména', 'Domain'), account: t('Účet', 'Account'), product: t('Produkt', 'Product'),
    card: t('Karta', 'Card'), notification: t('Interakce', 'Interaction'), consent: t('Souhlas', 'Consent'),
    application: t('Úvěrová žádost', 'Credit application'), case: t('AML případ', 'AML case'),
    device: t('Zařízení', 'Device'), document: t('Dokument', 'Document'),
  }

  if (!evidence.available) return null

  return <section aria-labelledby={headingId} className="card" style={{ padding: 24, marginBottom: 24 }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8, flexWrap: 'wrap' }}>
      <Network size={22} aria-hidden="true" className="tone-text-accent" />
      <h2 id={headingId} style={{ margin: 0, fontSize: 20 }}>{t('Kontextový graf', 'Context graph')}</h2>
      <span className="tag">{t('Klientská 360', 'Customer 360')}</span>
      <span className="tag" role="status">{loading
        ? t('Načítám živé zdroje…', 'Loading live sources…')
        : t(`${Math.max(0, GRAPH_FEEDS - live.unavailable.length)}/${GRAPH_FEEDS} doménových vstupů`, `${Math.max(0, GRAPH_FEEDS - live.unavailable.length)}/${GRAPH_FEEDS} domain feeds`)}</span>
    </div>
    <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginTop: 0 }}>
      {t(
        'Propojený pohled kombinuje uloženou událostní projekci s aktuálně autorizovanými údaji vlastnících služeb. Vyberte uzel pro stav, čas a zdroj evidence.',
        'The connected view combines the stored event projection with currently authorised data from owning services. Select a node for status, time and evidence source.',
      )}
    </p>
    {live.unavailable.length > 0 && <p role="status" style={{ color: 'var(--warning-text)', fontSize: 12 }}>
      {t('Nedostupné zdroje', 'Unavailable sources')}: {live.unavailable.join(', ')}. {t('Graf je částečný.', 'The graph is partial.')}
    </p>}
    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end', marginBottom: 16 }}>
      <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>{t('Typ uzlu', 'Node type')}
        <select className="input" value={kind} onChange={event => { setKind(event.target.value as CustomerGraphKind | 'all'); setSelectedId(null) }}>
          <option value="all">{t('Všechny typy', 'All types')}</option>
          {Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label style={{ display: 'grid', gap: 4, fontSize: 12, flex: '1 1 180px' }}>{t('Najít v grafu', 'Find in graph')}
        <input className="input" value={query} onChange={event => { setQuery(event.target.value); setSelectedId(null) }}
          placeholder={t('Produkt, stav, identifikátor nebo událost', 'Product, status, identifier or event')} />
      </label>
      <button type="button" className="btn btn-secondary" disabled={zoom <= 1} aria-label={t('Oddálit graf', 'Zoom out graph')} onClick={() => setZoom(v => Math.max(1, v - .25))}>−</button>
      <button type="button" className="btn btn-secondary" disabled={zoom >= 2} aria-label={t('Přiblížit graf', 'Zoom in graph')} onClick={() => setZoom(v => Math.min(2, v + .25))}>+</button>
      <button type="button" className="btn btn-secondary" onClick={() => setZoom(1)}>{t('Celý graf', 'Fit graph')}</button>
    </div>
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
      <div style={{ flex: '3 1 560px', minWidth: 0 }}>
        <div tabIndex={0} role="region" aria-label={t('Mapa vztahů — posuvná oblast', 'Relationship map — scrollable area')}
          style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--surface-2)' }}>
          <svg viewBox="0 0 800 500" role="group" aria-label={t('Vztahy klienta napříč doménami', 'Customer relationships across domains')}
            style={{ display: 'block', width: `${zoom * 100}%`, minWidth: 620 }}>
            {graph.edges.map(edge => {
              const from = edge.from === 'customer' ? { x: 400, y: 250 } : positions.get(edge.from)
              const to = edge.to === 'customer' ? { x: 400, y: 250 } : positions.get(edge.to)
              if (!from || !to) return null
              return <g key={edge.id}><title>{edge.relation}</title><line x1={from.x} y1={from.y} x2={to.x} y2={to.y}
                stroke="var(--text-tertiary)" strokeOpacity={selectedId === edge.from || selectedId === edge.to ? .9 : .3}
                strokeWidth={selectedId === edge.from || selectedId === edge.to ? 3 : 1.4} /></g>
            })}
            <circle cx={400} cy={250} r={44} fill="var(--surface)" stroke="var(--accent)" strokeWidth={3} />
            <text x={400} y={246} textAnchor="middle" fill="var(--text-primary)" fontSize={14} fontWeight={700}>{t('Klient', 'Customer')}</text>
            <text x={400} y={266} textAnchor="middle" fill="var(--text-secondary)" fontSize={11}>{graph.nodes.length} {t('souvislostí', 'connections')}</text>
            {visible.map(node => {
              const pos = positions.get(node.id)!
              return <g key={node.id} role="button" tabIndex={0} aria-pressed={selectedId === node.id}
                aria-label={`${labels[node.kind]}: ${node.label}`} style={{ cursor: 'pointer' }}
                onClick={() => setSelectedId(node.id)} onKeyDown={event => {
                  if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setSelectedId(node.id) }
                }}>
                <title>{node.label}</title>
                <circle cx={pos.x} cy={pos.y} r={selectedId === node.id ? 25 : 21} fill="var(--surface)" stroke={colors[node.kind]} strokeWidth={selectedId === node.id ? 4 : 2} />
                <text x={pos.x} y={pos.y + 4} textAnchor="middle" fill="var(--text-primary)" fontSize={10}>{labels[node.kind]}</text>
                <text x={pos.x} y={pos.y + 34} textAnchor="middle" fill="var(--text-secondary)" fontSize={10}>{node.label.length > 18 ? `${node.label.slice(0, 15)}…` : node.label}</text>
              </g>
            })}
          </svg>
        </div>
        <p role="status" style={{ color: 'var(--text-secondary)', fontSize: 12 }}>
          {t('Zobrazené uzly', 'Visible nodes')}: {visible.length} / {filtered.length}
          {(filtered.length > MAX_VISIBLE || graph.truncated) && ` · ${t('výsledek je omezen', 'result is bounded')}`}
        </p>
        {filtered.length === 0 && <p role="status">{t('Žádný uzel neodpovídá filtru.', 'No nodes match this filter.')}</p>}
      </div>
      <aside aria-label={t('Podklad vybraného uzlu', 'Selected node evidence')}
        style={{ flex: '1 1 240px', padding: 16, border: '1px solid var(--border)', borderRadius: 12, overflowWrap: 'anywhere' }}>
        <p style={{ marginTop: 0, fontWeight: 700 }}>{partyName}</p>
        <p style={{ color: 'var(--text-secondary)', fontSize: 12 }}>{evidence.partyId}</p>
        {selected ? <>
          <p><strong>{selected.label}</strong></p>
          <p style={{ fontSize: 12 }}>{t('Zdroj', 'Source')}: {selected.source}</p>
          <ul style={{ paddingLeft: 18, fontSize: 13, lineHeight: 1.7 }}>{selected.facts.map((fact, index) => <li key={index}>{fact}</li>)}</ul>
          <p style={{ fontSize: 12 }}>{t('Vztahy', 'Relations')}: {graph.edges.filter(e => e.from === selected.id || e.to === selected.id).map(e => e.relation).join(', ') || '—'}</p>
        </> : <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>{t('Vyberte uzel a zobrazte jeho podklad a vztahy.', 'Select a node to inspect its evidence and relationships.')}</p>}
        <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t(
          'Viditelnost detailu vynucuje každá zdrojová služba z role přihlášeného uživatele. Graf neprokazuje vlastnictví ani podezřelé jednání.',
          'Each source service enforces detail visibility from the signed-in user role. The graph does not by itself prove ownership or suspicious activity.',
        )}</p>
      </aside>
    </div>
  </section>
}
