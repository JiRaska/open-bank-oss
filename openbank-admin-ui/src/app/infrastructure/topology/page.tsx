// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect } from 'react'
import {
  Network, RefreshCw, Play, Pause,
  Cloud, GitBranch, Database, Eye, Server, X,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { edgeGeometry, mixHex, pathId } from '@/components/topology/geometry'
import { FlowParticle } from '@/components/topology/FlowParticle'
import { useFlowAnimation } from '@/components/topology/useFlowAnimation'
import { NodeShadow, ArrowMarker } from '@/components/topology/TopologyDefs'
import { layoutBands } from '@/components/topology/layout'
import { PageHeader, StatusBadge, statusTone } from '@/components/ui'

// ---------------------------------------------------------------------------
// Infrastructure topology (ADR-0027/0029). A companion to the code-derived
// service map (/docs/service-map): the SAME animated engine, but here the NODES
// are the real platform components (with LIVE status from /api/infra/status) and
// the EDGES are the *documented platform architecture* — how the pieces wire
// together (ArgoCD deploys, CNPG backs Postgres up to S3, External Secrets pulls
// from OpenBao, Karpenter provisions nodes, observability scrapes, Istio mesh,
// ingress routes). That backbone is not machine-declared anywhere (operators live
// in Terraform helm_release, gitops only has app→namespace + operator→workload),
// so — like the existing cloud-architecture / observability-stack pages — the
// edges are hand-authored but true & verifiable, NOT a derived-data claim.
// ---------------------------------------------------------------------------

type Status = 'UP' | 'DOWN' | 'UNKNOWN'
type GroupKey = 'aws' | 'platform' | 'data' | 'observability'
type EdgeType =
  | 'deploys' | 'provisions' | 'admits' | 'issues'
  | 'manages' | 'uses' | 'backs-up' | 'secrets' | 'pulls' | 'routes' | 'auth'
  | 'scales' | 'scrapes' | 'queries' | 'alerts' | 'mesh'

type NodeDef = { id: string; group: GroupKey; label: string; descCs: string; descEn: string; live: boolean }
type EdgeDef = { from: string; to: string; type: EdgeType }

// Group metadata — colour + bilingual label. Icons render in the detail panel (HTML).
const GROUPS: Record<GroupKey, { color: string; cs: string; en: string; Icon: typeof Cloud }> = {
  aws:           { color: '#f97316', cs: 'AWS substrát',            en: 'AWS substrate',        Icon: Cloud },
  platform:      { color: '#7c3aed', cs: 'Platforma / control plane', en: 'Platform / control plane', Icon: GitBranch },
  data:          { color: '#2563eb', cs: 'Data / backing služby',   en: 'Data / backing services', Icon: Database },
  observability: { color: '#0d9488', cs: 'Observabilita',           en: 'Observability',         Icon: Eye },
}
const GROUP_ORDER: GroupKey[] = ['aws', 'platform', 'data', 'observability']

// Nodes. `live` ids match the /api/infra/status contract (badge overlays); the
// rest are architectural-only (AWS substrate, operators, mesh) shown without a badge.
const NODES: NodeDef[] = [
  // AWS substrate
  { id: 'vpc',   group: 'aws', label: 'VPC',            descCs: 'Privátní síť, subnety, routing (ADR-0058).', descEn: 'Private network, subnets, routing (ADR-0058).', live: false },
  { id: 'eks',   group: 'aws', label: 'EKS',            descCs: 'Řízený Kubernetes cluster.', descEn: 'Managed Kubernetes cluster.', live: false },
  { id: 'nodes', group: 'aws', label: 'EC2 uzly',       descCs: 'Spot/arm64 uzly provisionované Karpenterem.', descEn: 'Spot/arm64 nodes provisioned by Karpenter.', live: false },
  { id: 'ecr',   group: 'aws', label: 'ECR',            descCs: 'Registr kontejnerů + pull-through cache.', descEn: 'Container registry + pull-through cache.', live: false },
  { id: 's3',    group: 'aws', label: 'S3',             descCs: 'Objektové úložiště — zálohy WAL, artefakty.', descEn: 'Object store — WAL backups, artifacts.', live: false },
  { id: 'nat',   group: 'aws', label: 'NAT (fck-nat)',  descCs: 'Odchozí egress (fck-nat instance, ADR-0058).', descEn: 'Outbound egress (fck-nat instance, ADR-0058).', live: false },
  // Platform / control plane
  { id: 'argocd',          group: 'platform', label: 'ArgoCD',          descCs: 'GitOps engine (app-of-apps) — nasazuje celou platformu.', descEn: 'GitOps engine (app-of-apps) — deploys the whole platform.', live: true },
  { id: 'argo-rollouts',   group: 'platform', label: 'Argo Rollouts',   descCs: 'Progresivní doručení (canary/blue-green).', descEn: 'Progressive delivery (canary/blue-green).', live: false },
  { id: 'karpenter',       group: 'platform', label: 'Karpenter',       descCs: 'Autoscaler uzlů (Spot/arm64), bin-packing.', descEn: 'Node autoscaler (Spot/arm64), bin-packing.', live: true },
  { id: 'keda',            group: 'platform', label: 'KEDA',            descCs: 'Event-driven scaling / scale-to-zero.', descEn: 'Event-driven scaling / scale-to-zero.', live: true },
  { id: 'kyverno',         group: 'platform', label: 'Kyverno',        descCs: 'Admission policy — ověřuje image attestace.', descEn: 'Admission policy — verifies image attestations.', live: true },
  { id: 'cert-manager',    group: 'platform', label: 'cert-manager',   descCs: 'Životní cyklus TLS certifikátů.', descEn: 'TLS certificate lifecycle.', live: true },
  { id: 'external-secrets',group: 'platform', label: 'External Secrets', descCs: 'Synchronizuje Secrety z OpenBao (ESO).', descEn: 'Syncs Secrets from OpenBao (ESO).', live: false },
  { id: 'ingress-nginx',   group: 'platform', label: 'ingress-nginx',  descCs: 'Vstupní routing HTTP(S) do clusteru.', descEn: 'HTTP(S) ingress routing into the cluster.', live: false },
  { id: 'istio',           group: 'platform', label: 'Istio',          descCs: 'Service mesh — mTLS STRICT mezi službami.', descEn: 'Service mesh — STRICT mTLS between services.', live: false },
  // Data / backing services
  { id: 'cnpg',            group: 'data', label: 'CloudNativePG',      descCs: 'Postgres operátor — spravuje DB clustery.', descEn: 'Postgres operator — manages DB clusters.', live: false },
  { id: 'postgres',        group: 'data', label: 'PostgreSQL',         descCs: 'Perzistentní stav služeb (per-service DB).', descEn: 'Per-service persistent state.', live: true },
  { id: 'strimzi',         group: 'data', label: 'Strimzi',           descCs: 'Kafka operátor (KRaft).', descEn: 'Kafka operator (KRaft).', live: false },
  { id: 'kafka',           group: 'data', label: 'Apache Kafka',      descCs: 'Asynchronní páteř událostí.', descEn: 'Async event backbone.', live: true },
  { id: 'schema-registry', group: 'data', label: 'Schema Registry',   descCs: 'Schémata událostí (Avro), kompatibilita.', descEn: 'Event schemas (Avro), compatibility.', live: true },
  { id: 'openbao',         group: 'data', label: 'OpenBao',           descCs: 'Trezor tajemství (Vault-kompatibilní).', descEn: 'Secrets vault (Vault-compatible).', live: true },
  { id: 'valkey',          group: 'data', label: 'Valkey',            descCs: 'In-memory cache (Redis-kompatibilní).', descEn: 'In-memory cache (Redis-compatible).', live: true },
  { id: 'keycloak',        group: 'data', label: 'Keycloak',          descCs: 'Vydavatel identit a tokenů (OIDC).', descEn: 'Identity & token issuer (OIDC).', live: true },
  { id: 'temporal',        group: 'data', label: 'Temporal',          descCs: 'Orchestrace workflow (durable).', descEn: 'Durable workflow orchestration.', live: true },
  { id: 'opa',             group: 'data', label: 'OPA',               descCs: 'Rozhodovací bod autorizace (rego).', descEn: 'Authorization decision point (rego).', live: false },
  // Observability
  { id: 'prometheus',   group: 'observability', label: 'Prometheus',    descCs: 'Metriky (time-series), pravidla, alerty.', descEn: 'Metrics (time-series), rules, alerts.', live: true },
  { id: 'grafana',      group: 'observability', label: 'Grafana',       descCs: 'Dashboardy nad metrikami/logy/trace.', descEn: 'Dashboards over metrics/logs/traces.', live: true },
  { id: 'loki',         group: 'observability', label: 'Loki',          descCs: 'Agregace logů.', descEn: 'Log aggregation.', live: true },
  { id: 'tempo',        group: 'observability', label: 'Tempo',         descCs: 'Distribuované trasování.', descEn: 'Distributed tracing.', live: true },
  { id: 'pyroscope',    group: 'observability', label: 'Pyroscope',     descCs: 'Kontinuální profilování.', descEn: 'Continuous profiling.', live: true },
  { id: 'alloy',        group: 'observability', label: 'Grafana Alloy',  descCs: 'Sběrný agent (metriky/logy) → backend.', descEn: 'Collector agent (metrics/logs) → backend.', live: true },
  { id: 'otel-collector', group: 'observability', label: 'OTel Collector', descCs: 'OTLP ingest — trace/metriky.', descEn: 'OTLP ingest — traces/metrics.', live: true },
  { id: 'alertmanager', group: 'observability', label: 'Alertmanager',  descCs: 'Směrování a deduplikace alertů.', descEn: 'Alert routing and dedup.', live: true },
  { id: 'pyrra',        group: 'observability', label: 'Pyrra (SLO)',   descCs: 'SLO / error budgety nad Prometheem.', descEn: 'SLO / error budgets over Prometheus.', live: true },
  { id: 'glitchtip',    group: 'observability', label: 'GlitchTip',     descCs: 'Sledování chyb (Sentry API).', descEn: 'Error tracking (Sentry API).', live: true },
  { id: 'goalert',      group: 'observability', label: 'GoAlert',       descCs: 'On-call eskalace.', descEn: 'On-call escalation.', live: true },
  { id: 'ntfy',         group: 'observability', label: 'ntfy',          descCs: 'Push notifikace alertů.', descEn: 'Alert push notifications.', live: true },
  { id: 'kafka-ui',     group: 'observability', label: 'Kafka UI',      descCs: 'Inspekce Kafka topiců.', descEn: 'Kafka topic inspection.', live: true },
]

// Curated architectural backbone — every edge is real & verifiable in gitops/TF.
const EDGES: EdgeDef[] = [
  // AWS substrate wiring
  { from: 'vpc', to: 'eks', type: 'uses' },
  { from: 'karpenter', to: 'nodes', type: 'provisions' },
  { from: 'nodes', to: 'eks', type: 'uses' },
  { from: 'eks', to: 'ecr', type: 'pulls' },
  { from: 'nat', to: 'vpc', type: 'routes' },
  // ArgoCD deploys the platform (representative set)
  { from: 'argocd', to: 'cnpg', type: 'deploys' },
  { from: 'argocd', to: 'strimzi', type: 'deploys' },
  { from: 'argocd', to: 'keda', type: 'deploys' },
  { from: 'argocd', to: 'kyverno', type: 'deploys' },
  { from: 'argocd', to: 'cert-manager', type: 'deploys' },
  { from: 'argocd', to: 'external-secrets', type: 'deploys' },
  { from: 'argocd', to: 'argo-rollouts', type: 'deploys' },
  { from: 'argocd', to: 'ingress-nginx', type: 'deploys' },
  { from: 'argocd', to: 'prometheus', type: 'deploys' },
  { from: 'argocd', to: 'grafana', type: 'deploys' },
  // Operators → managed workloads
  { from: 'cnpg', to: 'postgres', type: 'manages' },
  { from: 'strimzi', to: 'kafka', type: 'manages' },
  { from: 'postgres', to: 's3', type: 'backs-up' },
  { from: 'external-secrets', to: 'openbao', type: 'secrets' },
  { from: 'keda', to: 'kafka', type: 'scales' }, // KEDA scales workloads ON Kafka consumer-lag (see label)
  { from: 'kyverno', to: 'eks', type: 'admits' },
  { from: 'cert-manager', to: 'ingress-nginx', type: 'issues' },
  { from: 'cert-manager', to: 'istio', type: 'issues' },
  { from: 'ingress-nginx', to: 'keycloak', type: 'routes' },
  { from: 'ingress-nginx', to: 'grafana', type: 'routes' },
  // Backing-service usage
  { from: 'keycloak', to: 'postgres', type: 'uses' },
  { from: 'temporal', to: 'postgres', type: 'uses' },
  { from: 'schema-registry', to: 'kafka', type: 'uses' },
  { from: 'kafka-ui', to: 'kafka', type: 'uses' },
  { from: 'opa', to: 'keycloak', type: 'auth' }, // OPA fetches JWKS to validate Keycloak-issued JWTs
  // Observability pipeline
  { from: 'alloy', to: 'prometheus', type: 'scrapes' },
  { from: 'alloy', to: 'loki', type: 'scrapes' },
  { from: 'otel-collector', to: 'tempo', type: 'scrapes' },
  { from: 'otel-collector', to: 'prometheus', type: 'scrapes' },
  { from: 'grafana', to: 'prometheus', type: 'queries' },
  { from: 'grafana', to: 'loki', type: 'queries' },
  { from: 'grafana', to: 'tempo', type: 'queries' },
  { from: 'grafana', to: 'pyroscope', type: 'queries' },
  { from: 'pyrra', to: 'prometheus', type: 'queries' },
  { from: 'prometheus', to: 'alertmanager', type: 'alerts' },
  { from: 'alertmanager', to: 'goalert', type: 'alerts' },
  { from: 'alertmanager', to: 'ntfy', type: 'alerts' },
]

// Edge visual category (3 buckets keep the legend legible) + per-type label.
type EdgeCat = 'control' | 'data' | 'flow'
const EDGE_CAT: Record<EdgeType, EdgeCat> = {
  deploys: 'control', provisions: 'control', admits: 'control', issues: 'control',
  manages: 'data', uses: 'data', 'backs-up': 'data', secrets: 'data', pulls: 'data', routes: 'data', auth: 'data',
  scales: 'flow', scrapes: 'flow', queries: 'flow', alerts: 'flow', mesh: 'flow',
}
const CAT_COLOR: Record<EdgeCat, string> = { control: '#7c3aed', data: '#2563eb', flow: '#0d9488' }
const CAT_DASHED: Record<EdgeCat, boolean> = { control: false, data: false, flow: true }
const edgeTypeLabel = (type: EdgeType, t: (cs: string, en: string) => string): string => ({
  deploys: t('nasazuje', 'deploys'), provisions: t('provisiony', 'provisions'), admits: t('admission', 'admits'),
  issues: t('vydává cert', 'issues cert'), manages: t('spravuje', 'manages'), uses: t('používá', 'uses'),
  'backs-up': t('zálohuje', 'backs up'), secrets: t('tajemství', 'secrets'), pulls: t('stahuje', 'pulls'),
  routes: t('routuje', 'routes'), auth: t('validuje JWT', 'validates JWT'), scales: t('škáluje dle lagu', 'scales on lag'),
  scrapes: t('odesílá', 'ships'), queries: t('dotazuje', 'queries'), alerts: t('alerty', 'alerts'), mesh: t('mesh mTLS', 'mesh mTLS'),
}[type])

// ---------------------------------------------------------------------------
// Layout — full-width bands stacked top→bottom, each a centred wrapped grid of
// pills. Deterministic (NODES is static), so adding a node never overlaps.
// ---------------------------------------------------------------------------
const WIDTH = 1240
const CANVAS_PAD = 40
const PILL_H = 34
const BAND_HEAD = 30
const BAND_PAD_Y = 16
const BAND_GAP = 26
const PILL_GAP = 16
const pillWidth = (label: string) => Math.max(112, 34 + label.length * 7 + 20)

const BAND_CFG = { width: WIDTH, pad: CANVAS_PAD, pillH: PILL_H, bandHead: BAND_HEAD, bandPadY: BAND_PAD_Y, bandGap: BAND_GAP, pillGap: PILL_GAP, measure: pillWidth }
const LAYOUT = layoutBands(
  GROUP_ORDER.map(g => ({ key: g, items: NODES.filter(n => n.group === g).map(n => ({ id: n.id, label: n.label })) })),
  BAND_CFG,
)

type LifeMap = Record<string, { urgency?: string }>

const HALF_H = PILL_H / 2

export default function InfraTopologyPage() {
  const { t } = useLanguage()
  const [statuses, setStatuses] = useState<Record<string, { status: Status; latencyMs: number | null; checkedAt: string | null }>>({})
  const [lifecycle, setLifecycle] = useState<LifeMap>({})
  const [selected, setSelected] = useState<string | null>(null)
  const [hovered, setHovered] = useState<string | null>(null)
  const [filter, setFilter] = useState<'all' | GroupKey>('all')
  const [flow, setFlow] = useFlowAnimation()
  const [isChecking, setIsChecking] = useState(false)

  const load = async () => {
    setIsChecking(true)
    try {
      const [sRes, lRes] = await Promise.all([
        fetch('/api/infra/status', { cache: 'no-store' }),
        fetch('/api/infra/lifecycle', { cache: 'no-store' }),
      ])
      if (sRes.ok) setStatuses(await sRes.json())
      if (lRes.ok) {
        const data = await lRes.json() as { components?: { id: string; urgency?: string }[] }
        const map: LifeMap = {}
        for (const c of data.components ?? []) map[c.id] = { urgency: c.urgency }
        setLifecycle(map)
      }
    } catch {
      // graceful — the diagram renders from static topology; badges stay UNKNOWN
    } finally {
      setIsChecking(false)
    }
  }
  useEffect(() => { load() }, [])

  const nodeMap = Object.fromEntries(NODES.map(n => [n.id, n]))
  const activeNode = selected ?? hovered
  const visibleIds = new Set(NODES.filter(n => filter === 'all' || n.group === filter).map(n => n.id))
  const visibleEdges = EDGES.filter(e => visibleIds.has(e.from) && visibleIds.has(e.to))
  const isNeighbor = (id: string) => !!activeNode && (activeNode === id
    || EDGES.some(e => (e.from === activeNode && e.to === id) || (e.to === activeNode && e.from === id)))

  const selectedNode = selected ? nodeMap[selected] : null
  const statusOf = (id: string): Status | null => (nodeMap[id]?.live ? (statuses[id]?.status ?? 'UNKNOWN') : null)
  const selectedEdges = selected ? EDGES.filter(e => e.from === selected || e.to === selected) : []

  const groupLabel = (g: GroupKey) => t(GROUPS[g].cs, GROUPS[g].en)
  // SVG cannot consume the HTML StatusBadge primitive, but it still derives
  // colour from the same central vocabulary. The CSS tokens preserve dark-mode
  // contrast without inventing a second UP/DOWN/UNKNOWN colour taxonomy here.
  const liveStatusFill = (status: Status) => {
    const tone = statusTone(status)
    if (tone === 'success') return 'var(--success)'
    if (tone === 'danger') return 'var(--danger)'
    return 'var(--text-muted)'
  }

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px' }}>
      <PageHeader
        icon={<Network size={18} aria-hidden="true" />}
        title={t('Topologie infrastruktury', 'Infrastructure Topology')}
        subtitle={t('Jak jsou platformní komponenty zapojené — architektura toku dat s živým stavem. Hrany jsou zdokumentovaná architektura (ne odvozená data); uzly nesou živý stav z prób.',
          'How the platform components are wired — a data-flow architecture with live status. Edges are the documented architecture (not derived data); nodes carry live probe status.')}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span>{t('Infrastruktura', 'Infrastructure')}</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Topologie', 'Topology')}</span></div>}
      />

      {/* Controls */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: '12px' }}>
        <div role="group" aria-label={t('Filtrování vrstev infrastruktury', 'Infrastructure layer filters')} style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
          {(['all', ...GROUP_ORDER] as const).map(key => (
            <button key={key} type="button" onClick={() => setFilter(key)} aria-pressed={filter === key}
              style={{
                padding: '5px 12px', fontSize: '12px', fontWeight: 600, borderRadius: '20px',
                border: `1px solid ${filter === key ? 'var(--accent)' : 'var(--border)'}`,
                background: filter === key ? 'var(--accent)' : 'var(--surface)',
                color: filter === key ? '#fff' : 'var(--text-secondary)', cursor: 'pointer', fontFamily: 'inherit',
              }}>
              {key === 'all' ? t('Vše', 'All') : groupLabel(key)}
            </button>
          ))}
        </div>
        <div style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
          <button type="button" onClick={() => setFlow(v => !v)} aria-pressed={flow} aria-label={t(flow ? 'Pozastavit tok dat' : 'Spustit tok dat', flow ? 'Pause data flow' : 'Start data flow')} title={t('Přepnout tok dat', 'Toggle data flow')}
            style={{
              display: 'flex', alignItems: 'center', gap: '5px', padding: '5px 10px', fontSize: '12px', fontWeight: 600,
              borderRadius: '20px', cursor: 'pointer', fontFamily: 'inherit',
              border: `1px solid ${flow ? 'var(--accent)' : 'var(--border)'}`,
              background: flow ? 'var(--accent)' : 'var(--surface)', color: flow ? '#fff' : 'var(--text-secondary)',
            }}>
            {flow ? <Pause aria-hidden="true" size={13} /> : <Play aria-hidden="true" size={13} />}{t('Tok dat', 'Data flow')}
          </button>
          <button type="button" onClick={load} disabled={isChecking} aria-busy={isChecking} aria-label={t('Obnovit stav infrastruktury', 'Refresh infrastructure status')} className="btn btn-secondary"
            style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
            <RefreshCw aria-hidden="true" size={14} className={isChecking ? 'animate-spin' : ''} />
            {isChecking ? t('Zjišťuji…', 'Checking...') : t('Obnovit stav', 'Refresh Status')}
          </button>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: selectedNode ? '1fr 320px' : '1fr', gap: '16px' }}>
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <svg viewBox={`0 0 ${WIDTH} ${LAYOUT.height}`} style={{ width: '100%', height: 'auto', display: 'block', background: 'var(--surface)' }}>
            <defs>
              {(['control', 'data', 'flow'] as EdgeCat[]).map(cat => (
                <ArrowMarker key={cat} id={`ix-arrow-${cat}`} color={CAT_COLOR[cat]} />
              ))}
              <NodeShadow id="ix-shadow" />
            </defs>

            {/* Group bands */}
            {LAYOUT.bands.filter(b => filter === 'all' || b.key === filter).map(b => {
              const meta = GROUPS[b.key]
              return (
                <g key={b.key}>
                  <rect x={CANVAS_PAD - 14} y={b.y} width={WIDTH - (CANVAS_PAD - 14) * 2} height={b.h} rx="16"
                    fill={`${meta.color}0a`} stroke={`${meta.color}33`} strokeWidth="1" />
                  <text x={CANVAS_PAD} y={b.y + 20} fontSize="11" fill={meta.color} fontWeight="700" letterSpacing="0.08em">
                    {groupLabel(b.key).toUpperCase()}
                  </text>
                </g>
              )
            })}

            {/* Edges + particles */}
            {visibleEdges.map((e, i) => {
              const a = LAYOUT.pos[e.from], b = LAYOUT.pos[e.to]
              if (!a || !b) return null
              const cat = EDGE_CAT[e.type]
              const color = CAT_COLOR[cat]
              const touches = !!activeNode && (e.from === activeNode || e.to === activeNode)
              const dim = !!activeNode && !touches
              const { d } = edgeGeometry(a, b, { hw: a.w / 2, hh: HALF_H }, { hw: b.w / 2, hh: HALF_H })
              const pid = pathId('ix', e.from, e.to, i)
              return (
                <g key={i} opacity={dim ? 0.1 : touches ? 1 : 0.5} style={{ transition: 'opacity 0.15s' }}>
                  <path id={pid} d={d} fill="none" stroke={touches ? mixHex(color, '#000000', 0.15) : color}
                    strokeWidth={touches ? 2 : 1.3} strokeDasharray={CAT_DASHED[cat] ? '5,4' : undefined}
                    markerEnd={`url(#ix-arrow-${cat})`} />
                  {flow && <FlowParticle pathId={pid} color={color} dur={2.6 + (i % 5) * 0.26} begin={(i % 7) * 0.18} />}
                  {touches && (() => {
                    const lbl = edgeTypeLabel(e.type, t)
                    const { cx: mx, cy: my } = { cx: (a.cx + b.cx) / 2, cy: (a.cy + b.cy) / 2 }
                    return (
                      <g>
                        <rect x={mx - lbl.length * 3.1 - 5} y={my - 9} width={lbl.length * 6.2 + 10} height="15" rx="7.5"
                          fill="var(--surface)" stroke={`${color}55`} strokeWidth="0.75" opacity="0.97" />
                        <text x={mx} y={my + 2} fontSize="9" fill={color} textAnchor="middle" fontWeight="600">{lbl}</text>
                      </g>
                    )
                  })()}
                </g>
              )
            })}

            {/* Nodes */}
            {NODES.filter(n => visibleIds.has(n.id)).map(n => {
              const p = LAYOUT.pos[n.id]
              if (!p) return null
              const meta = GROUPS[n.group]
              const emphasized = !activeNode || isNeighbor(n.id)
              const isSel = selected === n.id
              const st = statusOf(n.id)
              const x = p.cx - p.w / 2, y = p.cy - PILL_H / 2
              return (
                <g key={n.id} role="button" tabIndex={0} aria-label={n.label} aria-pressed={isSel} aria-controls={isSel ? 'infra-topology-selection' : undefined}
                  style={{ cursor: 'pointer', transition: 'opacity 0.15s' }} opacity={emphasized ? 1 : 0.25}
                  onClick={() => setSelected(s => s === n.id ? null : n.id)}
                  onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSelected(s => s === n.id ? null : n.id) } }}
                  onFocus={() => setHovered(n.id)}
                  onMouseEnter={() => setHovered(n.id)} onMouseLeave={() => setHovered(h => h === n.id ? null : h)}>
                  <rect x={x} y={y} width={p.w} height={PILL_H} rx={PILL_H / 2}
                    fill={isSel ? meta.color : 'var(--surface)'} stroke={meta.color} strokeWidth={isSel ? 1.9 : 1.3}
                    filter="url(#ix-shadow)" />
                  <circle cx={x + 15} cy={p.cy} r={4} fill={meta.color} />
                  <text x={x + 26} y={p.cy + 4} fontSize="11" fontWeight="600" fill={isSel ? '#fff' : 'var(--text-primary)'}>{n.label}</text>
                  {st && (
                    <g transform={`translate(${x + p.w - 12}, ${y + 2})`}>
                      <circle cx="0" cy="0" r="6.5" fill={liveStatusFill(st)} stroke="var(--surface)" strokeWidth="1.5" />
                      {st === 'UP' && <path d="M-2.5,0 L-0.8,1.8 L2.6,-2.2" fill="none" stroke="#fff" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />}
                      {st === 'DOWN' && <path d="M-2,-2 L2,2 M-2,2 L2,-2" fill="none" stroke="#fff" strokeWidth="1.4" strokeLinecap="round" />}
                      {st === 'UNKNOWN' && <text x="0" y="2.5" fontSize="8" fill="#fff" textAnchor="middle" fontWeight="bold">?</text>}
                    </g>
                  )}
                </g>
              )
            })}
          </svg>

          {/* Legend */}
          <div style={{ padding: '12px 16px', borderTop: '1px solid var(--border)', display: 'flex', gap: '18px', flexWrap: 'wrap' }}>
            {([['control', t('Řízení (nasazení · admission · cert)', 'Control (deploy · admit · cert)')],
               ['data', t('Data (spravuje · používá · zálohuje)', 'Data (manages · uses · backs up)')],
               ['flow', t('Tok (škálování · telemetrie · alerty)', 'Flow (scale · telemetry · alerts)')]] as [EdgeCat, string][]).map(([cat, lbl]) => (
              <div key={cat} style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                <svg aria-hidden="true" width="30" height="10"><line x1="0" y1="5" x2="30" y2="5" stroke={CAT_COLOR[cat]} strokeWidth="1.6" strokeDasharray={CAT_DASHED[cat] ? '5,3' : undefined} markerEnd={`url(#ix-arrow-${cat})`} /></svg>
                {lbl}
              </div>
            ))}
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
              <span style={{ width: '10px', height: '10px', borderRadius: '50%', background: 'var(--success)' }} />{t('Živý stav (UP/DOWN)', 'Live status (UP/DOWN)')}
            </div>
          </div>
        </div>

        {/* Detail panel */}
        {selectedNode && (
          <div id="infra-topology-selection" className="card" role="region" aria-label={t('Detail infrastrukturní komponenty', 'Infrastructure component details')} style={{ padding: '20px', alignSelf: 'start' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '14px' }}>
              <div style={{ width: '28px', height: '28px', borderRadius: '8px', background: `${GROUPS[selectedNode.group].color}1a`, display: 'flex', alignItems: 'center', justifyContent: 'center', color: GROUPS[selectedNode.group].color }}>
                {(() => { const I = GROUPS[selectedNode.group].Icon; return <I aria-hidden="true" size={16} /> })()}
              </div>
              <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{selectedNode.label}</div>
              <button type="button" aria-label={t('Zavřít detail infrastruktury', 'Close infrastructure details')} onClick={() => setSelected(null)} style={{ marginLeft: 'auto', background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', padding: '2px' }}>
                <X aria-hidden="true" size={16} />
              </button>
              {statusOf(selectedNode.id) && (
                <div style={{ marginLeft: 'auto' }}>
                  <StatusBadge
                    status={statusOf(selectedNode.id)}
                    withDot
                  />
                </div>
              )}
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('VRSTVA', 'LAYER')}</div>
                <div style={{ fontSize: '12px', color: GROUPS[selectedNode.group].color, fontWeight: 600 }}>{groupLabel(selectedNode.group)}</div>
              </div>
              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('POPIS', 'DESCRIPTION')}</div>
                <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>{t(selectedNode.descCs, selectedNode.descEn)}</div>
              </div>
              {selectedNode.live && statuses[selectedNode.id]?.latencyMs != null && (
                <div>
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('LATENCE PRÓBY', 'PROBE LATENCY')}</div>
                  <div style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', color: 'var(--text-primary)' }}>{statuses[selectedNode.id].latencyMs} ms</div>
                </div>
              )}
              {lifecycle[selectedNode.id]?.urgency && lifecycle[selectedNode.id].urgency !== 'ok' && (
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
                  <span style={{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--warning)' }} />
                  <span style={{ color: 'var(--text-secondary)' }}>{t('Životní cyklus', 'Lifecycle')}: {lifecycle[selectedNode.id].urgency}</span>
                </div>
              )}
              {!selectedNode.live && (
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontStyle: 'italic' }}>
                  {t('Architektonický uzel — bez živé próby.', 'Architectural node — no live probe.')}
                </div>
              )}

              <div>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '6px' }}>{t('PROPOJENÍ', 'CONNECTIONS')} ({selectedEdges.length})</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                  {selectedEdges.map((e, i) => {
                    const out = e.from === selectedNode.id
                    const other = nodeMap[out ? e.to : e.from]
                    const color = CAT_COLOR[EDGE_CAT[e.type]]
                    return (
                      <div key={i} style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
                        <span style={{ color: 'var(--text-tertiary)', fontFamily: 'monospace' }}>{out ? '→' : '←'}</span>
                        <span style={{ color: GROUPS[other?.group ?? 'data'].color, fontWeight: 600 }}>{other?.label}</span>
                        <span style={{ marginLeft: 'auto', fontSize: '10px', color, background: `${color}18`, padding: '2px 6px', borderRadius: '4px' }}>{edgeTypeLabel(e.type, t)}</span>
                      </div>
                    )
                  })}
                </div>
              </div>

              <a href="/infrastructure" style={{
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px', padding: '8px 12px',
                background: 'var(--accent-bg)', color: 'var(--accent)', borderRadius: 'var(--r-md)', fontSize: '12px',
                fontWeight: 600, textDecoration: 'none', marginTop: '4px', border: '1px solid var(--accent-border, transparent)',
              }}>
                <Server aria-hidden="true" size={14} /> {t('Otevřít přehled infrastruktury', 'Open infrastructure overview')}
              </a>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
