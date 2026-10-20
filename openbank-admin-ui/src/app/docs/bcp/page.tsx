// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect, useRef } from 'react'
import { ShieldAlert, CheckCircle2, XCircle, Clock, AlertTriangle, RefreshCw, ChevronDown, ChevronRight, Zap, BookOpen } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { aggregateHealthStatuses } from '@/lib/health/workloadHealth'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { PrintDocumentButton } from '@/components/docs/PrintDocumentButton'
import { CatalogDriftBanner } from '@/components/governance/CatalogDriftBanner'
import { ContentSourceNote } from '@/components/docs/ContentSourceNote'
import type { ContentSourceMetadata } from '@/lib/evidence/contentSource'
import {
  bcpCatalogCoverageIsComplete,
  buildBcpRoster,
  type BcpPolicy,
  type BcpCatalogService,
  type BcpServiceGraph,
  type BcpTier,
} from '@/lib/bcp/continuityModel'

type Bilingual = [cs: string, en: string]

const INCIDENTS: {
  severity: string
  label: Bilingual
  color: string
  criteria: Bilingual
  response: Bilingual
  reporting: Bilingual
}[] = [
  { severity: 'P1', label: ['Kritický', 'Critical'], color: 'var(--danger)', criteria: ['Výpadek money-path, obejití autentizace/autorizace, potvrzený únik dat nebo aktivní zneužití.', 'Money-path outage, authentication/authorization bypass, confirmed data exposure, or active exploitation.'], response: ['Do 30 min', 'Within 30 min'], reporting: ['Prohlásit do 15 min od detekce', 'Declare within 15 min of detection'] },
  { severity: 'P2', label: ['Vysoký', 'High'], color: 'var(--warning)', criteria: ['Závažná zranitelnost bez potvrzeného zneužití, výpadek non-money-path service nebo degradace money-path.', 'High-severity vulnerability without confirmed exploitation, non-money-path outage, or degraded money-path service.'], response: ['Do 4 h', 'Within 4 h'], reporting: ['Prohlásit do 30 min od detekce', 'Declare within 30 min of detection'] },
  { severity: 'P3', label: ['Střední', 'Medium'], color: 'var(--accent)', criteria: ['Střední nález nebo degradace bez dopadu na zákazníky.', 'Medium finding or non-customer-facing degradation.'], response: ['Do 24 h', 'Within 24 h'], reporting: ['Prohlásit tentýž pracovní den', 'Declare the same business day'] },
  { severity: 'P4', label: ['Nízký', 'Low'], color: 'var(--text-secondary)', criteria: ['Informativní nález, nízké riziko a bez dopadu na uživatele.', 'Informational, low-severity finding with no user impact.'], response: ['Async — běžný issue', 'Async — regular issue'], reporting: ['Není deklarovaný incident', 'Not a declared incident'] },
]

type HealthStatus = 'healthy' | 'unhealthy' | 'starting' | 'unknown'

interface ServiceHealth {
  [container: string]: HealthStatus
}

const EVIDENCE_FETCH_TIMEOUT_MS = 8_000
const HEALTH_VALUES = new Set(['UP', 'DOWN', 'UNKNOWN'])

type ServiceHealthPayload = {
  services?: { name: string; status: 'UP' | 'DOWN' | 'UNKNOWN' }[]
  byContainer?: Record<string, { status: 'UP' | 'DOWN' | 'UNKNOWN' }>
}

function isServiceHealthPayload(value: unknown): value is ServiceHealthPayload {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const payload = value as Record<string, unknown>
  if ('services' in payload) {
    return Array.isArray(payload.services) && payload.services.length > 0
      && payload.services.every(service => service !== null && typeof service === 'object'
        && typeof (service as { name?: unknown }).name === 'string'
        && (service as { name: string }).name.trim().length > 0
        && typeof (service as { status?: unknown }).status === 'string'
        && HEALTH_VALUES.has((service as { status: string }).status))
  }
  const byContainer = payload.byContainer
  return byContainer !== null && typeof byContainer === 'object' && !Array.isArray(byContainer)
    && Object.keys(byContainer).length > 0
    && Object.entries(byContainer as Record<string, unknown>).every(([container, entry]) => container.trim().length > 0
      && entry !== null && typeof entry === 'object'
      && typeof (entry as { status?: unknown }).status === 'string'
      && HEALTH_VALUES.has((entry as { status: string }).status))
}

export default function BcpPage() {
  const { t, language } = useLanguage()
  const [health, setHealth] = useState<ServiceHealth>({})
  const [tiers, setTiers] = useState<BcpTier[]>([])
  const [catalogServiceNames, setCatalogServiceNames] = useState<string[]>([])
  const [catalogServiceCount, setCatalogServiceCount] = useState(0)
  const [policyServicesMissing, setPolicyServicesMissing] = useState<string[]>([])
  const [unclassifiedServices, setUnclassifiedServices] = useState<string[]>([])
  const [catalogAvailable, setCatalogAvailable] = useState(false)
  const [policyAvailable, setPolicyAvailable] = useState(false)
  const [policy, setPolicy] = useState<BcpPolicy | null>(null)
  const [graphAvailable, setGraphAvailable] = useState(false)
  const [infraAssets, setInfraAssets] = useState<{ id: string; label: string }[]>([])
  const [loading, setLoading] = useState(true)
  const [lastSuccessfulRefresh, setLastSuccessfulRefresh] = useState<Date | null>(null)
  const [healthRefreshState, setHealthRefreshState] = useState<'current' | 'partial' | 'unavailable'>('current')
  const [contentSource, setContentSource] = useState<ContentSourceMetadata | null>(null)
  const [expandedTiers, setExpandedTiers] = useState<Set<number>>(new Set([0, 1, 2]))
  const refreshGeneration = useRef(0)
  const refreshController = useRef<AbortController | null>(null)

  const fetchHealth = async () => {
    const generation = ++refreshGeneration.current
    refreshController.current?.abort()
    const controller = new AbortController()
    refreshController.current = controller
    const timeout = setTimeout(() => controller.abort(), EVIDENCE_FETCH_TIMEOUT_MS)
    setLoading(true)
    try {
      const [svcRes, infraRes, catalogRes, graphRes, policyRes] = await Promise.allSettled([
        fetch('/api/services/health', { cache: 'no-store', signal: controller.signal }).then(res => res.ok ? res.json() : null),
        fetch('/api/infra/status', { cache: 'no-store', signal: controller.signal }).then(res => res.ok ? res.json() : null),
        fetch('/api/catalog/services', { cache: 'no-store', signal: controller.signal }).then(res => res.ok ? res.json() : null),
        fetch('/api/catalog/graph', { cache: 'no-store', signal: controller.signal }).then(res => res.ok ? res.json() : null),
        fetch('/api/bcp/policy', { cache: 'no-store', signal: controller.signal }).then(res => res.ok ? res.json() : null),
      ])
      const toStatus = (s: string): HealthStatus =>
        s === 'UP' ? 'healthy' : s === 'DOWN' ? 'unhealthy' : 'unknown'
      const mapped: ServiceHealth = {}
      // Business services: keyed by the bare deployment name (ADR-0051 discovery),
      // which is exactly what the tier `name` fields now use. Prefer the canonical
      // `services[]` array (the shape the Dashboard consumes); fall back to the
      // legacy `byContainer` map so a future endpoint that drops one shape can't
      // silently blank every tier to UNKNOWN.
      const serviceHealthAvailable = svcRes.status === 'fulfilled' && isServiceHealthPayload(svcRes.value)
      if (serviceHealthAvailable) {
        const data = svcRes.value as ServiceHealthPayload
        if (Array.isArray(data.services)) {
          const statusesByName = new Map<string, ('UP' | 'DOWN' | 'UNKNOWN')[]>()
          for (const service of data.services) {
            const observations = statusesByName.get(service.name) ?? []
            observations.push(service.status === 'UP' || service.status === 'DOWN' ? service.status : 'UNKNOWN')
            statusesByName.set(service.name, observations)
          }
          for (const [name, statuses] of statusesByName) mapped[name] = toStatus(aggregateHealthStatuses(statuses))
        } else if (data.byContainer) {
          for (const [container, entry] of Object.entries(data.byContainer)) {
            mapped[container] = toStatus(entry.status)
          }
        }
      }
      const catalog = catalogRes.status === 'fulfilled' && catalogRes.value
        ? catalogRes.value as { available?: boolean; services?: BcpCatalogService[] }
        : null
      const graph = graphRes.status === 'fulfilled' && graphRes.value
        ? graphRes.value as BcpServiceGraph & { available?: boolean }
        : null
      const policyResponse = policyRes.status === 'fulfilled' && policyRes.value
        ? policyRes.value as { available?: boolean; policy?: BcpPolicy | null }
        : null
      const bcpPolicy = policyResponse?.available === true ? policyResponse.policy ?? null : null
      const hasCatalog = catalog?.available === true && Array.isArray(catalog.services)
      const hasGraph = graph?.available === true
        && Array.isArray(graph.edges) && Array.isArray(graph.infraEdges) && Array.isArray(graph.infraNodes)
      if (generation === refreshGeneration.current) {
        const source = [catalogRes, graphRes, policyRes]
          .find((result): result is PromiseFulfilledResult<any> => result.status === 'fulfilled' && Boolean(result.value?.contentSource))
          ?.value.contentSource as ContentSourceMetadata | undefined
        setContentSource(source ?? null)
        setCatalogAvailable(hasCatalog)
        setPolicyAvailable(bcpPolicy !== null)
        setPolicy(bcpPolicy)
        setGraphAvailable(hasGraph)
        if (hasCatalog) {
          const roster = buildBcpRoster(catalog.services!, hasGraph ? graph : undefined, bcpPolicy)
          setTiers(roster.tiers)
          setCatalogServiceNames(roster.tiers.flatMap(tier => tier.services.map(service => service.name)))
          setCatalogServiceCount(roster.catalogServiceCount)
          setPolicyServicesMissing(roster.policyServicesMissingFromCatalog)
          setUnclassifiedServices(roster.unclassifiedCatalogServices)
        } else {
          setTiers([])
          setCatalogServiceNames([])
          setCatalogServiceCount(0)
          setPolicyServicesMissing([])
          setUnclassifiedServices([])
        }
        setInfraAssets(hasGraph
          ? graph.infraNodes!.filter(node => node.kind === 'infra').map(node => ({ id: node.id, label: node.label }))
          : [])
      }
      // Infra: /api/infra/status is already keyed by the stable infra id
      // (postgres, kafka, keycloak, …) — the same ids the Tier 0/5 entries use.
      const infraHealthAvailable = infraRes.status === 'fulfilled' && infraRes.value !== null
        && typeof infraRes.value === 'object' && !Array.isArray(infraRes.value)
        && Object.keys(infraRes.value as Record<string, unknown>).length > 0
        && Object.values(infraRes.value as Record<string, unknown>).every(entry => entry !== null
          && typeof entry === 'object' && typeof (entry as { status?: unknown }).status === 'string'
          && HEALTH_VALUES.has((entry as { status: string }).status))
      if (infraHealthAvailable) {
        const data = infraRes.value as Record<string, { status: string }>
        for (const [id, entry] of Object.entries(data)) {
          mapped[id] = toStatus(entry.status)
        }
      }
      if (generation === refreshGeneration.current) {
        setHealth(mapped)
        const discoveryComplete = serviceHealthAvailable
          && (svcRes.value as ServiceHealthPayload & { discoveryStatus?: unknown }).discoveryStatus === 'complete'
        const complete = discoveryComplete && infraHealthAvailable
        setHealthRefreshState(complete ? 'current' : serviceHealthAvailable || infraHealthAvailable ? 'partial' : 'unavailable')
        if (complete) setLastSuccessfulRefresh(new Date())
      }
    } catch {
      // A failed refresh must not relabel the previous observation as current.
      if (generation === refreshGeneration.current) {
        setHealth({})
        setHealthRefreshState('unavailable')
      }
    } finally {
      clearTimeout(timeout)
      if (generation === refreshGeneration.current) {
        refreshController.current = null
        setLoading(false)
      }
    }
  }

  useEffect(() => {
    fetchHealth()
    const interval = setInterval(fetchHealth, 30000)
    return () => {
      clearInterval(interval)
      refreshGeneration.current += 1
      refreshController.current?.abort()
      refreshController.current = null
    }
  }, [])

  const toggleTier = (id: number) => {
    setExpandedTiers(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  const getTierStatus = (tier: BcpTier): { status: HealthStatus; healthy: number; total: number } => {
    const total = tier.services.length
    const statuses = tier.services.map(s => health[s.name] ?? 'unknown')
    const healthy = statuses.filter(s => s === 'healthy').length
    if (total === 0) return { status: 'unknown', healthy, total }
    const hasUnhealthy = statuses.some(s => s === 'unhealthy')
    const hasStarting = statuses.some(s => s === 'starting')
    const hasUnknown = statuses.some(s => s === 'unknown')

    if (hasUnhealthy) return { status: 'unhealthy', healthy, total }
    if (hasStarting) return { status: 'starting', healthy, total }
    if (hasUnknown) return { status: 'unknown', healthy, total }
    return { status: 'healthy', healthy, total }
  }

  const catalogCoverageComplete = catalogAvailable && policyAvailable && bcpCatalogCoverageIsComplete({
    tiers, policyServicesMissingFromCatalog: policyServicesMissing,
    unclassifiedCatalogServices: unclassifiedServices,
    catalogServiceCount,
  })
  // Payment processing needs both the core T0 ledger/transaction path and the
  // explicit compliance checks. An absent observation is unknown, never green.
  const complianceServices = ['balance-service', 'aml-service', 'sanctions-service']
  const complianceStatuses = complianceServices.map(name => health[name] ?? 'unknown')
  const complianceUnknown = !catalogAvailable || complianceStatuses.some(status => status === 'unknown')
  const complianceDegraded = complianceStatuses.some(status => status === 'unhealthy' || status === 'starting')
  const requiredPaymentServices = [
    'ledger-service', 'balance-service', 'transaction-service', 'clearing-service',
    'aml-service', 'sanctions-service',
  ]
  const paymentStatuses = requiredPaymentServices.map(name => health[name] ?? 'unknown')
  const paymentsUnknown = !catalogAvailable || policyServicesMissing.length > 0
    || paymentStatuses.some(status => status === 'unknown')
  const paymentsDegraded = paymentStatuses.some(status => status === 'unhealthy' || status === 'starting')
  const serviceStatuses = tiers.flatMap(tier => tier.services.map(service => health[service.name] ?? 'unknown'))
  const infraStatuses = infraAssets.map(asset => health[asset.id.replace(/^infra:/, '')] ?? 'unknown')
  const allObservedStatuses = [...serviceStatuses, ...infraStatuses]
  const overallStatus = allObservedStatuses.includes('unhealthy') || allObservedStatuses.includes('starting')
    ? 'DEGRADED'
    : !catalogCoverageComplete || policyServicesMissing.length > 0 || !graphAvailable || allObservedStatuses.includes('unknown')
      ? 'UNKNOWN'
      : 'NOMINAL'

  const overallHealthy = serviceStatuses.filter(status => status === 'healthy').length
  const overallTotal = serviceStatuses.length
  const infraHealthy = infraStatuses.filter(status => status === 'healthy').length
  const infraTotal = infraStatuses.length
  const t0Policy = policy?.tiers.find(tier => tier.key === 'T0')
  const t0Target = t0Policy ? `${t0Policy.rto} / ${t0Policy.rpo}` : t('NEZNÁMÉ', 'UNKNOWN')

  const StatusIcon = ({ status, size = 14 }: { status: HealthStatus; size?: number }) => {
    if (status === 'healthy')   return <CheckCircle2 size={size} style={{ color: 'var(--success)' }} />
    if (status === 'unhealthy') return <XCircle size={size} style={{ color: 'var(--danger)' }} />
    if (status === 'starting')  return <Clock size={size} style={{ color: 'var(--warning)' }} />
    return <AlertTriangle size={size} style={{ color: 'var(--text-tertiary)' }} />
  }

  const statusLabel = (s: HealthStatus) => ({
    healthy: 'healthy', unhealthy: 'unhealthy', starting: 'starting', unknown: 'unknown',
  }[s])

  const statusBadgeStyle = (s: HealthStatus): React.CSSProperties => {
    const map: Record<HealthStatus, React.CSSProperties> = {
      healthy:   { background: 'var(--success-bg)', color: 'var(--text-primary)', border: '1px solid var(--success-border)' },
      unhealthy: { background: 'var(--danger-bg)', color: 'var(--text-primary)', border: '1px solid var(--danger-border)' },
      starting:  { background: 'var(--warning-bg)', color: 'var(--text-primary)', border: '1px solid var(--warning-border)' },
      unknown:   { background: 'var(--surface-3)', color: 'var(--text-primary)', border: '1px solid var(--border-strong)' },
    }
    return map[s]
  }

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span>
            <span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Documentation')}</span>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Plán kontinuity provozu', 'Business Continuity Plan')}</span>
          </>}
        title={t('Plán kontinuity provozu', 'Business Continuity Plan')}
        subtitle={t('Prioritizovaný plán obnovy dle DORA Art. 11-12, CNB § 20d, EBA ICT Risk Guidelines', 'Prioritized recovery plan per DORA Art. 11-12, CNB § 20d, EBA ICT Risk Guidelines')}
        icon={<ShieldAlert aria-hidden="true" size={18} style={{ color: 'var(--danger)' }} />}
        actions={<div className="docs-header-actions" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {lastSuccessfulRefresh && (
            <span style={{ fontSize: '12px', color: 'var(--text-primary)' }}>
              {t('Poslední úspěšné načtení:', 'Last successful refresh:')} {lastSuccessfulRefresh.toLocaleTimeString(language === 'cs' ? 'cs-CZ' : 'en-GB')}
            </span>
          )}
          <PrintDocumentButton />
          <button
            type="button"
            className="btn"
            onClick={fetchHealth}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit stav BCP', 'Refresh BCP status')}
            style={{
              display: 'flex', alignItems: 'center', gap: '6px',
              padding: '8px 14px', borderRadius: '8px', border: '1px solid var(--border)',
              background: 'var(--surface-2)', cursor: 'pointer', fontSize: '13px',
              color: 'var(--text-primary)',
            }}
          >
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      <ContentSourceNote source={contentSource} />

      {healthRefreshState !== 'current' && (
        <div role="status" aria-live="polite" style={{ marginBottom: '16px', padding: '12px 16px', border: '1px solid var(--warning-border)', borderRadius: '10px', background: 'var(--warning-bg)', color: 'var(--text-primary)' }}>
          {healthRefreshState === 'partial'
            ? t('Některé údaje o provozním stavu jsou neúplné nebo nedostupné; neověřené stavy jsou označené.', 'Some health sources are incomplete or unavailable; unverified statuses are marked as such.')
            : lastSuccessfulRefresh
              ? t('Health zdroje nejsou dostupné; poslední úplné načtení zůstává uvedené výše.', 'Health sources are unavailable; the last complete refresh remains shown above.')
              : t('Health zdroje nejsou dostupné a zatím není k dispozici úplné načtení.', 'Health sources are unavailable and no complete refresh is available yet.')}
        </div>
      )}

      {/* Summary stats */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '12px', marginBottom: '24px' }}>
        {[
          {
            label: t('Celkový stav', 'Overall status'),
            value: loading ? '…' : overallStatus,
            sub: loading ? '' : t(`${overallHealthy}/${overallTotal} služeb v katalogu healthy`, `${overallHealthy}/${overallTotal} catalog services healthy`),
            color: overallStatus === 'NOMINAL' ? 'var(--success-text)' : overallStatus === 'UNKNOWN' ? 'var(--text-secondary)' : 'var(--danger-text)',
          },
          {
            label: t('Compliance service health', 'Compliance service health'),
            value: loading ? '…' : complianceDegraded ? 'DEGRADED' : complianceUnknown ? 'UNVERIFIED' : 'HEALTHY',
            sub: t('Pouze health probes Balance, AML a Sanctions; nejde o stav regulatorních kontrol.', 'Balance, AML and Sanctions health probes only; this is not regulatory-control status.'),
            color: complianceDegraded ? 'var(--danger-text)' : complianceUnknown ? 'var(--text-secondary)' : 'var(--success-text)',
          },
          {
            label: t('Payment-path service health', 'Payment-path service health'),
            value: loading ? '…' : paymentsDegraded ? 'DEGRADED' : paymentsUnknown ? t('NEOVĚŘENO', 'UNVERIFIED') : 'HEALTHY',
            sub: t('Health probes T0 a compliance services; stránka nerozhoduje o povolení transakce.', 'Health probes for T0 and compliance services; this page does not authorize transactions.'),
            color: paymentsDegraded ? 'var(--danger-text)' : paymentsUnknown ? 'var(--text-secondary)' : 'var(--success-text)',
          },
          {
            label: t('T0 cíl podle politiky', 'T0 policy target'),
            value: t0Target,
            sub: t0Policy
              ? t('RTO / RPO aspirace; výsledek drillů není změřen', 'RTO / RPO aspirational; drill result is not measured')
              : t('Kanonický cíl politiky není dostupný.', 'Canonical policy target is unavailable.'),
            color: 'var(--text-secondary)',
          },
        ].map(stat => (
          <div key={stat.label} className="card" style={{ padding: '16px' }}>
            <div style={{ fontSize: '11px', color: 'var(--text-primary)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{stat.label}</div>
            <div style={{ fontSize: '20px', fontWeight: 800, color: stat.color, marginBottom: '4px' }}>{stat.value}</div>
            <div style={{ fontSize: '11px', color: 'var(--text-primary)' }}>{stat.sub}</div>
          </div>
        ))}
      </div>

      {paymentsDegraded && (
        <div style={{
          background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', borderRadius: '10px',
          padding: '14px 18px', marginBottom: '20px',
          display: 'flex', alignItems: 'center', gap: '10px',
        }}>
          <AlertTriangle size={18} style={{ color: 'var(--danger)', flexShrink: 0 }} />
          <div>
            <div style={{ fontWeight: 700, color: 'var(--text-primary)', fontSize: '14px' }}>
              {t('Zdravotní stav závislostí platební cesty je degradovaný', 'Payment-path dependency health is degraded')}
            </div>
            <div style={{ fontSize: '12px', color: 'var(--text-primary)', marginTop: '2px' }}>
              {t('Alespoň jedna sledovaná platební nebo compliance service hlásí unhealthy/starting. Tato stránka zobrazuje service health, nikoli vynucení platební brány.', 'At least one monitored payment or compliance service reports unhealthy/starting. This page shows service health, not enforcement of a payment gate.')}
            </div>
          </div>
        </div>
      )}

      {/* Do not present a green result while the canonical roster/model is incomplete. */}
      {(!catalogAvailable || !policyAvailable || !catalogCoverageComplete || !graphAvailable || policyServicesMissing.length > 0) && (
        <div className="card" style={{
          marginBottom: '24px', padding: '12px 16px', borderLeft: '4px solid var(--warning)',
          display: 'flex', alignItems: 'flex-start', gap: '10px',
        }}>
          <AlertTriangle size={16} style={{ color: 'var(--warning)', flexShrink: 0, marginTop: '2px' }} />
          <div style={{ fontSize: '12px', color: 'var(--text-primary)' }}>
            {!catalogAvailable && t('Katalog služeb není dostupný; úplnost BCP rosteru nelze ověřit.', 'Service catalog is unavailable; BCP roster completeness cannot be verified.')}
            {!policyAvailable && t('Kanonická BCP politika není dostupná nebo její tabulka §2 nešla ověřit; zařazení a cíle jsou neznámé.', 'The canonical BCP policy is unavailable or its §2 table could not be validated; classifications and targets are unknown.')}
            {catalogAvailable && !catalogCoverageComplete && t('BCP roster nepokrývá každý katalogový service přesně jednou.', 'The BCP roster does not cover every catalog service exactly once.')}
            {unclassifiedServices.length > 0 && <>
              {t(' Nezařazené služby:', ' Unclassified services:')}{' '}
              <strong style={{ fontFamily: 'monospace', color: 'var(--text-primary)' }}>{unclassifiedServices.join(', ')}</strong>
            </>}
            {!graphAvailable && t('Graf závislostí není dostupný; závislosti obnovy nejsou ověřeny.', 'The dependency graph is unavailable; recovery dependencies are unverified.')}
            {policyServicesMissing.length > 0 && <>
              {t('Politika BCP odkazuje na service, který v aktuálním katalogu není; klasifikace se nepřemapuje odhadem: ', 'The BCP policy names a service absent from the current catalog; it is not guessed into a replacement: ')}
              <strong style={{ fontFamily: 'monospace', color: 'var(--text-primary)' }}>{policyServicesMissing.join(', ')}</strong>
            </>}
          </div>
        </div>
      )}

      {/* Recovery-policy tiers */}
      <div style={{ marginBottom: '32px' }}>
        <h2 style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <Zap size={15} style={{ color: 'var(--accent)' }} />
          {t('Katalog služeb podle BCP recovery tieru', 'Catalog services by BCP recovery tier')}
        </h2>
        <div style={{ fontSize: '12px', color: 'var(--text-primary)', marginBottom: '12px' }}>
          {t('RTO/RPO jsou cíle politiky, nikoli změřené výsledky; politika je označuje za aspirativní do Milestone M6.', 'RTO/RPO are policy targets, not measured results; the policy marks them aspirational until Milestone M6.')}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {tiers.map(tier => {
            const { status, healthy, total } = getTierStatus(tier)
            const expanded = expandedTiers.has(tier.id)

            return (
              <div key={tier.id} className="card" style={{ padding: 0, overflow: 'hidden', borderLeft: `4px solid ${tier.color}` }}>
                {/* Tier header */}
                <button
                  type="button"
                  aria-expanded={expanded}
                  aria-controls={`bcp-tier-${tier.id}`}
                  onClick={() => toggleTier(tier.id)}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '12px',
                    padding: '14px 18px', cursor: 'pointer',
                    background: expanded ? 'var(--surface-2)' : 'transparent',
                    border: 0, width: '100%', textAlign: 'left', color: 'inherit', font: 'inherit',
                  }}
                >
                  {/* Tier number */}
                  <div style={{
                    width: '28px', height: '28px', borderRadius: '50%',
                    background: tier.color, color: tier.textColor,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: '12px', fontWeight: 800, flexShrink: 0,
                  }}>
                    {tier.id}
                  </div>

                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                      <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{t(...tier.label)}</span>
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--text-primary)', marginTop: '2px' }}>{t(...tier.regulatoryBasis)}</div>
                  </div>

                  {/* RTO/RPO */}
                  <div style={{ display: 'flex', gap: '16px', flexShrink: 0 }}>
                    <div style={{ textAlign: 'center' }}>
                      <div style={{ fontSize: '10px', color: 'var(--text-primary)', textTransform: 'uppercase' }}>RTO</div>
                      <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{tier.rto}</div>
                    </div>
                    <div style={{ textAlign: 'center' }}>
                      <div style={{ fontSize: '10px', color: 'var(--text-primary)', textTransform: 'uppercase' }}>RPO</div>
                      <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{tier.rpo}</div>
                    </div>
                  </div>

                  {/* Health summary */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0 }}>
                    <StatusIcon status={status} size={16} />
                    <span style={{ ...statusBadgeStyle(status), fontSize: '11px', fontWeight: 600, padding: '3px 8px', borderRadius: '20px' }}>
                      {loading ? '…' : `${healthy}/${total}`}
                    </span>
                  </div>

                  {expanded ? <ChevronDown size={16} style={{ color: 'var(--text-secondary)', flexShrink: 0 }} /> : <ChevronRight size={16} style={{ color: 'var(--text-secondary)', flexShrink: 0 }} />}
                </button>

                {/* Services list */}
                {expanded && (
                  <div id={`bcp-tier-${tier.id}`} style={{ borderTop: '1px solid var(--border)', padding: '12px 18px' }}>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '8px' }}>
                      {tier.services.map(svc => {
                        const svcStatus = health[svc.name] ?? 'unknown'
                        return (
                          <div key={svc.name} style={{
                            display: 'flex', alignItems: 'flex-start', gap: '10px',
                            padding: '10px 12px', borderRadius: '8px',
                            background: 'var(--surface-2)', border: '1px solid var(--border)',
                          }}>
                            <StatusIcon status={svcStatus} size={14} />
                            <div style={{ flex: 1, minWidth: 0 }}>
                              <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{svc.label}</div>
                              <div style={{ fontSize: '11px', color: 'var(--text-primary)', marginTop: '2px' }}>{t(...svc.note)}</div>
                              <div style={{ fontSize: '10px', color: 'var(--text-primary)', marginTop: '2px', fontFamily: 'monospace' }}>{svc.name}</div>
                            </div>
                            <span style={{ ...statusBadgeStyle(svcStatus), fontSize: '10px', fontWeight: 600, padding: '2px 7px', borderRadius: '20px', flexShrink: 0 }}>
                              {loading ? '…' : statusLabel(svcStatus)}
                            </span>
                          </div>
                        )
                      })}
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>

      {graphAvailable && infraAssets.length > 0 && (
        <section className="card" aria-label={t('Sdílené recovery závislosti', 'Shared recovery dependencies')} style={{ padding: '16px', marginBottom: '32px' }}>
          <h2 style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)', margin: '0 0 6px' }}>
            {t('Sdílená infrastruktura v grafu závislostí', 'Shared infrastructure in the dependency graph')}
          </h2>
          <p style={{ fontSize: '12px', color: 'var(--text-primary)', margin: '0 0 12px' }}>
            {t('Politika nepřiřazuje těmto infrastrukturním uzlům samostatný RTO/RPO; jejich obnova ovlivňuje závislé služby.', 'The policy does not assign these infrastructure nodes separate RTO/RPO targets; their recovery affects dependent services.')}
          </p>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: '8px' }}>
            {infraAssets.map(asset => {
              const status = health[asset.id.replace(/^infra:/, '')] ?? 'unknown'
              return <div key={asset.id} style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '8px 10px', background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '8px' }}>
                <StatusIcon status={status} />
                <span style={{ flex: 1, color: 'var(--text-primary)', fontSize: '12px' }}>{asset.label}</span>
                <span style={{ ...statusBadgeStyle(status), fontSize: '10px', padding: '2px 6px', borderRadius: '20px' }}>{loading ? '…' : statusLabel(status)}</span>
              </div>
            })}
          </div>
          <div style={{ color: 'var(--text-primary)', fontSize: '11px', marginTop: '10px' }}>
            {t(`Infrastruktura healthy: ${infraHealthy}/${infraTotal}`, `Infrastructure healthy: ${infraHealthy}/${infraTotal}`)}
          </div>
        </section>
      )}

      {/* Incident classification */}
      <div style={{ marginBottom: '32px' }}>
        <h2 style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <AlertTriangle size={15} style={{ color: 'var(--warning)' }} />
          {t('Klasifikace incidentů (DORA Art. 17)', 'Incident classification (DORA Art. 17)')}
        </h2>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {INCIDENTS.map(inc => (
            <div key={inc.severity} className="card" style={{ padding: '14px 18px', borderLeft: `4px solid ${inc.color}` }}>
              <div style={{ display: 'grid', gridTemplateColumns: '80px 1fr 120px 200px', gap: '16px', alignItems: 'center' }}>
                <span style={{ fontSize: '13px', fontWeight: 800, color: 'var(--text-primary)' }}>{inc.severity} — {t(...inc.label)}</span>
                <span style={{ fontSize: '13px', color: 'var(--text-primary)' }}>{t(...inc.criteria)}</span>
                <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{t(...inc.response)}</span>
                <span style={{ fontSize: '12px', color: 'var(--text-primary)' }}>{t(...inc.reporting)}</span>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Test schedule */}
      <div style={{ marginBottom: '32px' }}>
        <h2 style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <BookOpen size={15} style={{ color: 'var(--accent)' }} />
          {t('Plán testování BCP (DORA Art. 11)', 'BCP testing schedule (DORA Art. 11)')}
        </h2>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
            <thead>
              <tr style={{ background: 'var(--surface-2)' }}>
                {[t('Typ testu', 'Test type'), t('Frekvence', 'Frequency'), t('Rozsah', 'Scope'), t('Vlastník', 'Owner')].map(h => (
                  <th key={h} style={{ padding: '10px 14px', textAlign: 'left', fontWeight: 600, color: 'var(--text-primary)', fontSize: '11px', textTransform: 'uppercase', borderBottom: '1px solid var(--border)' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {([
                { type: ['BCP tabletop', 'BCP tabletop'], freq: ['Čtvrtletně', 'Quarterly'], scope: ['Sandbox: kontrola runbooků a ověření záloh', 'Sandbox: review runbooks and verify backups'], owner: ['BCP Owner', 'BCP Owner'] },
                { type: ['Živý failover DR', 'Live DR failover'], freq: ['Ročně (po M6)', 'Annually (M6+)'], scope: ['Produkční failover a měření RTO/RPO', 'Production failover and measured RTO/RPO'], owner: ['BCP Owner', 'BCP Owner'] },
              ] as { type: Bilingual; freq: Bilingual; scope: Bilingual; owner: Bilingual }[]).map((row, i) => (
                <tr key={i} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '10px 14px', fontWeight: 600, color: 'var(--text-primary)' }}>{t(...row.type)}</td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-primary)' }}>{t(...row.freq)}</td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-primary)' }}>{t(...row.scope)}</td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-primary)' }}>{t(...row.owner)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ fontSize: '11px', color: 'var(--text-primary)', marginTop: '8px' }}>
          {t('Záznamy z testů musí být uchovávány ', 'Test records must be retained for ')}<strong>{t('5 let', '5 years')}</strong>{t(' (DORA Art. 11(6)).', ' (DORA Art. 11(6)).')}
        </div>
      </div>
      <CatalogDriftBanner present={catalogServiceNames} />
    </div>
  )
}
