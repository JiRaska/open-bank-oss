// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState, useCallback, useRef } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { fetchAllServiceSnapshots } from '@/lib/api'
import type { ServiceSnapshot, ServiceStack } from '@/types'
import { Package, RefreshCw, CheckCircle2, AlertTriangle, XCircle, Clock, ShieldAlert } from 'lucide-react'
import { SbomViewer } from '@/components/sbom/SbomViewer'
import { PageHeader } from '@/components/ui/PageHeader'

const POLL = 30_000

interface CveSummary {
  id: string
  summary: string | null
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN'
  score: number | null
  references: string[]
}

/**
 * Map a (display name, version) pair to the Maven coordinates OSV.dev expects.
 * Returns null for components we cannot reliably resolve (JDK, Gradle as a
 * tool, openbank-libs — these need different ecosystems / vendor advisories).
 */
function osvCoordinates(component: string): { ecosystem: string; pkg: string } | null {
  switch (component) {
    case 'Quarkus': return { ecosystem: 'Maven', pkg: 'io.quarkus:quarkus-core' }
    case 'Kotlin':  return { ecosystem: 'Maven', pkg: 'org.jetbrains.kotlin:kotlin-stdlib' }
    default: return null
  }
}

async function fetchCves(component: string, version: string): Promise<CveSummary[]> {
  const coord = osvCoordinates(component)
  if (!coord) return []
  try {
    const res = await fetch(
      `/api/sbom/cve?ecosystem=${coord.ecosystem}&pkg=${encodeURIComponent(coord.pkg)}&version=${encodeURIComponent(version)}`,
      { cache: 'no-store' },
    )
    if (!res.ok) return []
    const body = await res.json() as { vulns?: CveSummary[] }
    return body.vulns ?? []
  } catch {
    return []
  }
}

const SEVERITY_RANK = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, UNKNOWN: 0 } as const
function worstSeverity(vulns: CveSummary[]): CveSummary['severity'] {
  return vulns.reduce<CveSummary['severity']>(
    (acc, v) => SEVERITY_RANK[v.severity] > SEVERITY_RANK[acc] ? v.severity : acc,
    'UNKNOWN',
  )
}

interface ComponentAggregate {
  component: string
  /** Most common version across services. */
  primaryVersion: string
  /** Per-version breakdown — when this has more than one entry, drift is present. */
  versions: { version: string; services: string[] }[]
  /** Total services reachable for this component. */
  total: number
  /** Optional badge metadata when known. */
  lts?: boolean
  supportUntil?: string
}

/**
 * Aggregate per-service stack into per-component view. Sort versions by
 * service count descending so the "majority" version is first; that becomes
 * the primary version and everything else is drift.
 */
function aggregate(snapshots: ServiceSnapshot[]): ComponentAggregate[] {
  type ComponentReader = (s: ServiceStack) => { version: string; lts?: boolean; supportUntil?: string } | null
  const readers: Array<{ key: string; read: ComponentReader }> = [
    { key: 'Quarkus', read: s => s.quarkus ? { version: s.quarkus.version, lts: s.quarkus.lts, supportUntil: s.quarkus.supportUntil } : null },
    { key: 'Kotlin',  read: s => s.kotlin  ? { version: s.kotlin.version } : null },
    { key: 'JDK',     read: s => s.java    ? { version: s.java.version.split('+')[0] } : null },
    { key: 'Gradle',  read: s => s.gradle  ? { version: s.gradle.version } : null },
    { key: 'openbank-libs', read: s => s.libs ? { version: s.libs.version } : null },
  ]

  return readers.map(({ key, read }) => {
    const byVersion = new Map<string, string[]>()
    let lts: boolean | undefined
    let supportUntil: string | undefined
    for (const snap of snapshots) {
      const info = snap.info?.stack ? read(snap.info.stack) : null
      if (!info) continue
      const list = byVersion.get(info.version) ?? []
      list.push(snap.name)
      byVersion.set(info.version, list)
      // Capture LTS / supportUntil from any service that reports it (they
      // should all agree if aligned; if they differ, the drift table flags it).
      if (info.lts != null) lts = lts ?? info.lts
      if (info.supportUntil) supportUntil = supportUntil ?? info.supportUntil
    }
    const versions = Array.from(byVersion.entries())
      .map(([version, services]) => ({ version, services }))
      .sort((a, b) => b.services.length - a.services.length)
    const total = versions.reduce((sum, v) => sum + v.services.length, 0)
    return {
      component: key,
      primaryVersion: versions[0]?.version ?? 'n/a',
      versions,
      total,
      lts,
      supportUntil,
    }
  })
}

export default function TechInventoryPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [snapshots, setSnapshots] = useState<ServiceSnapshot[]>([])
  const [loading, setLoading] = useState(true)
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const inFlight = useRef(false)
  /** CVE lookups keyed by `${component}@${version}`. Lazy, fetched after snapshot refresh. */
  const [cveByComponent, setCveByComponent] = useState<Record<string, CveSummary[]>>({})

  const refresh = useCallback(async (spinner = false) => {
    if (inFlight.current) return
    inFlight.current = true
    if (spinner) setRefreshing(true)
    try {
      const snaps = await fetchAllServiceSnapshots()
      setSnapshots(snaps)
      setLastRefreshed(new Date())
      // Kick off CVE lookups for the components we know how to map. OSV.dev
      // responses are cached server-side for 24h, so this is cheap.
      const aggs = aggregate(snaps)
      const lookups = await Promise.all(aggs.map(async agg => {
        const vulns = await fetchCves(agg.component, agg.primaryVersion)
        return [`${agg.component}@${agg.primaryVersion}`, vulns] as const
      }))
      setCveByComponent(Object.fromEntries(lookups))
    } finally {
      inFlight.current = false
      setRefreshing(false)
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
    const id = setInterval(() => refresh(), POLL)
    return () => clearInterval(id)
  }, [refresh])

  const aggregates = aggregate(snapshots)
  const servicesWithStack = snapshots.filter(s => s.info?.stack).length
  const totalServices = snapshots.length
  const aligned = aggregates.filter(a => a.versions.length <= 1).length
  const drifted = aggregates.filter(a => a.versions.length > 1).length

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Systém', 'System')}</span></div>}
        icon={<Package size={20} aria-hidden="true" />}
        title={t('Tech Inventory', 'Tech Inventory')}
        subtitle={t('Verze runtime stacku napříč všemi službami. Generuje se z /api/v1/info každé služby.',
          'Runtime stack versions across all services. Sourced from each service\'s /api/v1/info.')}
        actions={<div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          {lastRefreshed && (
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '4px' }}>
              <Clock size={11} aria-hidden="true" /> {lastRefreshed.toLocaleTimeString(dateLocale)}
            </span>
          )}
          <button
            type="button"
            onClick={() => refresh(true)}
            disabled={refreshing}
            aria-busy={refreshing}
            aria-label={t('Obnovit inventář služeb', 'Refresh service inventory')}
            style={{
              padding: '6px 12px', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px',
              background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-md)',
              cursor: refreshing ? 'wait' : 'pointer', color: 'var(--text-secondary)',
            }}
          >
            <RefreshCw size={12} aria-hidden="true" style={{ animation: refreshing ? 'spin 1s linear infinite' : undefined }} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>}
      />

      {/* Summary cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px' }}>
        <SummaryCard
          label={t('Služby se stackem', 'Services reporting stack')}
          value={`${servicesWithStack} / ${totalServices}`}
          icon={<CheckCircle2 size={16} />}
          color="var(--success)" bg="var(--success-bg)" border="var(--success-border)"
        />
        <SummaryCard
          label={t('Komponenty v souladu', 'Components aligned')}
          value={`${aligned} / ${aggregates.length}`}
          icon={<CheckCircle2 size={16} />}
          color="var(--success)" bg="var(--success-bg)" border="var(--success-border)"
        />
        <SummaryCard
          label={t('Drift', 'Version drift')}
          value={`${drifted}`}
          icon={drifted > 0 ? <AlertTriangle size={16} /> : <CheckCircle2 size={16} />}
          color={drifted > 0 ? 'var(--warning)' : 'var(--success)'}
          bg={drifted > 0 ? 'var(--warning-bg)' : 'var(--success-bg)'}
          border={drifted > 0 ? 'var(--warning-border)' : 'var(--success-border)'}
        />
      </div>

      {/* Loading skeleton */}
      {loading ? (
        <div className="skeleton" style={{ height: '320px' }} />
      ) : (
        <>
          {/* Aggregate table */}
          <div style={{
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--r-lg)',
            overflow: 'hidden',
            boxShadow: 'var(--shadow-xs)',
          }}>
            <div style={{
              padding: '12px 16px', borderBottom: '1px solid var(--border)',
              background: 'var(--surface-2)',
              fontSize: '12px', fontWeight: 600, textTransform: 'uppercase',
              letterSpacing: '0.06em', color: 'var(--text-secondary)',
            }}>
              {t('Stack komponenty', 'Stack components')}
            </div>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
              <thead>
                <tr style={{ background: 'var(--surface-2)', textAlign: 'left' }}>
                  <Th>{t('Komponenta', 'Component')}</Th>
                  <Th>{t('Verze', 'Version')}</Th>
                  <Th align="right">{t('Služby', 'Services')}</Th>
                  <Th>{t('Stav', 'Status')}</Th>
                  <Th>{t('Poznámka', 'Note')}</Th>
                </tr>
              </thead>
              <tbody>
                {aggregates.map(agg => (
                  <AggregateRow
                    key={agg.component}
                    agg={agg}
                    totalServices={totalServices}
                    cves={cveByComponent[`${agg.component}@${agg.primaryVersion}`] ?? []}
                  />
                ))}
              </tbody>
            </table>
          </div>

          {/* Drift detail */}
          {drifted > 0 && (
            <div style={{
              background: 'var(--surface)',
              border: '1px solid var(--warning-border)',
              borderRadius: 'var(--r-lg)',
              overflow: 'hidden',
            }}>
              <div style={{
                padding: '12px 16px', borderBottom: '1px solid var(--border)',
                background: 'var(--warning-bg)',
                fontSize: '12px', fontWeight: 600, textTransform: 'uppercase',
                letterSpacing: '0.06em', color: 'var(--warning)',
                display: 'flex', alignItems: 'center', gap: '6px',
              }}>
                <AlertTriangle size={14} />
                {t('Drift v komponentách', 'Component drift')}
              </div>
              <div style={{ padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {aggregates.filter(a => a.versions.length > 1).map(agg => (
                  <div key={agg.component}>
                    <div style={{ fontSize: '13px', fontWeight: 600, marginBottom: '6px' }}>{agg.component}</div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                      {agg.versions.map(v => (
                        <div key={v.version} style={{ fontSize: '12px', display: 'flex', gap: '8px', alignItems: 'baseline' }}>
                          <span className="tag mono">{v.version}</span>
                          <span style={{ color: 'var(--text-tertiary)' }}>×{v.services.length}</span>
                          <span style={{ color: 'var(--text-secondary)' }}>
                            {v.services.slice(0, 5).join(', ')}{v.services.length > 5 ? ` +${v.services.length - 5}` : ''}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Per-service SBOM viewer — expand to see components, licenses, ecosystems. */}
          <div style={{
            background: 'var(--surface)', border: '1px solid var(--border)',
            borderRadius: 'var(--r-lg)', overflow: 'hidden',
          }}>
            <div style={{
              padding: '12px 16px', borderBottom: '1px solid var(--border)',
              background: 'var(--surface-2)', fontSize: '12px', fontWeight: 600,
              textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-secondary)',
              display: 'flex', alignItems: 'center', gap: '6px',
            }}>
              <Package size={14} />
              {t('SBOM (CycloneDX 1.5) — rozklik pro detail', 'SBOM (CycloneDX 1.5) — click to expand')}
            </div>
            <div style={{
              padding: '12px 16px',
              display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '8px',
            }}>
              {snapshots.map(s => (
                <SbomViewer key={s.name} serviceName={s.name} />
              ))}
            </div>
            <div style={{ padding: '8px 16px 12px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
              {t(
                'SBOM se baked do admin-ui image při buildu. Pokud služba ukazuje "not available", spusť ./gradlew sbomAll a rebuild admin-ui.',
                'SBOMs are baked into the admin-ui image at build time. If a service shows "not available", run ./gradlew sbomAll and rebuild admin-ui.',
              )}
            </div>
          </div>

          {/* Services without stack — old / broken / unreachable */}
          {servicesWithStack < totalServices && (
            <div style={{
              background: 'var(--surface)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--r-lg)',
              padding: '12px 16px',
              fontSize: '12px',
              color: 'var(--text-tertiary)',
              display: 'flex', alignItems: 'center', gap: '8px',
            }}>
              <XCircle size={14} />
              {t(
                `${totalServices - servicesWithStack} služeb nereportuje stack. Buď nejsou online, nebo neběží na openbank-libs v0.1.0+ (BuildInfo).`,
                `${totalServices - servicesWithStack} services do not report stack. Either offline, or running below openbank-libs v0.1.0 (no BuildInfo).`,
              )}
            </div>
          )}
        </>
      )}
    </div>
  )
}

function SummaryCard({ label, value, icon, color, bg, border }: {
  label: string; value: string; icon: React.ReactNode; color: string; bg: string; border: string
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
        <div style={{ fontSize: '20px', fontWeight: 300, color: 'var(--text-primary)', letterSpacing: '-0.02em' }}>{value}</div>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '1px' }}>{label}</div>
      </div>
    </div>
  )
}

function Th({ children, align }: { children: React.ReactNode; align?: 'left' | 'right' }) {
  return <th style={{
    padding: '10px 16px', fontSize: '11px', fontWeight: 600,
    textTransform: 'uppercase', letterSpacing: '0.05em',
    color: 'var(--text-tertiary)', textAlign: align ?? 'left',
    borderBottom: '1px solid var(--border)',
  }}>{children}</th>
}

function AggregateRow({
  agg, totalServices, cves,
}: {
  agg: ComponentAggregate
  totalServices: number
  cves: CveSummary[]
}) {
  const { t } = useLanguage()
  const aligned = agg.versions.length <= 1
  const coverage = totalServices === 0 ? 0 : Math.round((agg.total / totalServices) * 100)
  const worst = worstSeverity(cves)
  const cveColor =
    worst === 'CRITICAL' ? 'var(--danger)' :
    worst === 'HIGH' ? 'var(--danger)' :
    worst === 'MEDIUM' ? 'var(--warning)' :
    worst === 'LOW' ? 'var(--text-secondary)' :
    null

  return (
    <tr style={{ borderBottom: '1px solid var(--border)' }}>
      <td style={{ padding: '12px 16px', fontWeight: 600, fontSize: '13px' }}>{agg.component}</td>
      <td style={{ padding: '12px 16px' }}>
        <span className="tag mono">{agg.primaryVersion}</span>
        {agg.lts && agg.component === 'Quarkus' && (
          <span className="tag" style={{ marginLeft: '6px', background: 'var(--success-bg)', color: 'var(--success)', border: 'none' }}>LTS</span>
        )}
      </td>
      <td style={{ padding: '12px 16px', textAlign: 'right', color: 'var(--text-secondary)', fontFamily: 'JetBrains Mono, monospace', fontSize: '12px' }}>
        {agg.total} / {totalServices}
        <span style={{ color: 'var(--text-tertiary)', marginLeft: '6px' }}>({coverage}%)</span>
      </td>
      <td style={{ padding: '12px 16px' }}>
        {aligned ? (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', color: 'var(--success)', fontWeight: 500, fontSize: '12px' }}>
            <CheckCircle2 size={12} /> {t('V souladu', 'Aligned')}
          </span>
        ) : (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', color: 'var(--warning)', fontWeight: 500, fontSize: '12px' }}>
            <AlertTriangle size={12} /> {t('Drift', 'Drift')} ({agg.versions.length})
          </span>
        )}
      </td>
      <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>
        {cves.length > 0 ? (
          <span
            title={cves.slice(0, 5).map(c => `${c.id} (${c.severity}${c.score ? ' ' + c.score : ''})`).join('\n')}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: '4px',
              color: cveColor ?? 'var(--text-secondary)', fontWeight: 500,
            }}
          >
            <ShieldAlert size={12} />
            {cves.length} CVE{cves.length === 1 ? '' : 's'} ({worst})
          </span>
        ) : agg.supportUntil && agg.component === 'Quarkus' ? (
          <span>{t('Podpora do', 'Support until')} {agg.supportUntil}</span>
        ) : osvCoordinates(agg.component) ? (
          <span style={{ color: 'var(--success)' }}>{t('Žádné známé CVE', 'No known CVE')}</span>
        ) : (
          <span style={{ color: 'var(--text-tertiary)' }}>—</span>
        )}
      </td>
    </tr>
  )
}
