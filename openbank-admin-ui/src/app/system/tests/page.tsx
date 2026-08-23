// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Activity, BarChart3, CheckCircle2, CircleHelp, Dna, FlaskConical,
  Gauge, RefreshCw, ShieldCheck, Timer, TriangleAlert, XCircle,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type {
  ComponentTestPosture, EvidenceKind, EvidenceState, TestIntelligenceReport,
} from '@/lib/types/test-intelligence'
import { TestIntelligenceFlow } from '@/components/testing/TestIntelligenceFlow'
import { TestAgentPanel } from '@/components/testing/TestAgentPanel'
import { PageHeader } from '@/components/ui/PageHeader'

type Tab = 'posture' | 'tests' | 'history' | 'execution' | 'runtime' | 'coverage' | 'contracts' | 'mutation' | 'performance' | 'synthetic'

const STATE_COLOR: Record<EvidenceState, string> = {
  passed: '#16a34a', failed: '#dc2626', skipped: '#d97706', 'not-run': '#64748b',
  stale: '#d97706', blocked: '#7c3aed', unknown: '#64748b',
}

const KINDS: EvidenceKind[] = ['unit', 'integration', 'contract', 'e2e', 'mutation', 'simulation', 'performance', 'synthetic']

function StateBadge({ state }: { state: EvidenceState }) {
  const Icon = state === 'passed' ? CheckCircle2 : state === 'failed' ? XCircle
    : state === 'unknown' || state === 'not-run' ? CircleHelp : TriangleAlert
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: STATE_COLOR[state], fontSize: 11, fontWeight: 700 }}>
      <Icon size={12} />{state}
    </span>
  )
}

function Stat({ label, value, tone }: { label: string; value: number | string; tone?: string }) {
  return (
    <div style={{ padding: 16, border: '1px solid var(--border)', borderRadius: 10, background: 'var(--surface-1)' }}>
      <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginBottom: 6 }}>{label}</div>
      <div style={{ fontSize: 24, fontWeight: 750, color: tone ?? 'var(--text-primary)' }}>{value}</div>
    </div>
  )
}

function EvidenceCell({ component, kind }: { component: ComponentTestPosture; kind: EvidenceKind }) {
  const evidence = component.evidence.find(item => item.kind === kind)
  if (!evidence) return <StateBadge state="not-run" />
  return <StateBadge state={evidence.state} />
}

const tableStyle = { width: '100%', borderCollapse: 'collapse' as const, fontSize: 12 }
const thStyle = { padding: '10px 12px', textAlign: 'left' as const, borderBottom: '1px solid var(--border)', color: 'var(--text-tertiary)', fontSize: 11 }
const tdStyle = { padding: '10px 12px', borderBottom: '1px solid var(--border-subtle)' }
const formatTimestamp = (value: string) => new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))

function Posture({ report }: { report: TestIntelligenceReport }) {
  const { t } = useLanguage()
  const sorted = useMemo(() => [...report.components].sort((a, b) => {
    if (a.moneyPath !== b.moneyPath) return a.moneyPath ? -1 : 1
    return a.component.localeCompare(b.component)
  }), [report.components])
  return (
    <>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12, marginBottom: 20 }}>
        <Stat label="Inventoried components" value={report.totals.components} />
        <Stat label="With execution evidence" value={`${report.totals.componentsWithExecutionEvidence}/${report.totals.components}`} />
        <Stat label="Money-path components" value={report.totals.moneyPathComponents} />
        <Stat label="Failing evidence" value={report.totals.failingEvidence} tone={report.totals.failingEvidence ? '#dc2626' : '#16a34a'} />
        <Stat label="No execution evidence" value={report.totals.missingEvidence} tone={report.totals.missingEvidence ? '#d97706' : '#16a34a'} />
        <Stat label="Stale evidence" value={report.totals.staleEvidence} tone={report.totals.staleEvidence ? '#d97706' : '#16a34a'} />
      </div>
      <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}>
        <table style={tableStyle}>
          <thead><tr><th style={thStyle}>{t('Komponenta', 'Component')}</th>{KINDS.map(kind => <th key={kind} style={thStyle}>{kind}</th>)}<th style={thStyle}>{t('Řádky Kover', 'Kover lines')}</th></tr></thead>
          <tbody>{sorted.map(component => (
            <tr key={component.component}>
              <td style={{ ...tdStyle, fontWeight: 650 }}>{component.component}{component.moneyPath && <span style={{ marginLeft: 6, color: '#dc2626', fontSize: 9 }}>{t('PENĚŽNÍ TOK', 'MONEY PATH')}</span>}</td>
              {KINDS.map(kind => <td key={kind} style={tdStyle}><EvidenceCell component={component} kind={kind} /></td>)}
              <td style={tdStyle}>{component.coverage.lines.percentage === null ? <StateBadge state={component.coverage.state} /> : `${component.coverage.lines.percentage}%`}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </>
  )
}

function Execution({ report }: { report: TestIntelligenceReport }) {
  const rows = report.components.flatMap(component => component.evidence
    .filter(item => ['unit', 'integration', 'e2e', 'simulation'].includes(item.kind))
    .map(item => ({ component: component.component, ...item })))
  return (
    <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}>
      <table style={tableStyle}><thead><tr>{['Component', 'Kind', 'State', 'Discovered', 'Executed', 'Passed', 'Failed', 'Skipped', 'Observed'].map(label => <th key={label} style={thStyle}>{label}</th>)}</tr></thead>
        <tbody>{rows.map((row, index) => <tr key={`${row.component}-${row.kind}-${index}`}>
          <td style={{ ...tdStyle, fontWeight: 650 }}>{row.component}</td><td style={tdStyle}>{row.kind}</td><td style={tdStyle}><StateBadge state={row.state} /></td>
          <td style={tdStyle}>{row.counts?.discovered ?? '—'}</td><td style={tdStyle}>{row.counts?.executed ?? '—'}</td><td style={tdStyle}>{row.counts?.passed ?? '—'}</td>
          <td style={tdStyle}>{row.counts?.failed ?? '—'}</td><td style={tdStyle}>{row.counts?.skipped ?? '—'}</td><td style={tdStyle}>{row.observedAt ? formatTimestamp(row.observedAt) : '—'}</td>
        </tr>)}</tbody>
      </table>
    </div>
  )
}

function TestCases({ report }: { report: TestIntelligenceReport }) {
  const { t } = useLanguage()
  const flaky = report.testCases.filter(item => item.state === 'flaky')
  const failing = report.testCases.filter(item => item.state === 'failing')
  const wasted = report.testCases.reduce((sum, item) => sum + item.wastedDurationMs, 0)
  return <div style={{ display: 'grid', gap: 18 }}>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
      <Stat label="Tracked test definitions" value={report.testCases.length} />
      <Stat label="Observed flaky" value={flaky.length} tone={flaky.length ? '#d97706' : '#16a34a'} />
      <Stat label="Currently failing" value={failing.length} tone={failing.length ? '#dc2626' : '#16a34a'} />
      <Stat label="Failed runtime" value={`${Math.round(wasted / 1000)} s`} tone={wasted ? '#d97706' : undefined} />
    </div>
    <div style={{ padding: 12, border: '1px solid color-mix(in srgb, #16a34a 35%, var(--border))', borderRadius: 9, color: 'var(--text-secondary)', fontSize: 12 }}>
      {t('Test je označen jako flaky až po úspěšném i neúspěšném pozorování stejného commitu. Vlastnictví vychází z CODEOWNERS. Triage nikdy nemění deterministický verdikt CI ani nepřeskakuje peněžní kontroly.', 'A test is marked flaky only after pass and fail observations on the same commit. Ownership comes from CODEOWNERS. Triage never changes the deterministic CI verdict or skips money-path controls.')}
    </div>
    <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}><table style={tableStyle}>
      <thead><tr>{['State', 'Test definition', 'Component', 'Kind', 'Owner', 'Runs', 'Failure rate', 'Avg duration', 'Failed runtime', 'Fingerprint'].map(label => <th key={label} style={thStyle}>{label}</th>)}</tr></thead>
      <tbody>{report.testCases.map(item => <tr key={item.fingerprint}>
        <td style={tdStyle}><StateBadge state={item.state === 'stable' ? 'passed' : item.state === 'failing' ? 'failed' : item.state === 'skipped' ? 'skipped' : 'stale'} /></td>
        <td style={{ ...tdStyle, minWidth: 230 }}><strong>{item.name}</strong><div style={{ color: 'var(--text-tertiary)', fontSize: 10, marginTop: 3 }}>{item.classname}</div>{item.sameCommitTransitions > 0 && <div style={{ color: '#d97706', fontSize: 10, marginTop: 3 }}>{item.sameCommitTransitions} same-commit pass/fail transition(s)</div>}</td>
        <td style={tdStyle}>{item.component}</td><td style={tdStyle}>{item.kind}</td><td style={tdStyle}>{item.owner}</td><td style={tdStyle}>{item.observations}</td>
        <td style={tdStyle}>{item.failureRate === null ? '—' : `${item.failureRate}%`}</td><td style={tdStyle}>{item.averageDurationMs} ms</td><td style={tdStyle}>{item.wastedDurationMs} ms</td>
        <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: 10 }}>{item.fingerprint}</td>
      </tr>)}</tbody>
    </table>{report.testCases.length === 0 && <div style={{ padding: 18, color: 'var(--text-tertiary)', fontSize: 12 }}>{t('Zatím nejsou uchovány žádné per-test obálky běhů. Verdikty sad zůstávají autoritativní.', 'No per-test run envelopes have been retained yet. Suite verdicts remain authoritative.')}</div>}</div>
  </div>
}

function History({ report }: { report: TestIntelligenceReport }) {
  const { t } = useLanguage()
  const max = Math.max(1, ...report.history.map(point => point.components))
  return <div style={{ display: 'grid', gap: 18 }}><div style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 18, background: 'var(--surface-1)' }}>
    <div style={{ marginBottom: 16 }}><strong>{t('Historie fleet evidence', 'Fleet evidence history')}</strong><div style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 4 }}>{t('Neměnné deployment snapshoty uchované jako CI artefakty. Sloupce nikdy neodvozují chybějící běhy.', 'Immutable deployment snapshots retained as CI artifacts. Bars never infer missing runs.')}</div></div>
    {report.history.length < 2 && <div style={{ color: '#d97706', fontSize: 12, marginBottom: 12 }}><TriangleAlert size={13} style={{ verticalAlign: 'text-bottom', marginRight: 5 }} />The first snapshot is present; a trend appears after the next admin deployment.</div>}
    <div style={{ display: 'flex', alignItems: 'end', gap: 8, minHeight: 190, overflowX: 'auto', paddingTop: 12 }}>
      {report.history.map(point => <div key={point.collectedAt} title={`${new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(point.collectedAt))} · ${point.componentsWithExecutionEvidence}/${point.components} evidenced · ${point.failingEvidence} failing`} style={{ minWidth: 38, flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'end', gap: 3, height: 170 }}>
        <div style={{ height: `${Math.max(2, point.failingEvidence / max * 150)}px`, background: '#dc2626', borderRadius: '4px 4px 0 0' }} />
        <div style={{ height: `${Math.max(2, point.missingEvidence / max * 150)}px`, background: '#d97706' }} />
        <div style={{ height: `${Math.max(2, point.componentsWithExecutionEvidence / max * 150)}px`, background: '#16a34a', borderRadius: '0 0 4px 4px' }} />
        <span style={{ fontSize: 9, color: 'var(--text-tertiary)', textAlign: 'center' }}>{new Intl.DateTimeFormat('en-GB', { month: 'short', day: 'numeric' }).format(new Date(point.collectedAt))}</span>
      </div>)}
    </div>
    <div style={{ display: 'flex', gap: 14, marginTop: 12, fontSize: 11, color: 'var(--text-secondary)' }}><span style={{ color: '#16a34a' }}>● evidenced</span><span style={{ color: '#d97706' }}>● missing</span><span style={{ color: '#dc2626' }}>● failing</span></div>
  </div><div style={{ border: '1px solid var(--border)', borderRadius: 10, overflowX: 'auto' }}>
    <div style={{ padding: '16px 18px 8px' }}><strong>{t('Neměnné pokusy služeb', 'Immutable service attempts')}</strong><div style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 4 }}>{t('Nejnovější verzované CI obálky; opakované běhy zůstávají samostatnými pokusy.', 'Latest versioned CI envelopes; reruns remain separate attempts.')}</div></div>
    <table style={tableStyle}><thead><tr><th style={thStyle}>Component</th><th style={thStyle}>Run / attempt</th><th style={thStyle}>Commit</th><th style={thStyle}>{t('Stavy evidence', 'Evidence states')}</th><th style={thStyle}>Runtime</th><th style={thStyle}>Observed</th></tr></thead>
      <tbody>{report.runHistory.slice(0, 100).map(item => <tr key={`${item.component}-${item.run.id}-${item.run.attempt}`}><td style={{ ...tdStyle, fontWeight: 650 }}>{item.component}</td><td style={tdStyle}>{item.run.id} / {item.run.attempt}</td><td style={tdStyle}>{item.run.commit.slice(0, 8)}</td><td style={tdStyle}>{Object.entries(item.states).map(([kind, state]) => <span key={kind} style={{ marginRight: 9 }}>{kind}: <StateBadge state={state} /></span>)}</td><td style={tdStyle}>{item.infrastructureStarted} start · {item.infrastructureStopped} stop</td><td style={tdStyle}>{new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(item.run.observedAt))}</td></tr>)}</tbody>
    </table>
    {report.runHistory.length === 0 && <div style={{ padding: 18, color: 'var(--text-tertiary)', fontSize: 12 }}>{t('Zatím nejsou přibaleny žádné uchované service-run obálky.', 'No retained service-run envelopes are bundled yet.')}</div>}
  </div></div>
}

function RuntimeInfrastructure({ report }: { report: TestIntelligenceReport }) {
  const { t } = useLanguage()
  const rows = report.components.filter(component => component.testInfrastructure.declared.length > 0 || component.testInfrastructure.observed.length > 0)
  return <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}><table style={tableStyle}>
    <thead><tr><th style={thStyle}>Component</th><th style={thStyle}>{t('Deklarovaná topologie', 'Declared topology')}</th><th style={thStyle}>{t('Důkaz runtime', 'Runtime proof')}</th><th style={thStyle}>Lifecycle</th><th style={thStyle}>Observed</th></tr></thead>
    <tbody>{rows.map(row => {
      const started = row.testInfrastructure.observed.filter(item => item.lifecycle === 'started')
      const stopped = row.testInfrastructure.observed.filter(item => item.lifecycle === 'stopped')
      const state: EvidenceState = started.length === 0 ? 'unknown' : stopped.length < started.length ? 'failed' : 'passed'
      const latest = row.testInfrastructure.observed.at(-1)?.observedAt
      return <tr key={row.component}><td style={{ ...tdStyle, fontWeight: 650 }}>{row.component}</td><td style={tdStyle}>{row.testInfrastructure.declared.join(' · ') || 'none'}</td><td style={tdStyle}><StateBadge state={state} /></td><td style={tdStyle}>{started.length} started · {stopped.length} stopped</td><td style={tdStyle}>{latest ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(latest)) : 'not emitted by this run'}</td></tr>
    })}</tbody>
  </table></div>
}

function Coverage({ report }: { report: TestIntelligenceReport }) {
  return <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}><table style={tableStyle}>
    <thead><tr><th style={thStyle}>Component</th><th style={thStyle}>State</th><th style={thStyle}>Lines</th><th style={thStyle}>Branches</th><th style={thStyle}>Observed</th><th style={thStyle}>Source</th></tr></thead>
    <tbody>{report.components.map(row => <tr key={row.component}><td style={{ ...tdStyle, fontWeight: 650 }}>{row.component}</td><td style={tdStyle}><StateBadge state={row.coverage.state} /></td>
      <td style={tdStyle}>{row.coverage.lines.percentage === null ? '—' : `${row.coverage.lines.percentage}%`}</td><td style={tdStyle}>{row.coverage.branches.percentage === null ? '—' : `${row.coverage.branches.percentage}%`}</td>
      <td style={tdStyle}>{row.coverage.observedAt ? formatTimestamp(row.coverage.observedAt) : '—'}</td><td style={tdStyle}>{row.coverage.source ?? '—'}</td></tr>)}</tbody>
  </table></div>
}

function Contracts({ report }: { report: TestIntelligenceReport }) {
  return <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}><table style={tableStyle}>
    <thead><tr><th style={thStyle}>Consumer</th><th style={thStyle}>Provider</th><th style={thStyle}>State</th><th style={thStyle}>Interactions</th><th style={thStyle}>Pact</th><th style={thStyle}>Verified</th></tr></thead>
    <tbody>{report.contracts.map(row => <tr key={row.pactFile}><td style={tdStyle}>{row.consumer}</td><td style={tdStyle}>{row.provider}</td><td style={tdStyle}><StateBadge state={row.state} /></td><td style={tdStyle}>{row.interactions}</td><td style={tdStyle}>{row.pactFile}</td><td style={tdStyle}>{row.observedAt ? formatTimestamp(row.observedAt) : '—'}</td></tr>)}</tbody>
  </table></div>
}

function Mutations({ report }: { report: TestIntelligenceReport }) {
  return <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 12 }}>{report.mutations.map(row => <div key={row.component} style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 16, background: 'var(--surface-1)' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}><strong>{row.component}</strong><StateBadge state={row.state} /></div>
    <div style={{ fontSize: 28, fontWeight: 750, margin: '12px 0' }}>{row.score ?? '—'}%</div>
    <div style={{ color: 'var(--text-secondary)', fontSize: 12 }}>{row.killed} killed · {row.survived} survived · {row.noCoverage} no coverage</div>
    {row.run && <div style={{ color: 'var(--text-tertiary)', fontSize: 11, marginTop: 7 }}>run {row.run.id} · {row.run.commit.slice(0, 8)}</div>}
  </div>)}</div>
}

function Performance({ report }: { report: TestIntelligenceReport }) {
  return <div style={{ display: 'grid', gap: 10 }}>{report.performance.map(row => <div key={row.id} style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 16, display: 'flex', justifyContent: 'space-between', gap: 16 }}>
    <div><strong>{row.id}</strong><div style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 5 }}>{row.source} · {row.thresholds} threshold group(s)</div>{row.run && <div style={{ color: 'var(--text-tertiary)', fontSize: 11, marginTop: 5 }}>run {row.run.id} · {row.run.commit.slice(0, 8)} · {row.run.branch}</div>}{row.detail && <div style={{ color: 'var(--text-tertiary)', fontSize: 11, marginTop: 5 }}>{row.detail}</div>}</div><StateBadge state={row.state} />
  </div>)}</div>
}

function Synthetics({ report }: { report: TestIntelligenceReport }) {
  const { t } = useLanguage()
  return <div style={{ display: 'grid', gap: 12 }}>{report.syntheticJourneys.map(row => <div key={row.id} style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 18, background: 'var(--surface-1)' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}><div><strong>{row.title}</strong><span style={{ marginLeft: 8, color: 'var(--text-tertiary)', fontSize: 11 }}>{row.id}</span></div><StateBadge state={row.state} /></div>
    <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginTop: 10, fontSize: 12, color: 'var(--text-secondary)' }}><span>Status: {row.status}</span><span>Severity: {row.severity}</span><span>Schedule: {row.schedule ?? 'not scheduled'}</span><span>Environment: {row.environment ?? '—'}</span></div>
    {row.live && <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 8, marginTop: 12, padding: 12, borderRadius: 8, background: 'var(--surface-2)', fontSize: 11 }}>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Naposledy naplánováno', 'Last scheduled')}</span><br /><strong>{row.live.lastScheduledAt ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(row.live.lastScheduledAt)) : 'never observed'}</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Poslední úspěch', 'Last success')}</span><br /><strong>{row.live.lastSuccessfulAt ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(row.live.lastSuccessfulAt)) : 'never observed'}</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>Failures / 30m</span><br /><strong>{row.live.failuresLast30m ?? 'unavailable'}</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Čerstvost evidence', 'Evidence freshness')}</span><br /><strong>{row.live.freshnessSeconds === null ? 'unknown' : `${Math.round(row.live.freshnessSeconds / 60)} min`}</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Poslední Kubernetes běhy', 'Recent Kubernetes runs')}</span><br /><strong>{row.live.recentRuns.length || 'none retained'}</strong></div>
    </div>}
    {row.falsifies && <p style={{ fontSize: 12, color: 'var(--text-secondary)', margin: '10px 0 0' }}><strong>Falsification:</strong> {row.falsifies}</p>}
    {row.blocker && <p style={{ fontSize: 12, color: '#7c3aed', margin: '10px 0 0' }}><strong>Blocker:</strong> {row.blocker}</p>}
  </div>)}</div>
}

export default function TestIntelligencePage() {
  const { language, t } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [tab, setTab] = useState<Tab>('posture')
  const [report, setReport] = useState<TestIntelligenceReport | null>(null)
  const [loading, setLoading] = useState(true)
  const testLoading = loading
  const qualityLoading = false

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const response = await fetch('/api/test-intelligence', { cache: 'no-store' })
      setReport(await response.json() as TestIntelligenceReport)
    } catch { setReport(null) } finally { setLoading(false) }
  }, [])
  useEffect(() => { void load() }, [load])

  const tabs: { id: Tab; label: string; icon: React.ReactNode }[] = [
    { id: 'posture', label: t('Přehled', 'Posture'), icon: <Activity size={13} /> },
    { id: 'tests', label: t('Testy a flaky', 'Tests & flaky'), icon: <FlaskConical size={13} /> },
    { id: 'history', label: t('Historie', 'History'), icon: <BarChart3 size={13} /> },
    { id: 'execution', label: t('Běhy', 'Execution'), icon: <FlaskConical size={13} /> },
    { id: 'runtime', label: t('Testovací runtime', 'Test runtime'), icon: <Activity size={13} /> },
    { id: 'coverage', label: t('Pokrytí kódu', 'Code coverage'), icon: <BarChart3 size={13} /> },
    { id: 'contracts', label: t('Kontrakty', 'Contracts'), icon: <ShieldCheck size={13} /> },
    { id: 'mutation', label: t('Mutace', 'Mutation'), icon: <Dna size={13} /> },
    { id: 'performance', label: t('Výkon', 'Performance'), icon: <Gauge size={13} /> },
    { id: 'synthetic', label: t('Syntetika', 'Synthetics'), icon: <Timer size={13} /> },
  ]

  return <div style={{ padding: '28px 32px', maxWidth: 1600, animation: 'fadeIn 0.2s ease-out' }}>
    <PageHeader
      breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep" aria-hidden="true">/</span><span>{t('Systém', 'System')}</span><span className="breadcrumb-sep" aria-hidden="true">/</span><span className="breadcrumb-current">{t('Test Intelligence', 'Test Intelligence')}</span></div>}
      icon={<FlaskConical size={19} aria-hidden="true" style={{ color: 'var(--accent)' }} />}
      title={t('Test Intelligence', 'Test Intelligence')}
      subtitle={t('Jednotný pohled na běhy, pokrytí kódu, kontrakty, mutace, výkon a sandboxové syntetické scénáře.', 'One evidence view for execution, code coverage, contracts, mutation, performance, and sandbox synthetic journeys.')}
      actions={<button type="button" onClick={load} disabled={testLoading || qualityLoading} aria-busy={testLoading || qualityLoading} aria-label={t('Obnovit systémové testy', 'Refresh system tests')} className="btn btn-secondary btn-sm"><RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />{t('Obnovit', 'Refresh')}</button>}
    />
    <TestIntelligenceFlow report={report} />
    {report?.warnings.length ? <div style={{ marginBottom: 16, border: '1px solid #d97706', borderRadius: 8, padding: 12, color: '#d97706', fontSize: 12 }}><TriangleAlert size={14} style={{ verticalAlign: 'text-bottom', marginRight: 6 }} />{report.warnings.join(' · ')}</div> : null}
    <div role="group" aria-label={t('Přepínač pohledů kvality kódu', 'Code quality view')} style={{ display: 'flex', gap: 2, overflowX: 'auto', borderBottom: '1px solid var(--border)', marginBottom: 20 }}>{tabs.map(tabDef => <button key={tabDef.id} type="button"
            aria-pressed={tab === tabDef.id} onClick={() => setTab(tabDef.id)} style={{ display: 'flex', gap: 6, alignItems: 'center', padding: '9px 13px', whiteSpace: 'nowrap', border: 'none', borderBottom: tab === tabDef.id ? '2px solid var(--accent)' : '2px solid transparent', background: 'none', color: tab === tabDef.id ? 'var(--accent)' : 'var(--text-secondary)', cursor: 'pointer', fontWeight: tab === tabDef.id ? 650 : 450 }}><span aria-hidden="true">{tabDef.icon}</span>{tabDef.label}</button>)}</div>
    {loading && !report ? <div className="skeleton" style={{ height: 260 }} /> : report ? <>
      {tab === 'posture' && <Posture report={report} />}{tab === 'tests' && <TestCases report={report} />}{tab === 'history' && <History report={report} />}{tab === 'execution' && <Execution report={report} />}{tab === 'runtime' && <RuntimeInfrastructure report={report} />}{tab === 'coverage' && <Coverage report={report} />}
      {tab === 'contracts' && <Contracts report={report} />}{tab === 'mutation' && <Mutations report={report} />}{tab === 'performance' && <Performance report={report} />}{tab === 'synthetic' && <Synthetics report={report} />}
    </> : <div style={{ padding: 24, color: 'var(--text-secondary)' }}>{t('Report není dostupný.', 'Report is unavailable.')}</div>}
    {report && <TestAgentPanel />}
    {report && <div style={{ marginTop: 18, color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Schéma', 'Schema')} v{report.schemaVersion} · {t('sesbíráno', 'collected')} {new Intl.DateTimeFormat(dateLocale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(report.collectedAt))} · {t('absence se nikdy nevykresluje jako nula', 'absence is never rendered as zero')}</div>}
  </div>
}
