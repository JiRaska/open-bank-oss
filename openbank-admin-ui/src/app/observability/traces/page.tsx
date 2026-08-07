// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Trace Explorer — the bank's nervous system made visible. Lists recent
// distributed traces from Tempo (via the /api/tempo BFF proxy) and renders a
// span waterfall for a selected trace, so an operator can watch a single
// request hop across services with per-span timing. Read-only; degrades calmly
// when Tempo isn't reachable (most of the fleet isn't deployed in the sandbox).

import { useState, useEffect, useCallback } from 'react'
import { Activity, RefreshCw, GitBranch, Clock, ChevronRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'

interface TraceSummary {
  traceID: string
  rootServiceName?: string
  rootTraceName?: string
  startTimeUnixNano?: string
  durationMs?: number
}

interface FlatSpan {
  spanId: string
  parentSpanId?: string
  name: string
  service: string
  startNano: number
  endNano: number
}

// Stable per-service hue so the same service keeps its colour across spans.
function serviceColor(service: string): string {
  let h = 0
  for (let i = 0; i < service.length; i++) h = (h * 31 + service.charCodeAt(i)) % 360
  return `hsl(${h}, 62%, 48%)`
}

function attrString(attrs: { key: string; value?: { stringValue?: string } }[] | undefined, key: string): string | undefined {
  return attrs?.find(a => a.key === key)?.value?.stringValue
}

// Flatten an OTLP trace (Tempo /api/traces/{id}) into a sorted span list.
// Handles both `scopeSpans` (new) and `instrumentationLibrarySpans` (older).
function flattenTrace(otlp: unknown): FlatSpan[] {
  const batches = (otlp as { batches?: unknown[] })?.batches ?? []
  const spans: FlatSpan[] = []
  for (const batch of batches as Record<string, unknown>[]) {
    const resource = batch.resource as { attributes?: { key: string; value?: { stringValue?: string } }[] } | undefined
    const service = attrString(resource?.attributes, 'service.name') ?? 'unknown'
    const scopes = (batch.scopeSpans ?? batch.instrumentationLibrarySpans ?? []) as Record<string, unknown>[]
    for (const scope of scopes) {
      for (const s of (scope.spans ?? []) as Record<string, string>[]) {
        const startNano = Number(s.startTimeUnixNano ?? 0)
        const endNano = Number(s.endTimeUnixNano ?? 0)
        if (!startNano) continue
        spans.push({
          spanId: s.spanId, parentSpanId: s.parentSpanId,
          name: s.name ?? '(span)', service, startNano, endNano: endNano || startNano,
        })
      }
    }
  }
  return spans.sort((a, b) => a.startNano - b.startNano)
}

function fmtDuration(ms: number): string {
  if (ms < 1) return `${(ms * 1000).toFixed(0)}µs`
  if (ms < 1000) return `${ms.toFixed(1)}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

export default function TraceExplorerPage() {
  const { t } = useLanguage()
  const [traces, setTraces] = useState<TraceSummary[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<string | null>(null)
  const [spans, setSpans] = useState<FlatSpan[] | null>(null)
  const [spansLoading, setSpansLoading] = useState(false)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)

  const loadTraces = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      // eslint-disable-next-line react-hooks/purity -- time-relative display; timestamps are stable server data.
      const now = Math.floor(Date.now() / 1000)
      const res = await fetch(`/api/tempo/api/search?limit=20&start=${now - 3600}&end=${now}`, {
        signal: AbortSignal.timeout(8000),
      })
      if (!res.ok) {
        setUnavailable({ kind: res.status === 502 ? 'unreachable' : 'error' })
        setTraces(null)
        return
      }
      const json = await res.json()
      const list: TraceSummary[] = Array.isArray(json?.traces) ? json.traces : []
      setTraces(list)
      if (list.length === 0) setUnavailable({ kind: 'no_data' })
      setLastRefresh(new Date())
    } catch {
      setUnavailable({ kind: 'unreachable' })
      setTraces(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadTraces() }, [loadTraces])

  const openTrace = useCallback(async (traceID: string) => {
    setSelected(traceID)
    setSpans(null)
    setSpansLoading(true)
    try {
      const res = await fetch(`/api/tempo/api/traces/${traceID}`, { signal: AbortSignal.timeout(8000) })
      if (!res.ok) { setSpans([]); return }
      const json = await res.json()
      setSpans(flattenTrace(json))
    } catch {
      setSpans([])
    } finally {
      setSpansLoading(false)
    }
  }, [])

  // Waterfall geometry for the selected trace.
  const traceStart = spans && spans.length ? Math.min(...spans.map(s => s.startNano)) : 0
  const traceEnd = spans && spans.length ? Math.max(...spans.map(s => s.endNano)) : 0
  const traceTotal = Math.max(1, traceEnd - traceStart)

  return (
    <AuthGuard permission="system:view">
      <div>
        <div className="page-header">
          <div>
            <div className="breadcrumb">
              <span>OpenBank</span><span className="breadcrumb-sep">/</span>
              <span className="breadcrumb-current">{t('Trasování', 'Tracing')}</span>
            </div>
            <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <GitBranch size={18} style={{ color: 'var(--accent)' }} />
              {t('Trace Explorer', 'Trace Explorer')}
            </h1>
            <p className="page-subtitle">
              {t(
                'Sleduj jeden požadavek napříč službami — distribuované trasy z Tempa s časováním každého spanu.',
                'Watch a single request hop across services — distributed traces from Tempo with per-span timing.',
              )}
            </p>
          </div>
          <button onClick={loadTraces} className="btn btn-secondary" disabled={loading}
            style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <RefreshCw size={14} className={loading ? 'spin' : undefined} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>

        {unavailable && !traces?.length ? (
          <DataUnavailable
            kind={unavailable.kind}
            service="tempo (observability)"
            feature={t('Distribuované trasování', 'Distributed tracing')}
            lang={t('cs', 'en') as 'cs' | 'en'}
          />
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 340px) minmax(0, 1fr)', gap: '20px', alignItems: 'start' }}>
            {/* Trace list */}
            <div className="card" style={{ padding: '8px' }}>
              <div style={{
                fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.06em',
                color: 'var(--text-tertiary)', fontWeight: 700, padding: '8px 10px 6px',
              }}>
                {t('Poslední trasy', 'Recent traces')} {traces ? `(${traces.length})` : ''}
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '2px', maxHeight: '70vh', overflowY: 'auto' }}>
                {(traces ?? []).map(tr => {
                  const active = tr.traceID === selected
                  return (
                    <button key={tr.traceID} onClick={() => openTrace(tr.traceID)}
                      style={{
                        textAlign: 'left', border: 'none', cursor: 'pointer',
                        display: 'flex', alignItems: 'center', gap: '8px',
                        padding: '8px 10px', borderRadius: 'var(--r-sm)',
                        background: active ? 'var(--accent-bg)' : 'transparent',
                      }}>
                      <span style={{ width: 8, height: 8, borderRadius: '50%', flexShrink: 0, background: serviceColor(tr.rootServiceName ?? '?') }} />
                      <span style={{ flex: 1, minWidth: 0 }}>
                        <span style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: active ? 'var(--accent)' : 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {tr.rootServiceName ?? t('neznámá služba', 'unknown service')}
                        </span>
                        <span style={{ display: 'block', fontSize: '11px', color: 'var(--text-tertiary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {tr.rootTraceName ?? tr.traceID.slice(0, 12)}
                        </span>
                      </span>
                      {typeof tr.durationMs === 'number' && (
                        <span style={{ fontSize: '11px', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)' }}>
                          {fmtDuration(tr.durationMs)}
                        </span>
                      )}
                      <ChevronRight size={12} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                    </button>
                  )
                })}
              </div>
            </div>

            {/* Waterfall */}
            <div className="card" style={{ padding: '20px', minWidth: 0 }}>
              {!selected ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-tertiary)', fontSize: '13px', padding: '24px 0', justifyContent: 'center' }}>
                  <Activity size={15} /> {t('Vyber trasu vlevo pro zobrazení span waterfall.', 'Pick a trace on the left to see the span waterfall.')}
                </div>
              ) : spansLoading ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-tertiary)', fontSize: '13px', padding: '24px 0', justifyContent: 'center' }}>
                  <RefreshCw size={14} className="spin" /> {t('Načítám spany…', 'Loading spans…')}
                </div>
              ) : !spans?.length ? (
                <DataUnavailable kind="no_data" feature={t('Spany trasy', 'Trace spans')} lang={t('cs', 'en') as 'cs' | 'en'} dense />
              ) : (
                <>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px', gap: '8px', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '12px', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)' }}>
                      {selected.slice(0, 24)}…
                    </span>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '5px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                      <Clock size={12} /> {fmtDuration(traceTotal / 1e6)} · {spans.length} {t('spanů', 'spans')}
                    </span>
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '3px' }}>
                    {spans.map(s => {
                      const offsetPct = ((s.startNano - traceStart) / traceTotal) * 100
                      const widthPct = Math.max(0.6, ((s.endNano - s.startNano) / traceTotal) * 100)
                      const durMs = (s.endNano - s.startNano) / 1e6
                      return (
                        <div key={s.spanId} style={{ display: 'grid', gridTemplateColumns: '180px 1fr', gap: '10px', alignItems: 'center' }}>
                          <div style={{ minWidth: 0, display: 'flex', alignItems: 'center', gap: '6px' }}>
                            <span style={{ width: 7, height: 7, borderRadius: '50%', flexShrink: 0, background: serviceColor(s.service) }} />
                            <span title={`${s.service} · ${s.name}`} style={{ fontSize: '11px', color: 'var(--text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                              {s.name}
                            </span>
                          </div>
                          <div style={{ position: 'relative', height: 18, background: 'var(--surface-2)', borderRadius: 4 }}>
                            <div style={{
                              position: 'absolute', left: `${offsetPct}%`, width: `${widthPct}%`,
                              top: 0, bottom: 0, background: serviceColor(s.service), borderRadius: 4,
                              minWidth: 2,
                            }} />
                            <span style={{
                              position: 'absolute', left: `${Math.min(offsetPct, 80)}%`, top: '50%', transform: 'translateY(-50%)',
                              marginLeft: '4px', fontSize: '10px', color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace',
                              whiteSpace: 'nowrap', pointerEvents: 'none',
                            }}>
                              {fmtDuration(durMs)}
                            </span>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                </>
              )}
            </div>
          </div>
        )}

        {lastRefresh && (
          <div style={{ marginTop: '12px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
            {t('Zdroj: Tempo · ', 'Source: Tempo · ')}{t('aktualizováno', 'updated')} {lastRefresh.toLocaleTimeString()}
          </div>
        )}
      </div>
    </AuthGuard>
  )
}
