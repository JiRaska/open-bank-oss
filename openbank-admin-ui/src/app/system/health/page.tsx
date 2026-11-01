// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState, useCallback, useRef } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { RefreshCw, Clock, CheckCircle2, AlertTriangle, XCircle, Wifi, WifiOff, Tag, Cpu, GitCommit, Activity } from 'lucide-react'
import { fetchAllServiceSnapshotsEvidence, fetchAllServiceConfigSnapshots, fetchAllGovernanceManifests } from '@/lib/api'
import type { ServiceSnapshot, ServiceConfigResponse } from '@/types'
import type { GovernanceManifestEntry } from '@/lib/governance/manifest'
import { cn } from '@/lib/utils'
import { CatalogDriftBanner } from '@/components/governance/CatalogDriftBanner'
import { PageHeader } from '@/components/ui/PageHeader'
import { ContextualInsights } from '@/components/insights/ContextualInsights'
import { HEALTH_INSIGHTS } from '@/components/insights/catalog'
import { OperationalEvidence } from '@/components/observability/OperationalEvidence'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'

const POLL = 15_000

export default function SystemHealthPage() {
  const [snapshots, setSnapshots]   = useState<ServiceSnapshot[]>([])
  const [configMap, setConfigMap]   = useState<Record<string, ServiceConfigResponse | null>>({})
  const [govMap, setGovMap]         = useState<Record<string, GovernanceManifestEntry>>({})
  const [govTimestamp, setGovTimestamp] = useState<string | null>(null)
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [loading, setLoading]       = useState(true)
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const [healthFailure, setHealthFailure] = useState<UnavailableKind | null>(null)
  const [metadataUnavailable, setMetadataUnavailable] = useState(false)
  const isRefreshingRef = useRef(false)
  const metadataInFlightRef = useRef(false)
  const refreshCounterRef = useRef(0)

  // Optional enrichment has its own in-flight guard. It must never delay health,
  // discard a successful health sample, or prevent the next health poll.
  const refreshMetadata = useCallback(async () => {
    if (metadataInFlightRef.current) return
    metadataInFlightRef.current = true
    try {
      const [cfgs, govs] = await Promise.allSettled([
        fetchAllServiceConfigSnapshots(), fetchAllGovernanceManifests(),
      ])
      const map: Record<string, ServiceConfigResponse | null> = {}
      if (cfgs.status === 'fulfilled') cfgs.value.forEach(c => { map[c.name] = c.config })
      setConfigMap(map)
      setGovMap(govs.status === 'fulfilled' ? govs.value.byService : {})
      setGovTimestamp(govs.status === 'fulfilled' ? govs.value.timestamp : null)
      setMetadataUnavailable(cfgs.status === 'rejected' || govs.status === 'rejected')
    } catch {
      setConfigMap({})
      setGovMap({})
      setGovTimestamp(null)
      setMetadataUnavailable(true)
    } finally {
      metadataInFlightRef.current = false
    }
  }, [])

  const refresh = useCallback(async (spinner = false) => {
    if (isRefreshingRef.current) return
    isRefreshingRef.current = true
    if (spinner) setRefreshing(true)
    void refreshMetadata()

    const currentCounter = ++refreshCounterRef.current
    try {
      const evidence = await fetchAllServiceSnapshotsEvidence()
      if (currentCounter !== refreshCounterRef.current) return
      if (!evidence.ok) {
        setHealthFailure(evidence.failure)
        return
      }
      setSnapshots(evidence.snapshots)
      setHealthFailure(null)
      setLastRefreshed(new Date())
    }
    finally {
      if (currentCounter === refreshCounterRef.current) {
        setLoading(false)
        setRefreshing(false)
      }
      isRefreshingRef.current = false
    }
  }, [refreshMetadata])

  useEffect(() => { 
    const initial = setTimeout(() => void refresh(), 0)
    const id = setInterval(() => refresh(), POLL)
    return () => { clearTimeout(initial); clearInterval(id) }
  }, [refresh])

  const up       = snapshots.filter(s => s.reachable && s.health?.status === 'UP').length
  const degraded = snapshots.filter(s => s.reachable && s.health?.status === 'DOWN').length
  const down     = snapshots.filter(s => !s.reachable && s.health !== null).length
  const unknown  = snapshots.filter(s => s.health === null).length
  const hasSample = lastRefreshed !== null

  return (
    <div>
      <PageHeader
        icon={<Activity size={20} aria-hidden="true" />}
        title={t('Zdraví systému', 'System Health')}
        subtitle={`${t('Kontrola stavu každých', 'Health check every')} ${POLL / 1000}s`}
        actions={<div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {lastRefreshed && (
            <span style={{ fontSize: '12px', color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '5px' }}>
              <Clock size={12} aria-hidden="true" /> {t('Poslední úspěšná kontrola:', 'Last successful check:')} {lastRefreshed.toLocaleString(dateLocale)}
            </span>
          )}
          <button type="button" className="btn btn-secondary" onClick={() => refresh(true)} disabled={refreshing} aria-busy={refreshing} aria-label={t('Obnovit zdraví systému', 'Refresh system health')}>
            <RefreshCw size={13} aria-hidden="true" className={cn(refreshing && 'animate-spin')} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {healthFailure && <DataUnavailable
        kind={healthFailure}
        lang={language}
        title={hasSample
          ? t('Zobrazuji poslední ověřený stav služeb', 'Showing the last verified health snapshot')
          : t('Stav služeb se nepodařilo ověřit', 'Service health could not be verified')}
        detail={healthFailure === 'unauthorized'
          ? t('Vypršela relace nebo chybí oprávnění. Přihlaste se znovu. Nejde o potvrzený výpadek služeb.', 'Your session expired or access was denied. Sign in again. This does not confirm a service outage.')
          : hasSample
            ? t('Obnova selhala. Údaje níže jsou z poslední úspěšné kontroly a nemusí odpovídat aktuálnímu stavu.', 'The refresh failed. Values below are from the last successful check and may no longer reflect current health.')
            : t('Zdroj měření není dostupný nebo nevrátil platná data. To neznamená, že služby neběží.', 'The health collector is unavailable or returned invalid data. This does not mean the services are down.')}
      />}
      {metadataUnavailable && <p role="status">{t(
        'Některé údaje konfigurace nebo governance nejsou dostupné. Zdraví služeb se ověřuje nezávisle.',
        'Some configuration or governance details are unavailable. Service health is checked independently.',
      )}</p>}

      <OperationalEvidence />

      <ContextualInsights dashboardUid="openbank-slo" panels={HEALTH_INSIGHTS}
        titleCs="Dopad na zákazníky" titleEn="Customer impact"
        descriptionCs="Dostupnost klíčových platebních cest a tempo čerpání jejich chybového rozpočtu."
        descriptionEn="Availability of critical payment journeys and the rate at which they consume error budget."
        />

      {/* Summary */}
      {hasSample && snapshots.length > 0 && <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '12px', marginBottom: '20px' }}>
        <SummaryCard label={t('V pořádku', 'Healthy')}     count={up}       total={snapshots.length} icon={<CheckCircle2 size={16}/>} color="var(--success)" bg="var(--success-bg)" border="var(--success-border)" />
        <SummaryCard label={t('Zhoršené', 'Degraded')}    count={degraded} total={snapshots.length} icon={<AlertTriangle size={16}/>} color="var(--warning)" bg="var(--warning-bg)" border="var(--warning-border)" />
        <SummaryCard label={t('Nedostupné', 'Unreachable')} count={down}     total={snapshots.length} icon={<XCircle size={16}/>}      color="var(--danger)"  bg="var(--danger-bg)"  border="var(--danger-border)" />
        {unknown > 0 && <SummaryCard label={t('Neznámý stav', 'Unknown')} count={unknown} total={snapshots.length} icon={<Activity size={16}/>} color="var(--text-secondary)" bg="var(--surface-2)" border="var(--border)" />}
      </div>}

      {hasSample && snapshots.length === 0 && !healthFailure && <DataUnavailable
        kind="no_data" lang={language}
        title={t('Žádná pozorování zdraví služeb', 'No service health observations')}
        detail={t('Zdroj vrátil prázdný seznam. Zdraví služeb nelze vyhodnotit.', 'The collector returned an empty inventory. Service health cannot be assessed.')}
      />}

      {/* Service grid */}
      {loading ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px' }}>
          {Array.from({ length: 9 }).map((_, i) => <div key={i} className="skeleton" style={{ height: '180px' }} />)}
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '12px' }}>
          {snapshots.map(s => <ServiceCard key={s.namespace ? `${s.namespace}/${s.name}` : `${s.name}:${s.port}`} snapshot={s} resilience={configMap[s.name] ?? null} governance={govMap[s.name]} govTimestamp={govTimestamp} />)}
        </div>
      )}
      {hasSample && !healthFailure && snapshots.length > 0 && <CatalogDriftBanner present={snapshots.map(s => s.name)} />}
    </div>
  )
}

function SummaryCard({ label, count, total, icon, color, bg, border }: {
  label: string; count: number; total: number; icon: React.ReactNode; color: string; bg: string; border: string
}) {
  return (
    <div style={{
      background: 'var(--surface)', border: `1px solid ${border}`,
      borderRadius: 'var(--r-lg)', padding: '16px 18px',
      display: 'flex', alignItems: 'center', gap: '14px',
      boxShadow: 'var(--shadow-xs)',
    }}>
      <div style={{ padding: '10px', borderRadius: 'var(--r-md)', background: bg, color }}>{icon}</div>
      <div>
        <div style={{ fontSize: '24px', fontWeight: 300, color: 'var(--text-primary)', letterSpacing: '-0.02em' }}>
          {count}<span style={{ fontSize: '14px', color: 'var(--text-tertiary)', fontWeight: 400 }}>/{total}</span>
        </div>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '1px' }}>{label}</div>
      </div>
    </div>
  )
}

function ServiceCard({ snapshot, resilience, governance, govTimestamp }: { snapshot: ServiceSnapshot; resilience: ServiceConfigResponse | null; governance?: GovernanceManifestEntry; govTimestamp?: string | null }) {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const { name, namespace, port, info, health, rateLimitMax, rateLimitRemaining, apiVersion, latencyMs, reachable } = snapshot

  const isUnknown = health === null
  const isDegraded = reachable && health?.status === 'DOWN'
  const isDown = !reachable && !isUnknown

  const dotColor = isUnknown ? 'var(--text-tertiary)' : isDown ? 'var(--danger)' : isDegraded ? 'var(--warning)' : 'var(--success)'
  const statusLabel = isDown ? 'DOWN' : health?.status ?? 'UNKNOWN'
  const pillClass = isUnknown ? 'pill' : isDown ? 'pill pill-danger' : isDegraded ? 'pill pill-warning' : 'pill pill-success'

  const ratePct = rateLimitMax && rateLimitRemaining != null
    ? Math.round((rateLimitRemaining / rateLimitMax) * 100) : null

  return (
    <div style={{
      background: 'var(--surface)',
      border: `1px solid ${isDown ? 'var(--danger-border)' : 'var(--border)'}`,
      borderRadius: 'var(--r-lg)',
      boxShadow: 'var(--shadow-xs)',
      overflow: 'hidden',
    }}>
      {/* Card header */}
      <div style={{
        padding: '12px 14px',
        borderBottom: '1px solid var(--border)',
        background: isDown ? 'var(--danger-bg)' : 'var(--surface-2)',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', minWidth: 0 }}>
          <div style={{
            width: '8px', height: '8px', borderRadius: '50%',
            background: dotColor,
            boxShadow: `0 0 0 2px ${dotColor}33`,
            flex: '0 0 8px',
          }} />
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: '3px', minWidth: 0 }}>
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)', overflowWrap: 'anywhere' }}>{name}</span>
            {namespace && <span className="tag" aria-label={t(`Jmenný prostor ${namespace}`, `Namespace ${namespace}`)} style={{ fontSize: '10px' }}>{namespace}</span>}
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span className="tag" style={{ fontFamily: 'JetBrains Mono, monospace' }}>:{port}</span>
          <span className={pillClass}>{statusLabel}</span>
        </div>
      </div>

      {/* Meta */}
      <div style={{ padding: '12px 14px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
        <MetaRow icon={<Tag size={11}/>}       label={t('Verze', 'Version')} value={info?.version ?? '—'} />
        <MetaRow icon={<Cpu size={11}/>}       label="API"     value={info?.apiVersion ?? (apiVersion ? `v${apiVersion}` : '—')} />
        <MetaRow icon={<Activity size={11}/>}  label={t('Latence', 'Latency')} value={latencyMs != null ? `${latencyMs}ms` : '—'} mono />
        <MetaRow icon={<GitCommit size={11}/>} label="Commit"  value={info?.gitCommit ? info.gitCommit.slice(0, 7) : '—'} mono />
      </div>

      {/* Tech stack chips — Kotlin / Quarkus / JDK from /api/v1/info stack block (SBOM-3) */}
      {info?.stack && <TechStackChips stack={info.stack} />}

      {/* Rate limit */}
      {ratePct != null && (
        <div style={{ padding: '0 14px 12px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '5px' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
              {reachable ? <Wifi size={10}/> : <WifiOff size={10}/>} Rate limit
            </span>
            <span className="mono" style={{ fontSize: '11px' }}>{rateLimitRemaining}/{rateLimitMax}</span>
          </div>
          <div style={{ height: '4px', background: 'var(--surface-3)', borderRadius: '2px', overflow: 'hidden' }}>
            <div style={{
              height: '100%', borderRadius: '2px', transition: 'width 0.3s',
              width: `${ratePct}%`,
              background: ratePct > 50 ? 'var(--success)' : ratePct > 20 ? 'var(--warning)' : 'var(--danger)',
            }} />
          </div>
        </div>
      )}

      {/* Circuit breaker */}
      {resilience?.circuitBreaker && (
        <div style={{ padding: '10px 14px', borderTop: '1px solid var(--border)', background: 'var(--surface-2)' }}>
          <div style={{ fontSize: '10px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)', marginBottom: '6px' }}>
            {t('Jistič', 'Circuit Breaker')}
          </div>
          <div style={{ display: 'flex', gap: '5px', flexWrap: 'wrap' }}>
            <span className="tag">{resilience.circuitBreaker.failureRatio * 100}% {t('práh', 'threshold')}</span>
            <span className="tag">{resilience.circuitBreaker.delayMs / 1000}s {t('zpoždění', 'delay')}</span>
            <span className="tag">{resilience.circuitBreaker.requestVolumeThreshold} req vol</span>
          </div>
        </div>
      )}

      {governance && (
        <div style={{ padding: '10px 14px', borderTop: '1px solid var(--border)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
            <div style={{ fontSize: '10px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-tertiary)' }}>
              Governance
            </div>
            {govTimestamp && (
              <div style={{ fontSize: '10px', color: 'var(--text-tertiary)' }} title="Metadata freshness">
                {new Date(govTimestamp).toLocaleTimeString(dateLocale)}
              </div>
            )}
          </div>
          <div style={{ display: 'flex', gap: '5px', flexWrap: 'wrap', alignItems: 'center' }}>
            <span className="tag" style={{ background: 'var(--surface-3)', border: 'none' }} title="Database">
              {governance.databaseName ?? 'no database (stateless)'}
            </span>
            <span className="tag" style={{ background: 'var(--surface-3)', border: 'none' }} title="Current Version">
              {governance.flywayCurrentVersion || 'No version'}
            </span>
            <span className="tag" style={{
              background: governance.flywayDrift === true ? 'var(--danger-bg)' : governance.flywayDrift === false ? 'var(--success-bg)' : 'var(--warning-bg)',
              color: governance.flywayDrift === true ? 'var(--danger)' : governance.flywayDrift === false ? 'var(--success)' : 'var(--warning)',
              border: 'none'
            }} title={t('Stav driftu', 'Drift Status')}>
              {governance.flywayDrift === true ? `Drift: ${t('Ano', 'Yes')}` : governance.flywayDrift === false ? `Drift: ${t('Ne', 'No')}` : 'Drift: ?'}
            </span>
            {governance.dataClassification && (
              <span className="tag" style={{ background: 'var(--surface-3)', border: 'none' }} title={t('Klasifikace dat', 'Data Classification')}>
                {t('Třída', 'Class')}: {governance.dataClassification}
              </span>
            )}
            {governance.retentionPolicy && (
              <span className="tag" style={{ background: 'var(--surface-3)', border: 'none' }} title={t('Politika uchovávání', 'Retention Policy')}>
                {t('Ret', 'Ret')}: {governance.retentionPolicy}
              </span>
            )}
            <span className="tag" style={{ 
              background: governance.evidenceExported ? 'var(--success-bg)' : 'var(--warning-bg)', 
              color: governance.evidenceExported ? 'var(--success)' : 'var(--warning)', 
              border: 'none' 
            }} title={t('Exportovaný doklad', 'Evidence Exported')}>
              {t('Doklad', 'Evidence')}: {governance.evidenceExported ? t('Ano', 'Yes') : t('Ne', 'No')}
            </span>
          </div>
        </div>
      )}

      {/* Health checks */}
      {health?.checks && health.checks.length > 0 && (
        <div style={{ borderTop: '1px solid var(--border)' }}>
          {health.checks.map(c => (
            <div key={c.name} style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              padding: '7px 14px',
              borderBottom: '1px solid var(--border)',
              fontSize: '12px',
            }}>
              <span style={{ color: 'var(--text-secondary)' }}>{c.name}</span>
              <span style={{ fontWeight: 600, color: c.status === 'UP' ? 'var(--success)' : 'var(--danger)' }}>
                {c.status}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function MetaRow({ icon, label, value, mono }: { icon: React.ReactNode; label: string; value: string; mono?: boolean }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '12px' }}>
      <span style={{ color: 'var(--text-tertiary)', flexShrink: 0 }}>{icon}</span>
      <span style={{ color: 'var(--text-tertiary)' }}>{label}:</span>
      <span style={{ color: 'var(--text-primary)', fontWeight: 500, fontFamily: mono ? 'JetBrains Mono, monospace' : 'inherit', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        {value}
      </span>
    </div>
  )
}

import type { ServiceStack } from '@/types'

/**
 * Renders one chip per stack component (Kotlin / Quarkus / JDK / libs).
 * Quarkus LTS lines get a green badge; non-LTS gets a neutral tone so the
 * difference is obvious at a glance — useful for security audit walk-throughs.
 * JDK version strips the build qualifier ("25.0.1+5-LTS" → "25.0.1") because
 * the qualifier is noise for the at-a-glance use case; full string is in the
 * tooltip.
 */
function TechStackChips({ stack }: { stack: ServiceStack }) {
  const jdkShort = stack.java?.version?.split('+')[0] ?? null
  const jdkTooltip = stack.java
    ? [
        stack.java.version,
        stack.java.vendor,
        stack.java.arch,
        stack.java.cpu != null ? `${stack.java.cpu} CPU` : null,
        stack.java.maxHeapMib != null ? `${stack.java.maxHeapMib} MiB heap` : null,
      ].filter(Boolean).join(' · ')
    : ''

  return (
    <div style={{
      padding: '0 14px 12px',
      display: 'flex', flexWrap: 'wrap', gap: '4px', alignItems: 'center',
    }}>
      <span style={{
        fontSize: '10px', fontWeight: 600, textTransform: 'uppercase',
        letterSpacing: '0.06em', color: 'var(--text-tertiary)',
        marginRight: '4px',
      }}>
        Stack
      </span>
      {stack.kotlin && (
        <span className="tag" title={`Kotlin ${stack.kotlin.version}`}>
          Kotlin {stack.kotlin.version}
        </span>
      )}
      {stack.quarkus && (
        <span
          className="tag"
          title={stack.quarkus.lts
            ? `Quarkus ${stack.quarkus.version} LTS, supported until ${stack.quarkus.supportUntil ?? 'unknown'}`
            : `Quarkus ${stack.quarkus.version} (non-LTS)`}
          style={stack.quarkus.lts
            ? { background: 'var(--success-bg)', color: 'var(--success-text)', border: 'none' }
            : { background: 'var(--warning-bg)', color: 'var(--warning-text)', border: 'none' }}
        >
          Quarkus {stack.quarkus.version}{stack.quarkus.lts ? ' LTS' : ''}
        </span>
      )}
      {jdkShort && (
        <span className="tag" title={jdkTooltip}>
          JDK {jdkShort}
        </span>
      )}
      {stack.gradle && (
        <span className="tag" title={`Built with Gradle ${stack.gradle.version}`}
          style={{ opacity: 0.7 }}>
          Gradle {stack.gradle.version}
        </span>
      )}
    </div>
  )
}
