// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Flaky Test Hunter findings (ADR-0168, issue #5499) — active findings from the four checks
// (RunBlocking-unit drop, Pact local-verification blind spot, Pact provider collision, test-count
// drift), plus an operator "Run check now" trigger for the weekly sweep (FlakyTestResource,
// POST /check/trigger). Data flows through the dedicated BFF (/api/flaky-test-hunter/**), the
// same pattern devops-agent and finops-agent use, since the agent's namespace is not enumerated
// by ADR-0051 discovery (see the route comment).

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { FlaskConical, Play, RefreshCw, AlertTriangle, Bug, Clock, ChevronRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { hasPermission } from '@/lib/auth/roles'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import type { FlakyTestFinding } from '@/app/api/flaky-test-hunter/findings/route'
import type { FlakyTestReport } from '@/app/api/flaky-test-hunter/trigger/route'

const CHECK_TYPE_LABEL: Record<FlakyTestFinding['checkType'], { cs: string; en: string }> = {
  RUNBLOCKING_UNIT_MISSING:          { cs: 'runBlocking bez Unit',        en: 'runBlocking missing Unit' },
  PACT_LOCAL_VERIFICATION_BLIND_SPOT:{ cs: 'Pact — slepé místo lokálně',  en: 'Pact local blind spot' },
  PACT_PROVIDER_CLASS_COLLISION:     { cs: 'Pact — kolize providerů',     en: 'Pact provider collision' },
  TEST_COUNT_DRIFT:                  { cs: 'Odchylka počtu testů',       en: 'Test count drift' },
}

function FlakyTestHunterContent() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles: string[] = session?.user?.roles ?? []
  const canTrigger = hasPermission(roles, 'flaky-test-hunter:trigger')

  const [findings, setFindings] = useState<FlakyTestFinding[]>([])
  const [available, setAvailable] = useState(true)
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [triggering, setTriggering] = useState(false)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  const [lastReport, setLastReport] = useState<FlakyTestReport | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    try {
      const res = await fetch('/api/flaky-test-hunter/findings', { cache: 'no-store', signal: AbortSignal.timeout(10_000) })
      if (!res.ok) { setUnavailable({ kind: 'error' }); return }
      const body = await res.json() as { findings?: FlakyTestFinding[]; available?: boolean }
      setFindings(body.findings ?? [])
      setAvailable(body.available !== false)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const runCheck = useCallback(async () => {
    setTriggering(true)
    setNotice(null)
    try {
      const res = await fetch('/api/flaky-test-hunter/trigger', { method: 'POST', signal: AbortSignal.timeout(65_000) })
      if (!res.ok) {
        setNotice({
          ok: false,
          text: res.status === 403
            ? t('Nemáte oprávnění spustit kontrolu.', 'You are not authorized to run the check.')
            : t('Spuštění kontroly se nezdařilo.', 'Could not run the check.'),
        })
        return
      }
      const report = await res.json() as FlakyTestReport
      setLastReport(report)
      setNotice({
        ok: true,
        text: t(
          `Kontrola dokončena — ${report.findingsDetected.length} nálezů, ${report.testFilesScanned} souborů`,
          `Check completed — ${report.findingsDetected.length} findings across ${report.testFilesScanned} files`,
        ),
      })
      void load()
    } catch {
      setNotice({ ok: false, text: t('Spuštění kontroly se nezdařilo.', 'Could not run the check.') })
    } finally {
      setTriggering(false)
    }
  }, [t, load])

  const critical = findings.filter(f => f.severity === 'CRITICAL').length
  const open = findings.filter(f => f.status === 'OPEN').length

  if (unavailable) {
    return <DataUnavailable kind={unavailable.kind} service="flaky-test-hunter" feature={t('Flaky testy', 'Flaky tests')} lang={language} />
  }

  return (
    <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
      <PageHeader
        icon={<Bug size={20} aria-hidden="true" />}
        title={t('Flaky Test Hunter', 'Flaky Test Hunter')}
        subtitle={t(
          'Tichá selhání testů napříč monorepem — ADR-0168, týdenní sweep neděle 06:30 UTC',
          'Silent test-failure patterns across the monorepo — ADR-0168, weekly sweep Sunday 06:30 UTC',
        )}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><Link href="/iaops" className="breadcrumb-current" style={{ textDecoration: 'none' }}>{t('IAOps', 'IAOps')}</Link><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Flaky testy', 'Flaky tests')}</span></div>}
        actions={
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <button type="button" className="btn btn-secondary" onClick={() => void load()} disabled={loading} aria-busy={loading} aria-label={t('Obnovit flaky testy', 'Refresh flaky tests')}>
              <RefreshCw size={13} aria-hidden="true" className={loading ? 'animate-spin' : undefined} />
              {t('Obnovit', 'Refresh')}
            </button>
            {canTrigger && (
              <button
                className="btn btn-primary"
                onClick={() => void runCheck()}
                disabled={triggering}
                title={t('Spustit kontrolu nyní', 'Run the check now')}
              >
                <Play size={13} className={triggering ? 'animate-spin' : undefined} />
                {triggering ? t('Spouštím…', 'Running…') : t('Spustit kontrolu', 'Run check now')}
              </button>
            )}
          </div>
        }
      />

      {!available && (
        <div style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px',
          background: 'var(--surface-2)', border: '1px solid var(--border)',
          display: 'flex', alignItems: 'center', gap: '10px' }}>
          <AlertTriangle size={16} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
          <span style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
            {t('flaky-test-hunter není v tomto prostředí dostupný — zobrazuji poslední známý stav (žádný).', 'flaky-test-hunter is not reachable in this environment — showing the last known state (none).')}
          </span>
        </div>
      )}

      {notice && (
        <div style={{
          marginBottom: '20px', padding: '10px 14px', borderRadius: 'var(--r-md)', fontSize: '13px',
          background: notice.ok ? 'var(--success-bg)' : 'var(--warning-bg)',
          border: `1px solid ${notice.ok ? 'var(--success-border)' : 'var(--warning-border)'}`,
          color: notice.ok ? 'var(--success)' : 'var(--warning)',
        }}>
          {notice.text}
        </div>
      )}

      <div className="grid-4" style={{ marginBottom: '24px' }}>
        <StatCard label={t('Aktivní nálezy', 'Active findings')} value={findings.length} icon={<FlaskConical size={16} />} />
        <StatCard label={t('Kritické', 'Critical')} value={critical} icon={<AlertTriangle size={16} />} tone={critical > 0 ? 'danger' : undefined} />
        <StatCard label={t('Otevřené', 'Open')} value={open} icon={<Bug size={16} />} tone={open > 0 ? 'warning' : undefined} />
        <StatCard
          label={t('Poslední běh', 'Last run')}
          value={lastReport ? new Date(lastReport.completedAt).toLocaleTimeString(language === 'cs' ? 'cs-CZ' : 'en-US') : '—'}
          icon={<Clock size={16} />}
          hint={lastReport ? `${lastReport.testFilesScanned} ${t('souborů', 'files')}` : undefined}
        />
      </div>

      <div className="card">
        {loading ? (
          <div role="status" aria-live="polite" style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
            <RefreshCw size={20} aria-hidden="true" className="animate-spin" style={{ marginBottom: '8px', margin: '0 auto', display: 'block' }} />
            <div>{t('Načítám…', 'Loading…')}</div>
          </div>
        ) : findings.length === 0 ? (
          <DataUnavailable kind="no_data" feature={t('Nálezy', 'Findings')} lang={language}
            detail={t('Žádné aktivní nálezy — poslední sweep nic nenašel.', 'No active findings — the last sweep found nothing.')} />
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="table">
              <thead><tr>
                {[
                  t('Závažnost', 'Severity'), t('Typ kontroly', 'Check type'), t('Komponenta', 'Component'),
                  t('Nález', 'Finding'), t('Status', 'Status'), t('Zjištěno', 'Detected'), '',
                ].map(h => <th key={h}>{h}</th>)}
              </tr></thead>
              <tbody>
                {findings.map(f => (
                  <tr key={f.id}>
                    <td><StatusBadge status={f.severity} /></td>
                    <td className="mono" style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                      {language === 'cs' ? CHECK_TYPE_LABEL[f.checkType].cs : CHECK_TYPE_LABEL[f.checkType].en}
                    </td>
                    <td className="mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{f.component}</td>
                    <td style={{ fontWeight: 600 }}>{f.title}</td>
                    <td><StatusBadge status={f.status} /></td>
                    <td style={{ color: 'var(--text-tertiary)' }}>{new Date(f.detectedAt).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-US')}</td>
                    <td>
                      <Link href={`/iaops/flaky-test-hunter/${encodeURIComponent(f.id)}`} className="btn btn-secondary btn-sm">
                        {t('Detail', 'Detail')} <ChevronRight size={12} />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}

export default function FlakyTestHunterPage() {
  return (
    <AuthGuard permission="system:view">
      <FlakyTestHunterContent />
    </AuthGuard>
  )
}
