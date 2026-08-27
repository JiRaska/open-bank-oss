// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Activity, BarChart3, CheckCircle2, CircleHelp, Dna, FlaskConical,
  Gauge, RefreshCw, ShieldCheck, Timer, TriangleAlert, XCircle,
} from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { aggregateEvidenceState } from '@/lib/test-intelligence-state'
import { filterTestCases, type TestTriageFilter } from '@/lib/test-intelligence-triage'
import type {
  ComponentTestPosture, EvidenceKind, EvidenceState, TestIntelligenceReport,
} from '@/lib/types/test-intelligence'
import { TestIntelligenceFlow } from '@/components/testing/TestIntelligenceFlow'
import { TestAgentPanel } from '@/components/testing/TestAgentPanel'
import { PageHeader } from '@/components/ui/PageHeader'

type Tab = 'posture' | 'tests' | 'history' | 'execution' | 'runtime' | 'coverage' | 'contracts' | 'mutation' | 'performance' | 'synthetic' | 'clients'

const STATE_COLOR: Record<EvidenceState, string> = {
  passed: '#16a34a', failed: '#dc2626', skipped: '#d97706', 'not-run': '#64748b',
  stale: '#d97706', blocked: '#7c3aed', unknown: '#64748b',
}

const KINDS: EvidenceKind[] = ['unit', 'integration', 'contract', 'e2e', 'trace', 'mutation', 'simulation', 'performance', 'synthetic']

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

/**
 * A deliberately unweighted operator view.  It makes the four assurance
 * surfaces navigable without turning absent evidence into a misleading score.
 */
function AssuranceBoard({ report, selectTab }: { report: TestIntelligenceReport; selectTab: (tab: Tab) => void }) {
  const { t } = useLanguage()
  const syntheticActive = report.syntheticJourneys.filter(item => item.status === 'active')
  const syntheticState = aggregateEvidenceState(syntheticActive.map(item => item.state))
  const runtimeRows = report.components.filter(component => component.testInfrastructure.declared.length > 0)
  const runtimeState: EvidenceState = runtimeRows.some(component => {
    const observed = component.testInfrastructure.observed
    return observed.filter(item => item.lifecycle === 'stopped').length < observed.filter(item => item.lifecycle === 'started').length
  }) ? 'failed' : runtimeRows.some(component => component.testInfrastructure.observed.length === 0) ? 'unknown' : 'passed'
  const clientEvidence = report.clientExperiences ?? []
  const clientState = aggregateEvidenceState(
    clientEvidence.flatMap(client => [...client.evidence.map(item => item.state), client.rum.state]),
    'not-run',
  )
  const ciState: EvidenceState = report.totals.failingEvidence > 0 ? 'failed'
    : (report.totals.unresolvedEvidence ?? report.totals.unknownEvidence ?? 0) > 0 ? 'unknown'
      : report.totals.missingEvidence > 0 || report.totals.staleEvidence > 0 ? 'stale' : 'passed'
  const cards: { tab: Tab; title: string; eyebrow: string; state: EvidenceState; detail: string }[] = [
    { tab: 'posture', title: t('CI důkazy', 'CI evidence'), eyebrow: t('deterministické gate', 'deterministic gates'), state: ciState, detail: t(`${report.totals.componentsWithExecutionEvidence}/${report.totals.components} komponent s důkazem běhu`, `${report.totals.componentsWithExecutionEvidence}/${report.totals.components} components with run evidence`) },
    { tab: 'runtime', title: t('Testcontainers runtime', 'Testcontainers runtime'), eyebrow: t('skutečná topologie', 'actual topology'), state: runtimeState, detail: t(`${runtimeRows.length} deklarovaných testovacích runtime`, `${runtimeRows.length} declared test runtimes`) },
    { tab: 'synthetic', title: t('Sandbox syntetiky', 'Sandbox synthetics'), eyebrow: t('pravidelná falsifikace', 'scheduled falsification'), state: syntheticState, detail: t(`${syntheticActive.length} aktivních cest · ${report.syntheticJourneys.filter(item => item.status === 'planned').length} plánovaných`, `${syntheticActive.length} active paths · ${report.syntheticJourneys.filter(item => item.status === 'planned').length} planned`) },
    { tab: 'clients', title: t('Client & RUM', 'Client & RUM'), eyebrow: t('E2E + produkční signál', 'E2E + production signal'), state: clientState, detail: t(`${clientEvidence.length} klientských zkušeností · consent-gated RUM`, `${clientEvidence.length} client experiences · consent-gated RUM`) },
  ]
  return <section aria-label={t('Mapa testovacího ujištění', 'Testing assurance map')} style={{ marginBottom: 18, border: '1px solid color-mix(in srgb, var(--accent) 26%, var(--border))', borderRadius: 14, padding: 18, background: 'linear-gradient(135deg, color-mix(in srgb, var(--accent) 7%, var(--surface-1)), var(--surface-1))' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'start', marginBottom: 14 }}>
      <div><div style={{ color: 'var(--accent)', fontSize: 11, fontWeight: 750, letterSpacing: '.08em', textTransform: 'uppercase' }}>{t('Living assurance map', 'Living assurance map')}</div><strong style={{ fontSize: 18 }}>{t('Od změny až k zákaznickému signálu', 'From change to customer signal')}</strong><div style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 4 }}>{t('Klikni na vrstvu pro její neměnný důkaz, historii a známé mezery.', 'Open a layer for its immutable evidence, history, and known gaps.')}</div></div>
      <div style={{ maxWidth: 310, color: 'var(--text-secondary)', fontSize: 11, padding: '8px 10px', borderRadius: 8, background: 'var(--surface-2)' }}><strong>{t('AI guardrail', 'AI guardrail')}</strong><br />{t('Agenti smějí vysvětlit a navrhnout další krok. Nezvyšují verdikt, nemažou důkaz ani neschvalují release.', 'Agents may explain and propose a next step. They do not raise a verdict, delete evidence, or approve a release.')}</div>
    </div>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(205px, 1fr))', gap: 10 }}>{cards.map((card, index) => <button key={card.tab} type="button" onClick={() => selectTab(card.tab)} style={{ textAlign: 'left', cursor: 'pointer', border: `1px solid color-mix(in srgb, ${STATE_COLOR[card.state]} 42%, var(--border))`, background: 'var(--surface-1)', borderRadius: 11, padding: 14, animation: `fadeIn ${180 + index * 80}ms ease-out both` }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, alignItems: 'start' }}><div><div style={{ color: 'var(--text-tertiary)', fontSize: 10, textTransform: 'uppercase', letterSpacing: '.06em' }}>{card.eyebrow}</div><strong style={{ display: 'block', marginTop: 3 }}>{card.title}</strong></div><StateBadge state={card.state} /></div>
      <div style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 10 }}>{card.detail}</div>
      <div style={{ color: 'var(--accent)', fontSize: 11, fontWeight: 650, marginTop: 11 }}>{t('Otevřít důkaz →', 'Open evidence →')}</div>
    </button>)}</div>
  </section>
}

/**
 * A cross-layer attention queue. It is derived from the same immutable report rather than
 * maintained as another dashboard list: a planned schedule, generic mobile arrival or AI
 * explanation must not hide the actual missing proof on a different tab.
 */
function EvidenceGapQueue({ report, selectTab }: { report: TestIntelligenceReport; selectTab: (tab: Tab) => void }) {
  const { t } = useLanguage()
  const gaps: Array<{ id: string; tab: Tab; title: string; detail: string; state: EvidenceState }> = [
    ...report.performance.filter(row => row.state !== 'passed' || row.plan?.blocker).map(row => ({
      id: `performance-${row.id}`, tab: 'performance' as const, state: row.state,
      title: t(`Výkon: ${row.id}`, `Performance: ${row.id}`),
      detail: row.plan?.blocker ?? row.detail ?? t('Chybí aktuální performance evidence.', 'Current performance evidence is missing.'),
    })),
    ...report.syntheticJourneys.filter(row => row.status === 'planned' || row.state !== 'passed').map(row => ({
      id: `synthetic-${row.id}`, tab: 'synthetic' as const, state: row.state,
      title: t(`Syntetika: ${row.title}`, `Synthetic: ${row.title}`),
      detail: row.blocker ?? row.falsifies,
    })),
    ...(report.clientExperiences ?? []).flatMap(client => client.rum.platforms?.filter(platform => platform.runtime !== 'passed').map(platform => ({
      id: `rum-${client.id}-${platform.platform}`, tab: 'clients' as const, state: platform.runtime,
      title: t(`Mobilní RUM: ${platform.platform}`, `Mobile RUM: ${platform.platform}`), detail: platform.detail,
    })) ?? []),
  ]
  if (gaps.length === 0) return null
  return <section aria-label={t('Fronta mezer důkazů', 'Evidence gap queue')} style={{ marginBottom: 18, border: '1px solid color-mix(in srgb, #d97706 40%, var(--border))', borderRadius: 14, padding: 16, background: 'linear-gradient(135deg, color-mix(in srgb, #d97706 7%, var(--surface-1)), var(--surface-1))' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'baseline', marginBottom: 10 }}><div><strong>{t('Fronta skutečných mezer důkazů', 'Real evidence-gap queue')}</strong><div style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 4 }}>{t('Odvozeno z aktuálního reportu — ne backlog podle dojmu. Otevři položku pro zdroj, plán a hranici tvrzení.', 'Derived from the current report — not an impression-based backlog. Open an item for its source, plan and claim boundary.')}</div></div><span style={{ color: '#d97706', fontWeight: 750 }}>{gaps.length}</span></div>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 9 }}>{gaps.map(gap => <button key={gap.id} type="button" onClick={() => selectTab(gap.tab)} style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 8, padding: 11, textAlign: 'left', cursor: 'pointer', border: '1px solid var(--border)', borderRadius: 9, background: 'var(--surface-1)' }}><span><strong style={{ fontSize: 12 }}>{gap.title}</strong><span style={{ display: 'block', color: 'var(--text-secondary)', fontSize: 11, lineHeight: 1.35, marginTop: 4 }}>{gap.detail}</span></span><StateBadge state={gap.state} /></button>)}</div>
  </section>
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
        <Stat label="Unresolved evidence" value={report.totals.unresolvedEvidence ?? report.totals.unknownEvidence ?? 0} tone={(report.totals.unresolvedEvidence ?? report.totals.unknownEvidence ?? 0) ? '#64748b' : '#16a34a'} />
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
    .filter(item => ['unit', 'integration', 'e2e', 'trace', 'simulation'].includes(item.kind))
    .map(item => ({ component: component.component, ...item })))
  return (
    <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}>
      <table style={tableStyle}><thead><tr>{['Component', 'Kind', 'State', 'Discovered', 'Executed', 'Passed', 'Failed', 'Skipped', 'Evidence', 'Diagnostics', 'Observed'].map(label => <th key={label} style={thStyle}>{label}</th>)}</tr></thead>
        <tbody>{rows.map((row, index) => <tr key={`${row.component}-${row.kind}-${index}`}>
          <td style={{ ...tdStyle, fontWeight: 650 }}>{row.component}</td><td style={tdStyle}>{row.kind}</td><td style={tdStyle}><StateBadge state={row.state} /></td>
          <td style={tdStyle}>{row.counts?.discovered ?? '—'}</td><td style={tdStyle}>{row.counts?.executed ?? '—'}</td><td style={tdStyle}>{row.counts?.passed ?? '—'}</td>
          <td style={tdStyle}>{row.counts?.failed ?? '—'}</td><td style={tdStyle}>{row.counts?.skipped ?? '—'}</td>
          <td style={tdStyle}>{row.run?.url ? <a href={row.run.url} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)', fontWeight: 650 }}>{row.source}</a> : row.source}<div style={{ color: 'var(--text-tertiary)', fontSize: 10, marginTop: 3 }}>{row.detail ?? ''}</div></td>
          <td style={tdStyle}>{row.diagnostics?.map(item => <div key={item.name}><a href={item.url} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)', fontWeight: 650 }}>{item.kind}</a><div style={{ color: 'var(--text-tertiary)', fontSize: 10, marginTop: 3 }}>GitHub-authenticated · {item.retentionDays}d · may contain sensitive browser data</div></div>) ?? '—'}</td>
          <td style={tdStyle}>{row.observedAt ? formatTimestamp(row.observedAt) : '—'}</td>
        </tr>)}</tbody>
      </table>
    </div>
  )
}

function TestCases({ report }: { report: TestIntelligenceReport }) {
  const { t } = useLanguage()
  const [filter, setFilter] = useState<TestTriageFilter>('all')
  const [query, setQuery] = useState('')
  const flaky = report.testCases.filter(item => item.state === 'flaky')
  const failing = report.testCases.filter(item => item.state === 'failing')
  const wasted = report.testCases.reduce((sum, item) => sum + item.wastedDurationMs, 0)
  const impact = report.testImpact
  const visibleTests = useMemo(() => filterTestCases(report.testCases, filter, query), [report.testCases, filter, query])
  const filters: Array<{ id: TestTriageFilter; label: string }> = [
    { id: 'all', label: t('Vše', 'All') },
    { id: 'failing', label: t('Selhává', 'Failing') },
    { id: 'flaky', label: t('Flaky', 'Flaky') },
    { id: 'unstable', label: t('Nestabilní commit', 'Unstable commit') },
    { id: 'skipped', label: t('Přeskočeno', 'Skipped') },
  ]
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
    {impact && <div aria-label={t('Stav mapování test impactu', 'Test impact mapping state')} style={{ padding: 12, border: '1px solid color-mix(in srgb, #64748b 42%, var(--border))', borderRadius: 9, color: 'var(--text-secondary)', fontSize: 12, background: 'var(--surface-2)' }}>
      <strong>{t('Test impact selection: shadow only', 'Test impact selection: shadow only')}</strong><span style={{ marginLeft: 8 }}><StateBadge state="unknown" /></span><div style={{ marginTop: 5 }}>{impact.detail}</div><div style={{ marginTop: 5, color: 'var(--text-tertiary)' }}>{t('Cesta k testu není mapa závislostí do produkce. AI ji nesmí domýšlet ani vybírat povinné gate.', 'A test source path is not a production dependency map. AI must not infer it or select a required gate.')}</div>
    </div>}
    <div style={{ display: 'flex', gap: 10, justifyContent: 'space-between', flexWrap: 'wrap', alignItems: 'center' }}>
      <div role="group" aria-label={t('Filtr testovací triage', 'Test triage filter')} style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
        {filters.map(item => <button key={item.id} type="button" aria-pressed={filter === item.id} onClick={() => setFilter(item.id)} style={{ cursor: 'pointer', padding: '5px 9px', borderRadius: 999, border: `1px solid ${filter === item.id ? 'var(--accent)' : 'var(--border)'}`, color: filter === item.id ? 'var(--accent)' : 'var(--text-secondary)', background: filter === item.id ? 'color-mix(in srgb, var(--accent) 9%, var(--surface-1))' : 'var(--surface-1)', fontSize: 11, fontWeight: filter === item.id ? 700 : 500 }}>{item.label}</button>)}
      </div>
      <label style={{ display: 'grid', gap: 3, minWidth: 230, fontSize: 11, color: 'var(--text-secondary)' }}>
        {t('Hledat v testech a provenance', 'Search tests and provenance')}
        <input value={query} onChange={event => setQuery(event.target.value)} placeholder={t('název, komponenta, owner, fingerprint…', 'name, component, owner, fingerprint…')} style={{ border: '1px solid var(--border)', borderRadius: 7, padding: '7px 9px', color: 'var(--text-primary)', background: 'var(--surface-1)' }} />
      </label>
    </div>
    <div aria-live="polite" style={{ color: 'var(--text-secondary)', fontSize: 12 }}>{t(`${visibleTests.length}/${report.testCases.length} testových definic odpovídá aktuální triage.`, `${visibleTests.length}/${report.testCases.length} test definitions match the active triage.`)}</div>
    <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}><table style={tableStyle}>
      <thead><tr>{['State', 'Test definition', 'Test source', 'Component', 'Kind', 'Owner', 'Runs', 'Failure rate', 'Avg duration', 'Failed runtime', 'Fingerprint'].map(label => <th key={label} style={thStyle}>{label}</th>)}</tr></thead>
      <tbody>{visibleTests.map(item => <tr key={item.fingerprint}>
        <td style={tdStyle}><StateBadge state={item.state === 'stable' ? 'passed' : item.state === 'failing' ? 'failed' : item.state === 'skipped' ? 'skipped' : 'stale'} /></td>
        <td style={{ ...tdStyle, minWidth: 230 }}><strong>{item.name}</strong><div style={{ color: 'var(--text-tertiary)', fontSize: 10, marginTop: 3 }}>{item.classname}</div>{item.sameCommitTransitions > 0 && <div style={{ color: '#d97706', fontSize: 10, marginTop: 3 }}>{item.sameCommitTransitions} same-commit pass/fail transition(s)</div>}</td>
        <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: 10, maxWidth: 260, overflowWrap: 'anywhere' }}>{item.testDefinitionPath ?? 'not reported'}</td>
        <td style={tdStyle}>{item.component}</td><td style={tdStyle}>{item.kind}</td><td style={tdStyle}>{item.owner}</td><td style={tdStyle}>{item.observations}</td>
        <td style={tdStyle}>{item.failureRate === null ? '—' : `${item.failureRate}%`}</td><td style={tdStyle}>{item.averageDurationMs} ms</td><td style={tdStyle}>{item.wastedDurationMs} ms</td>
        <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: 10 }}>{item.fingerprint}</td>
      </tr>)}</tbody>
    </table>{report.testCases.length === 0 ? <div style={{ padding: 18, color: 'var(--text-tertiary)', fontSize: 12 }}>{t('Zatím nejsou uchovány žádné per-test obálky běhů. Verdikty sad zůstávají autoritativní.', 'No per-test run envelopes have been retained yet. Suite verdicts remain authoritative.')}</div> : visibleTests.length === 0 ? <div style={{ padding: 18, color: 'var(--text-tertiary)', fontSize: 12 }}>{t('Žádná testová definice neodpovídá vybrané triage. Filtr neznamená změnu CI verdiktu.', 'No test definition matches the selected triage. Filtering does not change the CI verdict.')}</div> : null}</div>
  </div>
}

function History({ report }: { report: TestIntelligenceReport }) {
  const { t } = useLanguage()
  const max = Math.max(1, ...report.history.map(point => point.components))
  return <div style={{ display: 'grid', gap: 18 }}><div style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 18, background: 'var(--surface-1)' }}>
    <div style={{ marginBottom: 16 }}><strong>{t('Historie fleet evidence', 'Fleet evidence history')}</strong><div style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 4 }}>{t('Neměnné deployment snapshoty uchované jako CI artefakty. Sloupce nikdy neodvozují chybějící běhy.', 'Immutable deployment snapshots retained as CI artifacts. Bars never infer missing runs.')}</div></div>
    {report.history.length < 2 && <div style={{ color: '#d97706', fontSize: 12, marginBottom: 12 }}><TriangleAlert size={13} style={{ verticalAlign: 'text-bottom', marginRight: 5 }} />The first snapshot is present; a trend appears after the next admin deployment.</div>}
    <div style={{ display: 'flex', alignItems: 'end', gap: 8, minHeight: 190, overflowX: 'auto', paddingTop: 12 }}>
      {report.history.map(point => <div key={point.collectedAt} title={`${new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(point.collectedAt))} · ${point.componentsWithExecutionEvidence}/${point.components} evidenced · ${point.failingEvidence} failing · ${point.unresolvedEvidence ?? point.unknownEvidence ?? 0} unresolved`} style={{ minWidth: 38, flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'end', gap: 3, height: 170 }}>
        <div style={{ height: `${Math.max(2, point.failingEvidence / max * 150)}px`, background: '#dc2626', borderRadius: '4px 4px 0 0' }} />
        <div style={{ height: `${Math.max(2, (point.unresolvedEvidence ?? point.unknownEvidence ?? 0) / max * 150)}px`, background: '#64748b' }} />
        <div style={{ height: `${Math.max(2, point.missingEvidence / max * 150)}px`, background: '#d97706' }} />
        <div style={{ height: `${Math.max(2, point.componentsWithExecutionEvidence / max * 150)}px`, background: '#16a34a', borderRadius: '0 0 4px 4px' }} />
        <span style={{ fontSize: 9, color: 'var(--text-tertiary)', textAlign: 'center' }}>{new Intl.DateTimeFormat('en-GB', { month: 'short', day: 'numeric' }).format(new Date(point.collectedAt))}</span>
      </div>)}
    </div>
    <div style={{ display: 'flex', gap: 14, marginTop: 12, fontSize: 11, color: 'var(--text-secondary)' }}><span style={{ color: '#16a34a' }}>● evidenced</span><span style={{ color: '#64748b' }}>● unresolved</span><span style={{ color: '#d97706' }}>● missing</span><span style={{ color: '#dc2626' }}>● failing</span></div>
  </div><div style={{ border: '1px solid var(--border)', borderRadius: 10, overflowX: 'auto' }}>
    <div style={{ padding: '16px 18px 8px' }}><strong>{t('Neměnné pokusy služeb', 'Immutable service attempts')}</strong><div style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 4 }}>{t('Nejnovější verzované CI obálky; opakované běhy zůstávají samostatnými pokusy.', 'Latest versioned CI envelopes; reruns remain separate attempts.')}</div></div>
    <table style={tableStyle}><thead><tr><th style={thStyle}>Component</th><th style={thStyle}>Run / attempt</th><th style={thStyle}>Commit</th><th style={thStyle}>{t('Stavy evidence', 'Evidence states')}</th><th style={thStyle}>Runtime</th><th style={thStyle}>Observed</th></tr></thead>
      <tbody>{report.runHistory.slice(0, 100).map(item => <tr key={`${item.component}-${item.run.id}-${item.run.attempt}`}><td style={{ ...tdStyle, fontWeight: 650 }}>{item.component}</td><td style={tdStyle}><a href={item.run.url} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)', fontWeight: 650 }}>{item.run.id} / {item.run.attempt}</a><div style={{ color: 'var(--text-tertiary)', fontSize: 10, marginTop: 3 }}>{item.run.workflow} · {item.run.branch}</div></td><td style={tdStyle}>{item.run.commit.slice(0, 8)}</td><td style={tdStyle}>{Object.entries(item.states).map(([kind, state]) => <span key={kind} style={{ marginRight: 9 }}>{kind}: <StateBadge state={state} /></span>)}</td><td style={tdStyle}>{item.infrastructureStarted} start · {item.infrastructureStopped} stop</td><td style={tdStyle}>{new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(item.run.observedAt))}</td></tr>)}</tbody>
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
      const completedLifecycles = Math.min(started.length, stopped.length)
      const unmatchedStarts = started.length - stopped.length
      const impossibleStops = stopped.length > started.length
      const state: EvidenceState = started.length === 0 || impossibleStops ? 'unknown' : unmatchedStarts > 0 ? 'failed' : 'passed'
      const latest = row.testInfrastructure.observed.at(-1)?.observedAt
      return <tr key={row.component}><td style={{ ...tdStyle, fontWeight: 650 }}>{row.component}</td><td style={tdStyle}>{row.testInfrastructure.declared.join(' · ') || 'none'}</td><td style={tdStyle}><StateBadge state={state} /></td><td style={tdStyle}><strong>{completedLifecycles} {t('dokončených izolovaných cyklů', 'completed isolated cycles')}</strong><div style={{ color: 'var(--text-tertiary)', fontSize: 10, marginTop: 3 }}>{started.length} started · {stopped.length} stopped</div>{unmatchedStarts > 0 && <div role="status" style={{ color: '#dc2626', fontSize: 10, marginTop: 3 }}>{t(`${unmatchedStarts} unmatched start`, `${unmatchedStarts} unmatched start${unmatchedStarts === 1 ? '' : 's'}`)}</div>}{impossibleStops && <div role="status" style={{ color: '#64748b', fontSize: 10, marginTop: 3 }}>{t('Nekonzistentní lifecycle evidence: více stop než start.', 'Inconsistent lifecycle evidence: more stops than starts.')}</div>}</td><td style={tdStyle}>{latest ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(latest)) : 'not emitted by this run'}</td></tr>
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
  const { t } = useLanguage()
  const unknown = report.contracts.filter(row => row.state === 'unknown')
  const contractSuites = report.runHistory.flatMap(run => Object.entries(run.states)
    .filter(([kind]) => kind === 'contract')
    .map(([, state]) => state))
  const suiteSummary = contractSuites.length === 0
    ? t('V uchované historii není přibalen žádný contract suite verdict.', 'No contract-suite verdict is bundled in retained run history.')
    : t(`${contractSuites.filter(state => state === 'passed').length}/${contractSuites.length} uchovaných CI contract suite verdiktů prošlo.`, `${contractSuites.filter(state => state === 'passed').length}/${contractSuites.length} retained CI contract-suite verdicts passed.`)
  return <div style={{ display: 'grid', gap: 12 }}>
    <div style={{ padding: 13, border: '1px solid color-mix(in srgb, #64748b 36%, var(--border))', borderRadius: 10, background: 'var(--surface-2)', color: 'var(--text-secondary)', fontSize: 12 }}>
      <strong style={{ color: 'var(--text-primary)' }}>{t('Dva nezaměnitelné druhy důkazu.', 'Two non-interchangeable evidence types.')}</strong>{' '}
      {t('Tabulka níže ukazuje per-Pact provider-verification verdikt z Pact Brokeru. Historie běhů ukazuje CI contract suite verdikt. Jeden není náhradou druhého a neznámý broker verdikt se nikdy nevydává za zelený.', 'The table below shows the per-Pact provider-verification verdict from the Pact Broker. Run history shows the CI contract-suite verdict. Neither substitutes for the other, and an unavailable broker verdict is never presented as green.')}
      <div style={{ marginTop: 7 }}>{suiteSummary}</div>
      {unknown.length > 0 && <div style={{ marginTop: 7, color: '#64748b' }}>{t(`${unknown.length} Pactů má neznámý broker verdikt v tomto snapshotu; otevři detail řádku pro přesný důvod.`, `${unknown.length} Pacts have an unavailable broker verdict in this snapshot; open a row detail for the precise reason.`)}</div>}
    </div>
    <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 10 }}><table style={tableStyle}>
      <thead><tr><th style={thStyle}>Consumer</th><th style={thStyle}>Provider</th><th style={thStyle}>{t('Broker verdict', 'Broker verdict')}</th><th style={thStyle}>Interactions</th><th style={thStyle}>Pact</th><th style={thStyle}>Verified</th><th style={thStyle}>{t('Evidence basis', 'Evidence basis')}</th></tr></thead>
      <tbody>{report.contracts.map(row => <tr key={row.pactFile}><td style={tdStyle}>{row.consumer}</td><td style={tdStyle}>{row.provider}</td><td style={tdStyle}><StateBadge state={row.state} /></td><td style={tdStyle}>{row.interactions}</td><td style={tdStyle}>{row.pactFile}</td><td style={tdStyle}>{row.observedAt ? formatTimestamp(row.observedAt) : '—'}</td><td style={{ ...tdStyle, minWidth: 300, color: 'var(--text-secondary)', fontSize: 11 }}>{row.verificationDetail ?? t('Snapshot does not provide a verification explanation.', 'Snapshot neposkytuje vysvětlení ověření.')}</td></tr>)}</tbody>
    </table></div>
  </div>
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
  const { t } = useLanguage()
  const declaredComponents = new Set(report.performance.flatMap(row => row.component ? [row.component] : []))
  const executed = report.performance.filter(row => row.state === 'passed' || row.state === 'failed').length
  const undeclared = report.components.filter(component => !declaredComponents.has(component.component)).length
  return <div style={{ display: 'grid', gap: 10 }}>
    <section aria-label={t('Rozsah pokrytí výkonnostních testů', 'Performance-test coverage scope')} style={{ border: '1px solid color-mix(in srgb, var(--accent) 35%, var(--border))', borderRadius: 12, padding: 16, background: 'linear-gradient(135deg, color-mix(in srgb, var(--accent) 9%, var(--surface-1)), var(--surface-1))' }}>
      <strong>{t('Rozsah výkonnostních důkazů', 'Performance evidence scope')}</strong>
      <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', marginTop: 10, fontSize: 13 }}>
        <span><strong>{report.performance.length}</strong> {t('deklarovaných scénářů', 'declared scenarios')}</span>
        <span><strong>{executed}</strong> {t('s výsledkem běhu', 'with run evidence')}</span>
        <span><strong>{undeclared}</strong> {t('komponent bez deklarovaného scénáře', 'components without a declared scenario')}</span>
      </div>
      <p style={{ color: 'var(--text-secondary)', fontSize: 12, margin: '10px 0 0' }}>{t('Absence scénáře není zelený výsledek. Tento panel odděluje měřený rozsah od neprovedeného či dosud nedefinovaného výkonového pokrytí.', 'An absent scenario is not a green result. This panel separates measured scope from performance coverage that is not run or not yet declared.')}</p>
    </section>
    {report.performance.map(row => <div key={row.id} style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 16, display: 'flex', justifyContent: 'space-between', gap: 16 }}>
    <div><strong>{row.id}</strong><div style={{ color: 'var(--text-secondary)', fontSize: 12, marginTop: 5 }}>{row.source} · {row.thresholds} threshold group(s)</div>{row.plan && <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8, fontSize: 11 }}><span style={{ padding: '3px 7px', borderRadius: 999, background: 'var(--surface-2)' }}>{row.plan.executionMode}</span><span>{row.plan.targetSchedule ? `${t('Cílový plán', 'Target schedule')}: ${row.plan.targetSchedule}` : t('Bez automatického plánu', 'No automated schedule')}</span>{row.plan.baselineReport && <span>{t('Zdokumentovaný baseline', 'Documented baseline')}: {row.plan.baselineReport}</span>}</div>}{row.metrics && <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 9, fontSize: 12 }}><span><strong>p95</strong> {row.metrics.p95Ms === null ? '—' : `${Math.round(row.metrics.p95Ms)} ms`}</span><span><strong>{t('Chybovost', 'Error rate')}</strong> {row.metrics.errorRatePercent === null ? '—' : `${row.metrics.errorRatePercent}%`}</span><span><strong>{t('Kontroly', 'Checks')}</strong> {row.metrics.checkPassRatePercent === null ? '—' : `${row.metrics.checkPassRatePercent}%`}</span><span><strong>{t('Požadavky', 'Requests')}</strong> {row.metrics.requests === null ? '—' : row.metrics.requests}</span></div>}{row.run && <div style={{ color: 'var(--text-tertiary)', fontSize: 11, marginTop: 5 }}>run {row.run.id} · {row.run.commit.slice(0, 8)} · {row.run.branch}</div>}{row.detail && <div style={{ color: 'var(--text-tertiary)', fontSize: 11, marginTop: 5 }}>{row.detail}</div>}{row.plan?.safetyBoundary && <div style={{ color: 'var(--text-secondary)', fontSize: 11, marginTop: 5 }}><strong>{t('Bezpečnostní hranice', 'Safety boundary')}:</strong> {row.plan.safetyBoundary}</div>}{row.plan?.blocker && <div style={{ color: '#7c3aed', fontSize: 11, marginTop: 5 }}><strong>Blocker:</strong> {row.plan.blocker}</div>}</div><StateBadge state={row.state} />
    </div>)}
  </div>
}

function Synthetics({ report }: { report: TestIntelligenceReport }) {
  const { t } = useLanguage()
  const coverage = report.journeyCoverage
  return <div style={{ display: 'grid', gap: 12 }}>
    {coverage && <section aria-label={t('Pokrytí money-path syntetickými cestami', 'Money-path synthetic journey coverage')} style={{ padding: 18, borderRadius: 12, border: '1px solid color-mix(in srgb, #7c3aed 35%, var(--border))', background: 'linear-gradient(135deg, color-mix(in srgb, #7c3aed 8%, var(--surface-1)), var(--surface-1))' }}>
      <strong>{t('Skutečné customer-journey pokrytí', 'Actual customer-journey coverage')}</strong>
      <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap', marginTop: 10, fontSize: 13 }}><span><strong>{coverage.activelyCovered}/{coverage.moneyPathTotal}</strong> {t('money-path služeb aktivně sledováno', 'money-path services actively covered')}</span><span><strong>{coverage.explicitlyUnwatched}</strong> {t('explicitně evidováno jako nesledované', 'explicitly accounted as unwatched')}</span></div>
      <p style={{ margin: '9px 0 0', color: 'var(--text-secondary)', fontSize: 12 }}>{t('Plánovaná cesta se do pokrytí nepočítá. Čitatel se zvýší až po nasazení aktivního scénáře, který službu výslovně pokrývá.', 'A planned journey does not count as coverage. The numerator advances only after an active scenario explicitly covers the service.')}</p>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 11 }}>{coverage.services.map(service => {
        const stateLabel = service.state === 'covered' ? t('Sledováno', 'Covered') : t('Nesledováno', 'Unwatched')
        const detail = service.reason ?? service.journeys.join(', ')
        return <span key={service.component} title={detail} aria-label={`${service.component}: ${stateLabel}${detail ? `. ${detail}` : ''}`} style={{ padding: '4px 7px', borderRadius: 999, fontSize: 10, border: '1px solid var(--border)', color: service.state === 'covered' ? '#16a34a' : '#d97706', background: 'var(--surface-2)' }}>{service.component.replace(/^openbank-/, '').replace(/-service$/, '')} · <strong>{stateLabel}</strong></span>
      })}</div>
    </section>}
    {report.syntheticJourneys.map(row => <div key={row.id} style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 18, background: 'var(--surface-1)' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}><div><strong>{row.title}</strong><span style={{ marginLeft: 8, color: 'var(--text-tertiary)', fontSize: 11 }}>{row.id}</span></div><StateBadge state={row.state} /></div>
    <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginTop: 10, fontSize: 12, color: 'var(--text-secondary)' }}><span>Status: {row.status}</span><span>Severity: {row.severity}</span><span>{row.status === 'planned' ? 'Target schedule' : 'Schedule'}: {row.schedule ?? 'not scheduled'}</span><span>Environment: {row.environment ?? '—'}</span></div>
    {row.capability && <p style={{ fontSize: 12, color: 'var(--text-secondary)', margin: '10px 0 0' }}><strong>{t('Co tato cesta dokazuje', 'What this journey proves')}:</strong> {row.capability}</p>}
    {row.live && <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 8, marginTop: 12, padding: 12, borderRadius: 8, background: 'var(--surface-2)', fontSize: 11 }}>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Naposledy naplánováno', 'Last scheduled')}</span><br /><strong>{row.live.lastScheduledAt ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(row.live.lastScheduledAt)) : 'never observed'}</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Poslední úspěch', 'Last success')}</span><br /><strong>{row.live.lastSuccessfulAt ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(row.live.lastSuccessfulAt)) : 'never observed'}</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Selhání v okně', 'Failures in window')}</span><br /><strong>{row.live.failuresWithinWindow ?? 'unavailable'} / {Math.round(row.live.failureWindowSeconds / 60)} min</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Právě běží', 'Running now')}</span><br /><strong>{row.live.activeJobs ?? 'unavailable'}</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Čerstvost evidence', 'Evidence freshness')}</span><br /><strong>{row.live.freshnessSeconds === null ? 'unknown' : `${Math.round(row.live.freshnessSeconds / 60)} min`}</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Poslední Kubernetes běhy', 'Recent Kubernetes runs')}</span><br /><strong>{row.live.recentRuns.length || 'none retained'}</strong></div>
    </div>}
    {row.live?.performance && <div aria-label={t(`Výkonnostní důkazy ${row.title}`, `Performance evidence for ${row.title}`)} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 8, marginTop: 9, padding: 12, borderRadius: 8, border: '1px solid color-mix(in srgb, var(--accent) 28%, var(--border))', background: 'linear-gradient(135deg, color-mix(in srgb, var(--accent) 6%, var(--surface-2)), var(--surface-2))', fontSize: 11 }}>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Nejhorší k6 p95', 'Worst k6 p95')}</span><br /><strong>{row.live.performance.worstP95Ms === null ? t('nepozorováno', 'not observed') : `${Math.round(row.live.performance.worstP95Ms)} ms`}</strong></div>
      <div><span style={{ color: 'var(--text-tertiary)' }}>{t('Nejnižší k6 check rate', 'Lowest k6 check rate')}</span><br /><strong>{row.live.performance.worstCheckPassRatePercent === null ? t('nepozorováno', 'not observed') : `${row.live.performance.worstCheckPassRatePercent}%`}</strong></div>
      <div style={{ color: 'var(--text-secondary)', lineHeight: 1.35 }}>{t(`Prometheus · nejhorší publikovaná hodnota v posledních ${Math.round(row.live.performance.windowSeconds / 60)} minutách. Nedostupná metrika není zelený výsledek a nemění Kubernetes verdikt cesty.`, `Prometheus · worst published value over the last ${Math.round(row.live.performance.windowSeconds / 60)} minutes. An unavailable metric is not a green result and does not change the journey's Kubernetes verdict.`)}</div>
    </div>}
    {row.live?.recentRuns.length ? <div aria-label={t(`Poslední běhy ${row.title}`, `Recent runs for ${row.title}`)} style={{ display: 'flex', flexWrap: 'wrap', gap: 7, marginTop: 9 }}>{row.live.recentRuns.map(run => <span key={run.id} title={run.id} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '4px 7px', border: '1px solid var(--border)', borderRadius: 999, background: 'var(--surface-2)', fontSize: 10 }}><StateBadge state={run.state} /><span>{new Intl.DateTimeFormat('en-GB', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(run.observedAt))}</span></span>)}</div> : null}
    {row.ci && <p style={{ fontSize: 11, color: 'var(--text-tertiary)', margin: '10px 0 0' }}><strong>CI / post-deploy:</strong> <StateBadge state={row.ci.state} /> · {row.ci.detail} · <a href={row.ci.run.url} target="_blank" rel="noreferrer">run {row.ci.run.id}</a></p>}
    {row.falsifies && <p style={{ fontSize: 12, color: 'var(--text-secondary)', margin: '10px 0 0' }}><strong>Falsification:</strong> {row.falsifies}</p>}
    {row.blocker && <p style={{ fontSize: 12, color: '#7c3aed', margin: '10px 0 0' }}><strong>Blocker:</strong> {row.blocker}</p>}
  </div>)}</div>
}

function ClientExperiences({ report }: { report: TestIntelligenceReport }) {
  const { t } = useLanguage()
  return <div style={{ display: 'grid', gap: 12 }}>{(report.clientExperiences ?? []).map(client => <div key={client.id} style={{ border: '1px solid var(--border)', borderRadius: 10, padding: 18, background: 'var(--surface-1)' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}><div><strong>{client.title}</strong><span style={{ marginLeft: 8, color: 'var(--text-tertiary)', fontSize: 11 }}>{client.platforms.join(' · ')}</span></div><StateBadge state={aggregateEvidenceState(client.evidence.map(item => item.state), 'not-run')} /></div>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 10, marginTop: 12 }}>{client.evidence.map((item, index) => <div key={`${item.kind}-${index}`} style={{ padding: 10, borderRadius: 8, background: 'var(--surface-2)', fontSize: 12 }}><strong>{item.kind}</strong><div style={{ marginTop: 6 }}><StateBadge state={item.state} /></div>{item.counts && <div style={{ color: 'var(--text-secondary)', marginTop: 5 }}>{item.counts.passed}/{item.counts.executed} passed</div>}{item.detail && <div style={{ color: 'var(--text-tertiary)', marginTop: 5 }}>{item.detail}</div>}{item.diagnostics?.map(diagnostic => <div key={diagnostic.name} style={{ marginTop: 7 }}><a href={diagnostic.url} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)', fontWeight: 650 }}>{t('Otevřít diagnostiku běhu', 'Open run diagnostics')}</a><div style={{ color: 'var(--text-tertiary)', fontSize: 10, marginTop: 3 }}>{t(`Chráněný GitHub artefakt · ${diagnostic.retentionDays} dní · může obsahovat citlivá browser data`, `GitHub-authenticated artifact · ${diagnostic.retentionDays} days · may contain sensitive browser data`)}</div></div>)}</div>)}</div>
    {client.evidence.length === 0 && <p style={{ fontSize: 12, color: '#d97706', margin: '12px 0 0' }}>{t('Není přibalen důkaz posledního client CI běhu; zdrojový kód se nesmí vydávat za proběhlý test.', 'No latest client-CI evidence is bundled; source code is not represented as a completed test.')}</p>}
    <div style={{ marginTop: 12, padding: 11, borderRadius: 8, background: 'var(--surface-2)', fontSize: 12 }}><strong>RUM</strong><span style={{ marginLeft: 8 }}><StateBadge state={client.rum.state} /></span>{client.rum.source && <span style={{ marginLeft: 8, color: 'var(--text-tertiary)' }}>{client.rum.source} · 7d</span>}<div style={{ color: 'var(--text-secondary)', marginTop: 5 }}>{client.rum.detail}</div>{client.rum.sampledSpansLast7d !== undefined && client.rum.sampledSpansLast7d !== null && <div style={{ color: 'var(--text-tertiary)', marginTop: 5 }}>{client.rum.sampledSpansLast7d} sampled {client.rum.source === 'tempo' ? 'traces' : 'span-counter increments'} · {client.rum.errorSpansLast7d ?? 'unknown'} error span-counter increments</div>}{client.rum.backendCorrelations && <div style={{ marginTop: 8, padding: 9, borderLeft: '2px solid var(--accent)', color: 'var(--text-secondary)', fontSize: 11 }}><strong>{t('Mobil → backend', 'Mobile → backend')}</strong><span style={{ marginLeft: 7 }}>{client.rum.backendCorrelations.correlatedTraces}/{client.rum.backendCorrelations.inspectedTraces} {t('prohlédnutých traceů sdílí kontext s backendem', 'inspected traces share context with a backend service')}</span>{client.rum.backendCorrelations.backendServices.length ? <div style={{ marginTop: 4, color: 'var(--text-tertiary)' }}>{t('Pozorované služby', 'Observed services')}: {client.rum.backendCorrelations.backendServices.join(', ')}</div> : null}<div style={{ marginTop: 3, color: 'var(--text-tertiary)' }}>{client.rum.backendCorrelations.truncated ? t('Omezený vzorek; nejde o odhad celého provozu.', 'Bounded sample; not an estimate of all traffic.') : t('Vzorek aktuálně dostupných mobile traceů; nejde o testový verdikt.', 'Sample of currently available mobile traces; not a test verdict.')}</div></div>}{client.rum.platforms?.length ? <div aria-label={t('Důkazy mobilních RUM platforem', 'Mobile RUM platform evidence')} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 8, marginTop: 10 }}>{client.rum.platforms.map(platform => <div key={platform.platform} style={{ padding: 8, border: '1px solid var(--border)', borderRadius: 7 }}><strong>{platform.platform}</strong><div style={{ display: 'flex', gap: 10, marginTop: 5 }}><span>{t('exportér', 'exporter')} <StateBadge state={platform.capability} /></span><span>{t('runtime', 'runtime')} <StateBadge state={platform.runtime} /></span></div><div style={{ color: 'var(--text-tertiary)', fontSize: 10, marginTop: 5 }}>{platform.detail}</div></div>)}</div> : null}</div>
    {client.blocker && <p style={{ fontSize: 12, color: '#7c3aed', margin: '10px 0 0' }}><strong>Blocker:</strong> {client.blocker}</p>}
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
  useEffect(() => {
    const timer = window.setTimeout(() => { void load() }, 0)
    return () => window.clearTimeout(timer)
  }, [load])

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
    { id: 'clients', label: t('Client experience', 'Client experience'), icon: <Activity size={13} /> },
  ]

  return <div style={{ padding: '28px 32px', maxWidth: 1600, animation: 'fadeIn 0.2s ease-out' }}>
    <PageHeader
      breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep" aria-hidden="true">/</span><span>{t('Systém', 'System')}</span><span className="breadcrumb-sep" aria-hidden="true">/</span><span className="breadcrumb-current">{t('Test Intelligence', 'Test Intelligence')}</span></div>}
      icon={<FlaskConical size={19} aria-hidden="true" style={{ color: 'var(--accent)' }} />}
      title={t('Test Intelligence', 'Test Intelligence')}
      subtitle={t('Jednotný pohled na běhy, pokrytí kódu, trace kontrakty, mutace, výkon a sandboxové syntetické scénáře.', 'One evidence view for execution, code coverage, trace contracts, mutation, performance, and sandbox synthetic journeys.')}
      actions={<button type="button" onClick={load} disabled={testLoading || qualityLoading} aria-busy={testLoading || qualityLoading} aria-label={t('Obnovit systémové testy', 'Refresh system tests')} className="btn btn-secondary btn-sm"><RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 0.8s linear infinite' : 'none' }} />{t('Obnovit', 'Refresh')}</button>}
    />
    <TestIntelligenceFlow report={report} />
    {report && <AssuranceBoard report={report} selectTab={setTab} />}
    {report && <EvidenceGapQueue report={report} selectTab={setTab} />}
    {report?.warnings.length ? <div style={{ marginBottom: 16, border: '1px solid #d97706', borderRadius: 8, padding: 12, color: '#d97706', fontSize: 12 }}><TriangleAlert size={14} style={{ verticalAlign: 'text-bottom', marginRight: 6 }} />{report.warnings.join(' · ')}</div> : null}
    <div role="group" aria-label={t('Přepínač pohledů kvality kódu', 'Code quality view')} style={{ display: 'flex', gap: 2, overflowX: 'auto', borderBottom: '1px solid var(--border)', marginBottom: 20 }}>{tabs.map(tabDef => <button key={tabDef.id} type="button"
            aria-pressed={tab === tabDef.id} onClick={() => setTab(tabDef.id)} style={{ display: 'flex', gap: 6, alignItems: 'center', padding: '9px 13px', whiteSpace: 'nowrap', border: 'none', borderBottom: tab === tabDef.id ? '2px solid var(--accent)' : '2px solid transparent', background: 'none', color: tab === tabDef.id ? 'var(--accent)' : 'var(--text-secondary)', cursor: 'pointer', fontWeight: tab === tabDef.id ? 650 : 450 }}><span aria-hidden="true">{tabDef.icon}</span>{tabDef.label}</button>)}</div>
    {loading && !report ? <div className="skeleton" style={{ height: 260 }} /> : report ? <>
      {tab === 'posture' && <Posture report={report} />}{tab === 'tests' && <TestCases report={report} />}{tab === 'history' && <History report={report} />}{tab === 'execution' && <Execution report={report} />}{tab === 'runtime' && <RuntimeInfrastructure report={report} />}{tab === 'coverage' && <Coverage report={report} />}
      {tab === 'contracts' && <Contracts report={report} />}{tab === 'mutation' && <Mutations report={report} />}{tab === 'performance' && <Performance report={report} />}{tab === 'synthetic' && <Synthetics report={report} />}
      {tab === 'clients' && <ClientExperiences report={report} />}
    </> : <div style={{ padding: 24, color: 'var(--text-secondary)' }}>{t('Report není dostupný.', 'Report is unavailable.')}</div>}
    {report && <TestAgentPanel />}
    {report && <div style={{ marginTop: 18, color: 'var(--text-tertiary)', fontSize: 11 }}>{t('Schéma', 'Schema')} v{report.schemaVersion} · {t('sesbíráno', 'collected')} {new Intl.DateTimeFormat(dateLocale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(report.collectedAt))} · {t('absence se nikdy nevykresluje jako nula', 'absence is never rendered as zero')}</div>}
  </div>
}
