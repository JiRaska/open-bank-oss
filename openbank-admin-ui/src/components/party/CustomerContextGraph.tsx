// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useEffect, useId, useMemo, useState } from 'react'
import Link from 'next/link'
import { Network, Pause, Play, ScanSearch, Sparkles } from 'lucide-react'
import { FlowParticle } from '@/components/topology/FlowParticle'
import { ArrowMarker, NodeShadow } from '@/components/topology/TopologyDefs'
import { pathId } from '@/components/topology/geometry'
import { useFlowAnimation } from '@/components/topology/useFlowAnimation'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { Customer360Evidence } from '@/lib/customer360/evidence'
import {
  buildCustomerGraph,
  emptyLiveCustomerFacts,
  selectGraphOverview,
  selectGraphFocus,
  type CustomerGraphKind,
  type LiveCustomerFacts,
} from '@/lib/context/customerGraph'
import { loadCustomerGraphFacts } from '@/lib/context/customerGraphClient'
import styles from './CustomerContextGraph.module.css'

const EMPTY_LIVE: LiveCustomerFacts = emptyLiveCustomerFacts()
const MAX_VISIBLE = 48
const GRAPH_FEEDS = 7
const MAX_FLOW_EDGES = 16

const NODE_COLORS: Record<CustomerGraphKind, string> = {
  domain: '#a78bfa', account: '#2dd4bf', product: '#60a5fa', card: '#fb7185',
  notification: '#fbbf24', consent: '#c084fc', application: '#22d3ee', case: '#f472b6',
  device: '#818cf8', document: '#4ade80',
}

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
  const [hoveredId, setHoveredId] = useState<string | null>(null)
  const [flow, setFlow] = useFlowAnimation()
  const [live, setLive] = useState<LiveCustomerFacts>(EMPTY_LIVE)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
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
  const filteredActive = kind !== 'all' || query.trim() !== ''
  const visible = !filteredActive
    ? selectGraphOverview(filtered, MAX_VISIBLE)
    : selectGraphFocus(graph, filtered, MAX_VISIBLE)
  const selected = graph.nodes.find(node => node.id === selectedId) ?? null
  const visibleIds = new Set(visible.map(node => node.id))
  const visibleEdges = graph.edges.filter(edge =>
    (edge.from === 'customer' || visibleIds.has(edge.from)) && visibleIds.has(edge.to),
  )
  const nodesById = new Map(graph.nodes.map(node => [node.id, node]))
  const positions = new Map(visible.map((node, index) => {
    const ring = index < 12 ? 0 : index < 30 ? 1 : 2
    const ringStart = ring === 0 ? 0 : ring === 1 ? 12 : 30
    const ringCount = ring === 0 ? Math.min(12, visible.length) : ring === 1 ? Math.min(18, visible.length - 12) : visible.length - 30
    const angle = ((index - ringStart) / Math.max(1, ringCount)) * Math.PI * 2 - Math.PI / 2
    const radiusX = ring === 0 ? 168 : ring === 1 ? 272 : 355
    const radiusY = ring === 0 ? 108 : ring === 1 ? 176 : 222
    return [node.id, { x: 400 + Math.cos(angle) * radiusX, y: 250 + Math.sin(angle) * radiusY }] as const
  }))
  const labels: Record<CustomerGraphKind, string> = {
    domain: t('Doména', 'Domain'), account: t('Účet', 'Account'), product: t('Produkt', 'Product'),
    card: t('Karta', 'Card'), notification: t('Interakce', 'Interaction'), consent: t('Souhlas', 'Consent'),
    application: t('Úvěrová žádost', 'Credit application'), case: t('AML případ', 'AML case'),
    device: t('Zařízení', 'Device'), document: t('Dokument', 'Document'),
  }

  const focusId = selectedId ?? hoveredId

  return <section aria-labelledby={headingId} className={styles.shell}>
    <div className={styles.heading}>
      <div className={styles.titleBlock}>
        <span className={styles.eyebrow}><Sparkles size={13} aria-hidden="true" /> {t('Živé vztahové pole', 'Live relationship field')}</span>
        <div className={styles.titleLine}>
          <Network size={24} aria-hidden="true" />
          <h2 id={headingId}>{t('Kontextový graf', 'Context graph')}</h2>
          <span className={styles.modeTag}>{t('Klientská 360', 'Customer 360')}</span>
        </div>
        <p>{t(
          'Propojený pohled kombinuje uloženou událostní projekci s aktuálně autorizovanými údaji vlastnících služeb. Vyberte uzel pro stav, čas a zdroj evidence.',
          'The connected view combines the stored event projection with currently authorised data from owning services. Select a node for status, time and evidence source.',
        )}</p>
      </div>
      <span className={styles.feedStatus} role="status"><span aria-hidden="true" className={loading ? styles.loadingDot : styles.liveDot} />{loading
        ? t('Načítám živé zdroje…', 'Loading live sources…')
        : t(`${Math.max(0, GRAPH_FEEDS - live.unavailable.length)}/${GRAPH_FEEDS} doménových vstupů`, `${Math.max(0, GRAPH_FEEDS - live.unavailable.length)}/${GRAPH_FEEDS} domain feeds`)}</span>
    </div>
    {!evidence.available && <p role="status" className={styles.warning}>
      {t(
        'Analytická projekce není dostupná. Vztahy ze zdrojových služeb se načítají nezávisle.',
        'The analytics projection is unavailable. Source-backed relationships load independently.',
      )}
    </p>}
    {live.unavailable.length > 0 && <p role="status" className={styles.warning}>
      {t('Nedostupné zdroje', 'Unavailable sources')}: {live.unavailable.join(', ')}. {t('Graf je částečný.', 'The graph is partial.')}
    </p>}
    <div className={styles.controls}>
      <label>{t('Typ uzlu', 'Node type')}
        <select value={kind} onChange={event => { setKind(event.target.value as CustomerGraphKind | 'all'); setSelectedId(null) }}>
          <option value="all">{t('Všechny typy', 'All types')}</option>
          {Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label className={styles.searchLabel}>{t('Najít v grafu', 'Find in graph')}
        <span className={styles.searchBox}><ScanSearch size={16} aria-hidden="true" /><input value={query} onChange={event => { setQuery(event.target.value); setSelectedId(null) }}
          placeholder={t('Produkt, stav, identifikátor nebo událost', 'Product, status, identifier or event')} />
        </span>
      </label>
      <div className={styles.controlButtons}>
        <button type="button" disabled={zoom <= 1} aria-label={t('Oddálit graf', 'Zoom out graph')} onClick={() => setZoom(v => Math.max(1, v - .25))}>−</button>
        <button type="button" disabled={zoom >= 2} aria-label={t('Přiblížit graf', 'Zoom in graph')} onClick={() => setZoom(v => Math.min(2, v + .25))}>+</button>
        <button type="button" onClick={() => setZoom(1)}>{t('Celý graf', 'Fit graph')}</button>
        <button type="button" aria-pressed={flow} onClick={() => setFlow(value => !value)}>
          {flow ? <Pause size={14} aria-hidden="true" /> : <Play size={14} aria-hidden="true" />}
          {flow ? t('Zastavit tok', 'Pause flow') : t('Spustit tok', 'Resume flow')}
        </button>
      </div>
    </div>
    <div className={styles.workspace}>
      <div className={styles.mapColumn}>
        <div tabIndex={0} role="region" aria-label={t('Mapa vztahů — posuvná oblast', 'Relationship map — scrollable area')}
          className={styles.viewport}>
          <div className={styles.scanLine} aria-hidden="true" />
          <svg viewBox="0 0 800 500" role="group" aria-label={t('Vztahy klienta napříč doménami', 'Customer relationships across domains')}
            className={styles.graph} style={{ width: `${zoom * 100}%` }}>
            <defs>
              <NodeShadow id="customer-graph-shadow" />
              <ArrowMarker id="customer-graph-arrow" color="#67e8f9" />
              <radialGradient id="customer-core" cx="35%" cy="30%">
                <stop offset="0" stopColor="#dffbff" /><stop offset=".3" stopColor="#22d3ee" /><stop offset="1" stopColor="#4338ca" />
              </radialGradient>
              <filter id="customer-graph-glow" x="-80%" y="-80%" width="260%" height="260%">
                <feGaussianBlur stdDeviation="7" result="blur" /><feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge>
              </filter>
            </defs>
            <g className={styles.guides} aria-hidden="true">
              <ellipse cx="400" cy="250" rx="168" ry="108" />
              <ellipse cx="400" cy="250" rx="272" ry="176" />
              <ellipse cx="400" cy="250" rx="355" ry="222" />
              <path d="M 400 20 V 480 M 25 250 H 775" />
            </g>
            {visibleEdges.map((edge, edgeIndex) => {
              const from = edge.from === 'customer' ? { x: 400, y: 250 } : positions.get(edge.from)
              const to = edge.to === 'customer' ? { x: 400, y: 250 } : positions.get(edge.to)
              if (!from || !to) return null
              const connected = !focusId || focusId === edge.from || focusId === edge.to
              const target = nodesById.get(edge.to === 'customer' ? edge.from : edge.to)
              const color = target ? NODE_COLORS[target.kind] : '#67e8f9'
              const pid = pathId('customer-graph-edge', edge.from, edge.to, edgeIndex)
              const d = `M ${from.x} ${from.y} L ${to.x} ${to.y}`
              return <g key={edge.id} className={connected ? styles.edgeActive : styles.edgeMuted}>
                <title>{edge.relation}</title>
                <path id={pid} d={d} fill="none" stroke={color} markerEnd="url(#customer-graph-arrow)" />
                {flow && connected && edgeIndex < MAX_FLOW_EDGES && <FlowParticle pathId={pid} color={color} dur={2.4 + (edgeIndex % 5) * .32} begin={(edgeIndex % 8) * .19} r={2.6} />}
              </g>
            })}
            <g className={styles.customerCore} onClick={() => setSelectedId(null)}>
              <circle cx={400} cy={250} r={61} className={styles.coreHalo} />
              <circle cx={400} cy={250} r={47} fill="url(#customer-core)" />
              <circle cx={400} cy={250} r={39} className={styles.coreInner} />
              <text x={400} y={246} textAnchor="middle" className={styles.coreTitle}>{t('Klient', 'Customer')}</text>
              <text x={400} y={266} textAnchor="middle" className={styles.coreMeta}>{graph.nodes.length} {t('souvislostí', 'connections')}</text>
            </g>
            {visible.map(node => {
              const pos = positions.get(node.id)!
              const active = selectedId === node.id
              const related = !focusId || active || hoveredId === node.id || visibleEdges.some(edge => (edge.from === focusId && edge.to === node.id) || (edge.to === focusId && edge.from === node.id))
              return <g key={node.id} role="button" tabIndex={0} aria-pressed={active}
                aria-label={`${labels[node.kind]}: ${node.label}`} className={`${styles.node} ${related ? '' : styles.nodeMuted}`}
                onClick={() => setSelectedId(node.id)} onKeyDown={event => {
                  if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setSelectedId(node.id) }
                }} onMouseEnter={() => setHoveredId(node.id)} onMouseLeave={() => setHoveredId(null)} onFocus={() => setHoveredId(node.id)} onBlur={() => setHoveredId(null)}>
                <title>{node.label}</title>
                {active && <circle cx={pos.x} cy={pos.y} r={31} fill="none" stroke={NODE_COLORS[node.kind]} className={styles.selectionRing} />}
                <circle cx={pos.x} cy={pos.y} r={active ? 24 : 20} fill="#071426" stroke={NODE_COLORS[node.kind]} strokeWidth={active ? 3 : 2} filter="url(#customer-graph-shadow)" />
                <circle cx={pos.x - 6} cy={pos.y - 7} r={3.2} fill={NODE_COLORS[node.kind]} className={styles.nodeBeacon} />
                <text x={pos.x} y={pos.y + 4} textAnchor="middle" className={styles.nodeType}>{labels[node.kind]}</text>
                <text x={pos.x} y={pos.y + 34} textAnchor="middle" className={styles.nodeLabel}>{node.label.length > 18 ? `${node.label.slice(0, 15)}…` : node.label}</text>
              </g>
            })}
          </svg>
          <div className={styles.coordinates} aria-hidden="true">REL / 360° · {visible.length.toString().padStart(2, '0')}</div>
        </div>
        <p role="status" className={styles.resultStatus}>
          {filteredActive
            ? t(`${filtered.length} shod · ${visible.length} uzlů včetně kontextu`, `${filtered.length} matches · ${visible.length} nodes including context`)
            : <>{t('Zobrazené uzly', 'Visible nodes')}: {visible.length} / {filtered.length}</>}
          {(filtered.length > MAX_VISIBLE || graph.truncated) && ` · ${t('výsledek je omezen', 'result is bounded')}`}
        </p>
        {filtered.length === 0 && <p role="status">{t('Žádný uzel neodpovídá filtru.', 'No nodes match this filter.')}</p>}
      </div>
      <aside aria-label={t('Podklad vybraného uzlu', 'Selected node evidence')}
        className={styles.evidence}>
        <span className={styles.evidenceEyebrow}><ScanSearch size={13} aria-hidden="true" /> {t('Evidence lens', 'Evidence lens')}</span>
        <p className={styles.partyName}>{partyName}</p>
        <p className={styles.partyId}>{evidence.partyId}</p>
        {selected ? <>
          <p className={styles.selectedLabel} style={{ borderColor: NODE_COLORS[selected.kind] }}><strong>{selected.label}</strong></p>
          <p className={styles.source}>{t('Zdroj', 'Source')}: {selected.source}</p>
          {selected.href && <Link className={styles.recordLink} href={selected.href}>
            {t('Otevřít zdrojový záznam', 'Open source record')}
          </Link>}
          <ul className={styles.facts}>{selected.facts.map((fact, index) => <li key={index}>{fact}</li>)}</ul>
          <p className={styles.relations}>{t('Vztahy', 'Relations')}: {graph.edges.filter(e => e.from === selected.id || e.to === selected.id).map(e => e.relation).join(', ') || '—'}</p>
        </> : <p className={styles.emptyEvidence}>{t('Vyberte uzel a zobrazte jeho podklad a vztahy.', 'Select a node to inspect its evidence and relationships.')}</p>}
        <p className={styles.disclaimer}>{t(
          'Viditelnost detailu vynucuje každá zdrojová služba z role přihlášeného uživatele. Graf neprokazuje vlastnictví ani podezřelé jednání.',
          'Each source service enforces detail visibility from the signed-in user role. The graph does not by itself prove ownership or suspicious activity.',
        )}</p>
      </aside>
    </div>
  </section>
}
