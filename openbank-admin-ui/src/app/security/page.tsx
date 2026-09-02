// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect, useCallback } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { Shield, RefreshCw, CheckCircle2, XCircle, AlertTriangle } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { summarizeReachable, serviceVerdict } from '@/lib/security/summary'
import { PageHeader } from '@/components/ui/PageHeader'

// Envelope returned by /api/security (never 500s — see that route): either the
// scanner answered with a report, or it's unavailable with a typed reason that
// maps straight onto <DataUnavailable kind=...>.
type SecurityEnvelope =
  | { available: true; report: PlatformReport }
  | { available: false; reason: 'not_deployed' | 'unreachable' | 'error' | 'unauthorized'; detail?: string }

interface ScanResult {
  serviceName: string; serviceUrl: string
  score: number; grade: string; scannedAt: string
  findings: Finding[]
  reachable: boolean; durationMs: number
  headersPresent: Record<string, boolean>
  openApiAvailable: boolean
}

interface PlatformReport {
  reportId: string; generatedAt: string
  totalServices: number; reachableServices: number
  serviceResults: ScanResult[]
  platformScore: number; platformGrade: string
  criticalFindings: number; highFindings: number
  owaspCoverage: Record<string, number>
  complianceStatus: Record<string, boolean>
}

interface Finding {
  id: string; category: string; severity: string; title: string
  description: string; remediation: string; cweId?: string; cvssScore?: number; endpoint?: string
}

const SEVERITY_COLORS: Record<string, { bg: string; text: string; border: string }> = {
  CRITICAL: { bg: '#fef2f2', text: '#991b1b', border: '#fecaca' },
  HIGH:     { bg: 'var(--danger-bg)',   text: 'var(--danger-text)',   border: 'var(--danger-border)' },
  MEDIUM:   { bg: 'var(--warning-bg)',  text: 'var(--warning-text)',  border: 'var(--warning-border)' },
  LOW:      { bg: 'var(--info-bg)',     text: 'var(--info-text)',     border: 'var(--info-border)' },
  INFO:     { bg: 'var(--surface-3)',   text: 'var(--text-tertiary)', border: 'var(--border)' },
}

const GRADE_COLORS: Record<string, string> = {
  'A+': '#059669', A: '#10b981', B: '#3b82f6', C: '#f59e0b', D: '#ef4444', F: '#991b1b'
}

const OWASP_LABELS: Record<string, [string, string]> = {
  A01_BROKEN_ACCESS_CONTROL:       ['A01 Řízení přístupu',      'A01 Access Control'],
  A02_CRYPTOGRAPHIC_FAILURES:      ['A02 Kryptografie',          'A02 Cryptographic Failures'],
  A03_INJECTION:                   ['A03 Injection',             'A03 Injection'],
  A04_INSECURE_DESIGN:             ['A04 Návrh systému',         'A04 Insecure Design'],
  A05_SECURITY_MISCONFIGURATION:   ['A05 Konfigurace',           'A05 Misconfiguration'],
  A06_VULNERABLE_COMPONENTS:       ['A06 Zranitelné komponenty', 'A06 Vulnerable Components'],
  A07_AUTH_FAILURES:               ['A07 Autentizace',           'A07 Auth Failures'],
  A08_SOFTWARE_INTEGRITY_FAILURES: ['A08 Integrita SW',          'A08 Integrity Failures'],
  A09_LOGGING_MONITORING_FAILURES: ['A09 Logování',              'A09 Logging Failures'],
  A10_SSRF:                        ['A10 SSRF',                  'A10 SSRF'],
}

export default function SecurityPage() {
  const [report, setReport] = useState<PlatformReport | null>(null)
  const { t, language } = useLanguage()
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<ScanResult | null>(null)
  const [filter, setFilter] = useState<'ALL' | 'CRITICAL' | 'HIGH'>('ALL')
  // When the scanner can't be reached we render <DataUnavailable> instead of an
  // empty "run the first scan" prompt that an operator misreads as "broken".
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind; detail?: string } | null>(null)

  const applyEnvelope = useCallback((data: SecurityEnvelope) => {
    if (data.available) {
      setUnavailable(null)
      setReport(data.report)
      if (data.report.serviceResults?.length > 0) setSelected(data.report.serviceResults[0])
    } else {
      setReport(null)
      setUnavailable({ kind: data.reason, detail: data.detail })
    }
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/security', { cache: 'no-store' })
      const data: SecurityEnvelope = await res.json()
      applyEnvelope(data)
    } catch {
      // The route is designed never to throw; a failure here is the internal
      // route itself being unavailable, which is still a "can't load" state.
      setReport(null)
      setUnavailable({ kind: 'error' })
    } finally {
      setLoading(false)
    }
  }, [applyEnvelope])

  useEffect(() => { load() }, [load])

  const results = report?.serviceResults ?? []
  // Only services the scanner could actually reach have a meaningful verdict. An
  // unreachable service has NO known findings — counting it as a vulnerability, or
  // letting its upstream F-grade drag the platform score, is a false positive. So
  // the headline counts/score are computed over reachable services only (pure,
  // unit-tested in src/lib/security/summary.ts); the unreachable ones are surfaced
  // separately as a coverage gap, not a failure.
  const { unreachableCount, criticalCount, highCount, avgScore, platformGrade } = summarizeReachable(results)

  const filteredResults = results.filter(r => {
    if (filter === 'ALL') return true
    if (filter === 'CRITICAL') return r.findings.some(f => f.severity === 'CRITICAL')
    if (filter === 'HIGH') return r.findings.some(f => f.severity === 'CRITICAL' || f.severity === 'HIGH')
    return true
  })

  const formatDate = (d: string) => {
    try {
      return new Intl.DateTimeFormat('cs-CZ', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(d))
    } catch {
      return d
    }
  }

  return (
    <AuthGuard permission="system:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <PageHeader
          icon={<Shield size={20} aria-hidden="true" />}
          title={t('Bezpečnostní skener', 'Security Scanner')}
          subtitle={t('OWASP Top 10 · EBA ICT · CVE skenování — všechny mikroservisy', 'OWASP Top 10 · EBA ICT · CVE scanning — all microservices')}
          actions={<div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            {report?.generatedAt && (
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginRight: '8px' }}>
                {t('Poslední sken:', 'Last scan:')} {formatDate(report.generatedAt)}
              </span>
            )}
            {report && (
              <span style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '13px', fontWeight: 800,
                padding: '4px 12px', borderRadius: '20px',
                background: ['A+','A'].includes(platformGrade) ? 'var(--success-bg)' : ['B'].includes(platformGrade) ? 'var(--info-bg)' : ['C'].includes(platformGrade) ? 'var(--warning-bg)' : 'var(--danger-bg)',
                color: ['A+','A'].includes(platformGrade) ? 'var(--success-text)' : ['B'].includes(platformGrade) ? 'var(--info-text)' : ['C'].includes(platformGrade) ? 'var(--warning-text)' : 'var(--danger-text)',
                border: `1px solid ${['A+','A'].includes(platformGrade) ? 'var(--success-border)' : ['B'].includes(platformGrade) ? 'var(--info-border)' : ['C'].includes(platformGrade) ? 'var(--warning-border)' : 'var(--danger-border)'}` }}>
                <Shield size={12} /> {platformGrade} · {avgScore}/100
              </span>
            )}
            <button
              type="button"
              onClick={load}
              disabled={loading}
              aria-busy={loading}
              aria-label={t('Obnovit bezpečnostní sken', 'Refresh security scan')}
              className="btn btn-secondary btn-sm"
            >
              <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />
              {t('Obnovit', 'Refresh')}
            </button>
          </div>}
        />
        <p style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '-20px', marginBottom: '28px', display: 'flex', alignItems: 'center', gap: '5px' }}>
          <Shield size={11} aria-hidden="true" style={{ flexShrink: 0 }} />
          {t('Skeny běží automaticky v CI/CD pipeline (Trivy · CodeQL · Gitleaks). Tato stránka zobrazuje poslední výsledky — pouze ke čtení.', 'Scans run automatically in the CI/CD pipeline (Trivy · CodeQL · Gitleaks). This page is a read-only view of the latest results.')}
        </p>

        {criticalCount > 0 && (
          <div style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px',
            background: '#fef2f2', border: '1px solid #fecaca',
            display: 'flex', alignItems: 'center', gap: '10px' }}>
            <AlertTriangle size={16} style={{ color: '#dc2626', flexShrink: 0 }} />
            <span style={{ fontSize: '13px', fontWeight: 600, color: '#991b1b' }}>
              {criticalCount} {criticalCount > 1 ? t('kritické zranitelnosti', 'critical vulnerabilities') : t('kritická zranitelnost', 'critical vulnerability')} — {t('okamžitá akce nutná', 'immediate action required')}
            </span>
          </div>
        )}

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Průměrné skóre', 'Platform Score'), value: `${avgScore}/100`, icon: <Shield size={16} />, color: avgScore >= 80 ? 'var(--success)' : avgScore >= 60 ? 'var(--warning)' : 'var(--danger)' },
            { label: t('Kritické', 'Critical'), value: criticalCount, icon: <AlertTriangle size={16} />, color: '#dc2626' },
            { label: t('Vysoké', 'High'), value: highCount, icon: <AlertTriangle size={16} />, color: 'var(--danger)' },
            { label: t('Skenované služby', 'Scanned Services'), value: `${report?.reachableServices ?? 0}/${report?.totalServices ?? 0}`, icon: <CheckCircle2 size={16} />, color: 'var(--accent)' },
          ].map(k => (
            <div key={k.label} className="stat-card">
              <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`,
                display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color, marginBottom: '10px' }}>{k.icon}</div>
              <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{k.value}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
            </div>
          ))}
        </div>

        {unreachableCount > 0 && (
          <div style={{ marginBottom: '20px', padding: '10px 16px', borderRadius: '8px',
            background: 'var(--info-bg)', border: '1px solid var(--info-border)',
            display: 'flex', alignItems: 'center', gap: '10px' }}>
            <Shield size={15} style={{ color: 'var(--info-text)', flexShrink: 0 }} />
            <span style={{ fontSize: '12px', color: 'var(--info-text)' }}>
              {t(
                `${unreachableCount} ${unreachableCount === 1 ? 'služba byla' : 'služeb bylo'} nedostupná při skenu (nejspíš scale-to-zero) — nebyla skenována. Chybějící sken není zranitelnost; skóre a počty výše vychází pouze z dosažitelných služeb.`,
                `${unreachableCount} service${unreachableCount === 1 ? ' was' : 's were'} unreachable during the scan (likely scaled to zero) — not scanned. A missing scan is not a vulnerability; the score and counts above reflect only the reachable services.`,
              )}
            </span>
          </div>
        )}

        {report?.complianceStatus && (
          <div className="card" style={{ padding: '16px 20px', marginBottom: '20px', display: 'flex', gap: '12px', flexWrap: 'wrap', alignItems: 'center' }}>
            <span style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em', marginRight: '4px' }}>{t('Shoda', 'Compliance')}</span>
            {Object.entries(report.complianceStatus).map(([key, ok]) => (
              <span key={key} style={{ display: 'flex', alignItems: 'center', gap: '4px', padding: '3px 10px', borderRadius: '12px', fontSize: '11px', fontWeight: 600,
                background: ok ? 'var(--success-bg)' : 'var(--danger-bg)',
                color: ok ? 'var(--success-text)' : 'var(--danger-text)',
                border: `1px solid ${ok ? 'var(--success-border)' : 'var(--danger-border)'}` }}>
                {ok ? <CheckCircle2 size={10} /> : <XCircle size={10} />}
                {key.replace(/_/g, ' ')}
              </span>
            ))}
          </div>
        )}

        {report?.owaspCoverage && Object.keys(report.owaspCoverage).length > 0 && (
          <div className="card" style={{ padding: '16px 20px', marginBottom: '20px' }}>
            <div style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '12px' }}>
              {t('OWASP Top 10 — nálezy', 'OWASP Top 10 — findings')}
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '8px' }}>
              {Object.entries(report.owaspCoverage).map(([key, count]) => {
                const [labelCs, labelEn] = OWASP_LABELS[key] ?? [key, key]
                const label = t(labelCs, labelEn)
                const color = count === 0 ? 'var(--success-text)' : count < 3 ? 'var(--warning-text)' : 'var(--danger-text)'
                const bg    = count === 0 ? 'var(--success-bg)'   : count < 3 ? 'var(--warning-bg)'   : 'var(--danger-bg)'
                const border= count === 0 ? 'var(--success-border)': count < 3 ? 'var(--warning-border)': 'var(--danger-border)'
                return (
                  <div key={key} style={{ padding: '8px 10px', borderRadius: '6px', background: bg, border: `1px solid ${border}`,
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px' }}>
                    <span style={{ fontSize: '11px', color: 'var(--text-secondary)', fontWeight: 500, lineHeight: 1.3 }}>{label}</span>
                    <span style={{ fontSize: '16px', fontWeight: 800, color, flexShrink: 0 }}>{count}</span>
                  </div>
                )
              })}
            </div>
          </div>
        )}

        {loading ? (
          <div role="status" aria-live="polite" className="card" style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
            <RefreshCw size={20} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám výsledky…', 'Loading results…')}</div>
          </div>
        ) : unavailable ? (
          <div className="card" style={{ padding: 0 }}>
            <DataUnavailable
              kind={unavailable.kind}
              service={t('Bezpečnostní skener', 'Security Scanner')}
              feature={t('Bezpečnostní sken', 'Security scan')}
              lang={language}
              detail={unavailable.detail && unavailable.kind === 'error' ? unavailable.detail : undefined}
            />
          </div>
        ) : results.length === 0 ? (
          <div className="card" style={{ padding: 0 }}>
            <DataUnavailable
              kind="no_data"
              feature={t('Výsledky skenování', 'Scan results')}
              lang={language}
              detail={t('Zatím nejsou k dispozici žádné výsledky z CI. Objeví se po prvním běhu bezpečnostního skenu v pipeline.', 'No CI results are available yet. They will appear after the first security-scan run in the pipeline.')}
            />
          </div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(500px, 1.2fr) 1fr', gap: '20px' }}>
            <div className="card" style={{ padding: 0, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
              <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {t('Výsledky skenování', 'Scan Results')} <span style={{ fontWeight: 400, color: 'var(--text-tertiary)' }}>({results.length})</span>
                </span>
                <div role="group" aria-label={t('Filtr závažnosti nálezů', 'Finding severity filters')} style={{ display: 'flex', gap: '4px', background: 'var(--surface-2)', padding: '4px', borderRadius: '6px' }}>
                  {(['ALL', 'CRITICAL', 'HIGH'] as const).map(f => (
                    <button key={f} type="button" aria-pressed={filter === f} onClick={() => setFilter(f)}
                      style={{ padding: '4px 10px', borderRadius: '4px', fontSize: '11px', fontWeight: 600, border: 'none', cursor: 'pointer',
                        background: filter === f ? 'var(--surface-1)' : 'transparent',
                        color: filter === f ? 'var(--text-primary)' : 'var(--text-tertiary)',
                        boxShadow: filter === f ? '0 1px 2px rgba(0,0,0,0.05)' : 'none', transition: 'all 0.2s' }}>
                      {f === 'ALL' ? t('Vše', 'All') : f === 'CRITICAL' ? t('Kritické', 'Critical') : t('Vysoké', 'High')}
                    </button>
                  ))}
                </div>
              </div>
              
              <div style={{ overflowY: 'auto', maxHeight: '600px' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                  <thead style={{ position: 'sticky', top: 0, background: 'var(--surface-1)', zIndex: 1 }}>
                    <tr style={{ borderBottom: '1px solid var(--border)', fontSize: '11px', color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                      <th style={{ padding: '12px 16px', fontWeight: 600 }}>{t('Služba', 'Service')}</th>
                      <th style={{ padding: '12px 16px', fontWeight: 600 }}>{t('Skóre', 'Score')}</th>
                      <th style={{ padding: '12px 16px', fontWeight: 600 }}>{t('Nálezy', 'Findings')}</th>
                      <th style={{ padding: '12px 16px', fontWeight: 600 }}>{t('Čas', 'Time')}</th>
                      <th style={{ padding: '12px 16px', fontWeight: 600 }}>{t('Status', 'Status')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredResults.map((r, i) => {
                      const isSelected = selected?.serviceName === r.serviceName
                      return (
                        <tr key={`${r.serviceName}-${i}`} tabIndex={0} aria-label={t(`Vybrat službu ${r.serviceName}`, `Select service ${r.serviceName}`)} onClick={() => setSelected(r)}
                          onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSelected(r) } }}
                          style={{ borderBottom: '1px solid var(--border)', cursor: 'pointer',
                            background: isSelected ? 'var(--accent-light)' : 'transparent',
                            transition: 'background 0.15s' }}
                          onMouseEnter={e => { if (!isSelected) e.currentTarget.style.background = 'var(--surface-2)' }}
                          onMouseLeave={e => { if (!isSelected) e.currentTarget.style.background = 'transparent' }}>
                          <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>
                            <div>{r.serviceName}</div>
                            {!r.reachable && <div style={{ fontSize: '10px', color: 'var(--danger-text)', fontWeight: 400 }}>{t('nedostupné', 'unreachable')}</div>}
                          </td>
                          <td style={{ padding: '12px 16px' }}>
                            {r.reachable ? (
                              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                                <span style={{ fontSize: '14px', fontWeight: 800, color: GRADE_COLORS[r.grade] ?? 'var(--text-secondary)' }}>{r.grade}</span>
                                <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: 'var(--text-tertiary)' }}>{r.score}</span>
                              </div>
                            ) : (
                              <span style={{ fontSize: '14px', fontWeight: 800, color: 'var(--text-tertiary)' }}>—</span>
                            )}
                          </td>
                          <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                            {r.reachable ? r.findings.length : '—'}
                          </td>
                          <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                            {formatDate(r.scannedAt)}
                          </td>
                          <td style={{ padding: '12px 16px' }}>
                            {(() => {
                              // Verdict is centralized + unit-tested; unreachable → "Not scanned",
                              // never a red "Fail" (that mis-mapping was the false positive).
                              const style = {
                                not_scanned: { bg: 'var(--info-bg)',    fg: 'var(--info-text)',    bd: 'var(--info-border)',    label: t('Nelze skenovat', 'Not scanned') },
                                fail:        { bg: 'var(--danger-bg)',  fg: 'var(--danger-text)',  bd: 'var(--danger-border)',  label: t('Selhání', 'Fail') },
                                pass:        { bg: 'var(--success-bg)', fg: 'var(--success-text)', bd: 'var(--success-border)', label: t('V pořádku', 'Pass') },
                                review:      { bg: 'var(--warning-bg)', fg: 'var(--warning-text)', bd: 'var(--warning-border)', label: t('Ke kontrole', 'Review') },
                              }[serviceVerdict(r)]
                              return (
                                <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '10px', fontWeight: 700,
                                  background: style.bg, color: style.fg, border: `1px solid ${style.bd}` }}>
                                  {style.label}
                                </span>
                              )
                            })()}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </div>

            {selected && (
              <div className="card" style={{ padding: 0, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
                <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', background: 'var(--surface-1)' }}>
                  <div>
                    <div style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)' }}>{selected.serviceName}</div>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)' }}>{selected.serviceUrl}</div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    {selected.reachable ? (
                      <>
                        <span style={{ fontSize: '20px', fontWeight: 900, color: GRADE_COLORS[selected.grade] ?? 'var(--text-secondary)' }}>{selected.grade}</span>
                        <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{selected.score}/100</span>
                      </>
                    ) : (
                      <span style={{ padding: '3px 10px', borderRadius: '12px', fontSize: '11px', fontWeight: 700,
                        background: 'var(--info-bg)', color: 'var(--info-text)', border: '1px solid var(--info-border)' }}>
                        {t('Nedostupné', 'Unreachable')}
                      </span>
                    )}
                  </div>
                </div>
                <div style={{ overflowY: 'auto', maxHeight: '540px' }}>
                  {!selected.reachable ? (
                    // Not reachable during the scan — no verdict either way. Do NOT
                    // show the green "passed all checks" state (that would be a false
                    // positive in the opposite direction).
                    <div style={{ padding: '48px 32px', textAlign: 'center' }}>
                      <Shield size={32} style={{ color: 'var(--info-text)', marginBottom: '12px', marginInline: 'auto' }} />
                      <div style={{ fontSize: '15px', fontWeight: 600, color: 'var(--text-primary)' }}>{t('Služba nebyla skenována', 'Service was not scanned')}</div>
                      <div style={{ fontSize: '13px', color: 'var(--text-secondary)', maxWidth: '360px', margin: '4px auto 0' }}>
                        {t('Při skenu byla nedostupná (nejspíš scale-to-zero). Chybějící sken neznamená zranitelnost ani že služba prošla — jen že se nedala prověřit.', 'It was unreachable during the scan (likely scaled to zero). A missing scan means neither a vulnerability nor a pass — only that it could not be checked.')}
                      </div>
                    </div>
                  ) : (selected.findings ?? []).length === 0 ? (
                    <div style={{ padding: '48px 32px', textAlign: 'center' }}>
                      <CheckCircle2 size={32} style={{ color: 'var(--success)', marginBottom: '12px', marginInline: 'auto' }} />
                      <div style={{ fontSize: '15px', fontWeight: 600, color: 'var(--text-primary)' }}>{t('Žádné nálezy', 'No findings')}</div>
                      <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>{t('Tato služba prošla všemi kontrolami.', 'This service passed all checks.')}</div>
                    </div>
                  ) : (
                    <div style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
                      {selected.findings.map(f => {
                        const sc = SEVERITY_COLORS[f.severity] ?? SEVERITY_COLORS.INFO
                        return (
                          <div key={f.id} style={{ padding: '16px', borderRadius: '8px', border: `1px solid ${sc.border}`, background: sc.bg }}>
                            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '8px' }}>
                              <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)', lineHeight: 1.4 }}>{f.title}</span>
                              <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '10px', fontWeight: 700,
                                background: sc.bg, color: sc.text, border: `1px solid ${sc.border}`, flexShrink: 0, marginLeft: '12px' }}>{f.severity}</span>
                            </div>
                            {f.cweId && <div style={{ fontSize: '10px', fontFamily: 'var(--font-mono)', color: 'var(--text-tertiary)', marginBottom: '6px' }}>{f.cweId}{f.cvssScore ? ` · CVSS ${f.cvssScore}` : ''}</div>}
                            <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginBottom: '12px', lineHeight: 1.5 }}>{f.description}</div>
                            {f.remediation && (
                              <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', background: 'rgba(0,0,0,0.02)', padding: '8px 12px', borderRadius: '4px', borderLeft: '3px solid var(--accent)' }}>
                                {f.remediation}
                              </div>
                            )}
                          </div>
                        )
                      })}
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </AuthGuard>
  )
}
