// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  Server, Database, Activity, RefreshCw, AlertTriangle,
  Lock, Layers, HardDrive, CheckCircle2, XCircle, LayoutTemplate, Eye,
  Workflow, Zap, GitBranch, ShieldCheck, Cpu
} from 'lucide-react'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { LifecycleStrip, type CompLifecycle } from '@/components/infra/LifecycleStrip'
import { PageHeader, StatusBadge, TONE_BORDER_LEFT_CLASS, statusTone } from '@/components/ui'
import { cn } from '@/lib/utils'
import { ContextualInsights } from '@/components/insights/ContextualInsights'
import { EVENT_INSIGHTS } from '@/components/insights/catalog'
import { parseInfrastructureStatuses, type InfrastructureStatus, type InfrastructureStatusResult } from '@/lib/infra/statusContract'
import { classifyBffFailure } from '@/lib/services/bff'
import { usePlatformView } from '@/lib/use-platform-view'

type InfraStatus = InfrastructureStatus

interface InfraComponent {
  id: string
  name: string
  probeNote: string
  icon: React.ReactNode
}

const INFRA_COMPONENTS: InfraComponent[] = [
  { id: 'postgres',        name: 'PostgreSQL',        probeNote: 'TCP :5432 service probe',              icon: <Database size={20} /> },
  { id: 'kafka',           name: 'Apache Kafka',       probeNote: 'TCP :9093 mTLS listener',               icon: <Layers size={20} /> },
  { id: 'keycloak',        name: 'Keycloak IAM',       probeNote: 'HTTP /realms/master',                   icon: <Lock size={20} /> },
  { id: 'valkey',          name: 'Valkey (Cache)',      probeNote: 'Prometheus readiness (≤2m sample age)', icon: <HardDrive size={20} /> },
  { id: 'openbao',         name: 'OpenBao',            probeNote: 'HTTP /v1/sys/health',                    icon: <Lock size={20} /> },
  { id: 'schema-registry', name: 'Schema Registry',   probeNote: 'TCP :8080 service probe',              icon: <Server size={20} /> },
  { id: 'grafana',         name: 'Grafana',            probeNote: 'HTTP /api/health',                      icon: <Eye size={20} /> },
  { id: 'prometheus',      name: 'Prometheus',         probeNote: 'HTTP /-/ready',                         icon: <Activity size={20} /> },
  { id: 'loki',            name: 'Loki',               probeNote: 'HTTP /ready',                           icon: <Activity size={20} /> },
  { id: 'tempo',           name: 'Tempo',              probeNote: 'TCP :3200 service probe',              icon: <Activity size={20} /> },
  { id: 'kafka-ui',        name: 'Kafka UI',           probeNote: 'HTTP /actuator/health',                icon: <LayoutTemplate size={20} /> },
  // Expanded observability stack (ADR-0077/0079/0088), all in the observability namespace.
  { id: 'pyroscope',       name: 'Pyroscope',          probeNote: 'TCP :4040 · continuous profiling',     icon: <Activity size={20} /> },
  { id: 'alertmanager',    name: 'Alertmanager',       probeNote: 'TCP :9093 · alert routing',            icon: <AlertTriangle size={20} /> },
  { id: 'alloy',           name: 'Grafana Alloy',      probeNote: 'TCP :12345 · OTel collector agent',    icon: <Layers size={20} /> },
  { id: 'otel-collector',  name: 'OTel Collector',     probeNote: 'TCP :4317 · OTLP ingest',              icon: <Activity size={20} /> },
  { id: 'pyrra',           name: 'Pyrra (SLO)',        probeNote: 'TCP :9099 · SLO / error budgets',      icon: <Eye size={20} /> },
  { id: 'glitchtip',       name: 'GlitchTip',          probeNote: 'TCP :80 · error tracking (Sentry API)', icon: <AlertTriangle size={20} /> },
  { id: 'goalert',         name: 'GoAlert',            probeNote: 'TCP :8080 · on-call escalation',       icon: <AlertTriangle size={20} /> },
  { id: 'ntfy',            name: 'ntfy',               probeNote: 'TCP :8080 · push notifications',       icon: <Activity size={20} /> },
  // Platform control plane + orchestration (verified against the live cluster).
  { id: 'temporal',        name: 'Temporal',           probeNote: 'TCP :7233 · workflow orchestration',   icon: <Workflow size={20} /> },
  { id: 'keda',            name: 'KEDA',               probeNote: 'TCP :9666 · event-driven / scale-to-zero', icon: <Zap size={20} /> },
  { id: 'argocd',          name: 'ArgoCD',             probeNote: 'TCP :80 · GitOps engine (app-of-apps)', icon: <GitBranch size={20} /> },
  { id: 'kyverno',         name: 'Kyverno',            probeNote: 'TCP :8000 · admission policy',          icon: <ShieldCheck size={20} /> },
  { id: 'cert-manager',    name: 'cert-manager',       probeNote: 'TCP :9402 · TLS certificate lifecycle', icon: <Lock size={20} /> },
  { id: 'karpenter',       name: 'Karpenter',          probeNote: 'TCP :8080 · node autoscaler (Spot/arm64)', icon: <Cpu size={20} /> },
]

type StatusResult = InfrastructureStatusResult

type KafkaTopic = {
  name: string
  partitions: number
  replicas: number
  segmentSize: number
}

function InfrastructureStatusBadge({ status }: { status: InfraStatus }) {
  const icon = status === 'UP' ? <CheckCircle2 size={13} /> : status === 'DOWN' ? <XCircle size={13} /> : <AlertTriangle size={13} />
  return <StatusBadge status={status} leading={icon} />
}

export default function InfrastructurePage() {
  const { t, language } = useLanguage()
  const platformView = usePlatformView()
  const declaredApplications = platformView?.inventory?.gitops?.applications
  const helmReleaseDeclarations = platformView?.inventory?.iac?.helmReleaseDeclarations
  const moduleTraversalGaps = platformView?.inventory?.iac?.moduleTraversalGaps
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [statuses, setStatuses] = useState<Record<string, StatusResult>>({})
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [loading, setLoading] = useState(true)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const [kafkaTopics, setKafkaTopics] = useState<KafkaTopic[]>([])
  const [kafkaCluster, setKafkaCluster] = useState<string>('')
  const [kafkaUnavailable, setKafkaUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [lifecycle, setLifecycle] = useState<Record<string, CompLifecycle>>({})

  const loadLifecycle = useCallback(async () => {
    try {
      const res = await fetch('/api/infra/lifecycle', { cache: 'no-store' })
      if (!res.ok) return
      const data = await res.json() as { components: CompLifecycle[] }
      const map: Record<string, CompLifecycle> = {}
      for (const c of data.components ?? []) map[c.id] = c
      setLifecycle(map)
    } catch { /* lifecycle is additive; health view stands without it */ }
  }, [])

  const loadKafkaTopics = useCallback(async () => {
    try {
      const res = await fetch('/api/infra/kafka-topics', { cache: 'no-store' })
      if (!res.ok) {
        setKafkaUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const data = await res.json() as { topics: KafkaTopic[]; clusterName: string }
      setKafkaTopics(data.topics)
      setKafkaCluster(data.clusterName)
      setKafkaUnavailable(null)
    } catch {
      setKafkaUnavailable({ kind: 'unreachable' })
    }
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const res = await fetch('/api/infra/status', { cache: 'no-store' })
      if (!res.ok) {
        setUnavailable({ kind: await classifyBffFailure(res) })
      } else {
        try {
          setStatuses(parseInfrastructureStatuses(await res.json() as unknown))
          setUnavailable(null)
          setLastRefresh(new Date())
        } catch {
          setUnavailable({ kind: 'error' })
        }
      }
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    const initialKafkaId = window.setTimeout(loadKafkaTopics, 0)
    const kafkaId = setInterval(loadKafkaTopics, 30_000)
    return () => {
      clearTimeout(initialKafkaId)
      clearInterval(kafkaId)
    }
  }, [loadKafkaTopics])

  useEffect(() => {
    const initialId = window.setTimeout(load, 0)
    const id = setInterval(load, 15_000)
    return () => {
      clearTimeout(initialId)
      clearInterval(id)
    }
  }, [load])

  useEffect(() => {
    const initialLifecycleId = window.setTimeout(loadLifecycle, 0)
    const id = setInterval(loadLifecycle, 60_000)
    return () => {
      clearTimeout(initialLifecycleId)
      clearInterval(id)
    }
  }, [loadLifecycle])

  const upCount = Object.values(statuses).filter(s => s.status === 'UP').length
  const totalCount = INFRA_COMPONENTS.length
  const downCount = Object.values(statuses).filter(s => s.status === 'DOWN').length
  const unknownCount = Math.max(0, totalCount - upCount - downCount)
  const attention = Object.values(lifecycle).filter(c => ['vulnerable', 'eol-soon', 'eol'].includes(c.urgency)).length
  const upgradable = Object.values(lifecycle).filter(c => c.urgency === 'patch-available' || c.urgency === 'major-available').length

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Platforma', 'Platform')}</span></div>}
        icon={<Server size={20} aria-hidden="true" />}
        title={t('Infrastruktura', 'Infrastructure')}
        subtitle={t('Zdraví, životní cyklus a zranitelnosti komponent platformy — patch & EoL evidence (ADR-0079, DORA)', 'Component health, lifecycle & vulnerabilities — patch & EoL evidence (ADR-0079, DORA)')}
        actions={<div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {lastRefresh && (
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
              {t('Aktualizováno', 'Updated')} {lastRefresh.toLocaleTimeString(dateLocale)}
            </span>
          )}
          {!loading && !unavailable && (
            <span role="status" style={{ fontSize: '11px', color: downCount > 0 ? 'var(--danger)' : unknownCount > 0 ? 'var(--warning-text)' : 'var(--success)', fontWeight: 600 }}>
              {upCount}/{totalCount} UP · {downCount} DOWN · {unknownCount} UNKNOWN
            </span>
          )}
          {attention > 0 && (
            <span style={{ fontSize: '11px', color: '#dc2626', fontWeight: 700 }} title={t('Po/blízko EoL nebo se zranitelnostmi', 'Past/near EoL or vulnerable')}>
              {attention} {t('vyžaduje pozornost', 'need attention')}
            </span>
          )}
          {upgradable > 0 && (
            <span style={{ fontSize: '11px', color: '#d97706', fontWeight: 600 }}>
              {upgradable} {t('k aktualizaci', 'upgradable')}
            </span>
          )}
          <button type="button" onClick={load} disabled={loading} aria-busy={loading} aria-label={t('Obnovit stav infrastruktury', 'Refresh infrastructure status')} className="btn btn-secondary btn-sm">
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      <ContextualInsights dashboardUid="openbank-evb" panels={EVENT_INSIGHTS}
        titleCs="Tok událostí" titleEn="Event processing"
        descriptionCs="Spolehlivost předávání, dead letters a služby, ve kterých se práce hromadí."
        descriptionEn="Delivery reliability, dead letters and services where work accumulates." />

      {unavailable && (
        <div style={{
          background: 'var(--surface-2)', border: '1px solid var(--border)',
          borderRadius: '8px', marginBottom: '20px',
        }}>
          <DataUnavailable
            kind={unavailable.kind}
            service={t('Gatus monitoring agent', 'Gatus monitoring agent')}
            feature={t('Stav infrastruktury', 'Infrastructure status')}
            lang={language}
            detail={Object.keys(statuses).length > 0
              ? t('Zobrazen je poslední ověřený snapshot infrastruktury; aktuální obnova selhala a stav se mohl změnit.', 'The last verified infrastructure snapshot is shown; the current refresh failed and status may have changed.')
              : undefined}
            dense
          />
        </div>
      )}

      {loading && !lastRefresh ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '12px' }}>
          {Array.from({ length: INFRA_COMPONENTS.length }).map((_, i) => (
            <div key={i} className="skeleton" style={{ height: '120px' }} />
          ))}
        </div>
      ) : (
        <>
          <h2 style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '6px' }}>
            <Server size={16} color="var(--accent)" />
            {t('Komponenty infrastruktury', 'Infrastructure Components')}
          </h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '16px', marginBottom: '32px' }}>
            {INFRA_COMPONENTS.map(comp => {
              const st = statuses[comp.id]
              const status: InfraStatus = st?.status ?? 'UNKNOWN'
              const tone = statusTone(status)

              return (
                <div key={comp.id} className={cn('card tone-border-left', TONE_BORDER_LEFT_CLASS[tone])} style={{ padding: '18px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '10px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <div style={{ padding: '7px', background: 'var(--surface-2)', borderRadius: '7px', color: 'var(--text-secondary)' }}>
                        {comp.icon}
                      </div>
                      <div>
                        <div style={{ fontWeight: 700, fontSize: '13px', color: 'var(--text-primary)' }}>{comp.name}</div>
                        <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontFamily: 'monospace', marginTop: '1px' }}>
                          {comp.probeNote}
                        </div>
                      </div>
                    </div>
                    <InfrastructureStatusBadge status={status} />
                  </div>

                  {st && (
                    <div style={{ display: 'flex', gap: '16px', marginTop: '8px', paddingTop: '8px', borderTop: '1px solid var(--border)' }}>
                      {st.latencyMs !== null && (
                        <div>
                          <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                            {t('Odezva', 'Latency')}
                          </div>
                          <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>
                            {st.latencyMs}ms
                          </div>
                        </div>
                      )}
                      {st.checkedAt && (
                        <div>
                          <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                            {t('Ověřeno', 'Checked')}
                          </div>
                          <div style={{ fontSize: '11px', color: 'var(--text-secondary)', fontFamily: 'monospace' }}>
                            {new Date(st.checkedAt).toLocaleTimeString(dateLocale)}
                          </div>
                        </div>
                      )}
                    </div>
                  )}

                  {lifecycle[comp.id] && (
                    <LifecycleStrip data={lifecycle[comp.id]} name={comp.name} t={t} dateLocale={dateLocale} />
                  )}
                </div>
              )
            })}
          </div>

          <h2 style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '8px', display: 'flex', alignItems: 'center', gap: '6px' }}>
            <LayoutTemplate size={16} color="var(--info)" />
            {t('Kafka fronty', 'Kafka Topics')}
            {kafkaCluster && (
              <span style={{ fontSize: '11px', fontWeight: 400, color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace' }}>
                {kafkaCluster}
              </span>
            )}
            {!kafkaUnavailable && kafkaTopics.length > 0 && (
              <span style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)' }}>
                ({kafkaTopics.length})
              </span>
            )}
          </h2>
          <p style={{ fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '16px' }}>
            {t(
              'Live data z Kafka UI API. Detailní lag metriky v Grafana → Kafka dashboard.',
              'Live data from Kafka UI API. Detailed lag metrics in Grafana → Kafka dashboard.',
            )}
          </p>
          {kafkaUnavailable ? (
            <div style={{ background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '8px', marginBottom: '20px' }}>
              <DataUnavailable
                kind={kafkaUnavailable.kind}
                service={t('Kafka UI', 'Kafka UI')}
                feature={t('Kafka fronty', 'Kafka topics')}
                lang={language}
                dense
              />
            </div>
          ) : kafkaTopics.length === 0 ? (
            <div style={{ background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '8px', marginBottom: '20px' }}>
              <DataUnavailable
                kind="no_data"
                service={t('Kafka UI', 'Kafka UI')}
                feature={t('Kafka fronty', 'Kafka topics')}
                lang={language}
                dense
              />
            </div>
          ) : (
            <div className="card" style={{ padding: '0', overflow: 'hidden' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                <thead>
                  <tr style={{ background: 'var(--surface-2)', borderBottom: '1px solid var(--border)', textAlign: 'left', color: 'var(--text-secondary)' }}>
                    <th style={{ padding: '12px 16px', fontWeight: 600 }}>{t('Topic', 'Topic')}</th>
                    <th style={{ padding: '12px 16px', fontWeight: 600 }}>{t('Partitions', 'Partitions')}</th>
                    <th style={{ padding: '12px 16px', fontWeight: 600 }}>{t('Repliky', 'Replicas')}</th>
                    <th style={{ padding: '12px 16px', fontWeight: 600 }}>{t('Velikost segmentu', 'Segment size')}</th>
                  </tr>
                </thead>
                <tbody>
                  {kafkaTopics.map((topic, idx) => (
                    <tr key={topic.name} style={{ borderBottom: idx === kafkaTopics.length - 1 ? 'none' : '1px solid var(--border)' }}>
                      <td style={{ padding: '12px 16px', fontWeight: 500, fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', color: 'var(--text-primary)' }}>
                        {topic.name}
                      </td>
                      <td style={{ padding: '12px 16px', fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', color: 'var(--text-secondary)' }}>
                        {topic.partitions}
                      </td>
                      <td style={{ padding: '12px 16px', fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', color: 'var(--text-secondary)' }}>
                        {topic.replicas}
                      </td>
                      <td style={{ padding: '12px 16px', fontFamily: 'JetBrains Mono, monospace', fontSize: '12px', color: 'var(--text-secondary)' }}>
                        {topic.segmentSize > 0 ? `${(topic.segmentSize / 1024).toFixed(1)} KB` : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
      <section aria-labelledby="declared-applications-title" style={{ marginTop: '32px', marginBottom: '32px' }}>
        <h2 id="declared-applications-title" style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '8px', display: 'flex', alignItems: 'center', gap: '6px' }}>
          <LayoutTemplate size={16} color="var(--info)" />
          {t('Deklarované GitOps aplikace', 'Declared GitOps applications')}
        </h2>
        <p style={{ fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '16px' }}>
          {t(
            'Zdrojový inventář deklarací. Neprokazuje nasazení, běh ani úplnost platformy; živé sondy jsou uvedeny výše.',
            'Source inventory of declarations. It does not prove deployment, runtime, or platform completeness; live probes are shown above.',
          )}
        </p>
        {!declaredApplications ? (
          <div className="card" role="status" style={{ padding: '16px', color: 'var(--text-secondary)' }}>
            {t('Inventář deklarovaných aplikací není dostupný.', 'Declared application inventory is unavailable.')}
          </div>
        ) : (
          <details style={{ marginBottom: '16px' }}>
            <summary style={{ cursor: 'pointer', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)', padding: '10px 0' }}>
              {t('Deklarace GitOps aplikací', 'GitOps application declarations')} ({declaredApplications.length})
            </summary>
            {declaredApplications.length === 0 ? (
              <div role="status" className="card" style={{ padding: '16px', color: 'var(--text-secondary)' }}>
                {t('Inventář neobsahuje žádné záznamy aplikací.', 'The inventory contains no application records.')}
              </div>
            ) : (
              <div className="card" style={{ padding: '0', overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                  <thead>
                    <tr style={{ background: 'var(--surface-2)', borderBottom: '1px solid var(--border)', textAlign: 'left', color: 'var(--text-secondary)' }}>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>{t('Deklarace', 'Declaration')}</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>{t('Namespace', 'Namespace')}</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>{t('Zdroj', 'Source')}</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>{t('Mapování workloadu', 'Workload mapping')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {declaredApplications.map((application, index) => (
                      <tr key={`${application.sourceFile}:${index}`} style={{ borderBottom: index === declaredApplications.length - 1 ? 'none' : '1px solid var(--border)' }}>
                        <td style={{ padding: '10px 14px', color: 'var(--text-primary)' }}>
                          <div style={{ fontWeight: 600 }}>{application.name ?? t('Neznámá deklarace', 'Unknown declaration')}</div>
                          <div style={{ color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace', overflowWrap: 'anywhere' }}>{application.sourceFile}</div>
                          {application.kind === 'unknown' && <div style={{ color: 'var(--warning-text)' }}>{t('Typ deklarace neznámý', 'Declaration type unknown')}</div>}
                        </td>
                        <td style={{ padding: '10px 14px', color: 'var(--text-secondary)' }}>{application.namespace ?? t('Neznámý', 'Unknown')}</td>
                        <td style={{ padding: '10px 14px', color: 'var(--text-secondary)' }}>
                          {application.sources.length === 0 ? t('Neznámý zdroj', 'Unknown source') : application.sources.map((source, sourceIndex) => (
                            <div key={`${source.kind}:${sourceIndex}`} style={{ overflowWrap: 'anywhere' }}>
                              {source.kind === 'helm-chart' ? `Helm chart: ${source.chart}` : source.kind === 'manifest-path' ? `Manifest path: ${source.path}` : t('Neznámý zdroj', 'Unknown source')}
                            </div>
                          ))}
                        </td>
                        <td style={{ padding: '10px 14px', color: 'var(--text-secondary)' }}>
                          {application.workloadMapping === 'unknown' ? t('Neznámé; pouze deklarace', 'Unknown; declaration only') : t('Neznámé', 'Unknown')}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </details>
        )}
        {!helmReleaseDeclarations ? (
          <div role="status" style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
            {t('Inventář Helm deklarací není dostupný.', 'Helm declaration inventory is unavailable.')}
          </div>
        ) : (
          <details style={{ marginBottom: '16px' }}>
            <summary style={{ cursor: 'pointer', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)', padding: '10px 0' }}>
              {t('Deklarace Helm release v IaC', 'IaC Helm release declarations')} ({helmReleaseDeclarations.length})
              {moduleTraversalGaps && moduleTraversalGaps.length > 0 && ` · ${t('mezery v procházení modulů', 'module traversal gaps')}: ${moduleTraversalGaps.length}`}
            </summary>
            {helmReleaseDeclarations.length === 0 ? (
              <div role="status" className="card" style={{ padding: '16px', color: 'var(--text-secondary)' }}>
                {t('Inventář neobsahuje žádné záznamy Helm deklarací.', 'The inventory contains no Helm declaration records.')}
              </div>
            ) : (
              <div className="card" style={{ padding: '0', overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                  <thead>
                    <tr style={{ background: 'var(--surface-2)', borderBottom: '1px solid var(--border)', textAlign: 'left', color: 'var(--text-secondary)' }}>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>{t('Prostředí', 'Environment')}</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>{t('Deklarace', 'Declaration')}</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>{t('Zdroj', 'Source')}</th>
                      <th style={{ padding: '10px 14px', fontWeight: 600 }}>{t('Rozbalení instance', 'Instance expansion')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {helmReleaseDeclarations.map((release, index) => (
                      <tr key={`${release.environment}:${release.sourceFile}:${release.address}:${index}`} style={{ borderBottom: index === helmReleaseDeclarations.length - 1 ? 'none' : '1px solid var(--border)' }}>
                        <td style={{ padding: '10px 14px', color: 'var(--text-secondary)' }}>{release.environment}</td>
                        <td style={{ padding: '10px 14px', color: 'var(--text-primary)', fontFamily: 'JetBrains Mono, monospace', overflowWrap: 'anywhere' }}>{release.address}</td>
                        <td style={{ padding: '10px 14px', color: 'var(--text-secondary)', fontFamily: 'JetBrains Mono, monospace', overflowWrap: 'anywhere' }}>{release.sourceFile}</td>
                        <td style={{ padding: '10px 14px', color: 'var(--text-secondary)' }}>
                          {release.instanceExpansion === 'unknown' ? t('Neznámé; deklarace není instance', 'Unknown; declaration is not an instance') : t('Neznámé', 'Unknown')}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </details>
        )}
        {moduleTraversalGaps && moduleTraversalGaps.length > 0 && (
          <div role="status" className="card" style={{ padding: '16px', marginTop: '8px', borderColor: 'var(--warning)' }}>
            <div style={{ fontWeight: 600, marginBottom: '8px', color: 'var(--warning-text)' }}>
              {t('Inventář IaC je částečný: neprošly všechny moduly.', 'IaC inventory is partial: some modules could not be traversed.')}
            </div>
            <ul style={{ margin: 0, paddingLeft: '20px', fontSize: '12px', color: 'var(--text-secondary)' }}>
              {moduleTraversalGaps.map((gap, index) => (
                <li key={`${gap.environment}:${gap.modulePath}:${index}`}>
                  <span style={{ fontFamily: 'JetBrains Mono, monospace' }}>{gap.environment}: {gap.modulePath}</span> — {gap.reason}
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>
    </div>
  )
}
