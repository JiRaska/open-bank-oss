// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Flaky Test Hunter findings (ADR-0168, issue #5499) — active findings from the four checks
// (RunBlocking-unit drop, Pact local-verification blind spot, Pact provider collision, test-count
// drift), plus an operator "Run check now" trigger for the weekly sweep. Trigger admission is
// asynchronous: the bounded IAOps BFF returns a durable workflow id, never a completed report.
// Findings continue to flow through their dedicated read-only BFF.

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

const CHECK_TYPE_LABEL: Record<FlakyTestFinding['checkType'], { cs: string; en: string }> = {
  RUNBLOCKING_UNIT_MISSING:          { cs: 'runBlocking bez Unit',        en: 'runBlocking missing Unit' },
  PACT_LOCAL_VERIFICATION_BLIND_SPOT:{ cs: 'Pact — slepé místo lokálně',  en: 'Pact local blind spot' },
  PACT_PROVIDER_CLASS_COLLISION:     { cs: 'Pact — kolize providerů',     en: 'Pact provider collision' },
  TEST_COUNT_DRIFT:                  { cs: 'Odchylka počtu testů',       en: 'Test count drift' },
}

const TRIGGER_RECOVERY_DAY_KEY = 'openbank.flaky-test-hunter.trigger.requested-on'

function currentUtcDay(): string {
  return new Date().toISOString().slice(0, 10)
}

function triggerRecoveryDay(): string {
  try {
    return window.localStorage.getItem(TRIGGER_RECOVERY_DAY_KEY) || currentUtcDay()
  } catch {
    return currentUtcDay()
  }
}

function persistTriggerRecoveryDay(requestedOn: string | null) {
  try {
    if (requestedOn) window.localStorage.setItem(TRIGGER_RECOVERY_DAY_KEY, requestedOn)
    else window.localStorage.removeItem(TRIGGER_RECOVERY_DAY_KEY)
  } catch {
    // The in-memory request still reuses the same day for this mounted page. A browser that blocks
    // storage loses cross-reload recovery, but the server's one-workflow-per-UTC-day key remains.
  }
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
  const [notice, setNotice] = useState<{ tone: 'accepted' | 'nonfinal' | 'error'; text: string } | null>(null)
  const [lastWorkflowId, setLastWorkflowId] = useState<string | null>(null)
  const [recoveryDay, setRecoveryDay] = useState<string | null>(null)

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
    const requestedOn = recoveryDay ?? triggerRecoveryDay()
    setRecoveryDay(requestedOn)
    persistTriggerRecoveryDay(requestedOn)
    try {
      const res = await fetch('/api/iaops/flaky-test-hunter/trigger', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ requestedOn }),
        signal: AbortSignal.timeout(15_000),
      })
      const payload = await res.json().catch(() => null) as {
        error?: unknown
        workflowId?: unknown
        upstreamStatus?: unknown
      } | null
      if (res.status === 401 || res.status === 403) {
        setNotice({
          tone: 'error',
          text: t(
            `Nemáte oprávnění spustit kontrolu. Po obnovení přístupu se znovu použije UTC klíč ${requestedOn}.`,
            `You are not authorized to run the check. After access is restored, retry reuses UTC key ${requestedOn}.`,
          ),
        })
        return
      }
      if (payload?.error === 'idempotent_admission_not_supported') {
        setNotice({
          tone: 'nonfinal',
          text: t(
            `Tato backendová replika ještě nepodporuje idempotentní přijetí. Tento pokus workflow nespustil; další pokus znovu použije UTC klíč ${requestedOn}.`,
            `This backend replica does not support idempotent admission yet. This attempt did not start a workflow; the next retry reuses UTC key ${requestedOn}.`,
          ),
        })
        return
      }
      if (res.status === 400 || payload?.error === 'admission_rejected') {
        persistTriggerRecoveryDay(null)
        setRecoveryDay(null)
        const rejectedStatus = typeof payload?.upstreamStatus === 'number' ? payload.upstreamStatus : res.status
        setNotice({
          tone: 'error',
          text: t(
            `Požadavek byl odmítnut před přijetím (HTTP ${rejectedStatus}). Je bezpečné jej po opravě zopakovat.`,
            `The request was rejected before admission (HTTP ${rejectedStatus}). It is safe to retry after correcting it.`,
          ),
        })
        return
      }
      if (payload?.error === 'admission_accepted_handle_unknown') {
        setNotice({
          tone: 'nonfinal',
          text: t(
            `Workflow byl přijat, ale jeho identifikátor chybí. Opakování je bezpečné: znovu použije UTC klíč ${requestedOn}.`,
            `The workflow was admitted, but its identifier is missing. Retrying is safe: it reuses UTC key ${requestedOn}.`,
          ),
        })
        return
      }
      if (res.status !== 202 || payload?.error === 'admission_outcome_unknown') {
        setNotice({
          tone: 'nonfinal',
          text: t(
            `Přijetí nelze potvrdit ani vyvrátit. Opakování je bezpečné: znovu použije UTC klíč ${requestedOn}.`,
            `Admission cannot be confirmed or ruled out. Retrying is safe: it reuses UTC key ${requestedOn}.`,
          ),
        })
        return
      }
      if (!payload || typeof payload.workflowId !== 'string' || !payload.workflowId.trim()) {
        setNotice({
          tone: 'nonfinal',
          text: t(
            `Workflow byl přijat, ale jeho identifikátor chybí. Opakování je bezpečné: znovu použije UTC klíč ${requestedOn}.`,
            `The workflow was admitted, but its identifier is missing. Retrying is safe: it reuses UTC key ${requestedOn}.`,
          ),
        })
        return
      }
      setLastWorkflowId(payload.workflowId)
      persistTriggerRecoveryDay(null)
      setRecoveryDay(null)
      setNotice({
        tone: 'accepted',
        text: t(
          `Požadavek byl přijat jako workflow ${payload.workflowId}. To nedokládá, zda workflow právě běží, nebo už skončilo.`,
          `The request was admitted as workflow ${payload.workflowId}. This does not prove whether the workflow is running or already complete.`,
        ),
      })
    } catch {
      // A browser-side timeout or connection loss after POST dispatch cannot prove that Temporal
      // rejected the workflow. Treat it like the BFF's 504 and prevent an unsafe blind retry.
      setNotice({
        tone: 'nonfinal',
        text: t(
          `Přijetí nelze potvrdit ani vyvrátit. Opakování je bezpečné: znovu použije UTC klíč ${requestedOn}.`,
          `Admission cannot be confirmed or ruled out. Retrying is safe: it reuses UTC key ${requestedOn}.`,
        ),
      })
    } finally {
      setTriggering(false)
    }
  }, [recoveryDay, t])

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
                title={notice?.tone === 'nonfinal'
                  ? t('Bezpečně zopakovat se stejným UTC klíčem', 'Retry safely with the same UTC key')
                  : t('Spustit nebo obnovit dnešní kontrolu', 'Run or recover today\'s check')}
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
        <div role={notice.tone === 'error' ? 'alert' : 'status'} style={{
          marginBottom: '20px', padding: '10px 14px', borderRadius: 'var(--r-md)', fontSize: '13px',
          background: notice.tone === 'error' ? 'var(--danger-bg)' : 'var(--warning-bg)',
          border: `1px solid ${notice.tone === 'error' ? 'var(--danger-border)' : 'var(--warning-border)'}`,
          color: notice.tone === 'error' ? 'var(--danger-text)' : 'var(--warning)',
        }}>
          {notice.text}
        </div>
      )}

      <div className="grid-4" style={{ marginBottom: '24px' }}>
        <StatCard label={t('Aktivní nálezy', 'Active findings')} value={findings.length} icon={<FlaskConical size={16} />} />
        <StatCard label={t('Kritické', 'Critical')} value={critical} icon={<AlertTriangle size={16} />} tone={critical > 0 ? 'danger' : undefined} />
        <StatCard label={t('Otevřené', 'Open')} value={open} icon={<Bug size={16} />} tone={open > 0 ? 'warning' : undefined} />
        <StatCard
          label={t('Poslední přijaté workflow', 'Latest admitted workflow')}
          value={lastWorkflowId ? t('Přijato', 'Accepted') : '—'}
          icon={<Clock size={16} />}
          hint={lastWorkflowId ?? undefined}
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
