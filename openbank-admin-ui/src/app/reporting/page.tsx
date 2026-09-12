// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Warehouse reporting — ADR-0286 (issue #8943).
//
// Two read-paths into the ClickHouse warehouse, on one page, with the boundary the ADR draws kept
// visible: AUTHORITATIVE reports run through the governed query registry (/api/reporting/[queryId]
// — named queries over gold marts, validated parameters, per-entry permissions, never raw SQL from
// the browser), and EXPLORATORY analytics is the existing Grafana business-warehouse dashboard
// embedded in kiosk mode. A figure an operator acts on comes from the registry; a figure an
// operator explores comes from Grafana.

import { useCallback, useEffect, useRef, useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { WarehouseDashboard } from '@/components/reporting/WarehouseDashboard'
import { ReportTrend } from '@/components/reporting/ReportTrend'
import { PageHeader } from '@/components/ui/PageHeader'
import { BarChart3, Play, RefreshCw, ShieldCheck, Table as TableIcon } from 'lucide-react'
import { parseReportCatalogue, parseReportResult, type CatalogueColumn, type CatalogueReport, type ReportResult } from '@/lib/reporting/clientContract'

// Shapes mirrored from /api/reporting/route.ts and /api/reporting/[queryId]/route.ts. The page
// deliberately does NOT import the registry module: its SQL builders stay server-side.
function isoDay(d: Date) { return d.toISOString().slice(0, 10) }

function formatCell(value: unknown, format: CatalogueColumn['format'], locale: string): string {
  if (value === null || value === undefined) return '—'
  const raw = String(value)
  if (format === 'number') {
    const n = Number(raw)
    return Number.isFinite(n) ? new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(n) : raw
  }
  return raw
}

export default function ReportingPage() {
  const { t, language } = useLanguage()
  const cs = language === 'cs'

  const requestId = useRef(0)
  const [page, setPage] = useState(0)
  const [catalogueFailed, setCatalogueFailed] = useState(false)
  const [catalogue, setCatalogue] = useState<CatalogueReport[] | null>(null)
  const [catalogueDenied, setCatalogueDenied] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [paramValues, setParamValues] = useState<Record<string, string>>({})
  const [result, setResult] = useState<ReportResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [failure, setFailure] = useState<'unauthorized' | 'not_found' | 'invalid' | null>(null)

  useEffect(() => () => { requestId.current += 1 }, [])

  // Pre-fill parameter defaults when a report is selected; today/30d-ago for date params. Done in
  // the event handler, not an effect — setState-in-effect is an eslint error-level pattern here.
  const selectReport = useCallback((r: CatalogueReport) => {
    requestId.current += 1
    setLoading(false)
    setPage(0)
    const defaults: Record<string, string> = {}
    const today = isoDay(new Date())
    const thirtyAgo = isoDay(new Date(Date.now() - 29 * 864e5))
    for (const p of r.params) {
      defaults[p.name] = p.defaultValue ?? (p.type === 'date' ? (p.name === 'from' ? thirtyAgo : today) : '')
    }
    setSelected(r.id)
    setParamValues((previous) => ({ ...defaults, from: previous.from || defaults.from, to: previous.to || defaults.to }))
    setResult(null)
    setFailure(null)
  }, [])

  useEffect(() => {
    void (async () => {
      try {
        const res = await fetch('/api/reporting', { cache: 'no-store', signal: AbortSignal.timeout(10_000) })
        if (res.status === 401 || res.status === 403) { setCatalogueDenied(true); return }
        if (!res.ok) { setCatalogueFailed(true); setCatalogue([]); return }
        const reports = parseReportCatalogue(await res.json())
        setCatalogue(reports)
        selectReport(reports[0])
      } catch {
        setCatalogueFailed(true)
        setCatalogue([])
      }
    })()
  }, [selectReport])

  const report = catalogue?.find((r) => r.id === selected) ?? null

  const runReport = useCallback(async () => {
    if (!report) return
    if (paramValues.from > paramValues.to) { setFailure('invalid'); return }
    const id = ++requestId.current
    setPage(0)
    setResult(null)
    setLoading(true)
    setFailure(null)
    try {
      const qs = new URLSearchParams()
      for (const p of report.params) {
        const v = paramValues[p.name] ?? p.defaultValue
        if (v) qs.set(p.name, v)
      }
      const res = await fetch(`/api/reporting/${encodeURIComponent(report.id)}?${qs}`, {
        cache: 'no-store', signal: AbortSignal.timeout(15_000),
      })
      if (id !== requestId.current) return
      if (res.status === 401 || res.status === 403) { setFailure('unauthorized'); setResult(null); return }
      if (res.status === 404) { setFailure('not_found'); setResult(null); return }
      if (res.status === 400) { setFailure('invalid'); setResult(null); return }
      if (!res.ok) throw new Error('report unavailable')
      const body = parseReportResult(await res.json(), report)
      if (id === requestId.current) setResult(body)
    } catch {
      if (id !== requestId.current) return
      setResult({ available: false, reportId: report.id, columns: report.columns, rows: [], generatedAt: null, rowCount: 0, truncated: false })
    } finally {
      if (id === requestId.current) setLoading(false)
    }
  }, [report, paramValues])

  return (
    <div>
      <PageHeader
        icon={<BarChart3 size={18} aria-hidden="true" />}
        title={t('Reporting a analytika', 'Reporting and analytics')}
        subtitle={t(
          'Objemy, rizikové signály a kvalita dat. Vyberte report a období pro podrobný přehled.',
          'Volumes, risk signals and data quality. Choose a report and period for a detailed view.',
        )}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Reporting', 'Reporting')}</span></div>}
      />

      {/* Registry reports */}
      <div className="grid items-start gap-4 mb-6 lg:grid-cols-[280px_minmax(0,1fr)]">
        <div className="card" style={{ padding: '16px', alignSelf: 'start' }}>
          <h3 style={{ fontSize: '13px', fontWeight: 700, marginBottom: '4px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
            <ShieldCheck size={14} aria-hidden="true" /> {t('Vyberte report', 'Choose a report')}
          </h3>
          <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>
            {t('Pět pohledů na obchodní aktivitu a kvalitu dat.', 'Five views of business activity and data quality.')}
          </div>
          {catalogueDenied && <DataUnavailable kind="unauthorized" service="Reporting" feature={t('reporting', 'reporting')} dense />}
          {catalogue && catalogue.length === 0 && !catalogueDenied && (
            <DataUnavailable kind={catalogueFailed ? "unreachable" : "no_data"} feature={t('seznam reportů', 'report catalogue')} dense />
          )}
          {catalogue?.map((r) => (
            <button
              key={r.id}
              type="button"
              onClick={() => selectReport(r)}
              aria-pressed={selected === r.id}
              style={{
                display: 'block', width: '100%', textAlign: 'left', padding: '10px 12px', marginBottom: '6px',
                borderRadius: '6px', cursor: 'pointer', font: 'inherit',
                border: selected === r.id ? '1px solid var(--accent)' : '1px solid var(--border)',
                background: selected === r.id ? 'var(--surface-2)' : 'transparent',
              }}
            >
              <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)' }}>{cs ? r.titleCs : r.titleEn}</div>
              <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{cs ? r.descriptionCs : r.descriptionEn}</div>
            </button>
          ))}
        </div>

        <div className="card min-w-0" style={{ padding: '20px' }}>
          {!report && !catalogueDenied && (
            <DataUnavailable kind="no_data" feature={t('report', 'report')} dense />
          )}
          {report && (
            <>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap', marginBottom: '12px' }}>
                <div>
                  <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{cs ? report.titleCs : report.titleEn}</div>
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{cs ? report.descriptionCs : report.descriptionEn}</div>
                </div>
              </div>

              {/* Parameters */}
              <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: '16px' }}>
                {report.params.map((p) => (
                  <label key={p.name} style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '11px', color: 'var(--text-secondary)' }}>
                    {cs ? p.labelCs : p.labelEn}{p.required ? ' *' : ''}
                    {p.type === 'enum' ? (
                      <select
                        value={paramValues[p.name] ?? ''}
                        onChange={(e) => { requestId.current += 1; setLoading(false); setResult(null); setFailure(null); setParamValues((v) => ({ ...v, [p.name]: e.target.value })) }}
                        style={{ font: 'inherit', color: 'var(--text-primary)', border: '1px solid var(--border)', borderRadius: '4px', padding: '5px 8px', background: 'var(--surface-1)' }}
                      >
                        {!p.required && <option value="">{t('(vše)', '(all)')}</option>}
                        {p.options?.map((o) => <option key={o} value={o}>{o}</option>)}
                      </select>
                    ) : (
                      <input
                        type={p.type === 'date' ? 'date' : 'text'}
                        inputMode={p.type === 'number' ? 'numeric' : undefined}
                        value={paramValues[p.name] ?? ''}
                        onChange={(e) => { requestId.current += 1; setLoading(false); setResult(null); setFailure(null); setParamValues((v) => ({ ...v, [p.name]: e.target.value })) }}
                        style={{ font: 'inherit', color: 'var(--text-primary)', border: '1px solid var(--border)', borderRadius: '4px', padding: '5px 8px', background: 'var(--surface-1)' }}
                      />
                    )}
                  </label>
                ))}
                <button type="button" className="btn btn-primary" style={{ fontSize: '12px' }} onClick={() => void runReport()} disabled={loading} aria-busy={loading}>
                  {loading ? <RefreshCw size={13} className="animate-spin" aria-hidden="true" /> : <Play size={13} aria-hidden="true" />}
                  {t('Spustit report', 'Run report')}
                </button>
              </div>

              {failure === 'unauthorized' && <DataUnavailable kind="unauthorized" service="Reporting" feature={cs ? report.titleCs : report.titleEn} dense />}
              {failure === 'invalid' && (
                <DataUnavailable kind="error" feature={cs ? report.titleCs : report.titleEn}
                  title={t('Neplatný parametr', 'Invalid parameter')}
                  detail={t('Hodnota parametru neprošla validací — report se nespustil, protože by odpovídal na jinou otázku, než ukazuje jeho popisek.', 'A parameter value failed validation — the report did not run, because it would answer a different question than its label shows.')}
                  dense />
              )}
              {failure === 'not_found' && <DataUnavailable kind="not_found" feature={cs ? report.titleCs : report.titleEn} dense />}

              {result && !failure && (
                result.available ? (
                  <>
                    {result.truncated && (
                      <div role="status" style={{ padding: '8px 12px', marginBottom: '8px', color: 'var(--text-secondary)', background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '6px', fontSize: '12px' }}>
                        {t(`Zobrazeno prvních ${result.rowCount} řádků — report je zkrácený. Zužte parametry.`, `Showing the first ${result.rowCount} rows — the report is truncated. Narrow the parameters.`)}
                      </div>
                    )}
                    {result.rows.length === 0 ? (
                      <DataUnavailable kind="no_data" feature={cs ? report.titleCs : report.titleEn} dense />
                    ) : (
                      <div>
                        <ReportTrend reportId={result.reportId} rows={result.rows} truncated={result.truncated} />
                        <div style={{ overflowX: 'auto' }}>
                        <table className="data-table" style={{ width: '100%' }}>
                          <thead>
                            <tr>{result.columns.map((c) => <th key={c.key}>{cs ? c.labelCs : c.labelEn}</th>)}</tr>
                          </thead>
                          <tbody>
                            {result.rows.slice(page * 25, (page + 1) * 25).map((row, i) => (
                              <tr key={i}>
                                {result.columns.map((c) => (
                                  <td key={c.key} style={{ fontSize: '12px', fontFamily: c.format === 'number' ? 'JetBrains Mono, monospace' : 'inherit', textAlign: c.format === 'number' ? 'right' : 'left' }}>
                                    {formatCell(row[c.key], c.format, cs ? 'cs-CZ' : 'en-GB')}
                                  </td>
                                ))}
                              </tr>
                            ))}
                          </tbody>
                        </table>
                        </div>
                        {result.rows.length > 25 && <div className="flex items-center justify-between gap-2 mt-3 text-sm">
                          <button className="btn btn-secondary" disabled={page === 0} onClick={() => setPage((n) => n - 1)}>{t('Předchozí', 'Previous')}</button>
                          <span>{page * 25 + 1}–{Math.min((page + 1) * 25, result.rows.length)} / {result.rows.length}</span>
                          <button className="btn btn-secondary" disabled={(page + 1) * 25 >= result.rows.length} onClick={() => setPage((n) => n + 1)}>{t('Další', 'Next')}</button>
                        </div>}
                      </div>
                    )}
                    <div style={{ marginTop: '8px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                      {t('Interní report nad eventuálně konzistentní projekcí (ADR-0022) — nepodklad pro regulatorní výkaz.', 'Internal report over an eventually-consistent projection (ADR-0022) — not a source for a regulatory return.')}
                      {result.generatedAt ? ` · ${result.generatedAt}` : ''}
                    </div>
                  </>
                ) : (
                  <DataUnavailable kind="unreachable" service="ClickHouse (analytics)" feature={cs ? report.titleCs : report.titleEn} dense />
                )
              )}
              {loading && <p role="status" className="text-sm text-[var(--text-secondary)]">{t('Načítání reportu…', 'Loading report…')}</p>}
              {!result && !failure && !loading && (
                <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <TableIcon size={13} aria-hidden="true" />
                  {t('Nastavte parametry a spusťte report.', 'Set the parameters and run the report.')}
                </div>
              )}
            </>
          )}
        </div>
      </div>

      {paramValues.from && paramValues.to && paramValues.from <= paramValues.to && <WarehouseDashboard from={paramValues.from} to={paramValues.to} />}
    </div>
  )
}
