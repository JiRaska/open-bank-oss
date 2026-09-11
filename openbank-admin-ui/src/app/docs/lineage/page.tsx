// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect, useCallback, useRef } from 'react'
import { Network, RefreshCw, Play, Pause, ArrowRight, ArrowLeft, BookOpen } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { edgeGeometry, pathId } from '@/components/topology/geometry'
import { FlowParticle } from '@/components/topology/FlowParticle'
import { useFlowAnimation } from '@/components/topology/useFlowAnimation'
import { NodeShadow, ArrowMarker } from '@/components/topology/TopologyDefs'
import { layoutBands } from '@/components/topology/layout'
import {
  parseGovernanceLineage,
  type GovernanceLineageService as GovService,
  type LineageDomain as Domain,
  type LineageRelation as Rel,
  type LineageRole as Role,
} from '@/lib/governance/lineage-evidence'

// ---------------------------------------------------------------------------
// Data-lineage flow (ADR-0071). Companion to the service map, but the graph here
// is the DERIVED data-governance lineage: each service declares its upstream /
// downstream relationships (typed api / topic / datastore) in its governance.yaml,
// aggregated into governance.json and served read-only at /api/catalog/governance.
// Nodes = services grouped by data domain; edges = declared lineage, animated as
// data flowing producer → consumer. Honest-by-construction (code-derived), like
// the service map — NOT hand-authored.
// ---------------------------------------------------------------------------

const DOMAIN_META: Record<Domain, { color: string; cs: string; en: string }> = {
  core:           { color: 'var(--map-core)', cs: 'Jádro',         en: 'Core' },
  payments:       { color: 'var(--map-payment)', cs: 'Platby',        en: 'Payments' },
  compliance:     { color: 'var(--map-compliance)', cs: 'Compliance',    en: 'Compliance' },
  identity:       { color: 'var(--map-identity)', cs: 'Identita',      en: 'Identity' },
  'open-banking': { color: 'var(--map-psd2)', cs: 'Open Banking',  en: 'Open Banking' },
  platform:       { color: 'var(--map-platform)', cs: 'Platforma',     en: 'Platform' },
}
const DOMAIN_ORDER: Domain[] = ['core', 'payments', 'compliance', 'identity', 'open-banking', 'platform']

const REL_COLOR: Record<Rel, string> = { api: 'var(--map-core)', topic: 'var(--map-payment)', datastore: 'var(--map-identity)', unknown: 'var(--map-platform)' }
const REL_DASHED: Record<Rel, boolean> = { api: false, topic: true, datastore: false, unknown: false }
const relLabel = (r: Rel, t: (cs: string, en: string) => string) => ({ api: 'API', topic: t('téma', 'topic'), datastore: t('úložiště', 'datastore'), unknown: t('jiné', 'other') }[r])
const roleLabel = (r: Role | null, t: (cs: string, en: string) => string) =>
  r === 'producer' ? t('producent', 'producer') : r === 'consumer' ? t('konzument', 'consumer') : r === 'both' ? t('oba', 'both') : r === 'internal' ? t('interní', 'internal') : '—'

const prettyName = (s: string) => s.replace(/-service$/, '')

// Band layout — stacked full-width domain bands, centred wrapped pill grid.
const WIDTH = 1280
const CANVAS_PAD = 40
const PILL_H = 30
const BAND_HEAD = 28
const BAND_PAD_Y = 14
const BAND_GAP = 22
const PILL_GAP = 14
const HALF_H = PILL_H / 2
const pillWidth = (label: string) => Math.max(96, 22 + label.length * 6.6 + 14)

const BAND_CFG = { width: WIDTH, pad: CANVAS_PAD, pillH: PILL_H, bandHead: BAND_HEAD, bandPadY: BAND_PAD_Y, bandGap: BAND_GAP, pillGap: PILL_GAP, measure: pillWidth }

export default function LineageFlowPage() {
  const { t } = useLanguage()
  const [services, setServices] = useState<GovService[]>([])
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [hovered, setHovered] = useState<string | null>(null)
  const [relFilter, setRelFilter] = useState<'all' | Rel>('all')
  const [domFilter, setDomFilter] = useState<'all' | Domain>('all')
  const [flow, setFlow] = useFlowAnimation()
  const [isChecking, setIsChecking] = useState(true)
  const [hasVerifiedSnapshot, setHasVerifiedSnapshot] = useState(false)
  const requestRef = useRef(0)

  const load = useCallback(async () => {
    const request = ++requestRef.current
    setIsChecking(true)
    setUnavailable(null)
    try {
      const res = await fetch('/api/catalog/governance', { cache: 'no-store', signal: AbortSignal.timeout(8_000) })
      if (request !== requestRef.current) return
      if (!res.ok) {
        if (res.status === 401 || res.status === 403) {
          setServices([])
          setSelected(null)
          setHasVerifiedSnapshot(false)
        }
        setUnavailable({ kind: res.status === 401 || res.status === 403 ? 'unauthorized' : res.status === 404 ? 'not_deployed' : 'unreachable' })
        return
      }
      const evidence = parseGovernanceLineage(await res.json())
      if (request !== requestRef.current) return
      if (!evidence) { setUnavailable({ kind: 'error' }); return }
      if (!evidence.available) { setUnavailable({ kind: 'not_deployed' }); return }
      setServices(evidence.services)
      setSelected(current => current && evidence.services.some(service => service.serviceName === current) ? current : null)
      setHasVerifiedSnapshot(true)
      if (!evidence.services.length) setUnavailable({ kind: 'no_data' })
    } catch {
      if (request === requestRef.current) setUnavailable({ kind: 'unreachable' })
    } finally {
      if (request === requestRef.current) setIsChecking(false)
    }
  }, [])
  useEffect(() => {
    const initialLoad = window.setTimeout(() => { void load() }, 0)
    return () => {
      window.clearTimeout(initialLoad)
      requestRef.current += 1
    }
  }, [load])

  const byId = Object.fromEntries(services.map(s => [s.serviceName, s]))
  const known = new Set(services.map(s => s.serviceName))
  const activeNode = selected ?? hovered

  // Directed lineage edges = union of every service's declared upstream/downstream,
  // deduped. Both endpoints must be known services (no half-anchored lines).
  const edgeMap = new Map<string, { from: string; to: string; type: Rel }>()
  for (const s of services) {
    for (const d of s.lineage?.downstream ?? []) {
      if (known.has(d.serviceName)) edgeMap.set(`${s.serviceName}|${d.serviceName}|${d.relationType}`, { from: s.serviceName, to: d.serviceName, type: d.relationType })
    }
    for (const u of s.lineage?.upstream ?? []) {
      if (known.has(u.serviceName)) edgeMap.set(`${u.serviceName}|${s.serviceName}|${u.relationType}`, { from: u.serviceName, to: s.serviceName, type: u.relationType })
    }
  }
  const allEdges = [...edgeMap.values()]

  const domainOf = (s: GovService): Domain => (s.dataDomain && DOMAIN_META[s.dataDomain] ? s.dataDomain : 'platform')
  const visible = services.filter(s => domFilter === 'all' || domainOf(s) === domFilter)
  const visibleIds = new Set(visible.map(s => s.serviceName))
  const byDomain: Record<string, GovService[]> = {}
  for (const s of visible) (byDomain[domainOf(s)] ||= []).push(s)
  const LAYOUT = layoutBands(
    DOMAIN_ORDER.map(dom => ({ key: dom, items: (byDomain[dom] ?? []).map(s => ({ id: s.serviceName, label: prettyName(s.serviceName) })) })),
    BAND_CFG,
  )

  const visibleEdges = allEdges.filter(e =>
    visibleIds.has(e.from) && visibleIds.has(e.to) && (relFilter === 'all' || e.type === relFilter))
  const isNeighbor = (id: string) => !!activeNode && (activeNode === id
    || allEdges.some(e => (e.from === activeNode && e.to === id) || (e.to === activeNode && e.from === id)))

  const selectedSvc = selected ? byId[selected] : null
  const selectedEdges = selected ? allEdges.filter(e => e.from === selected || e.to === selected) : []
  const domLabel = (d: Domain) => t(DOMAIN_META[d].cs, DOMAIN_META[d].en)

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px' }}>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Datová lineage', 'Data Lineage')}</span>
          </>}
        title={t('Tok datové lineage', 'Data Lineage Flow')}
        subtitle={t('Kdo produkuje data pro koho — deklarovaná governance lineage (ADR-0071), odvozená z governance.yaml každé služby, animovaná jako tok producent → konzument.',
               'Who produces data for whom — the declared data-governance lineage (ADR-0071), derived from each service’s governance.yaml, animated as producer → consumer flow.')}
        icon={<Network aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
      />

      {isChecking && !hasVerifiedSnapshot && !unavailable ? (
        <div className="card" role="status" aria-live="polite" data-testid="lineage-loading" style={{ padding: 40, textAlign: 'center', color: 'var(--text-tertiary)' }}>
          <RefreshCw aria-hidden="true" size={15} className="animate-spin" />{' '}{t('Načítám ověřenou datovou lineage…', 'Loading verified data lineage…')}
        </div>
      ) : unavailable && !services.length ? (
        <DataUnavailable kind={unavailable.kind} service="governance" feature={t('datová lineage', 'data lineage')} dense />
      ) : (
        <>
          {unavailable ? (
            <div className="card" style={{ padding: 0, marginBottom: 16 }}>
              <DataUnavailable
                kind={unavailable.kind}
                service="governance"
                feature={t('Aktualizace datové lineage', 'Data lineage refresh')}
                detail={t('Zobrazuje se poslední ověřená mapa; novější vztahy mohou chybět.', 'Showing the last verified map; newer relationships may be missing.')}
                dense
              />
            </div>
          ) : null}
          {/* Controls */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: '12px' }}>
            <div role="group" aria-label={t('Filtrování domén', 'Domain filters')} style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
              {(['all', ...DOMAIN_ORDER] as const).map(key => (
                <button key={key} onClick={() => setDomFilter(key)}
                  type="button" aria-pressed={domFilter === key}
                  style={{
                    padding: '5px 12px', fontSize: '12px', fontWeight: 600, borderRadius: '20px',
                    border: `1px solid ${domFilter === key ? 'var(--accent)' : 'var(--border)'}`,
                    color: domFilter === key ? 'var(--accent-text)' : 'var(--text-secondary)', cursor: 'pointer', fontFamily: 'inherit',
                    background: domFilter === key ? 'var(--accent-bg)' : 'var(--surface)',
                  }}>
                  {key === 'all' ? t('Vše', 'All') : domLabel(key)}
                </button>
              ))}
            </div>
            <div style={{ display: 'flex', gap: '6px', alignItems: 'center', flexWrap: 'wrap' }}>
              <div role="group" aria-label={t('Filtrování vztahů', 'Relationship filters')} style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                {(['all', 'api', 'topic', 'datastore'] as const).map(r => (
                <button key={r} onClick={() => setRelFilter(r)} aria-pressed={relFilter === r}
                  type="button"
                  style={{
                    padding: '4px 10px', fontSize: '11px', fontWeight: 600, borderRadius: '20px', cursor: 'pointer', fontFamily: 'inherit',
                    border: `1px solid ${relFilter === r ? (r === 'all' ? 'var(--accent)' : REL_COLOR[r as Rel]) : 'var(--border)'}`,
                    background: relFilter === r ? `color-mix(in srgb, ${r === 'all' ? 'var(--accent)' : REL_COLOR[r as Rel]} 14%, var(--surface))` : 'var(--surface)',
                    color: relFilter === r ? (r === 'all' ? 'var(--accent-text)' : REL_COLOR[r as Rel]) : 'var(--text-secondary)',
                  }}>
                  {r === 'all' ? t('Vše', 'All') : relLabel(r as Rel, t)}
                </button>
                ))}
              </div>
              <button onClick={() => setFlow(v => !v)} aria-pressed={flow} title={t('Přepnout tok dat', 'Toggle data flow')}
                type="button" aria-label={flow ? t('Pozastavit tok dat', 'Pause data flow') : t('Spustit tok dat', 'Play data flow')}
                style={{
                  display: 'flex', alignItems: 'center', gap: '5px', padding: '5px 10px', fontSize: '12px', fontWeight: 600,
                  borderRadius: '20px', cursor: 'pointer', fontFamily: 'inherit',
                  border: `1px solid ${flow ? 'var(--accent)' : 'var(--border)'}`,
                  background: flow ? 'var(--accent-bg)' : 'var(--surface)', color: flow ? 'var(--accent-text)' : 'var(--text-secondary)',
                }}>
                {flow ? <Pause aria-hidden="true" size={13} /> : <Play aria-hidden="true" size={13} />}{t('Tok', 'Flow')}
              </button>
              <button onClick={load} disabled={isChecking} type="button" aria-busy={isChecking} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
                <RefreshCw aria-hidden="true" size={14} className={isChecking ? 'animate-spin' : ''} />
                {isChecking ? t('Načítám…', 'Loading...') : t('Obnovit', 'Refresh')}
              </button>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: selectedSvc ? '1fr 320px' : '1fr', gap: '16px' }}>
            <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
              <svg viewBox={`0 0 ${WIDTH} ${LAYOUT.height}`} style={{ width: '100%', height: 'auto', display: 'block', background: 'var(--surface)' }}>
                <defs>
                  {(['api', 'topic', 'datastore', 'unknown'] as Rel[]).map(r => (
                    <ArrowMarker key={r} id={`ln-arrow-${r}`} color={REL_COLOR[r]} />
                  ))}
                  <NodeShadow id="ln-shadow" />
                </defs>

                {/* Domain bands */}
                {LAYOUT.bands.map(b => (
                  <g key={b.key}>
                    <rect x={CANVAS_PAD - 12} y={b.y} width={WIDTH - (CANVAS_PAD - 12) * 2} height={b.h} rx="16"
                      fill={`color-mix(in srgb, ${DOMAIN_META[b.key].color} 4%, transparent)`} stroke={`color-mix(in srgb, ${DOMAIN_META[b.key].color} 24%, var(--border))`} strokeWidth="1" />
                    <text x={CANVAS_PAD} y={b.y + 19} fontSize="11" fill={DOMAIN_META[b.key].color} fontWeight="700" letterSpacing="0.08em">
                      {domLabel(b.key).toUpperCase()} · {b.count}
                    </text>
                  </g>
                ))}

                {/* Edges + particles */}
                {visibleEdges.map((e, i) => {
                  const a = LAYOUT.pos[e.from], b = LAYOUT.pos[e.to]
                  if (!a || !b) return null
                  const color = REL_COLOR[e.type]
                  const touches = !!activeNode && (e.from === activeNode || e.to === activeNode)
                  const dim = !!activeNode && !touches
                  const { d } = edgeGeometry(a, b, { hw: a.w / 2, hh: HALF_H }, { hw: b.w / 2, hh: HALF_H })
                  const pid = pathId('ln', e.from, e.to, i)
                  return (
                    <g key={i} opacity={dim ? 0.08 : touches ? 1 : 0.42} style={{ transition: 'opacity 0.15s' }}>
                      <path id={pid} d={d} fill="none" stroke={touches ? `color-mix(in srgb, ${color} 85%, var(--text-primary))` : color}
                        strokeWidth={touches ? 2 : 1.2} strokeDasharray={REL_DASHED[e.type] ? '5,4' : undefined}
                        markerEnd={`url(#ln-arrow-${e.type})`} />
                      {flow && <FlowParticle pathId={pid} color={color} dur={2.6 + (i % 6) * 0.24} begin={(i % 8) * 0.16} r={2.3} />}
                    </g>
                  )
                })}

                {/* Nodes */}
                {visible.map(s => {
                  const p = LAYOUT.pos[s.serviceName]
                  if (!p) return null
                  const color = DOMAIN_META[domainOf(s)].color
                  const emphasized = !activeNode || isNeighbor(s.serviceName)
                  const isSel = selected === s.serviceName
                  const x = p.cx - p.w / 2, y = p.cy - PILL_H / 2
                  return (
                    <g key={s.serviceName} style={{ cursor: 'pointer', transition: 'opacity 0.15s' }} opacity={emphasized ? 1 : 0.22}
                      onClick={() => setSelected(v => v === s.serviceName ? null : s.serviceName)}
                      onMouseEnter={() => setHovered(s.serviceName)} onMouseLeave={() => setHovered(h => h === s.serviceName ? null : h)}>
                      <rect x={x} y={y} width={p.w} height={PILL_H} rx={PILL_H / 2}
                        fill={isSel ? `color-mix(in srgb, ${color} 16%, var(--surface))` : 'var(--surface)'} stroke={color} strokeWidth={isSel ? 1.9 : 1.2} filter="url(#ln-shadow)" />
                      <circle cx={x + 13} cy={p.cy} r={3.5} fill={color} />
                      <text x={x + 23} y={p.cy + 4} fontSize="10.5" fontWeight="600" fill="var(--text-primary)">{prettyName(s.serviceName)}</text>
                    </g>
                  )
                })}
              </svg>

              {/* Legend */}
              <div style={{ padding: '12px 16px', borderTop: '1px solid var(--border)', display: 'flex', gap: '18px', flexWrap: 'wrap' }}>
                {(['api', 'topic', 'datastore'] as Rel[]).map(r => (
                  <div key={r} style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                    <svg width="30" height="10"><line x1="0" y1="5" x2="30" y2="5" stroke={REL_COLOR[r]} strokeWidth="1.6" strokeDasharray={REL_DASHED[r] ? '5,3' : undefined} markerEnd={`url(#ln-arrow-${r})`} /></svg>
                    {relLabel(r, t)}
                  </div>
                ))}
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Šipka míří producent → konzument', 'Arrow points producer → consumer')}</div>
              </div>
            </div>

            {/* Detail panel */}
            {selectedSvc && (
              <div className="card" style={{ padding: '20px', alignSelf: 'start' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '14px' }}>
                  <div style={{ width: '12px', height: '12px', borderRadius: '50%', background: DOMAIN_META[domainOf(selectedSvc)].color }} />
                  <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{prettyName(selectedSvc.serviceName)}</div>
                  <div style={{ marginLeft: 'auto', fontSize: '10px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', padding: '2px 8px', borderRadius: '12px', background: `color-mix(in srgb, ${DOMAIN_META[domainOf(selectedSvc)].color} 12%, transparent)`, color: 'var(--text-primary)' }}>
                    {roleLabel(selectedSvc.dataLineageRole, t)}
                  </div>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  <div>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('DOMÉNA', 'DOMAIN')}</div>
                    <div style={{ fontSize: '12px', color: DOMAIN_META[domainOf(selectedSvc)].color, fontWeight: 600 }}>{domLabel(domainOf(selectedSvc))}</div>
                  </div>
                  <div>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '4px' }}>{t('ROZHRANÍ', 'INTERFACES')}</div>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                      {(selectedSvc.lineage?.interfaces?.apis ?? []).map((a, i) => <span key={`a${i}`} style={{ fontSize: '10px', background: 'var(--info-bg)', color: 'var(--info-text)', padding: '2px 6px', borderRadius: '4px' }}>API: {a}</span>)}
                      {(selectedSvc.lineage?.interfaces?.topics ?? []).map((tp, i) => <span key={`t${i}`} style={{ fontSize: '10px', background: 'var(--accent-bg)', color: 'var(--accent-text)', padding: '2px 6px', borderRadius: '4px' }}>{t('téma', 'topic')}: {tp}</span>)}
                      {(selectedSvc.lineage?.interfaces?.datastores ?? []).map((ds, i) => <span key={`d${i}`} style={{ fontSize: '10px', background: 'var(--success-bg)', color: 'var(--success-text)', padding: '2px 6px', borderRadius: '4px' }}>DB: {ds}</span>)}
                      {!(selectedSvc.lineage?.interfaces?.apis?.length || selectedSvc.lineage?.interfaces?.topics?.length || selectedSvc.lineage?.interfaces?.datastores?.length) && (
                        <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('žádná deklarovaná', 'none declared')}</span>
                      )}
                    </div>
                  </div>
                  {(selectedSvc.lineage?.upstream?.length ?? 0) > 0 && (
                    <div>
                      <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginBottom: '4px', display: 'flex', alignItems: 'center', gap: '4px' }}><ArrowLeft size={10} /> {t('Upstream (zdroje)', 'Upstream (sources)')}</div>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                        {selectedSvc.lineage!.upstream!.map((u, i) => (
                          <div key={i} style={{ display: 'flex', justifyContent: 'space-between', gap: '6px', fontSize: '12px', background: 'var(--surface)', padding: '4px 8px', borderRadius: '4px', border: '1px solid var(--border)' }}>
                            <button type="button" disabled={!known.has(u.serviceName)} aria-label={known.has(u.serviceName)
                              ? t(`Vybrat ${prettyName(u.serviceName)}`, `Select ${prettyName(u.serviceName)}`)
                              : t(`${prettyName(u.serviceName)} není načtená`, `${prettyName(u.serviceName)} is not loaded`)}
                              style={{ fontWeight: 500, color: 'var(--text-secondary)', cursor: known.has(u.serviceName) ? 'pointer' : 'default', opacity: known.has(u.serviceName) ? 1 : 0.7, background: 'none', border: 0, padding: 0, font: 'inherit', textAlign: 'left' }}
                              onClick={() => known.has(u.serviceName) && setSelected(u.serviceName)}>{prettyName(u.serviceName)}</button>
                            <span style={{ fontSize: '10px', color: REL_COLOR[u.relationType] }}>{relLabel(u.relationType, t)}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                  {(selectedSvc.lineage?.downstream?.length ?? 0) > 0 && (
                    <div>
                      <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginBottom: '4px', display: 'flex', alignItems: 'center', gap: '4px' }}><ArrowRight size={10} /> {t('Downstream (odběratelé)', 'Downstream (consumers)')}</div>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                        {selectedSvc.lineage!.downstream!.map((dn, i) => (
                          <div key={i} style={{ display: 'flex', justifyContent: 'space-between', gap: '6px', fontSize: '12px', background: 'var(--surface)', padding: '4px 8px', borderRadius: '4px', border: '1px solid var(--border)' }}>
                            <button type="button" disabled={!known.has(dn.serviceName)} aria-label={known.has(dn.serviceName)
                              ? t(`Vybrat ${prettyName(dn.serviceName)}`, `Select ${prettyName(dn.serviceName)}`)
                              : t(`${prettyName(dn.serviceName)} není načtená`, `${prettyName(dn.serviceName)} is not loaded`)}
                              style={{ fontWeight: 500, color: 'var(--text-secondary)', cursor: known.has(dn.serviceName) ? 'pointer' : 'default', opacity: known.has(dn.serviceName) ? 1 : 0.7, background: 'none', border: 0, padding: 0, font: 'inherit', textAlign: 'left' }}
                              onClick={() => known.has(dn.serviceName) && setSelected(dn.serviceName)}>{prettyName(dn.serviceName)}</button>
                            <span style={{ fontSize: '10px', color: REL_COLOR[dn.relationType] }}>{relLabel(dn.relationType, t)}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Propojení', 'Connections')}: {selectedEdges.length}</div>
                  <a href={`/services/${prettyName(selectedSvc.serviceName)}/docs`} style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px', padding: '8px 12px',
                    background: 'var(--accent-bg)', color: 'var(--accent-text)', borderRadius: 'var(--r-md)', fontSize: '12px',
                    fontWeight: 600, textDecoration: 'none', marginTop: '4px', border: '1px solid var(--accent-border)',
                  }}>
                    <BookOpen size={14} /> {t('Dokumentace služby', 'Service documentation')}
                  </a>
                </div>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}
