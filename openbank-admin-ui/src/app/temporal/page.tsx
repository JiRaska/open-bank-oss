// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback } from 'react'
import { RefreshCw, CheckCircle, Clock, AlertTriangle, Zap, Database, Shield, GitBranch, Activity, Server, ChevronRight, ArrowRight } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { MONEY_WORKFLOWS } from '@/lib/temporal/workflows'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui/PageHeader'

// ─── types ───────────────────────────────────────────────────────────────────

interface TemporalMetrics {
  workflows: {
    scheduled1h: number
    completed1h: number
    failed1h: number
    timedOut1h: number
  }
  latency: {
    activityScheduleToStartMs: number | null
    workflowTaskScheduleToStartMs: number | null
    serverRequestP99Ms: number | null
  }
  persistence: {
    requestsPerSec: number | null
  }
  workers: {
    totalSlotsAvailable: number | null
    slotsUsed: number | null
  }
  namespaces: string[]
}

interface StatusData {
  available: boolean
  temporalDeployed: boolean
  metrics: TemporalMetrics | null
}

type Tab = 'overview' | 'migration' | 'metrics' | 'architecture'

// ─── migration phases ─────────────────────────────────────────────────────────

const PHASES = [
  {
    id: 'P0',
    labelCs: 'Sdílená knihovna',
    labelEn: 'Shared Library',
    status: 'partial' as const,
    services: ['openbank-libs'],
    descCs: 'Dodáno: klient a konfigurace (TemporalClientProducer, TemporalConfig). Saga DSL, OPA interceptor a deterministický RNG zatím ne.',
    descEn: 'Shipped: client and config (TemporalClientProducer, TemporalConfig). Saga DSL, OPA interceptor and deterministic RNG are not.',
  },
  {
    id: 'P1',
    labelCs: 'SEPA platby',
    labelEn: 'SEPA Payments',
    status: 'done' as const,
    services: ['openbank-sepa-payment-service'],
    descCs: 'První migrace money-path workflowu — pilotní integrace a testování workflow',
    descEn: 'First money-path workflow migration — pilot integration and workflow testing',
  },
  {
    id: 'P2',
    labelCs: 'Tuzemské platby',
    labelEn: 'Domestic Payments',
    status: 'done' as const,
    services: ['openbank-domestic-payment-service'],
    descCs: 'Domácí převody přesunuty do Temporal; outbox_saga_state tabulka degradována',
    descEn: 'Domestic transfers moved to Temporal; outbox_saga_state table deprecated',
  },
  {
    id: 'P3',
    labelCs: 'Settlement (2 approvaly)',
    labelEn: 'Settlement (2 approvals)',
    status: 'done' as const,
    services: ['openbank-settlement-service'],
    descCs: 'Money-path — vyžaduje 2 approvaly + aktualizaci threat modelu (ADR-0030)',
    descEn: 'Money-path — requires 2 approvals + threat model update (ADR-0030)',
    moneyPath: true,
  },
  {
    id: 'P4',
    labelCs: 'FX & výpisy',
    labelEn: 'FX & Statements',
    status: 'done' as const,
    services: ['openbank-fx-service', 'openbank-statement-service'],
    descCs: 'FX konverze a EoD/EoM/EoY záverky přesunuty do Temporal',
    descEn: 'FX conversions and EoD/EoM/EoY closings moved to Temporal',
  },
  {
    id: 'P5',
    labelCs: 'Dekomisace legacy',
    labelEn: 'Decommission legacy',
    status: 'pending' as const,
    services: ['openbank-libs'],
    descCs: 'Odstranění outbox_saga_state, SagaCoordinator a custom kompenzačních tabulek',
    descEn: 'Remove outbox_saga_state, SagaCoordinator and custom compensation tables',
  },
]

const COMPLIANCE_LINKS = [
  { code: 'DORA Art. 11', descCs: 'Business continuity — durable execution přežije restart', descEn: 'Business continuity — durable execution survives restart' },
  { code: 'DORA Art. 17', descCs: 'Advanced testing — deterministická simulace (ADR-0100)', descEn: 'Advanced testing — deterministic simulation (ADR-0100)' },
  { code: 'PSD2 Art. 5(3)', descCs: 'SCA integrita — plánováno (ADR-0034); activity-level policy gate zatím není nasazen', descEn: 'SCA integrity — planned (ADR-0034); no activity-level policy gate is deployed yet' },
  { code: 'PCI-DSS Req. 10', descCs: 'Auditní záznamy — Temporal history je immutable log', descEn: 'Audit records — Temporal history is an immutable log' },
  { code: 'GDPR', descCs: '90denní retence, EU region (eu-north-1), datová minimalizace', descEn: '90-day retention, EU region (eu-north-1), data minimization' },
]

// ─── comparison data ──────────────────────────────────────────────────────────

const COMPARISON = [
  {
    dimensionCs: 'Specifikace workflowu',
    dimensionEn: 'Workflow specification',
    beforeCs: 'Stavový automat v DB tabulce (outbox_saga_state) + kód SagaCoordinator',
    beforeEn: 'State machine in DB table (outbox_saga_state) + SagaCoordinator code',
    afterCs: 'Kotlin kód IS specifikace — Temporal replay rekonstruuje stav ze history',
    afterEn: 'Kotlin code IS the specification — Temporal replay reconstructs state from history',
  },
  {
    dimensionCs: 'Odolnost vůči výpadkům',
    dimensionEn: 'Failure resilience',
    beforeCs: 'Ruční retry logika, možnost ztráty stavu při restartu',
    beforeEn: 'Manual retry logic, potential state loss on restart',
    afterCs: 'Durable execution — workflow pokračuje přesně tam, kde skončil, i po crashu',
    afterEn: 'Durable execution — workflow resumes exactly where it stopped, even after crash',
  },
  {
    dimensionCs: 'Kompenzace (Saga)',
    dimensionEn: 'Compensation (Saga)',
    beforeCs: 'Kompenzace řešené ad hoc v jednotlivých službách; sdílené saga primitivum neexistuje',
    beforeEn: 'Compensation handled ad hoc per service; no shared saga primitive exists',
    afterCs: 'Saga kompenzace jako nativní Temporal pattern, plně perzistentní',
    afterEn: 'Saga compensation as native Temporal pattern, fully persistent',
  },
  {
    dimensionCs: 'Auditní záznamy',
    dimensionEn: 'Audit records',
    beforeCs: 'Vyžaduje custom implementaci v audit-service',
    beforeEn: 'Requires custom implementation in audit-service',
    afterCs: 'Temporal history je immutable log každého rozhodnutí (DORA Art. 17)',
    afterEn: 'Temporal history is an immutable log of every decision (DORA Art. 17)',
  },
  {
    dimensionCs: 'Testovatelnost',
    dimensionEn: 'Testability',
    beforeCs: 'Integrační testy s reálnou DB, obtížné deterministické scénáře',
    beforeEn: 'Integration tests with real DB, difficult deterministic scenarios',
    afterCs: 'TestWorkflowEnvironment — plně deterministická simulace bez infrastruktury',
    afterEn: 'TestWorkflowEnvironment — fully deterministic simulation without infrastructure',
  },
  {
    dimensionCs: 'Policy enforcement',
    dimensionEn: 'Policy enforcement',
    beforeCs: 'HTTP middleware, není vynuceno na úrovni aktivity',
    beforeEn: 'HTTP middleware, not enforced at activity level',
    afterCs: 'Plánováno: OpaActivityInterceptor (ADR-0034). Dnes nezměněno — autorizace zůstává na HTTP vrstvě.',
    afterEn: 'Planned: OpaActivityInterceptor (ADR-0034). Unchanged today — authorization stays at the HTTP layer.',
  },
]

// ─── money-path workflow map ──────────────────────────────────────────────────


// ─── helpers ──────────────────────────────────────────────────────────────────

function MetricCard({ label, value, unit, status }: {
  label: string
  value: number | null | string
  unit?: string
  status?: 'good' | 'warn' | 'bad' | 'neutral'
}) {
  const colors: Record<string, string> = {
    good: 'var(--color-success, #22c55e)',
    warn: 'var(--color-warning, #f59e0b)',
    bad: 'var(--color-danger, #ef4444)',
    neutral: 'var(--text-muted)',
  }
  const color = status ? colors[status] : 'var(--text)'
  return (
    <div aria-label={`${label}: ${value === null ? 'unavailable' : value}${unit && value !== null ? ` ${unit}` : ''}`} style={{
      background: 'var(--card-bg)',
      border: '1px solid var(--border)',
      borderRadius: '12px',
      padding: '20px',
    }}>
      <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '8px' }}>{label}</div>
      <div style={{ fontSize: '28px', fontWeight: 800, color, fontVariantNumeric: 'tabular-nums' }}>
        {value === null ? '—' : value}{unit && value !== null ? <span style={{ fontSize: '14px', fontWeight: 500, color: 'var(--text-muted)', marginLeft: '4px' }}>{unit}</span> : null}
      </div>
    </div>
  )
}

function PhaseRow({ phase, t }: { phase: typeof PHASES[0]; t: (cs: string, en: string) => string }) {
  const isDone = phase.status === 'done'
  return (
    <div style={{
      display: 'flex',
      gap: '16px',
      padding: '16px',
      borderRadius: '10px',
      background: isDone ? 'rgba(34, 197, 94, 0.06)' : 'var(--card-bg)',
      border: `1px solid ${isDone ? 'rgba(34, 197, 94, 0.25)' : 'var(--border)'}`,
      alignItems: 'flex-start',
    }}>
      <div style={{
        width: '36px', height: '36px', borderRadius: '50%', flexShrink: 0,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: isDone ? 'rgba(34, 197, 94, 0.15)' : 'var(--muted-bg, rgba(255,255,255,0.05))',
        border: `2px solid ${isDone ? '#22c55e' : 'var(--border)'}`,
        fontSize: '12px', fontWeight: 800, color: isDone ? '#22c55e' : 'var(--text-muted)',
      }}>
        {isDone ? <CheckCircle size={16} color="#22c55e" /> : phase.id}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px', flexWrap: 'wrap' }}>
          <span style={{ fontWeight: 700, fontSize: '14px', color: 'var(--text)' }}>
            {t(phase.labelCs, phase.labelEn)}
          </span>
          {phase.moneyPath && (
            <span style={{
              fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '8px',
              background: 'rgba(239,68,68,0.15)', color: '#ef4444', border: '1px solid rgba(239,68,68,0.3)',
            }}>money-path</span>
          )}
          {isDone && (
            <span style={{
              fontSize: '10px', fontWeight: 700, padding: '2px 7px', borderRadius: '8px',
              background: 'rgba(34,197,94,0.15)', color: '#22c55e', border: '1px solid rgba(34,197,94,0.3)',
            }}>{t('DOKONČENO', 'DONE')}</span>
          )}
        </div>
        <div style={{ fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
          {t(phase.descCs, phase.descEn)}
        </div>
        <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
          {phase.services.map(svc => (
            <span key={svc} style={{
              fontSize: '11px', padding: '2px 8px', borderRadius: '6px',
              background: 'var(--muted-bg, rgba(255,255,255,0.06))', color: 'var(--text-muted)',
              fontFamily: 'monospace', border: '1px solid var(--border)',
            }}>{svc}</span>
          ))}
        </div>
      </div>
    </div>
  )
}

// ─── main component ───────────────────────────────────────────────────────────

export default function TemporalPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [tab, setTab] = useState<Tab>('overview')
  const [status, setStatus] = useState<StatusData | null>(null)
  const [loading, setLoading] = useState(true)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/temporal/status')
      if (res.ok) {
        setStatus(await res.json() as StatusData)
      } else {
        setStatus({ available: false, temporalDeployed: false, metrics: null })
      }
    } catch {
      setStatus({ available: false, temporalDeployed: false, metrics: null })
    } finally {
      setLoading(false)
      setLastRefresh(new Date())
    }
  }, [])

  useEffect(() => { load() }, [load])

  const tabs: { id: Tab; labelCs: string; labelEn: string }[] = [
    { id: 'overview', labelCs: 'Přehled', labelEn: 'Overview' },
    { id: 'migration', labelCs: 'Migrace', labelEn: 'Migration' },
    { id: 'metrics', labelCs: 'Metriky', labelEn: 'Metrics' },
    { id: 'architecture', labelCs: 'Architektura', labelEn: 'Architecture' },
  ]

  const m = status?.metrics
  const workerUtilPct = m?.workers.totalSlotsAvailable && m.workers.slotsUsed !== null
    ? Math.round((m.workers.slotsUsed / (m.workers.totalSlotsAvailable + m.workers.slotsUsed)) * 100)
    : null

  return (
    <AuthGuard permission="system:view">
      <div style={{ maxWidth: '1200px', margin: '0 auto' }}>

        <PageHeader
          breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">Temporal</span></div>}
          icon={<Zap size={20} aria-hidden="true" />}
          title="Temporal"
          subtitle={t('Durable execution engine pro money-path workflows — challenger model nahrazující ruční outbox sagu', 'Durable execution engine for money-path workflows — challenger model replacing hand-rolled outbox saga')}
          actions={<div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexShrink: 0 }}>
              {status && (
                <div style={{
                  display: 'flex', alignItems: 'center', gap: '6px',
                  padding: '6px 12px', borderRadius: '20px',
                  background: status.temporalDeployed ? 'rgba(34,197,94,0.1)' : 'rgba(245,158,11,0.1)',
                  border: `1px solid ${status.temporalDeployed ? 'rgba(34,197,94,0.3)' : 'rgba(245,158,11,0.3)'}`,
                  fontSize: '12px', fontWeight: 600,
                  color: status.temporalDeployed ? '#22c55e' : '#f59e0b',
                }}>
                  <div style={{
                    width: '6px', height: '6px', borderRadius: '50%',
                    background: status.temporalDeployed ? '#22c55e' : '#f59e0b',
                  }} />
                  {status.temporalDeployed
                    ? t('Provozní', 'Running')
                    : status.available
                      ? t('Workers: aktif, metriky: inicializace', 'Workers: active, metrics: initialising')
                      : t('Prometheus nedostupný', 'Prometheus unavailable')}
                </div>
              )}
              <button
                type="button"
                onClick={load}
                disabled={loading}
                aria-busy={loading}
                aria-label={t('Obnovit stav Temporal', 'Refresh Temporal status')}
                style={{
                  display: 'flex', alignItems: 'center', gap: '6px',
                  padding: '8px 14px', borderRadius: '8px',
                  background: 'var(--card-bg)', border: '1px solid var(--border)',
                  color: 'var(--text)', fontSize: '13px', fontWeight: 600, cursor: loading ? 'wait' : 'pointer',
                }}
              >
                <RefreshCw size={14} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
                {t('Obnovit', 'Refresh')}
              </button>
          </div>}
        />
        {lastRefresh && <div style={{ fontSize: '12px', color: 'var(--text-muted)', margin: '8px 0 16px' }}>{t('Aktualizováno', 'Updated')}: {lastRefresh.toLocaleTimeString(dateLocale)}</div>}

        {/* Tabs */}
        <div style={{ display: 'flex', gap: '4px', marginBottom: '24px', borderBottom: '1px solid var(--border)', paddingBottom: '0' }}>
          {tabs.map(tb => (
            <button
              key={tb.id}
              onClick={() => setTab(tb.id)}
              style={{
                padding: '10px 18px', fontSize: '14px', fontWeight: tab === tb.id ? 700 : 500,
                color: tab === tb.id ? '#818cf8' : 'var(--text-muted)',
                background: 'transparent', border: 'none',
                borderBottom: `2px solid ${tab === tb.id ? '#818cf8' : 'transparent'}`,
                cursor: 'pointer', marginBottom: '-1px', transition: 'all 0.15s',
              }}
            >
              {t(tb.labelCs, tb.labelEn)}
            </button>
          ))}
        </div>

        {/* ── OVERVIEW TAB ── */}
        {tab === 'overview' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>

            {/* Challenger callout */}
            <div style={{
              background: 'linear-gradient(135deg, rgba(99,102,241,0.08) 0%, rgba(139,92,246,0.08) 100%)',
              border: '1px solid rgba(99,102,241,0.25)',
              borderRadius: '16px', padding: '24px',
            }}>
              <div style={{ fontWeight: 800, fontSize: '16px', color: '#a5b4fc', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Zap size={18} />
                {t('Proč Temporal — challenger model', 'Why Temporal — challenger model')}
              </div>
              <p style={{ color: 'var(--text)', fontSize: '14px', lineHeight: '1.7', margin: '0 0 12px' }}>
                {t(
    'OpenBank dnes nemá sdílené orchestrační primitivum pro money-path workflowy: kompenzace řeší každá služba po svém, přes transakční outbox a vlastní stavové přechody. Ani třída OpenBankSaga, ani tabulka outbox_saga_state v repozitáři neexistují — tato stránka je návrh cílového stavu podle ADR-0101, ne popis nasazeného systému.',
    'OpenBank has no shared orchestration primitive for money-path workflows today: each service handles compensation its own way, through the transactional outbox and its own state transitions. Neither an OpenBankSaga class nor an outbox_saga_state table exists in the repository — this page describes the ADR-0101 target state, not a deployed system.',
                )}
              </p>
              <p style={{ color: 'var(--text)', fontSize: '14px', lineHeight: '1.7', margin: 0 }}>
                {t(
                  'Temporal je durable execution engine: kód workflowu je specifikace, server perzistuje každou událost a může přesně zreprodukovat stav pomocí replay. Není třeba spravovat stavové tabulky ani retry logiku — workflow „přežije" restart serveru, OOMKill i výpadek sítě přesně tam, kde skončil.',
                  'Temporal is a durable execution engine: the workflow code is the specification, the server persists every event and can exactly reproduce state using replay. No need to manage state tables or retry logic — a workflow "survives" a server restart, OOMKill or network outage exactly where it stopped.',
                )}
              </p>
            </div>

            {/* Money-path workflows in OpenBank */}
            <div>
              <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text)', marginBottom: '6px', display: 'flex', alignItems: 'center', gap: 8 }}>
                <Activity size={16} color="#818cf8" />
                {t('Money-path workflowy v OpenBank', 'Money-path workflows in OpenBank')}
              </h2>
              <p style={{ fontSize: '13px', color: 'var(--text-muted)', marginBottom: '16px', maxWidth: 680 }}>
                {t(
                  'Každý money-path workflow žije jako kód — Temporal ho přerozehraje krok po kroku i po restartu procesu nebo výpadku. Klíčové: workeři jsou Quarkus služby, Temporal server pouze koordinuje a loguje historii.',
                  'Each money-path workflow lives as code — Temporal can replay it step by step even after a process restart or outage. Key insight: the workers are Quarkus services; Temporal server only coordinates and records history.',
                )}
              </p>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: '12px' }}>
                {MONEY_WORKFLOWS.map((wf) => {
                  const WfIcon = wf.icon
                  return (
                    <div key={wf.svc} style={{ padding: '16px', borderRadius: '12px', background: 'var(--card-bg)', border: '1px solid var(--border)', borderLeft: `3px solid ${wf.color}` }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                        <WfIcon size={15} style={{ color: wf.color }} />
                        <span style={{ fontWeight: 700, fontSize: '13.5px', color: 'var(--text)' }}>{t(wf.serviceCs, wf.serviceEn)}</span>
                      </div>
                      <div style={{ fontSize: '11.5px', fontFamily: 'monospace', color: wf.color, marginBottom: 8, background: `${wf.color}14`, padding: '3px 8px', borderRadius: 5, display: 'inline-block' }}>
                        {wf.workflowCs}
                      </div>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 8 }}>
                        {wf.stepsCs.map((step, i) => (
                          <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 6, fontSize: '12px', color: 'var(--text-muted)' }}>
                            <ArrowRight size={11} style={{ color: wf.color, flexShrink: 0, marginTop: 2 }} />
                            {t(step, wf.stepsEn[i])}
                          </div>
                        ))}
                      </div>
                      <div style={{ fontSize: '10.5px', color: 'var(--text-tertiary)', fontFamily: 'monospace', background: 'var(--surface-2)', padding: '3px 8px', borderRadius: 4 }}>
                        {wf.svc}
                      </div>
                    </div>
                  )
                })}
              </div>
              <div style={{ marginTop: 12, padding: '12px 14px', borderRadius: 10, background: 'rgba(34,197,94,0.07)', border: '1px solid rgba(34,197,94,0.2)', fontSize: '12.5px', color: 'var(--text-muted)', display: 'flex', gap: 8 }}>
                <CheckCircle size={14} color="#22c55e" style={{ flexShrink: 0, marginTop: 1 }} />
                {t(
                  'Workery (Quarkus služby) se připojují k Temporal serveru přes gRPC a registrují workflow + activity implementace. Temporal server neobsahuje byznys logiku — ta žije výhradně v kódu workerů. Při výpadku workera Temporal čeká na jeho restart a pokračuje od posledního checkpointu.',
                  'Workers (Quarkus services) connect to the Temporal server over gRPC and register their workflow + activity implementations. The Temporal server contains no business logic — that lives exclusively in the worker code. When a worker goes down, Temporal waits for it to restart and continues from the last checkpoint.',
                )}
              </div>
            </div>

            {/* Comparison table */}
            <div>
              <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text)', marginBottom: '16px' }}>
                {t('Porovnání: ruční saga vs. Temporal', 'Comparison: hand-rolled saga vs. Temporal')}
              </h2>
              <div style={{ border: '1px solid var(--border)', borderRadius: '12px', overflow: 'hidden' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                  <thead>
                    <tr style={{ background: 'var(--card-bg)' }}>
                      <th style={{ textAlign: 'left', padding: '12px 16px', color: 'var(--text-muted)', fontWeight: 600, borderBottom: '1px solid var(--border)', width: '22%' }}>
                        {t('Oblast', 'Dimension')}
                      </th>
                      <th style={{ textAlign: 'left', padding: '12px 16px', color: '#f87171', fontWeight: 600, borderBottom: '1px solid var(--border)', width: '39%' }}>
                        {t('Před (outbox saga)', 'Before (outbox saga)')}
                      </th>
                      <th style={{ textAlign: 'left', padding: '12px 16px', color: '#4ade80', fontWeight: 600, borderBottom: '1px solid var(--border)', width: '39%' }}>
                        {t('Po (Temporal)', 'After (Temporal)')}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {COMPARISON.map((row, i) => (
                      <tr key={i} style={{ borderBottom: i < COMPARISON.length - 1 ? '1px solid var(--border)' : 'none' }}>
                        <td style={{ padding: '12px 16px', fontWeight: 600, color: 'var(--text)', verticalAlign: 'top' }}>
                          {t(row.dimensionCs, row.dimensionEn)}
                        </td>
                        <td style={{ padding: '12px 16px', color: 'var(--text-muted)', verticalAlign: 'top' }}>
                          {t(row.beforeCs, row.beforeEn)}
                        </td>
                        <td style={{ padding: '12px 16px', color: 'var(--text)', verticalAlign: 'top' }}>
                          {t(row.afterCs, row.afterEn)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Compliance grid */}
            <div>
              <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text)', marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Shield size={16} color="#818cf8" />
                {t('Regulatorní zarovnání', 'Regulatory alignment')}
              </h2>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '12px' }}>
                {COMPLIANCE_LINKS.map(item => (
                  <div key={item.code} style={{
                    padding: '14px 16px', borderRadius: '10px',
                    background: 'var(--card-bg)', border: '1px solid var(--border)',
                    display: 'flex', gap: '12px', alignItems: 'flex-start',
                  }}>
                    <span style={{
                      fontSize: '11px', fontWeight: 800, padding: '3px 8px', borderRadius: '6px',
                      background: 'rgba(99,102,241,0.15)', color: '#818cf8',
                      border: '1px solid rgba(99,102,241,0.3)', flexShrink: 0, marginTop: '2px',
                    }}>{item.code}</span>
                    <span style={{ fontSize: '13px', color: 'var(--text-muted)', lineHeight: '1.5' }}>
                      {t(item.descCs, item.descEn)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* ── MIGRATION TAB ── */}
        {tab === 'migration' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>

            {/* Progress bar */}
            <div style={{
              background: 'var(--card-bg)', border: '1px solid var(--border)',
              borderRadius: '12px', padding: '20px',
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
                <span style={{ fontWeight: 700, fontSize: '14px', color: 'var(--text)' }}>
                  {t('Celkový postup migrace', 'Overall migration progress')}
                </span>
                <span style={{ fontWeight: 800, fontSize: '16px', color: '#818cf8' }}>
                  {PHASES.filter((p) => p.status === 'done').length} / {PHASES.length} {t('fází', 'phases')}
                </span>
              </div>
              <div style={{ height: '8px', borderRadius: '8px', background: 'var(--border)', overflow: 'hidden' }}>
                <div style={{
                  height: '100%', borderRadius: '8px',
                  background: 'linear-gradient(90deg, #6366f1 0%, #8b5cf6 100%)',
                  width: `${Math.round((PHASES.filter((p) => p.status === 'done').length / PHASES.length) * 100)}%`,
                  transition: 'width 0.5s ease',
                }} />
              </div>
            </div>

            {/* Phase list */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {PHASES.map(phase => (
                <PhaseRow key={phase.id} phase={phase} t={t} />
              ))}
            </div>

            {/* Timeline note */}
            <div style={{
              padding: '14px 16px', borderRadius: '10px',
              background: 'rgba(34,197,94,0.08)', border: '1px solid rgba(34,197,94,0.25)',
              fontSize: '13px', color: 'var(--text-muted)', display: 'flex', gap: '10px', alignItems: 'flex-start',
            }}>
              <CheckCircle size={16} color="#22c55e" style={{ flexShrink: 0, marginTop: '1px' }} />
              <span>
                {t(
                  'Toto je referenční migrační plán z ADR-0100, ne živý stav běhů. Aktuální aktivitu a typy workflow potvrzují pouze metriky níže.',
                  'This is the ADR-0100 reference migration plan, not live execution state. Only the metrics below confirm current activity and workflow types.',
                )}
              </span>
            </div>
          </div>
        )}

        {/* ── METRICS TAB ── */}
        {tab === 'metrics' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>

            {/* Status banner */}
            {!status?.available && (
              <div style={{
                padding: '16px', borderRadius: '10px',
                background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.25)',
                display: 'flex', gap: '10px', alignItems: 'center',
              }}>
                <AlertTriangle size={16} color="#f59e0b" />
                <span style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
                  {t('Prometheus nedostupný — metriky nelze načíst', 'Prometheus unavailable — metrics cannot be loaded')}
                </span>
              </div>
            )}

            {status?.available && !status.temporalDeployed && (
              <div style={{
                padding: '16px', borderRadius: '10px',
                background: 'rgba(99,102,241,0.08)', border: '1px solid rgba(99,102,241,0.25)',
                display: 'flex', gap: '12px', alignItems: 'flex-start',
              }}>
                <Clock size={16} color="#818cf8" style={{ flexShrink: 0, marginTop: 2 }} />
                <div>
                  <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: 4 }}>
                    {t('Temporal nelze potvrdit z Promethea', 'Temporal cannot be confirmed from Prometheus')}
                  </div>
                  <div style={{ fontSize: '12.5px', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
                    {t(
                      'Prometheus odpověděl, ale nevrátil žádnou temporal_restarts řadu. To může znamenat nenasazený Temporal nebo chybějící scrape; tato stránka z toho neodvozuje, že server či workflow běží. Ověř PodMonitor, target health a Temporal frontend.',
                      'Prometheus responded but returned no temporal_restarts series. Temporal may be absent or its scrape may be missing; this page does not infer that the server or workflows are running. Check the PodMonitor, target health, and Temporal frontend.',
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* Workflow execution metrics */}
            <div>
              <h2 style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '12px' }}>
                {t('Workflowy (posledních 60 minut)', 'Workflows (last 60 minutes)')}
              </h2>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: '12px' }}>
                <MetricCard
                  label={t('Spuštěno', 'Scheduled')}
                  value={m?.workflows.scheduled1h ?? null}
                  status="neutral"
                />
                <MetricCard
                  label={t('Dokončeno', 'Completed')}
                  value={m?.workflows.completed1h ?? null}
                  status={m ? 'good' : 'neutral'}
                />
                <MetricCard
                  label={t('Selhalo', 'Failed')}
                  value={m?.workflows.failed1h ?? null}
                  status={m && (m.workflows.failed1h ?? 0) > 0 ? 'bad' : 'neutral'}
                />
                <MetricCard
                  label={t('Vypršelo', 'Timed out')}
                  value={m?.workflows.timedOut1h ?? null}
                  status={m && (m.workflows.timedOut1h ?? 0) > 0 ? 'warn' : 'neutral'}
                />
              </div>
            </div>

            {/* Latency metrics */}
            <div>
              <h2 style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '12px' }}>
                {t('Latence (p50)', 'Latency (p50)')}
              </h2>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: '12px' }}>
                <MetricCard
                  label={t('Aktivita (schedule→start)', 'Activity (schedule→start)')}
                  value={m?.latency.activityScheduleToStartMs ?? null}
                  unit="ms"
                  status={m?.latency.activityScheduleToStartMs
                    ? m.latency.activityScheduleToStartMs < 100 ? 'good' : m.latency.activityScheduleToStartMs < 500 ? 'warn' : 'bad'
                    : 'neutral'}
                />
                <MetricCard
                  label={t('Workflow task (schedule→start)', 'Workflow task (schedule→start)')}
                  value={m?.latency.workflowTaskScheduleToStartMs ?? null}
                  unit="ms"
                  status={m?.latency.workflowTaskScheduleToStartMs
                    ? m.latency.workflowTaskScheduleToStartMs < 50 ? 'good' : m.latency.workflowTaskScheduleToStartMs < 200 ? 'warn' : 'bad'
                    : 'neutral'}
                />
                <MetricCard
                  label={t('Server gRPC (p99)', 'Server gRPC (p99)')}
                  value={m?.latency.serverRequestP99Ms ?? null}
                  unit="ms"
                  status={m?.latency.serverRequestP99Ms
                    ? m.latency.serverRequestP99Ms < 50 ? 'good' : m.latency.serverRequestP99Ms < 200 ? 'warn' : 'bad'
                    : 'neutral'}
                />
              </div>
            </div>

            {/* Workers & persistence */}
            <div>
              <h2 style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '12px' }}>
                {t('Workers & persistence', 'Workers & persistence')}
              </h2>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: '12px' }}>
                <MetricCard
                  label={t('Worker sloty (využití %)', 'Worker slots (utilisation %)')}
                  value={workerUtilPct}
                  unit="%"
                  status={workerUtilPct !== null
                    ? workerUtilPct < 70 ? 'good' : workerUtilPct < 90 ? 'warn' : 'bad'
                    : 'neutral'}
                />
                <MetricCard
                  label={t('DB persistence req/s', 'DB persistence req/s')}
                  value={m?.persistence.requestsPerSec ?? null}
                  unit="/s"
                  status="neutral"
                />
                <MetricCard
                  label={t('Namespace', 'Namespace')}
                  value={m?.namespaces.join(', ') ?? null}
                  status="neutral"
                />
              </div>
            </div>

            {/* Grafana link hint */}
            <div style={{
              padding: '14px 16px', borderRadius: '10px',
              background: 'var(--card-bg)', border: '1px solid var(--border)',
              fontSize: '13px', color: 'var(--text-muted)', display: 'flex', gap: '10px', alignItems: 'center',
            }}>
              <Activity size={16} color="#818cf8" />
              <span>
                {t(
                  'Podrobné dashboardy jsou dostupné v Grafaně (INTERNAL). Temporal nabízí oficiální dashboard IDs 10716 a 10717 pro import.',
                  'Detailed dashboards are available in Grafana (INTERNAL). Temporal offers official dashboard IDs 10716 and 10717 for import.',
                )}
              </span>
            </div>
          </div>
        )}

        {/* ── ARCHITECTURE TAB ── */}
        {tab === 'architecture' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>

            {/* Architecture diagram (text-based) */}
            <div style={{
              background: 'var(--card-bg)', border: '1px solid var(--border)',
              borderRadius: '12px', padding: '24px',
            }}>
              <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text)', marginBottom: '20px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Server size={16} color="#818cf8" />
                {t('Komponenty', 'Components')}
              </h2>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: '12px' }}>
                {[
                  {
                    titleCs: 'Temporal Server',
                    titleEn: 'Temporal Server',
                    icon: <Server size={18} color="#818cf8" />,
                    itemsCs: ['Frontend (gRPC:7233)', 'History service', 'Matching service', 'Worker service', 'Helm chart v1.2.0'],
                    itemsEn: ['Frontend (gRPC:7233)', 'History service', 'Matching service', 'Worker service', 'Helm chart v1.2.0'],
                    ns: 'temporal',
                  },
                  {
                    titleCs: 'Persistence (CNPG)',
                    titleEn: 'Persistence (CNPG)',
                    icon: <Database size={18} color="#22d3ee" />,
                    itemsCs: ['DB: temporal (core events)', 'DB: temporal_visibility (vyhledávání)', 'S3 záloha, 30d retence', 'PodMonitor → Prometheus'],
                    itemsEn: ['DB: temporal (core events)', 'DB: temporal_visibility (search)', 'S3 backup, 30d retention', 'PodMonitor → Prometheus'],
                    ns: 'temporal',
                  },
                  {
                    titleCs: 'Worker SDK (openbank-libs)',
                    titleEn: 'Worker SDK (openbank-libs)',
                    icon: <GitBranch size={18} color="#4ade80" />,
                    itemsCs: ['TemporalClientProducer (CDI) — dodáno', 'TemporalConfig — dodáno', 'OpenBankSaga DSL — plánováno', 'OpaActivityInterceptor — plánováno', 'DeterministicRandom (ADR-0100) — plánováno', 'Feature flag: openbank.temporal.enabled'],
                    itemsEn: ['TemporalClientProducer (CDI) — shipped', 'TemporalConfig — shipped', 'OpenBankSaga DSL — planned', 'OpaActivityInterceptor — planned', 'DeterministicRandom (ADR-0100) — planned', 'Feature flag: openbank.temporal.enabled'],
                    ns: 'service namespace',
                  },
                  {
                    titleCs: 'OPA Policy Gate (ADR-0034)',
                    titleEn: 'OPA Policy Gate (ADR-0034)',
                    icon: <Shield size={18} color="#f59e0b" />,
                    itemsCs: ['PLÁNOVÁNO — nic z tohoto není nasazeno', 'Sidecar OPA v každém workeru', 'Policy: openbank/temporal/allow', 'Blokovalo by neoprávněné aktivity', 'Audit log každého rozhodnutí'],
                    itemsEn: ['PLANNED — none of this is deployed', 'OPA sidecar in each worker', 'Policy: openbank/temporal/allow', 'Would block unauthorized activities', 'Audit log of each decision'],
                    ns: 'service namespace',
                  },
                ].map(comp => (
                  <div key={comp.titleEn} style={{
                    padding: '16px', borderRadius: '10px',
                    background: 'rgba(255,255,255,0.03)', border: '1px solid var(--border)',
                  }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                      {comp.icon}
                      <span style={{ fontWeight: 700, fontSize: '14px', color: 'var(--text)' }}>
                        {t(comp.titleCs, comp.titleEn)}
                      </span>
                    </div>
                    <ul style={{ margin: 0, padding: '0 0 0 16px', listStyle: 'disc' }}>
                      {(language === 'cs' ? comp.itemsCs : comp.itemsEn).map((item, idx) => (
                        <li key={idx} style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '4px' }}>
                          {item}
                        </li>
                      ))}
                    </ul>
                    <div style={{
                      marginTop: '10px', fontSize: '11px', padding: '3px 8px',
                      borderRadius: '6px', background: 'rgba(99,102,241,0.1)',
                      color: '#818cf8', display: 'inline-block', fontFamily: 'monospace',
                    }}>
                      ns: {comp.ns}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Data flow */}
            <div style={{
              background: 'var(--card-bg)', border: '1px solid var(--border)',
              borderRadius: '12px', padding: '24px',
            }}>
              <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text)', marginBottom: '16px' }}>
                {t('Tok dat: platba → Temporal', 'Data flow: payment → Temporal')}
              </h2>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                {[
                  { stepCs: 'REST API volání', stepEn: 'REST API call', detailCs: 'Klient volá /api/v1/payments (sepa-payment-service)', detailEn: 'Client calls /api/v1/payments (sepa-payment-service)' },
                  { stepCs: 'OPA authz check (plánováno)', stepEn: 'OPA authz check (planned)', detailCs: 'Sidecar OPA by potvrdil oprávnění volající party — není nasazeno', detailEn: 'An OPA sidecar would confirm the calling party\'s authorization — not deployed' },
                  { stepCs: 'Workflow start', stepEn: 'Workflow start', detailCs: 'TemporalClient.start(SepaPaymentWorkflow, input)', detailEn: 'TemporalClient.start(SepaPaymentWorkflow, input)' },
                  { stepCs: 'Temporal server → History service', stepEn: 'Temporal server → History service', detailCs: 'Workflow execution event persistován do CNPG (temporal DB)', detailEn: 'Workflow execution event persisted to CNPG (temporal DB)' },
                  { stepCs: 'Worker task queue', stepEn: 'Worker task queue', detailCs: 'History service zařadí workflow task do sepa-payment-queue', detailEn: 'History service enqueues workflow task into sepa-payment-queue' },
                  { stepCs: 'Activity dispatch', stepEn: 'Activity dispatch', detailCs: 'ValidateSEPA activity (bez policy checku — OpaActivityInterceptor neexistuje)', detailEn: 'ValidateSEPA activity (no policy check — OpaActivityInterceptor does not exist)' },
                  { stepCs: 'Kompenzace při chybě', stepEn: 'Compensation on failure', detailCs: 'Saga.addCompensation { revertBalanceDebit() } → automaticky spuštěno', detailEn: 'Saga.addCompensation { revertBalanceDebit() } → automatically executed' },
                  { stepCs: 'Audit trail', stepEn: 'Audit trail', detailCs: 'Každý krok v Temporal history = immutable záznam pro DORA/PCI-DSS', detailEn: 'Each step in Temporal history = immutable record for DORA/PCI-DSS' },
                ].map((step, idx) => (
                  <div key={idx} style={{ display: 'flex', gap: '12px', alignItems: 'flex-start' }}>
                    <div style={{
                      width: '24px', height: '24px', borderRadius: '50%', flexShrink: 0,
                      background: 'rgba(99,102,241,0.15)', border: '1px solid rgba(99,102,241,0.3)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: '11px', fontWeight: 700, color: '#818cf8',
                    }}>{idx + 1}</div>
                    <div>
                      <div style={{ fontWeight: 600, fontSize: '13px', color: 'var(--text)' }}>
                        {t(step.stepCs, step.stepEn)}
                      </div>
                      <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontFamily: 'monospace' }}>
                        {t(step.detailCs, step.detailEn)}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* ADR links */}
            <div style={{
              background: 'var(--card-bg)', border: '1px solid var(--border)',
              borderRadius: '12px', padding: '20px',
            }}>
              <h2 style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text)', marginBottom: '12px' }}>
                {t('Relevantní ADRs', 'Relevant ADRs')}
              </h2>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                {[
                  { adr: 'ADR-0101', titleCs: 'Temporal Durable Execution', titleEn: 'Temporal Durable Execution', descCs: 'Primární rozhodnutí o adopci', descEn: 'Primary adoption decision', href: '/docs/adr' },
                  { adr: 'ADR-0100', titleCs: 'Deterministické simulační testování', titleEn: 'Deterministic Simulation Testing', descCs: 'Testovací harness pro workflow scénáře', descEn: 'Testing harness for workflow scenarios', href: '/docs/adr' },
                  { adr: 'ADR-0034', titleCs: 'OPA unifikovaná autorizace', titleEn: 'OPA unified authorisation', descCs: 'Policy gate pro každou Temporal aktivitu — plánováno, nenasazeno', descEn: 'Policy gate for each Temporal activity — planned, not deployed', href: '/docs/adr' },
                  { adr: 'ADR-0004', titleCs: 'Saga pattern', titleEn: 'Saga pattern', descCs: 'Zakladatel kompenzačního modelu', descEn: 'Foundation of the compensation model', href: '/docs/adr' },
                ].map(item => (
                  <a key={item.adr} href={item.href} style={{ textDecoration: 'none' }}>
                    <div style={{
                      display: 'flex', alignItems: 'center', gap: '12px',
                      padding: '10px 14px', borderRadius: '8px',
                      border: '1px solid var(--border)', background: 'transparent',
                      transition: 'background 0.15s',
                    }}
                      onMouseEnter={e => { e.currentTarget.style.background = 'rgba(99,102,241,0.06)' }}
                      onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
                    >
                      <span style={{
                        fontSize: '11px', fontWeight: 800, padding: '2px 8px', borderRadius: '6px',
                        background: 'rgba(99,102,241,0.15)', color: '#818cf8',
                        border: '1px solid rgba(99,102,241,0.3)', flexShrink: 0, fontFamily: 'monospace',
                      }}>{item.adr}</span>
                      <div style={{ flex: 1 }}>
                        <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text)' }}>
                          {t(item.titleCs, item.titleEn)}
                        </div>
                        <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                          {t(item.descCs, item.descEn)}
                        </div>
                      </div>
                      <ChevronRight size={14} color="var(--text-muted)" />
                    </div>
                  </a>
                ))}
              </div>
            </div>
          </div>
        )}

        <style>{`
          @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
        `}</style>
      </div>
    </AuthGuard>
  )
}
