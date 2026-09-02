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

type InfraStatus = 'UP' | 'DOWN' | 'UNKNOWN'

interface InfraComponent {
  id: string
  name: string
  probeNote: string
  icon: React.ReactNode
}

const INFRA_COMPONENTS: InfraComponent[] = [
  { id: 'postgres',        name: 'PostgreSQL',        probeNote: 'TCP :5432 via Gatus',                  icon: <Database size={20} /> },
  { id: 'kafka',           name: 'Apache Kafka',       probeNote: 'TCP :9092 via Gatus',                  icon: <Layers size={20} /> },
  { id: 'keycloak',        name: 'Keycloak IAM',       probeNote: 'HTTP /health/ready via Gatus',         icon: <Lock size={20} /> },
  { id: 'valkey',          name: 'Valkey (Cache)',      probeNote: 'TCP :6379 via Gatus',                  icon: <HardDrive size={20} /> },
  { id: 'openbao',         name: 'OpenBao',            probeNote: 'HTTP /v1/sys/health via Gatus',        icon: <Lock size={20} /> },
  { id: 'schema-registry', name: 'Schema Registry',   probeNote: 'HTTP :8081 via Gatus',                 icon: <Server size={20} /> },
  { id: 'grafana',         name: 'Grafana',            probeNote: 'HTTP /api/health via Gatus',           icon: <Eye size={20} /> },
  { id: 'prometheus',      name: 'Prometheus',         probeNote: 'HTTP /-/healthy via Gatus',            icon: <Activity size={20} /> },
  { id: 'loki',            name: 'Loki',               probeNote: 'HTTP /ready via Gatus',                icon: <Activity size={20} /> },
  { id: 'tempo',           name: 'Tempo',              probeNote: 'HTTP /ready via Gatus',                icon: <Activity size={20} /> },
  { id: 'kafka-ui',        name: 'Kafka UI',           probeNote: 'HTTP /actuator/health via BFF',        icon: <LayoutTemplate size={20} /> },
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

interface StatusResult {
  id: string
  status: InfraStatus
  latencyMs: number | null
  checkedAt: string | null
}

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
        setKafkaUnavailable({ kind: 'not_deployed' })
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
        setStatuses({})
        setUnavailable({ kind: res.status === 404 ? 'not_deployed' : 'unreachable' })
      } else {
        setStatuses(await res.json())
      }
    } catch {
      setStatuses({})
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
      setLastRefresh(new Date())
    }
  }, [])

  useEffect(() => {
    loadKafkaTopics()
    const kafkaId = setInterval(loadKafkaTopics, 30_000)
    return () => clearInterval(kafkaId)
  }, [loadKafkaTopics])

  useEffect(() => {
    load()
    const id = setInterval(load, 15_000)
    return () => clearInterval(id)
  }, [load])

  useEffect(() => {
    loadLifecycle()
    const id = setInterval(loadLifecycle, 60_000)
    return () => clearInterval(id)
  }, [loadLifecycle])

  const upCount = Object.values(statuses).filter(s => s.status === 'UP').length
  const totalCount = INFRA_COMPONENTS.length
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
            <span style={{ fontSize: '11px', color: upCount === totalCount ? 'var(--success)' : 'var(--danger)', fontWeight: 600 }}>
              {upCount}/{totalCount} UP
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
    </div>
  )
}
