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

import { useCallback, useEffect, useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { BarChart3, ExternalLink, Play, RefreshCw, ShieldCheck, Table as TableIcon } from 'lucide-react'

// Shapes mirrored from /api/reporting/route.ts and /api/reporting/[queryId]/route.ts. The page
// deliberately does NOT import the registry module: its SQL builders stay server-side.
interface CatalogueParam {
  name: string
  labelCs: string
  labelEn: string
  type: 'date' | 'number' | 'enum'
  required: boolean
  defaultValue?: string
  options?: readonly string[]
}
interface CatalogueColumn { key: string; labelCs: string; labelEn: string; format: 'text' | 'number' | 'money' | 'datetime' }
interface CatalogueReport {
  id: string
  titleCs: string
  titleEn: string
  descriptionCs: string
  descriptionEn: string
  permission: string
  params: readonly CatalogueParam[]
  columns: readonly CatalogueColumn[]
}
interface ReportResult {
  available: boolean
  reportId: string
  columns: readonly CatalogueColumn[]
  rows: Record<string, unknown>[]
  generatedAt: string | null
  rowCount: number
  truncated: boolean
  error?: string
}

// Exploratory surface: the existing Grafana business-warehouse dashboard (dashboard ConfigMap uid
// openbank-business-warehouse), embedded in kiosk mode. Auth is the operator's existing Keycloak
// SSO session; the iframe fails soft into the fallback link below when Grafana is unreachable.
const GRAFANA_BASE = process.env.NEXT_PUBLIC_GRAFANA_URL?.trim() || 'https://admin.open-bank.tech/tools/grafana'
const GRAFANA_DASHBOARD_UID = process.env.NEXT_PUBLIC_GRAFANA_WAREHOUSE_DASHBOARD_UID?.trim() || 'openbank-business-warehouse'
const GRAFANA_EMBED_URL = `${GRAFANA_BASE}/d/${GRAFANA_DASHBOARD_UID}?kiosk`

function isoDay(d: Date) { return d.toISOString().slice(0, 10) }

function formatCell(value: unknown, format: CatalogueColumn['format']): string {
  if (value === null || value === undefined) return '—'
  const raw = String(value)
  if (format === 'number') {
    const n = Number(raw)
    return Number.isFinite(n) ? new Intl.NumberFormat('cs-CZ', { maximumFractionDigits: 2 }).format(n) : raw
  }
  return raw
}

export default function ReportingPage() {
  const { t, language } = useLanguage()
  const cs = language === 'cs'

  const [catalogue, setCatalogue] = useState<CatalogueReport[] | null>(null)
  const [catalogueDenied, setCatalogueDenied] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [paramValues, setParamValues] = useState<Record<string, string>>({})
  const [result, setResult] = useState<ReportResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [failure, setFailure] = useState<'unauthorized' | 'not_found' | 'invalid' | null>(null)

  // Pre-fill parameter defaults when a report is selected; today/30d-ago for date params. Done in
  // the event handler, not an effect — setState-in-effect is an eslint error-level pattern here.
  const selectReport = useCallback((r: CatalogueReport) => {
    const defaults: Record<string, string> = {}
    const today = isoDay(new Date())
    const thirtyAgo = isoDay(new Date(Date.now() - 30 * 864e5))
    for (const p of r.params) {
      defaults[p.name] = p.defaultValue ?? (p.type === 'date' ? (p.name === 'from' ? thirtyAgo : today) : '')
    }
    setSelected(r.id)
    setParamValues(defaults)
    setResult(null)
    setFailure(null)
  }, [])

  useEffect(() => {
    void (async () => {
      try {
        const res = await fetch('/api/reporting', { cache: 'no-store', signal: AbortSignal.timeout(10_000) })
        if (res.status === 401 || res.status === 403) { setCatalogueDenied(true); return }
        if (!res.ok) { setCatalogue([]); return }
        const body = (await res.json()) as { reports: CatalogueReport[] }
        setCatalogue(body.reports)
        if (body.reports.length > 0) selectReport(body.reports[0])
      } catch {
        setCatalogue([])
      }
    })()
  }, [selectReport])

  const report = catalogue?.find((r) => r.id === selected) ?? null

  const runReport = useCallback(async () => {
    if (!report) return
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
      if (res.status === 401 || res.status === 403) { setFailure('unauthorized'); setResult(null); return }
      if (res.status === 404) { setFailure('not_found'); setResult(null); return }
      if (res.status === 400) { setFailure('invalid'); setResult(null); return }
      setResult((await res.json()) as ReportResult)
    } catch {
      setResult({ available: false, reportId: report.id, columns: report.columns, rows: [], generatedAt: null, rowCount: 0, truncated: false })
    } finally {
      setLoading(false)
    }
  }, [report, paramValues])

  return (
    <div>
      <PageHeader
        icon={<BarChart3 size={18} aria-hidden="true" />}
        title={t('Reporting nad datovým skladem', 'Warehouse reporting')}
        subtitle={t(
          'Autoritativní reporty přes governed query registry (gold marts) · explorace v Grafaně · ADR-0286',
          'Authoritative reports via the governed query registry (gold marts) · exploration in Grafana · ADR-0286',
        )}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Reporting', 'Reporting')}</span></div>}
      />

      {/* Registry reports */}
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(240px, 300px) 1fr', gap: '16px', marginBottom: '24px' }}>
        <div className="card" style={{ padding: '16px', alignSelf: 'start' }}>
          <h3 style={{ fontSize: '13px', fontWeight: 700, marginBottom: '4px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
            <ShieldCheck size={14} aria-hidden="true" /> {t('Registry reportů', 'Report registry')}
          </h3>
          <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>
            {t('Pojmenované dotazy nad gold vrstvou — browser nikdy neposílá SQL.', 'Named queries over the gold layer — the browser never sends SQL.')}
          </div>
          {catalogueDenied && <DataUnavailable kind="unauthorized" service="Reporting" feature={t('reporting', 'reporting')} dense />}
          {catalogue && catalogue.length === 0 && !catalogueDenied && (
            <DataUnavailable kind="no_data" feature={t('registry reportů', 'report registry')} dense />
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

        <div className="card" style={{ padding: '16px' }}>
          {!report && !catalogueDenied && (
            <DataUnavailable kind="no_data" feature={t('report', 'report')} dense />
          )}
          {report && (
            <>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap', marginBottom: '12px' }}>
                <div>
                  <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{cs ? report.titleCs : report.titleEn}</div>
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace' }}>{report.id} · {report.permission}</div>
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
                        onChange={(e) => setParamValues((v) => ({ ...v, [p.name]: e.target.value }))}
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
                        onChange={(e) => setParamValues((v) => ({ ...v, [p.name]: e.target.value }))}
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
                      <div role="status" style={{ padding: '8px 12px', marginBottom: '8px', color: '#92400e', background: '#fffbeb', border: '1px solid #fde68a', borderRadius: '6px', fontSize: '12px' }}>
                        {t(`Zobrazeno prvních ${result.rowCount} řádků — report je zkrácený. Zužte parametry.`, `Showing the first ${result.rowCount} rows — the report is truncated. Narrow the parameters.`)}
                      </div>
                    )}
                    {result.rows.length === 0 ? (
                      <DataUnavailable kind="no_data" feature={cs ? report.titleCs : report.titleEn} dense />
                    ) : (
                      <div style={{ overflowX: 'auto' }}>
                        <table className="data-table" style={{ width: '100%' }}>
                          <thead>
                            <tr>{result.columns.map((c) => <th key={c.key}>{cs ? c.labelCs : c.labelEn}</th>)}</tr>
                          </thead>
                          <tbody>
                            {result.rows.map((row, i) => (
                              <tr key={i}>
                                {result.columns.map((c) => (
                                  <td key={c.key} style={{ fontSize: '12px', fontFamily: c.format === 'number' ? 'JetBrains Mono, monospace' : 'inherit', textAlign: c.format === 'number' ? 'right' : 'left' }}>
                                    {formatCell(row[c.key], c.format)}
                                  </td>
                                ))}
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                    <div style={{ marginTop: '8px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                      {t('Interní report nad eventuálně konzistentní projekcí (ADR-0022) — nepodklad pro regulatorní výkaz.', 'Internal report over an eventually-consistent projection (ADR-0022) — not a source for a regulatory return.')}
                      {result.generatedAt ? ` · ${result.generatedAt}` : ''}
                    </div>
                  </>
                ) : (
                  <DataUnavailable kind="no_data" service="ClickHouse (analytics)" feature={cs ? report.titleCs : report.titleEn} dense />
                )
              )}
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

      {/* Exploratory surface: embedded Grafana (kiosk) */}
      <div className="card" style={{ padding: '16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap', marginBottom: '12px' }}>
          <div>
            <h3 style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <BarChart3 size={14} aria-hidden="true" /> {t('Explorativní analytika — Grafana', 'Exploratory analytics — Grafana')}
            </h3>
            <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>
              {t('Trendy a ad-hoc pohledy nad warehouse. Autoritativní čísla vždy přes registry výše.', 'Trends and ad-hoc cuts over the warehouse. Authoritative figures always via the registry above.')}
            </div>
          </div>
          <a href={`${GRAFANA_BASE}/d/${GRAFANA_DASHBOARD_UID}`} target="_blank" rel="noreferrer" className="btn btn-secondary" style={{ fontSize: '12px' }}>
            <ExternalLink size={12} aria-hidden="true" /> {t('Otevřít v Grafaně', 'Open in Grafana')}
          </a>
        </div>
        <iframe
          src={GRAFANA_EMBED_URL}
          title={t('Grafana — business warehouse dashboard', 'Grafana — business warehouse dashboard')}
          style={{ width: '100%', height: '560px', border: '1px solid var(--border)', borderRadius: '6px', background: 'var(--surface-2)' }}
        />
      </div>
    </div>
  )
}
