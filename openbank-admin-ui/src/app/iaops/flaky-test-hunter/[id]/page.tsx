// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Flaky Test Hunter finding detail (ADR-0168, issue #5499) — the full record for one finding
// (GET /api/v1/flaky-test-hunter/findings/{id}), including root cause and the proposed fix diff
// when the agent's phase-3 writer has produced one.

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, Bug, RefreshCw, FileText, GitPullRequest } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import type { FlakyTestFinding } from '@/app/api/flaky-test-hunter/findings/route'

function FlakyTestFindingDetailContent() {
  const params = useParams<{ id: string }>()
  const id = params.id
  const { t, language } = useLanguage()
  const [finding, setFinding] = useState<FlakyTestFinding | null>(null)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const res = await fetch(`/api/flaky-test-hunter/findings/${encodeURIComponent(id)}`, { cache: 'no-store', signal: AbortSignal.timeout(10_000) })
      if (res.status === 404) { setUnavailable({ kind: 'not_found' }); return }
      if (!res.ok) { setUnavailable({ kind: 'error' }); return }
      setFinding(await res.json())
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => { load() }, [load])

  const locale = language === 'cs' ? 'cs-CZ' : 'en-US'

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1000px', animation: 'fadeIn 0.2s ease-out' }}>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/iaops" className="breadcrumb-current" style={{ textDecoration: 'none' }}>{t('IAOps', 'IAOps')}</Link><span className="breadcrumb-sep">/</span><Link href="/iaops/flaky-test-hunter" className="breadcrumb-current" style={{ textDecoration: 'none' }}>{t('Flaky testy', 'Flaky tests')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{id}</span></div>}
        icon={<Bug size={20} aria-hidden="true" />}
        title={finding?.title ?? id}
        subtitle={finding?.component}
        actions={<Link href="/iaops/flaky-test-hunter" className="btn btn-secondary btn-sm"><ArrowLeft size={13} aria-hidden="true" /> {t('Zpět na nálezy', 'Back to findings')}</Link>}
      />

      {loading ? (
        <div role="status" aria-live="polite" style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '40px', color: 'var(--text-tertiary)' }}>
          <RefreshCw size={16} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: '13px' }}>{t('Načítám nález…', 'Loading finding…')}</span>
        </div>
      ) : unavailable ? (
        <DataUnavailable kind={unavailable.kind} service={id} feature={t('Nález', 'Finding')} lang={language} />
      ) : finding ? (
        <>
          <div className="card" style={{ marginBottom: '20px' }}>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px', marginBottom: '16px' }}>
              <StatusBadge status={finding.severity} />
              <StatusBadge status={finding.status} />
              <span className="mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)', alignSelf: 'center' }}>{finding.checkType}</span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '14px', fontSize: '12px' }}>
              <div>
                <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Soubor', 'File')}</div>
                <div className="mono" style={{ color: 'var(--text-primary)', wordBreak: 'break-all' }}>{finding.filePath}</div>
              </div>
              <div>
                <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Naměřená hodnota / práh', 'Measured value / threshold')}</div>
                <div className="mono" style={{ color: 'var(--text-primary)' }}>{String(finding.rawMetricValue)} / {String(finding.threshold)}</div>
              </div>
              <div>
                <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Zjištěno', 'Detected')}</div>
                <div style={{ color: 'var(--text-primary)' }}>{new Date(finding.detectedAt).toLocaleString(locale)}</div>
              </div>
              {finding.diagnosedAt && (
                <div>
                  <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Diagnostikováno', 'Diagnosed')}</div>
                  <div style={{ color: 'var(--text-primary)' }}>{new Date(finding.diagnosedAt).toLocaleString(locale)}</div>
                </div>
              )}
              {finding.proposedAt && (
                <div>
                  <div style={{ color: 'var(--text-tertiary)', marginBottom: '3px' }}>{t('Navrženo', 'Proposed')}</div>
                  <div style={{ color: 'var(--text-primary)' }}>{new Date(finding.proposedAt).toLocaleString(locale)}</div>
                </div>
              )}
            </div>
          </div>

          {finding.rootCause && (
            <div className="card" style={{ marginBottom: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
                <FileText size={14} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '13px', fontWeight: 700 }}>{t('Příčina', 'Root cause')}</span>
              </div>
              <p style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: 1.6, margin: 0, whiteSpace: 'pre-wrap' }}>{finding.rootCause}</p>
            </div>
          )}

          {finding.proposedFixDiff && (
            <div className="card" style={{ marginBottom: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
                <GitPullRequest size={14} style={{ color: '#6366f1' }} />
                <span style={{ fontSize: '13px', fontWeight: 700 }}>{t('Navržená oprava', 'Proposed fix')}</span>
                {finding.proposalUrl && (
                  <a href={finding.proposalUrl} target="_blank" rel="noopener noreferrer" style={{ fontSize: '11px', fontWeight: 700, color: '#6366f1', textDecoration: 'none', marginLeft: 'auto' }}>
                    {t('Zobrazit PR →', 'View PR →')}
                  </a>
                )}
              </div>
              <pre style={{ fontSize: '11px', background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '8px',
                padding: '12px', overflowX: 'auto', margin: 0, fontFamily: 'monospace', color: 'var(--text-primary)' }}>
                {finding.proposedFixDiff}
              </pre>
            </div>
          )}
        </>
      ) : null}
    </div>
  )
}

export default function FlakyTestFindingDetailPage() {
  return (
    <AuthGuard permission="system:view">
      <FlakyTestFindingDetailContent />
    </AuthGuard>
  )
}
