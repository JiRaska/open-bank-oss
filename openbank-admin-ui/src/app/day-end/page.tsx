// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Closings cockpit ("Závěrky") — the accounting close cycle in two tabs:
//
//   EoD — the balance-service daily control-account ⇄ sub-ledger tie-out
//         (ADR-0039 Phase A), read-only from
//         GET /api/v1/balances/reconciliation/latest via the generic /api/svc BFF.
//   EoM — the monthly per-pocket statement close cadence (ADR-0035 / ADR-0069 D3):
//         latest run, run history with per-pocket failures, and an operator
//         catch-up trigger, served by the dedicated closings BFF
//         (/api/closings/**, session-gated relay to statement-service).
//
// The year-end close (EoY) is not wired yet — shown as an honest roadmap card
// rather than faked status, per the read-only-consumer rule.

import { Suspense, useEffect, useState, useCallback, useRef } from 'react'
import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'
import { useRouter, useSearchParams } from 'next/navigation'
import { useSession } from 'next-auth/react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  RefreshCw, Clock, CheckCircle2, AlertTriangle, Scale, CalendarClock, Coins,
  ArrowRightLeft, CalendarCheck2, Play, ChevronDown, ChevronRight, History, FileClock, ShieldCheck,
} from 'lucide-react'
import { svcUrl, classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import { hasPermission } from '@/lib/auth/roles'
import { useCheckLog, type CheckLogEntry } from '@/lib/services/useCheckLog'
import { PageHeader } from '@/components/ui/PageHeader'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { RegulatoryPeriodPanel } from '@/components/closings/RegulatoryPeriodPanel'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'

const POLL = 30_000
// A healthy daily tie-out (23:30) is at most ~24h old; past 25h the day's close likely
// didn't run. Mirrors the balance-service ReconciliationFreshnessWatchdog SLA.
const EOD_STALE_HOURS = 25
const RUNNING_POLL = 5_000
const HISTORY_LIMIT = 20

// ---------------------------------------------------------------------------
// Page shell — tabbed view (EoD | EoM), tab restorable via ?tab=eom
// ---------------------------------------------------------------------------

type Tab = 'eod' | 'eom' | 'regulatory'

export default function ClosingsPage() {
  const { t } = useLanguage()
  return (
    <Suspense fallback={<p>{t('Načítání…', 'Loading…')}</p>}>
      <ClosingsContent />
    </Suspense>
  )
}

function ClosingsContent() {
  const { t } = useLanguage()
  const searchParams = useSearchParams()
  const router = useRouter()
  const requestedTab = searchParams.get('tab')
  const [tab, setTab] = useState<Tab>(requestedTab === 'eom' || requestedTab === 'regulatory' ? requestedTab : 'eod')

  const changeTab = useCallback((next: Tab) => {
    setTab(next)
    router.replace(next === 'eod' ? '/day-end' : `/day-end?tab=${next}`, { scroll: false })
  }, [router])

  return (
    <div>
      <PageHeader breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span>{t('Účetnictví', 'Accounting')}</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Závěrky', 'Closings')}</span></div>} icon={<CalendarClock size={20} aria-hidden="true" />} title={t('Závěrky', 'Closings')} subtitle={t('Denní tie-out (EoD) a měsíční uzávěrka výpisů (EoM)', 'Daily tie-out (EoD) and monthly statement close (EoM)')} />

      {/* Tab nav — same pattern as /payments */}
      <div role="group" aria-label={t('Typ závěrky', 'Closing type')} style={{ display: 'flex', gap: '2px', marginBottom: '20px', borderBottom: '1px solid var(--border)' }}>
        {([
          { key: 'eod' as Tab, icon: Scale, labelCs: 'Závěrka dne (EoD)', labelEn: 'Day-end (EoD)' },
          { key: 'eom' as Tab, icon: CalendarCheck2, labelCs: 'Měsíční uzávěrka (EoM)', labelEn: 'Month-end (EoM)' },
          { key: 'regulatory' as Tab, icon: ShieldCheck, labelCs: 'Regulatorní období', labelEn: 'Regulatory period' },
        ]).map(item => {
          const Icon = item.icon
          const isActive = tab === item.key
          return (
            <button key={item.key} type="button" aria-pressed={isActive} aria-label={t(item.labelCs, item.labelEn)} onClick={() => changeTab(item.key)}
              style={{
                display: 'flex', alignItems: 'center', gap: '6px', padding: '10px 18px', fontSize: '13px',
                fontWeight: isActive ? 700 : 500, color: isActive ? 'var(--accent)' : 'var(--text-secondary)',
                border: 'none', borderBottom: isActive ? '2px solid var(--accent)' : '2px solid transparent',
                background: 'transparent', cursor: 'pointer', marginBottom: '-1px', transition: 'all 0.15s ease',
              }}>
              <Icon size={14} aria-hidden="true" />
              {t(item.labelCs, item.labelEn)}
            </button>
          )
        })}
      </div>

      {tab === 'eod' ? <EodPanel /> : tab === 'eom' ? <EomPanel /> : <RegulatoryPeriodPanel />}
    </div>
  )
}

// ---------------------------------------------------------------------------
// EoD — daily ledger ⇄ sub-ledger tie-out (unchanged behaviour)
// ---------------------------------------------------------------------------

interface CurrencyReconciliation {
  currency: string
  ledgerControlBalance: number | string
  subLedgerBookedSum: number | string
  difference: number | string
  withinTolerance: boolean
  // ADR-0178 Phase 3 — future-value-dated pipeline (posted, not yet effective). Optional: reports
  // recorded before the field existed omit it, so render 0 rather than NaN.
  futureValueDatedPipeline?: number | string
}

interface ReconciliationReport {
  asOf: string
  generatedAt: string
  tolerance: number | string
  currencies: CurrencyReconciliation[]
}

function num(v: number | string): number {
  return typeof v === 'number' ? v : Number(v)
}

function EodPanel() {
  const { t, language } = useLanguage()
  const [report, setReport] = useState<ReconciliationReport | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null)
  const busyRef = useRef(false)

  const refresh = useCallback(async (spinner = false) => {
    if (busyRef.current) return
    busyRef.current = true
    if (spinner) setRefreshing(true)
    try {
      const res = await fetch(
        svcUrl('balance-service', '/api/v1/balances/reconciliation/latest'),
        { cache: 'no-store', signal: AbortSignal.timeout(8000) },
      )
      if (res.status === 404) {
        // A bare 404 here means the scheduler has not produced a run yet — the
        // service IS deployed, it just has no report. Distinguish from a missing
        // service (404 "Unknown service" → not_deployed) via the classifier.
        const kind = await classifyBffFailure(res)
        setReport(null)
        setUnavailable({ kind: kind === 'not_found' ? 'no_data' : kind })
        return
      }
      if (!res.ok) {
        setReport(null)
        setUnavailable({ kind: await classifyBffFailure(res) as BffFailure })
        return
      }
      const data = (await res.json()) as ReconciliationReport
      setReport(data)
      setUnavailable(null)
      setLastRefreshed(new Date())
    } catch {
      setReport(null)
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
      setRefreshing(false)
      busyRef.current = false
    }
  }, [])

  useEffect(() => {
    refresh()
    const id = setInterval(() => refresh(), POLL)
    return () => clearInterval(id)
  }, [refresh])

  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const fmtAmount = (v: number | string) =>
    new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(num(v))
  const fmtDate = (iso: string) => { try { return new Date(iso).toLocaleDateString(locale) } catch { return iso } }
  const fmtDateTime = (iso: string) => { try { return new Date(iso).toLocaleString(locale) } catch { return iso } }

  const drifted = report ? report.currencies.filter(c => !c.withinTolerance) : []
  const hasDrift = drifted.length > 0

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px' }}>
        <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
          {t('Denní účetní tie-out · automatická obnova každých', 'Daily accounting tie-out · auto-refresh every')} {POLL / 1000}s
        </span>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {lastRefreshed && (
            <span style={{ fontSize: '12px', color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '5px' }}>
              <Clock size={12} aria-hidden="true" /> {lastRefreshed.toLocaleTimeString(locale)}
            </span>
          )}
          <button type="button" className="btn btn-secondary" aria-busy={refreshing} aria-label={t('Obnovit denní závěrku', 'Refresh day-end close')} onClick={() => refresh(true)} disabled={refreshing}>
            <RefreshCw size={13} aria-hidden="true" className={refreshing ? 'animate-spin' : undefined} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>
      </div>

      {loading ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: '12px' }}>
          {Array.from({ length: 4 }).map((_, i) => <div key={i} className="skeleton" style={{ height: '96px' }} />)}
        </div>
      ) : unavailable && unavailable.kind === 'no_data' ? (
        // Zero reconciliation records is NOT "no data yet" for a daily control — it means the
        // EoD tie-out has never run. Surface it as an alert an operator/auditor must act on,
        // not the calm gray no-data panel.
        <div style={{ display: 'flex', alignItems: 'center', gap: '14px', padding: '16px 18px',
          background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', borderRadius: 'var(--r-lg)' }}>
          <div style={{ padding: '10px', borderRadius: 'var(--r-md)', background: 'var(--surface)', color: 'var(--danger)' }}>
            <AlertTriangle size={20} />
          </div>
          <div>
            <div style={{ fontSize: '15px', fontWeight: 700, color: 'var(--danger)' }}>
              {t('Denní tie-out dosud neproběhl', 'The daily tie-out has never run')}
            </div>
            <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginTop: '2px' }}>
              {t('Kontrola hlavní kniha ⇄ sub-ledger (ADR-0039) nemá žádný záznam — plánovač buď neběžel, nebo každý běh selhal. Vyžaduje prošetření.',
                 'The ledger ⇄ sub-ledger control (ADR-0039) has no record — the scheduler has not run, or every run failed. Needs investigation.')}
            </div>
          </div>
        </div>
      ) : unavailable ? (
        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)' }}>
          <DataUnavailable
            kind={unavailable.kind}
            service="Balance-service"
            feature={t('Závěrka dne', 'Day-end close')}
            lang={language}
            dense
          />
        </div>
      ) : report ? (
        <>
          {/* Staleness warning — a report exists but the last tie-out is older than the daily SLA,
              so today's close likely didn't run. The report below is then a stale prior day. */}
          {(() => {
            // eslint-disable-next-line react-hooks/purity -- staleness display intentionally uses current time; the stale state is computed from server data, the display is time-relative.
            const renderNow = Date.now()
            const ageHours = (renderNow - new Date(report.generatedAt).getTime()) / 3_600_000
            if (!(ageHours > EOD_STALE_HOURS)) return null
            return (
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '12px 16px', marginBottom: '16px',
                background: 'var(--warning-bg)', border: '1px solid var(--warning-border)', borderRadius: 'var(--r-lg)' }}>
                <AlertTriangle size={16} style={{ color: 'var(--warning)', flexShrink: 0 }} />
                <span style={{ fontSize: '13px', color: 'var(--warning)' }}>
                  {t(`Poslední tie-out je ${Math.round(ageHours)} h starý (${fmtDateTime(report.generatedAt)}) — dnešní denní závěrka zřejmě neproběhla.`,
                     `The last tie-out is ${Math.round(ageHours)}h old (${fmtDateTime(report.generatedAt)}) — today's daily close appears not to have run.`)}
                </span>
              </div>
            )
          })()}

          {/* Overall status banner */}
          <div style={{
            display: 'flex', alignItems: 'center', gap: '14px',
            padding: '16px 18px', marginBottom: '16px',
            background: hasDrift ? 'var(--danger-bg)' : 'var(--success-bg)',
            border: `1px solid ${hasDrift ? 'var(--danger-border)' : 'var(--success-border)'}`,
            borderRadius: 'var(--r-lg)',
          }}>
            <div style={{ padding: '10px', borderRadius: 'var(--r-md)', background: 'var(--surface)', color: hasDrift ? 'var(--danger)' : 'var(--success)' }}>
              {hasDrift ? <AlertTriangle size={20} /> : <CheckCircle2 size={20} />}
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: '15px', fontWeight: 700, color: hasDrift ? 'var(--danger)' : 'var(--success)' }}>
                {hasDrift
                  ? t(`Zjištěn rozdíl v ${drifted.length} měně/měnách`, `Drift detected in ${drifted.length} currency/currencies`)
                  : t('Účetní kniha a sub-ledger souhlasí', 'Ledger and sub-ledger are in agreement')}
              </div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '2px' }}>
                {hasDrift
                  ? t(`Měny s rozdílem: ${drifted.map(d => d.currency).join(', ')}`, `Drifted currencies: ${drifted.map(d => d.currency).join(', ')}`)
                  : t('Kontrolní účet hlavní knihy se rovná součtu zákaznických zůstatků ve všech měnách.', 'The ledger control account equals the sum of customer balances across every currency.')}
              </div>
            </div>
            <span className={hasDrift ? 'pill pill-danger' : 'pill pill-success'}>
              {hasDrift ? t('ROZDÍL', 'DRIFT') : t('SOUHLASÍ', 'OK')}
            </span>
          </div>

          {/* KPI row */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: '12px', marginBottom: '16px' }}>
            <Kpi icon={<CalendarClock size={16} />} label={t('Účetní den', 'Accounting date')} value={fmtDate(report.asOf)} />
            <Kpi icon={<Clock size={16} />} label={t('Poslední běh', 'Last run')} value={fmtDateTime(report.generatedAt)} />
            <Kpi icon={<Coins size={16} />} label={t('Měn vyrovnáno', 'Currencies tied out')} value={String(report.currencies.length)} />
            <Kpi
              icon={<Scale size={16} />}
              label={t('Tolerance', 'Tolerance')}
              value={fmtAmount(report.tolerance)}
            />
          </div>

          {/* Per-currency tie-out table */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', overflow: 'hidden', boxShadow: 'var(--shadow-xs)' }}>
            <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', background: 'var(--surface-2)', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <ArrowRightLeft size={14} style={{ color: 'var(--text-tertiary)' }} />
              <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>
                {t('Vyrovnání po měnách (EoD)', 'Per-currency tie-out (EoD)')}
              </span>
            </div>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                <thead>
                  <tr style={{ background: 'var(--surface-2)', textAlign: 'right', color: 'var(--text-tertiary)' }}>
                    <th style={{ textAlign: 'left', padding: '8px 16px', fontWeight: 600 }}>{t('Měna', 'Currency')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600 }}>{t('Kontrolní účet HK', 'Ledger control')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600 }}>{t('Součet sub-ledgeru', 'Sub-ledger sum')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600 }} title={t('Zaúčtované pohyby s pozdějším datem valuty — ještě nejsou v žádné straně vyrovnání, proto nejde o rozdíl.', 'Posted movements value-dated after asOf — counted by neither side of the tie-out, so not drift.')}>{t('Očekávané pohyby (valuta)', 'Value-date pipeline')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600 }}>{t('Nevysvětlený rozdíl', 'Unexplained difference')}</th>
                    <th style={{ textAlign: 'center', padding: '8px 16px', fontWeight: 600 }}>{t('Stav', 'Status')}</th>
                  </tr>
                </thead>
                <tbody>
                  {report.currencies.map(row => {
                    const diff = num(row.difference)
                    const pipeline = num(row.futureValueDatedPipeline ?? 0)
                    return (
                      <tr key={row.currency} style={{ borderTop: '1px solid var(--border)' }}>
                        <td style={{ padding: '10px 16px', fontWeight: 600, color: 'var(--text-primary)' }}>{row.currency}</td>
                        <td style={{ padding: '10px 16px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)' }}>{fmtAmount(row.ledgerControlBalance)}</td>
                        <td style={{ padding: '10px 16px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)' }}>{fmtAmount(row.subLedgerBookedSum)}</td>
                        <td style={{ padding: '10px 16px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', color: pipeline === 0 ? 'var(--text-tertiary)' : 'var(--text-secondary)' }}>{fmtAmount(row.futureValueDatedPipeline ?? 0)}</td>
                        <td style={{ padding: '10px 16px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', fontWeight: 600, color: diff === 0 ? 'var(--text-secondary)' : 'var(--danger)' }}>{fmtAmount(row.difference)}</td>
                        <td style={{ padding: '10px 16px', textAlign: 'center' }}>
                          <span className={row.withinTolerance ? 'pill pill-success' : 'pill pill-danger'}>
                            {row.withinTolerance ? t('SOUHLASÍ', 'OK') : t('ROZDÍL', 'DRIFT')}
                          </span>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </>
      ) : null}

      {/* What this is + close-cycle roadmap — honest scope, never faked status */}
      <div style={{ marginTop: '20px' }}>
        <div style={{ fontSize: '11px', fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--text-tertiary)', marginBottom: '10px' }}>
          {t('Závěrkový cyklus', 'Close cycle')}
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '12px' }}>
          <CycleCard
            tag="EoD"
            status="live"
            title={t('Denní závěrka', 'End-of-day')}
            body={t(
              'Denní tie-out kontrolního účtu hlavní knihy proti sub-ledgeru (ADR-0039, fáze A). Plánovač balance-service běží každý den ve 23:30 Europe/Prague. To je výše.',
              'Daily tie-out of the ledger control account against the sub-ledger (ADR-0039 Phase A). The balance-service scheduler runs nightly at 23:30 Europe/Prague. Shown above.',
            )}
          />
          <CycleCard
            tag="EoM"
            status="live"
            title={t('Měsíční závěrka', 'End-of-month')}
            body={t(
              'Měsíční uzávěrka výpisů po měnových kapsách (ADR-0035). Plánovač běží 1. v měsíci ve 02:30 a uzavírá předchozí měsíc; vynechané běhy se samy doženou. Detail a ruční spuštění jsou v záložce EoM.',
              'Monthly per-pocket statement close (ADR-0035). The scheduler runs on the 1st at 02:30 and closes the prior month; missed runs self-heal. Details and the manual trigger live in the EoM tab.',
            )}
          />
          <CycleCard
            tag="EoY"
            status="none"
            title={t('Roční závěrka', 'End-of-year')}
            body={t(
              'Roční účetní závěrka zatím není navržena ani implementována.',
              'A year-end accounting close is not yet designed or implemented.',
            )}
          />
        </div>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// EoM — monthly statement close runs (ADR-0069 D3 / issue #470)
// ---------------------------------------------------------------------------

interface CloseRun {
  id: string
  trigger: 'SCHEDULED' | 'MANUAL'
  status: 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_FAILURES'
  periodFrom: string | null
  periodTo: string | null
  accountsEnumerated: number
  pocketsClosed: number
  pocketsFailed: number
  pocketsSkipped: number
  startedAt: string
  finishedAt: string | null
}

interface CloseFailure {
  id: string
  runId: string
  accountId: string
  pocketCurrency: string
  periodFrom: string
  periodTo: string
  reason: 'RECONCILIATION' | 'UPSTREAM' | 'UNKNOWN'
  detail: string | null
  failedAt: string
}

type FailuresState = 'loading' | 'error' | CloseFailure[]

/**
 * Live view of the monthly statement close cadence: latest-run summary, run
 * history with expandable per-pocket failures, and an operator catch-up trigger
 * (gated by the closings:run permission). Data flows through the dedicated
 * closings BFF (/api/closings/**) and degrades through the graceful-state rule
 * when statement-service is unreachable in this environment.
 */
function EomPanel() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles: string[] = session?.user?.roles ?? []
  const canTrigger = hasPermission(roles, 'closings:run')

  const [runs, setRuns] = useState<CloseRun[]>([])
  const [empty, setEmpty] = useState(false)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null)
  const [triggering, setTriggering] = useState(false)
  const [triggerReviewOpen, setTriggerReviewOpen] = useState(false)
  const triggerButtonRef = useRef<HTMLButtonElement | null>(null)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  const [failures, setFailures] = useState<Record<string, FailuresState>>({})
  const busyRef = useRef(false)
  // Operator-local check trail — survives reloads and (crucially) statement-service
  // outages, so a later operator/agent can see WHEN the close was checked and what
  // state it was in, even when the live run history isn't answering.
  const { entries: checkLog, record: recordCheck } = useCheckLog('closings-eom')

  const load = useCallback(async (spinner = false) => {
    if (busyRef.current) return
    busyRef.current = true
    if (spinner) setRefreshing(true)
    try {
      const res = await fetch(`/api/closings/runs?limit=${HISTORY_LIMIT}`, {
        cache: 'no-store', signal: AbortSignal.timeout(8000),
      })
      if (!res.ok) {
        setRuns([]); setEmpty(false)
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const list = (await res.json()) as CloseRun[]
      setRuns(list)
      setEmpty(list.length === 0)
      setUnavailable(null)
      setLastRefreshed(new Date())
    } catch {
      setRuns([]); setEmpty(false)
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
      setRefreshing(false)
      busyRef.current = false
    }
  }, [])

  // Poll faster while a run is in flight so the operator sees it converge.
  const latest = runs[0] ?? null
  const running = latest?.status === 'RUNNING'
  useEffect(() => {
    load()
    const id = setInterval(() => load(), running ? RUNNING_POLL : POLL)
    return () => clearInterval(id)
  }, [load, running])

  // Record each distinct observed state (deduped by signature) so the check trail
  // reflects genuine transitions, not every poll.
  useEffect(() => {
    if (loading) return
    if (unavailable) { recordCheck('error', 'unavailable', `unavail:${unavailable.kind}`, { kind: unavailable.kind }); return }
    if (empty || !latest) { recordCheck('warn', 'no_run', 'no_run'); return }
    recordCheck(
      latest.status === 'COMPLETED_WITH_FAILURES' ? 'warn' : 'ok',
      'run',
      `run:${latest.status}:${runs.length}:${latest.pocketsFailed}`,
      { status: latest.status, runs: runs.length, failed: latest.pocketsFailed },
    )
  }, [loading, unavailable, empty, latest, runs.length, recordCheck])

  // The closings endpoint is itself idempotent, so a duplicate POST does not create a
  // second close run — but it does produce a second operator check-trail entry and a
  // second 35 s request. Serialize it anyway (#7102).
  const triggerFlight = useSingleFlight()

  const trigger = useCallback(async () => {
    let succeeded = false
    const outcome = await triggerFlight.run('closings:catch-up', async () => {
    setTriggering(true); setNotice(null)
    try {
      const res = await fetch('/api/closings/runs', {
        method: 'POST', cache: 'no-store', signal: AbortSignal.timeout(35_000),
      })
      if (!res.ok) {
        setNotice({ ok: false, text: t('Spuštění catch-up uzávěrky se nezdařilo.', 'Could not start the catch-up close run.') })
        return
      }
      // Optimistic: show the accepted run immediately, then refresh the history.
      const acceptedRun = (await res.json()) as CloseRun
      setRuns(prev => [acceptedRun, ...prev.filter(r => r.id !== acceptedRun.id)])
      setEmpty(false)
      setNotice({ ok: true, text: t('Catch-up uzávěrka přijata.', 'Catch-up close run accepted.') })
      recordCheck('ok', 'trigger', `trigger:${acceptedRun.id}`)
      succeeded = true
      void load()
    } catch {
      setNotice({ ok: false, text: t('Spuštění catch-up uzávěrky se nezdařilo.', 'Could not start the catch-up close run.') })
    } finally {
      setTriggering(false)
    }
    })
    if (wasSkipped(outcome)) return false
    return succeeded
  }, [triggerFlight, t, load, recordCheck])

  const closeTriggerReview = () => {
    if (triggering) return
    setTriggerReviewOpen(false)
    requestAnimationFrame(() => triggerButtonRef.current?.focus())
  }

  const toggleFailures = useCallback(async (run: CloseRun) => {
    const open = !expanded[run.id]
    setExpanded(prev => ({ ...prev, [run.id]: open }))
    if (!open || failures[run.id]) return
    setFailures(prev => ({ ...prev, [run.id]: 'loading' }))
    try {
      const res = await fetch(`/api/closings/runs/${run.id}/failures`, {
        cache: 'no-store', signal: AbortSignal.timeout(8000),
      })
      if (!res.ok) {
        setFailures(prev => ({ ...prev, [run.id]: 'error' }))
        return
      }
      const data = (await res.json().catch(() => [])) as CloseFailure[]
      setFailures(prev => ({ ...prev, [run.id]: Array.isArray(data) ? data : [] }))
    } catch {
      setFailures(prev => ({ ...prev, [run.id]: 'error' }))
    }
  }, [expanded, failures])

  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const fmtTs = (s: string | null) => { if (!s) return '—'; try { return new Date(s).toLocaleString(locale) } catch { return s } }
  const fmtDate = (s: string | null) => { if (!s) return '—'; try { return new Date(s).toLocaleDateString(locale) } catch { return s } }
  const fmtPeriod = (run: { periodFrom: string | null; periodTo: string | null }) =>
    run.periodFrom || run.periodTo ? `${fmtDate(run.periodFrom)} – ${fmtDate(run.periodTo)}` : '—'
  const fmtDuration = (from: string, to: string | null) => {
    if (!to) return t('běží…', 'running…')
    const ms = new Date(to).getTime() - new Date(from).getTime()
    if (!Number.isFinite(ms) || ms < 0) return '—'
    const s = Math.round(ms / 1000)
    if (s < 60) return `${s} s`
    return `${Math.floor(s / 60)} min ${s % 60} s`
  }

  const statusPill = (status: CloseRun['status']) => {
    const map = {
      RUNNING: { cls: 'pill', label: t('Běží', 'Running') },
      COMPLETED: { cls: 'pill pill-success', label: t('Dokončeno', 'Completed') },
      COMPLETED_WITH_FAILURES: { cls: 'pill pill-danger', label: t('Dokončeno s chybami', 'Completed with failures') },
    }[status]
    return <span className={map.cls}>{map.label}</span>
  }

  const reasonLabel = (reason: CloseFailure['reason']) => ({
    RECONCILIATION: t('Rekonciliace nesedí', 'Reconciliation mismatch'),
    UPSTREAM: t('Výpadek závislé služby', 'Upstream dependency failed'),
    UNKNOWN: t('Neznámá chyba', 'Unknown error'),
  }[reason] ?? reason)

  const logLabel = (e: CheckLogEntry): string => {
    switch (e.code) {
      case 'unavailable': {
        const k = String(e.meta?.kind ?? '')
        return t(`Uzávěrka nedostupná (${k})`, `Close unavailable (${k})`)
      }
      case 'no_run':
        return t('Uzávěrka zatím neproběhla', 'No close run yet')
      case 'run': {
        const s = String(e.meta?.status ?? '')
        const failed = Number(e.meta?.failed ?? 0)
        return t(
          `Poslední běh: ${s}${failed ? ` · ${failed} selhalo` : ''}`,
          `Latest run: ${s}${failed ? ` · ${failed} failed` : ''}`,
        )
      }
      case 'trigger':
        return t('Ručně spuštěna catch-up uzávěrka', 'Catch-up close triggered manually')
      default:
        return e.code
    }
  }

  return (
    <div>
      {/* Toolbar: refresh + catch-up trigger */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px' }}>
        <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
          {t('Měsíční uzávěrka výpisů po kapsách (ADR-0035) · plánovač 1. v měsíci 02:30', 'Monthly per-pocket statement close (ADR-0035) · scheduled on the 1st at 02:30')}
        </span>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {lastRefreshed && (
            <span style={{ fontSize: '12px', color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '5px' }}>
              <Clock size={12} aria-hidden="true" /> {lastRefreshed.toLocaleTimeString(locale)}
            </span>
          )}
          <button type="button" className="btn btn-secondary" aria-busy={refreshing} aria-label={t('Obnovit měsíční závěrku', 'Refresh month-end close')} onClick={() => load(true)} disabled={refreshing}>
            <RefreshCw size={13} aria-hidden="true" className={refreshing ? 'animate-spin' : undefined} />
            {t('Obnovit', 'Refresh')}
          </button>
          {canTrigger && (
            <button
              ref={triggerButtonRef}
              type="button"
              aria-label={t('Zkontrolovat catch-up uzávěrku', 'Review catch-up close')}
              className="btn btn-primary"
              onClick={() => { setNotice(null); setTriggerReviewOpen(true) }}
              disabled={triggering || running || unavailable !== null}
              title={t('Spustit dohánějící uzávěrku nyní (idempotentní)', 'Run a catch-up close now (idempotent)')}
            >
              <Play size={13} aria-hidden="true" />
              {t('Zkontrolovat a spustit', 'Review and run')}
            </button>
          )}
        </div>
      </div>

      {triggerReviewOpen && <ClosingTriggerReviewDialog
        latest={latest}
        historyCount={runs.length}
        busy={triggering}
        error={notice?.ok === false ? notice.text : null}
        onCancel={closeTriggerReview}
        onConfirm={async () => {
          if (await trigger()) setTriggerReviewOpen(false)
        }}
      />}

      {notice && !triggerReviewOpen && (
        <div style={{
          marginBottom: '12px', padding: '10px 14px', borderRadius: 'var(--r-md)', fontSize: '13px',
          background: notice.ok ? 'var(--success-bg)' : 'var(--warning-bg)',
          border: `1px solid ${notice.ok ? 'var(--success-border)' : 'var(--warning-border)'}`,
          color: notice.ok ? 'var(--success)' : 'var(--warning)',
        }}>
          {notice.text}
        </div>
      )}

      {loading ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: '12px' }}>
          {Array.from({ length: 4 }).map((_, i) => <div key={i} className="skeleton" style={{ height: '96px' }} />)}
        </div>
      ) : unavailable ? (
        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)' }}>
          <DataUnavailable
            kind={unavailable.kind}
            service="Statement-service"
            feature={t('Měsíční uzávěrka výpisů', 'Monthly statement close')}
            lang={language}
            dense
          />
        </div>
      ) : empty || !latest ? (
        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', padding: '28px', textAlign: 'center' }}>
          <CalendarCheck2 size={22} style={{ color: 'var(--text-tertiary)', marginBottom: '8px' }} />
          <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>
            {t('Uzávěrka zatím neproběhla', 'No close run yet')}
          </div>
          <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginTop: '4px' }}>
            {canTrigger
              ? t('Plánovač zatím neproběhl. Můžete spustit catch-up uzávěrku ručně.', 'The scheduler has not run yet. You can trigger a catch-up close manually.')
              : t('Plánovač zatím neproběhl.', 'The scheduler has not run yet.')}
          </div>
        </div>
      ) : (
        <>
          {/* Latest run summary */}
          <div style={{
            background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)',
            padding: '16px 18px', marginBottom: '16px', boxShadow: 'var(--shadow-xs)',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '12px', flexWrap: 'wrap' }}>
              <span style={{ fontSize: '11px', fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--text-tertiary)' }}>
                {t('Poslední běh', 'Latest run')}
              </span>
              {statusPill(latest.status)}
              <span className="pill">
                {latest.trigger === 'SCHEDULED' ? t('Plánovaný', 'Scheduled') : t('Ruční', 'Manual')}
              </span>
              <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
                {t('Období', 'Period')}: {fmtPeriod(latest)}
              </span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: '12px' }}>
              <Kpi icon={<CheckCircle2 size={16} />} label={t('Uzavřeno kapes', 'Pockets closed')} value={String(latest.pocketsClosed)} />
              <Kpi icon={<AlertTriangle size={16} />} label={t('Selhalo', 'Failed')} value={String(latest.pocketsFailed)} />
              <Kpi icon={<CalendarClock size={16} />} label={t('Přeskočeno', 'Skipped')} value={String(latest.pocketsSkipped)} />
              <Kpi icon={<Scale size={16} />} label={t('Účtů', 'Accounts')} value={String(latest.accountsEnumerated)} />
              <Kpi icon={<Clock size={16} />} label={t('Doba běhu', 'Duration')} value={fmtDuration(latest.startedAt, latest.finishedAt)} />
            </div>
          </div>

          {/* Run history with expandable failures */}
          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', overflow: 'hidden', boxShadow: 'var(--shadow-xs)' }}>
            <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', background: 'var(--surface-2)', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <History size={14} style={{ color: 'var(--text-tertiary)' }} />
              <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>
                {t('Historie běhů', 'Run history')}
              </span>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                {t(`posledních ${runs.length}`, `last ${runs.length}`)}
              </span>
            </div>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                <thead>
                  <tr style={{ background: 'var(--surface-2)', textAlign: 'left', color: 'var(--text-tertiary)' }}>
                    <th style={{ padding: '8px 16px', fontWeight: 600, width: '28px' }} aria-label={t('Detail', 'Detail')} />
                    <th style={{ padding: '8px 16px', fontWeight: 600 }}>{t('Zahájeno', 'Started')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600 }}>{t('Spouštěč', 'Trigger')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600 }}>{t('Období', 'Period')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600 }}>{t('Stav', 'Status')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600, textAlign: 'right' }}>{t('Uzavřeno', 'Closed')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600, textAlign: 'right' }}>{t('Selhalo', 'Failed')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600, textAlign: 'right' }}>{t('Přeskočeno', 'Skipped')}</th>
                    <th style={{ padding: '8px 16px', fontWeight: 600, textAlign: 'right' }}>{t('Doba', 'Duration')}</th>
                  </tr>
                </thead>
                <tbody>
                  {runs.map(run => {
                    const expandable = run.pocketsFailed > 0
                    const isOpen = !!expanded[run.id]
                    const fState = failures[run.id]
                    return (
                      <RunRows
                        key={run.id}
                        run={run}
                        expandable={expandable}
                        isOpen={isOpen}
                        fState={fState}
                        onToggle={() => void toggleFailures(run)}
                        statusPill={statusPill}
                        reasonLabel={reasonLabel}
                        fmtTs={fmtTs}
                        fmtPeriod={fmtPeriod}
                        fmtDuration={fmtDuration}
                      />
                    )
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}

      {/* Operator check log — a local breadcrumb trail of observed close states.
          Always visible, so even when statement-service is down there's a record
          that the close was checked, when, and what state it was in. */}
      <div style={{ marginTop: '20px', background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', overflow: 'hidden', boxShadow: 'var(--shadow-xs)' }}>
        <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', background: 'var(--surface-2)', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <FileClock size={14} style={{ color: 'var(--text-tertiary)' }} />
          <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>
            {t('Záznam kontrol', 'Check log')}
          </span>
          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
            {t('lokální stopa pozorovaných stavů', 'local trail of observed states')}
          </span>
        </div>
        {checkLog.length === 0 ? (
          <div style={{ padding: '16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>
            {t('Zatím žádné záznamy. Každá kontrola této obrazovky sem zapíše pozorovaný stav.', 'No entries yet. Each check of this screen records the observed state here.')}
          </div>
        ) : (
          <ul style={{ listStyle: 'none', margin: 0, padding: '6px 0', maxHeight: '220px', overflowY: 'auto' }}>
            {checkLog.slice(0, 15).map((e, i) => (
              <li key={`${e.at}-${i}`} style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '6px 16px', fontSize: '12px' }}>
                <span style={{ width: '7px', height: '7px', borderRadius: '50%', flexShrink: 0,
                  background: e.kind === 'error' ? 'var(--danger)' : e.kind === 'warn' ? 'var(--warning)' : 'var(--success)' }} />
                <span style={{ color: 'var(--text-tertiary)', fontFamily: 'JetBrains Mono, monospace', whiteSpace: 'nowrap' }}>
                  {new Date(e.at).toLocaleString(locale)}
                </span>
                <span style={{ color: 'var(--text-secondary)' }}>{logLabel(e)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function ClosingTriggerReviewDialog({ latest, historyCount, busy, error, onCancel, onConfirm }: {
  latest: CloseRun | null
  historyCount: number
  busy: boolean
  error: string | null
  onCancel: () => void
  onConfirm: () => Promise<void>
}) {
  const { t, language } = useLanguage()
  const dialogRef = useRef<HTMLDivElement>(null)
  const titleId = 'closing-catch-up-review-title'
  const impactId = 'closing-catch-up-review-impact'
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const formatDate = (value: string | null) => value ? new Date(value).toLocaleDateString(locale) : '—'
  const period = latest ? `${formatDate(latest.periodFrom)} – ${formatDate(latest.periodTo)}` : t('zatím bez běhu', 'no run yet')

  return <div
    ref={dialogRef}
    role="alertdialog"
    aria-modal="true"
    aria-labelledby={titleId}
    aria-describedby={impactId}
    aria-busy={busy}
    onKeyDown={event => {
      if (event.key === 'Escape' && !busy) onCancel()
      trapDialogFocus(event, dialogRef.current)
    }}
    style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.72)', display: 'grid', placeItems: 'center', padding: 20 }}
  >
    <div className="card" style={{ width: 'min(600px, 100%)', maxHeight: 'calc(100dvh - 40px)', overflowY: 'auto', padding: 22 }}>
      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
        <CalendarCheck2 size={20} aria-hidden="true" style={{ color: 'var(--accent)', flexShrink: 0, marginTop: 2 }} />
        <div>
          <h2 id={titleId} style={{ margin: 0, fontSize: 17, fontWeight: 750 }}>{t('Spustit catch-up měsíční uzávěrku', 'Run monthly catch-up close')}</h2>
          <p id={impactId} style={{ margin: '6px 0 0', fontSize: 12.5, lineHeight: 1.5, color: 'var(--text-secondary)' }}>{t(
            'Statement-service určí chybějící období a přijme idempotentní běh po kapsách. Přijetí pouze potvrzuje zahájení — úspěch, přeskočení a chyby uvidíte až v historii běhu.',
            'Statement-service determines the missing period and accepts an idempotent per-pocket run. Acceptance only confirms the start — completion, skips, and failures appear later in run history.',
          )}</p>
        </div>
      </div>
      <div style={{ marginTop: 14, padding: 12, borderRadius: 9, border: '1px solid var(--warning-border)', background: 'var(--warning-bg)', color: 'var(--warning)', fontSize: 12.5, lineHeight: 1.5 }}>
        {t('Nespouštějte ručně jen proto, že plánovaný běh ještě není vidět. Ověřte plánovač 1. den v měsíci v 02:30 a poslední běh níže.', 'Do not trigger manually only because the scheduled run is not visible yet. Check the 1st-of-month 02:30 schedule and the latest run below.')}
      </div>
      <dl style={{ margin: '14px 0 0', padding: 12, borderRadius: 9, border: '1px solid var(--border)', background: 'var(--surface-2)', display: 'grid', gap: 8, fontSize: 12.5 }}>
        <div><dt style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Plán', 'Schedule')}</dt><dd style={{ margin: '2px 0 0', fontWeight: 650 }}>{t('1. den v měsíci · 02:30', '1st of month · 02:30')}</dd></div>
        <div><dt style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Poslední pozorované období', 'Latest observed period')}</dt><dd style={{ margin: '2px 0 0' }}>{period}</dd></div>
        <div><dt style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Poslední běh', 'Latest run')}</dt><dd className="mono" style={{ margin: '2px 0 0', wordBreak: 'break-all' }}>{latest ? `${latest.id} · ${latest.status} · ${latest.trigger}` : t('žádný', 'none')}</dd></div>
        <div><dt style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Načtená historie', 'Loaded history')}</dt><dd style={{ margin: '2px 0 0' }}>{historyCount} {t('běhů', 'runs')}</dd></div>
      </dl>
      {error && <p role="alert" style={{ margin: '12px 0 0', padding: '10px 12px', borderRadius: 8, color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: 12 }}>{error}</p>}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
        <button type="button" autoFocus className="btn btn-secondary" disabled={busy} onClick={onCancel}>{t('Zpět ke kontrole', 'Back to review')}</button>
        <button type="button" className="btn btn-primary" disabled={busy} aria-busy={busy} onClick={() => void onConfirm()}><Play size={13} aria-hidden="true" />{busy ? t('Spouštím…', 'Starting…') : t('Potvrdit spuštění', 'Confirm run')}</button>
      </div>
    </div>
  </div>
}

function RunRows({ run, expandable, isOpen, fState, onToggle, statusPill, reasonLabel, fmtTs, fmtPeriod, fmtDuration }: {
  run: CloseRun
  expandable: boolean
  isOpen: boolean
  fState: FailuresState | undefined
  onToggle: () => void
  statusPill: (s: CloseRun['status']) => React.ReactNode
  reasonLabel: (r: CloseFailure['reason']) => string
  fmtTs: (s: string | null) => string
  fmtPeriod: (r: { periodFrom: string | null; periodTo: string | null }) => string
  fmtDuration: (from: string, to: string | null) => string
}) {
  const { t } = useLanguage()
  return (
    <>
      <tr style={{ borderTop: '1px solid var(--border)' }}>
        <td style={{ padding: '8px 8px 8px 16px' }}>
          {expandable && (
            <button
              type="button"
              onClick={onToggle}
              aria-label={isOpen ? t('Skrýt chyby', 'Hide failures') : t('Zobrazit chyby', 'Show failures')}
              style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--text-tertiary)', padding: 0, display: 'flex' }}
            >
              {isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            </button>
          )}
        </td>
        <td style={{ padding: '10px 16px', color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>{fmtTs(run.startedAt)}</td>
        <td style={{ padding: '10px 16px', color: 'var(--text-secondary)' }}>
          {run.trigger === 'SCHEDULED' ? t('Plánovaný', 'Scheduled') : t('Ruční', 'Manual')}
        </td>
        <td style={{ padding: '10px 16px', color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>{fmtPeriod(run)}</td>
        <td style={{ padding: '10px 16px' }}>{statusPill(run.status)}</td>
        <td style={{ padding: '10px 16px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)' }}>{run.pocketsClosed}</td>
        <td style={{ padding: '10px 16px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', fontWeight: run.pocketsFailed > 0 ? 700 : 400, color: run.pocketsFailed > 0 ? 'var(--danger)' : 'var(--text-secondary)' }}>{run.pocketsFailed}</td>
        <td style={{ padding: '10px 16px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)' }}>{run.pocketsSkipped}</td>
        <td style={{ padding: '10px 16px', textAlign: 'right', color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>{fmtDuration(run.startedAt, run.finishedAt)}</td>
      </tr>
      {expandable && isOpen && (
        <tr style={{ borderTop: '1px solid var(--border)', background: 'var(--surface-2)' }}>
          <td colSpan={9} style={{ padding: '10px 16px 14px 38px' }}>
            {fState === 'loading' || fState === undefined ? (
              <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{t('Načítání chyb…', 'Loading failures…')}</span>
            ) : fState === 'error' ? (
              <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
                {t('Detail chyb se nepodařilo načíst.', 'Failure details are not available right now.')}
              </span>
            ) : fState.length === 0 ? (
              <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
                {t('K tomuto běhu nejsou zaznamenány žádné chyby.', 'No failures recorded for this run.')}
              </span>
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                <thead>
                  <tr style={{ textAlign: 'left', color: 'var(--text-tertiary)' }}>
                    <th style={{ padding: '4px 10px 4px 0', fontWeight: 600 }}>{t('Účet', 'Account')}</th>
                    <th style={{ padding: '4px 10px', fontWeight: 600 }}>{t('Měna', 'Currency')}</th>
                    <th style={{ padding: '4px 10px', fontWeight: 600 }}>{t('Období', 'Period')}</th>
                    <th style={{ padding: '4px 10px', fontWeight: 600 }}>{t('Důvod', 'Reason')}</th>
                    <th style={{ padding: '4px 10px', fontWeight: 600 }}>{t('Detail', 'Detail')}</th>
                    <th style={{ padding: '4px 10px', fontWeight: 600 }}>{t('Selhalo v', 'Failed at')}</th>
                  </tr>
                </thead>
                <tbody>
                  {fState.map(f => (
                    <tr key={f.id} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={{ padding: '6px 10px 6px 0', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-secondary)' }}>{f.accountId}</td>
                      <td style={{ padding: '6px 10px', fontWeight: 600, color: 'var(--text-primary)' }}>{f.pocketCurrency}</td>
                      <td style={{ padding: '6px 10px', whiteSpace: 'nowrap', color: 'var(--text-secondary)' }}>{fmtPeriod(f)}</td>
                      <td style={{ padding: '6px 10px' }}>
                        <span className={f.reason === 'UPSTREAM' ? 'pill pill-warning' : 'pill pill-danger'}>{reasonLabel(f.reason)}</span>
                      </td>
                      <td style={{ padding: '6px 10px', color: 'var(--text-secondary)' }}>{f.detail ?? '—'}</td>
                      <td style={{ padding: '6px 10px', whiteSpace: 'nowrap', color: 'var(--text-secondary)' }}>{fmtTs(f.failedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

function Kpi({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="stat-card" style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '14px 16px' }}>
      <div style={{ padding: '9px', borderRadius: 'var(--r-md)', background: 'var(--surface-2)', color: 'var(--text-secondary)' }}>{icon}</div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: '15px', fontWeight: 600, color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{value}</div>
        <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '1px' }}>{label}</div>
      </div>
    </div>
  )
}

function CycleCard({ tag, status, title, body }: { tag: string; status: 'live' | 'pending' | 'none'; title: string; body: string }) {
  const { t } = useLanguage()
  const cfg = {
    live: { label: t('Běží', 'Live'), color: 'var(--success)', bg: 'var(--success-bg)', border: 'var(--success-border)' },
    pending: { label: t('Připraveno', 'Pending'), color: 'var(--warning)', bg: 'var(--warning-bg)', border: 'var(--warning-border)' },
    none: { label: t('Není', 'None'), color: 'var(--text-tertiary)', bg: 'var(--surface-2)', border: 'var(--border)' },
  }[status]
  return (
    <div style={{ background: 'var(--surface)', border: `1px solid ${cfg.border}`, borderRadius: 'var(--r-lg)', padding: '14px 16px', boxShadow: 'var(--shadow-xs)' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' }}>
        <span style={{ fontSize: '11px', fontWeight: 800, letterSpacing: '0.06em', fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-tertiary)' }}>{tag}</span>
        <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '10px', textTransform: 'uppercase', letterSpacing: '0.05em', color: cfg.color, background: cfg.bg, border: `1px solid ${cfg.border}` }}>{cfg.label}</span>
      </div>
      <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '4px' }}>{title}</div>
      <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', lineHeight: 1.5 }}>{body}</div>
    </div>
  )
}
