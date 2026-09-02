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
import { PageHeader } from '@/components/ui/PageHeader'
import { ExplorerGuide } from '@/components/brand/ExplorerGuide'

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
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [traces, setTraces] = useState<TraceSummary[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<string | null>(null)
  const [spans, setSpans] = useState<FlatSpan[] | null>(null)
  const [spansLoading, setSpansLoading] = useState(false)
  const [spansUnavailable, setSpansUnavailable] = useState<UnavailableKind | null>(null)
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
    setSpansUnavailable(null)
    setSpansLoading(true)
    try {
      const res = await fetch(`/api/tempo/api/traces/${traceID}`, { signal: AbortSignal.timeout(8000) })
      // A failed span fetch used to `setSpans([])`, which rendered the same "No data yet" panel
      // as a trace that genuinely carries no spans — so a 502 from Tempo read to the operator as
      // "this trace is empty". Keep the two apart (issue #5904).
      if (!res.ok) {
        setSpansUnavailable(res.status === 502 ? 'unreachable' : 'error')
        return
      }
      const json = await res.json()
      setSpans(flattenTrace(json))
    } catch {
      setSpansUnavailable('unreachable')
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
        <PageHeader
          icon={<GitBranch size={18} aria-hidden="true" />}
          title={t('Trace Explorer', 'Trace Explorer')}
          subtitle={t('Sleduj jeden požadavek napříč službami — distribuované trasy z Tempa s časováním každého spanu.', 'Watch a single request hop across services — distributed traces from Tempo with per-span timing.')}
          breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Trasování', 'Tracing')}</span></div>}
          actions={<button type="button" onClick={loadTraces} className="btn btn-secondary" disabled={loading} aria-busy={loading} aria-label={t('Obnovit trasy z Tempa', 'Refresh traces from Tempo')} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <RefreshCw size={14} aria-hidden="true" className={loading ? 'spin' : undefined} />
            {t('Obnovit', 'Refresh')}
          </button>}
        />

        {!selected && !unavailable && !loading && (
          <ExplorerGuide compact title={t('Najděte nejpomalejší předávku', 'Find the slowest hand-off')}>
            {t(
              'Vyberte trasu a čtěte waterfall zleva doprava. Dlouhý span ukazuje, kde požadavek čekal; změna barvy znamená přechod do další služby. Explorer doporučuje začít u nejdelšího pruhu, ne u nejhlasitějšího logu.',
              'Select a trace and read the waterfall from left to right. A long span shows where the request waited; a colour change marks a hand-off to another service. Explorer recommends starting with the longest bar, not the loudest log.',
            )}
          </ExplorerGuide>
        )}

        {/*
          Three distinct states, never collapsed into one another (issue #5904):
          LOADING  — the first fetch is still in flight and we have nothing yet. Before this
                     branch existed the page fell straight through to the grid and rendered an
                     empty "Recent traces ()" card, which is byte-for-byte what a successful
                     but empty search also renders. An operator could not tell "still asking"
                     from "Tempo holds no traces".
          EMPTY    — the search succeeded and returned zero traces (`kind: 'no_data'`). This is
                     currently the HONEST state for admin-ui and openbank-app, which emit no
                     spans at all (#5735); browser instrumentation is proposed in #5847.
          FAILED   — the search could not be answered (`unreachable` / `error`).
          A refresh over an existing list deliberately does NOT re-enter the loading branch —
          `traces` is still populated, so the list stays put and only the button spins.
        */}
        {loading && !traces && !unavailable ? (
          <div className="card" data-testid="trace-list-loading" role="status" aria-live="polite"
            style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px', padding: '48px 0', color: 'var(--text-tertiary)', fontSize: '13px' }}>
            <RefreshCw size={15} className="spin" aria-hidden="true" />
            {t('Načítám trasy z Tempa…', 'Loading traces from Tempo…')}
          </div>
        ) : unavailable && !traces?.length ? (
          <DataUnavailable
            kind={unavailable.kind}
            service="tempo (observability)"
            feature={t('Distribuované trasování', 'Distributed tracing')}
            lang={t('cs', 'en') as 'cs' | 'en'}
          />
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 340px) minmax(0, 1fr)', gap: '20px', alignItems: 'start' }}>
            {/* Trace list */}
            <div className="card" role="region" aria-label={t('Seznam posledních tras', 'Recent traces list')} style={{ padding: '8px' }}>
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
                    <button key={tr.traceID} type="button" onClick={() => openTrace(tr.traceID)} aria-pressed={active} aria-label={`${tr.rootServiceName ?? t('neznámá služba', 'unknown service')} — ${tr.rootTraceName ?? tr.traceID.slice(0, 12)}`}
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
                      <ChevronRight aria-hidden="true" size={12} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                    </button>
                  )
                })}
              </div>
            </div>

            {/* Waterfall */}
            <div className="card" style={{ padding: '20px', minWidth: 0 }}>
              {!selected ? (
                <div role="status" style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-tertiary)', fontSize: '13px', padding: '24px 0', justifyContent: 'center' }}>
                  <Activity aria-hidden="true" size={15} /> {t('Vyber trasu vlevo pro zobrazení span waterfall.', 'Pick a trace on the left to see the span waterfall.')}
                </div>
              ) : spansLoading ? (
                <div role="status" aria-live="polite" style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-tertiary)', fontSize: '13px', padding: '24px 0', justifyContent: 'center' }}>
                  <RefreshCw aria-hidden="true" size={14} className="spin" /> {t('Načítám spany…', 'Loading spans…')}
                </div>
              ) : spansUnavailable ? (
                <DataUnavailable kind={spansUnavailable} service="tempo (observability)" feature={t('Spany trasy', 'Trace spans')} lang={t('cs', 'en') as 'cs' | 'en'} dense />
              ) : !spans?.length ? (
                <DataUnavailable kind="no_data" feature={t('Spany trasy', 'Trace spans')} lang={t('cs', 'en') as 'cs' | 'en'} dense />
              ) : (
                <>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px', gap: '8px', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '12px', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)' }}>
                      {selected.slice(0, 24)}…
                    </span>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '5px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                      <Clock aria-hidden="true" size={12} /> {fmtDuration(traceTotal / 1e6)} · {spans.length} {t('spanů', 'spans')}
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
            {t('Zdroj: Tempo · ', 'Source: Tempo · ')}{t('aktualizováno', 'updated')} {lastRefresh.toLocaleTimeString(dateLocale)}
          </div>
        )}
      </div>
    </AuthGuard>
  )
}
