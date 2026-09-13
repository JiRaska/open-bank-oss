// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import { ChevronRight, ChevronDown, Download, Package, Scale, Layers, Loader2, GitCompareArrows } from 'lucide-react'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'

interface SbomSummary {
  service: string
  generatedAt: string | null
  generator: string | null
  specVersion: string | null
  rootComponent: { name: string | null; version: string | null; purl: string | null }
  totals: {
    components: number
    direct: number
    transitive: number
    withLicense: number
    withoutLicense: number
  }
  licenses: Array<{ license: string; count: number }>
  ecosystems: Array<{ ecosystem: string; count: number }>
  topComponents: Array<{
    group: string | null
    name: string
    version: string | null
    purl: string | null
    licenses: string[]
    scope: string | null
  }>
}

// ADR-0030 D5 phase 1 (issue #861): whether the running pod matches what
// GitOps currently declares for this service. Undefined for any service not
// covered by the scan (most services here aren't money-path) — no badge shown.
interface DriftEntry {
  status: 'checked' | 'no-pod-found'
  runningImage?: string
  declaredImage?: string
  inSync?: boolean
}

interface Props {
  serviceName: string
}

export function SbomViewer({ serviceName }: Props) {
  const { t } = useLanguage()
  const [open, setOpen] = useState(false)
  const [data, setData] = useState<SbomSummary | null>(null)
  // 'not_generated' = the SBOM simply wasn't baked into this image build (HTTP
  // 404) — an expected, non-error state we explain calmly. 'error' = an actual
  // unexpected failure. null = no failure.
  const [failure, setFailure] = useState<'not_generated' | 'error' | null>(null)
  const [loading, setLoading] = useState(false)
  const [filter, setFilter] = useState('')
  const [drift, setDrift] = useState<DriftEntry | null>(null)

  useEffect(() => {
    let cancelled = false
    fetch('/api/sbom/drift', { cache: 'no-store' })
      .then(r => (r.ok ? r.json() : null))
      .then((d: { services?: Record<string, DriftEntry> } | null) => {
        if (!cancelled) setDrift(d?.services?.[serviceName] ?? null)
      })
      .catch(() => { if (!cancelled) setDrift(null) })
    return () => { cancelled = true }
  }, [serviceName])

  useEffect(() => {
    if (!open || data || failure) return
    let cancelled = false
    setLoading(true)
    fetch(`/api/services/${serviceName}/sbom?summary=true`, { cache: 'no-store' })
      .then(async r => {
        if (!r.ok) {
          if (!cancelled) { setFailure(r.status === 404 ? 'not_generated' : 'error'); setLoading(false) }
          return null
        }
        return r.json() as Promise<SbomSummary>
      })
      .then(d => { if (d && !cancelled) { setData(d); setLoading(false) } })
      .catch(() => { if (!cancelled) { setFailure('error'); setLoading(false) } })
    return () => { cancelled = true }
  }, [open, serviceName, data, failure])

  const filtered = data?.topComponents.filter(c => {
    if (!filter) return true
    const hay = `${c.group ?? ''} ${c.name} ${c.version ?? ''}`.toLowerCase()
    return hay.includes(filter.toLowerCase())
  }) ?? []

  return (
    <div style={{
      border: '1px solid var(--border)',
      borderRadius: 'var(--r-sm)',
      background: 'var(--surface)',
      overflow: 'hidden',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '8px 12px', gap: '8px',
      }}>
        <button
          onClick={() => setOpen(v => !v)}
          style={{
            display: 'flex', alignItems: 'center', gap: '6px',
            flex: 1, minWidth: 0, padding: 0, border: 'none', background: 'transparent',
            cursor: 'pointer', color: 'var(--text-secondary)', fontSize: '12px',
            textAlign: 'left',
          }}
        >
          {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 }}>
            {serviceName}
          </span>
          {data && (
            <span style={{
              fontSize: '10px', color: 'var(--text-tertiary)',
              padding: '2px 6px', background: 'var(--surface-2)', borderRadius: '8px',
            }}>
              {data.totals.components} deps
            </span>
          )}
          {drift?.status === 'checked' && !drift.inSync && (
            <span
              title={`Running image doesn't match GitOps: ${drift.runningImage ?? '?'} vs declared ${drift.declaredImage ?? '?'}`}
              style={{
                display: 'flex', alignItems: 'center', gap: '3px',
                fontSize: '10px', color: 'var(--warning, #d97706)',
                padding: '2px 6px', background: 'var(--surface-2)', borderRadius: '8px',
              }}
            >
              <GitCompareArrows size={10} /> drift
            </span>
          )}
        </button>
        <a
          href={`/api/services/${serviceName}/sbom`}
          download
          title="Download CycloneDX SBOM"
          style={{
            display: 'flex', alignItems: 'center', gap: '4px',
            color: 'var(--text-tertiary)', textDecoration: 'none',
            fontSize: '11px', padding: '2px 6px',
          }}
          onClick={e => e.stopPropagation()}
        >
          <Download size={11} />
        </a>
      </div>

      {open && (
        <div style={{
          borderTop: '1px solid var(--border)',
          background: 'var(--surface-2)',
          padding: '12px',
        }}>
          {loading && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: 'var(--text-tertiary)', fontSize: '12px' }}>
              <Loader2 size={12} style={{ animation: 'spin 1s linear infinite' }} />
              {t('Načítání SBOM…', 'Loading SBOM…')}
            </div>
          )}

          {failure === 'not_generated' && (
            <DataUnavailable
              kind="no_data"
              feature={`SBOM — ${serviceName}`}
              lang="en"
              detail="No SBOM was generated for this service in this admin-ui build. It appears automatically once the image is built with ./gradlew sbomAll (CI generates it on release)."
              dense
            />
          )}
          {failure === 'error' && (
            <DataUnavailable
              kind="error"
              feature={`SBOM — ${serviceName}`}
              lang="en"
              detail="Failed to load the SBOM due to an unexpected error. Please try again."
              dense
            />
          )}

          {data && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {/* Header strip — root component + meta */}
              <div style={{
                display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '10px',
                fontSize: '11px',
              }}>
                <MetaBlock
                  icon={<Package size={12} />}
                  label="Root component"
                  value={data.rootComponent.name ? `${data.rootComponent.name}${data.rootComponent.version ? ` @ ${data.rootComponent.version}` : ''}` : '—'}
                />
                <MetaBlock
                  icon={<Layers size={12} />}
                  label="CycloneDX spec"
                  value={data.specVersion ? `v${data.specVersion}` : '—'}
                />
                <MetaBlock
                  icon={<Scale size={12} />}
                  label="Generator"
                  value={data.generator ?? '—'}
                />
              </div>

              {/* Totals grid */}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '8px' }}>
                <Stat label="Components" value={data.totals.components} />
                <Stat label="Direct" value={data.totals.direct} />
                <Stat label="Transitive" value={data.totals.transitive} />
                <Stat label="With license" value={`${data.totals.withLicense} / ${data.totals.components}`} />
              </div>

              {/* Two-column: licenses bar + ecosystems bar */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                <BarBlock title="Licenses" rows={data.licenses.slice(0, 8).map(l => ({ label: l.license, count: l.count }))} total={data.totals.components} />
                <BarBlock title="Ecosystems" rows={data.ecosystems.map(e => ({ label: e.ecosystem, count: e.count }))} total={data.totals.components} />
              </div>

              {/* Components table */}
              <div>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '6px' }}>
                  <div style={{ fontSize: '11px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-secondary)' }}>
                    Components {data.topComponents.length < data.totals.components && (
                      <span style={{ color: 'var(--text-tertiary)', fontWeight: 400 }}>
                        (first {data.topComponents.length} of {data.totals.components}, download for full)
                      </span>
                    )}
                  </div>
                  <input
                    type="text"
                    placeholder="filter…"
                    aria-label={t('Filtro komponent SBOM', 'Filter SBOM components')}
                    value={filter}
                    onChange={e => setFilter(e.target.value)}
                    style={{
                      fontSize: '11px', padding: '3px 8px',
                      border: '1px solid var(--border)', borderRadius: '4px',
                      background: 'var(--surface)', color: 'var(--text-primary)',
                      width: '160px',
                    }}
                  />
                </div>
                <div style={{
                  maxHeight: '60vh', overflowY: 'auto', resize: 'vertical',
                  border: '1px solid var(--border)', borderRadius: 'var(--r-sm)',
                  background: 'var(--surface)',
                }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '11px' }}>
                    <thead style={{ position: 'sticky', top: 0, background: 'var(--surface-2)', zIndex: 1 }}>
                      <tr>
                        <Th>Group</Th>
                        <Th>Name</Th>
                        <Th>Version</Th>
                        <Th>License</Th>
                      </tr>
                    </thead>
                    <tbody>
                      {filtered.map((c, i) => (
                        <tr key={`${c.group}/${c.name}/${i}`} style={{ borderTop: '1px solid var(--border)' }}>
                          <Td>{c.group ?? '—'}</Td>
                          <Td bold>{c.name}</Td>
                          <Td mono>{c.version ?? '—'}</Td>
                          <Td>{c.licenses.length > 0 ? c.licenses.join(', ') : <span style={{ color: 'var(--text-tertiary)' }}>—</span>}</Td>
                        </tr>
                      ))}
                      {filtered.length === 0 && (
                        <tr><td colSpan={4} style={{ padding: '12px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '11px' }}>
                          {filter ? `No match for "${filter}"` : 'No components'}
                        </td></tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>

              {data.generatedAt && (
                <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', textAlign: 'right' }}>
                  Generated {data.generatedAt}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function MetaBlock({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div style={{
      padding: '6px 8px', background: 'var(--surface)', borderRadius: 'var(--r-sm)',
      border: '1px solid var(--border)', minWidth: 0,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '4px', color: 'var(--text-tertiary)', fontSize: '10px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
        {icon} {label}
      </div>
      <div style={{ marginTop: '2px', fontSize: '12px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={value}>
        {value}
      </div>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div style={{
      padding: '8px 10px', background: 'var(--surface)', borderRadius: 'var(--r-sm)',
      border: '1px solid var(--border)',
    }}>
      <div style={{ fontSize: '18px', fontWeight: 300, color: 'var(--text-primary)', letterSpacing: '-0.02em' }}>{value}</div>
      <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em', marginTop: '2px' }}>{label}</div>
    </div>
  )
}

function BarBlock({ title, rows, total }: { title: string; rows: Array<{ label: string; count: number }>; total: number }) {
  const max = Math.max(...rows.map(r => r.count), 1)
  return (
    <div style={{ padding: '8px 10px', background: 'var(--surface)', borderRadius: 'var(--r-sm)', border: '1px solid var(--border)' }}>
      <div style={{ fontSize: '10px', textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-tertiary)', marginBottom: '6px' }}>{title}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
        {rows.length === 0 && <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>—</div>}
        {rows.map(r => {
          const pct = total > 0 ? Math.round((r.count / total) * 100) : 0
          const widthPct = (r.count / max) * 100
          return (
            <div key={r.label} style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '11px' }}>
              <div style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.label}>{r.label}</div>
              <div style={{ flex: 2, height: '8px', background: 'var(--surface-2)', borderRadius: '4px', overflow: 'hidden' }}>
                <div style={{ width: `${widthPct}%`, height: '100%', background: 'var(--accent, #2563eb)' }} />
              </div>
              <div style={{ width: '60px', textAlign: 'right', color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace' }}>
                {r.count} · {pct}%
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function Th({ children }: { children: React.ReactNode }) {
  return <th style={{
    padding: '6px 10px', textAlign: 'left', fontSize: '10px', fontWeight: 600,
    textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-tertiary)',
    borderBottom: '1px solid var(--border)',
  }}>{children}</th>
}

function Td({ children, bold, mono }: { children: React.ReactNode; bold?: boolean; mono?: boolean }) {
  return <td style={{
    padding: '4px 10px',
    fontWeight: bold ? 600 : 400,
    fontFamily: mono ? 'JetBrains Mono, monospace' : undefined,
    fontSize: mono ? '10.5px' : '11px',
    color: 'var(--text-secondary)',
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '0',
  }}>{children}</td>
}
