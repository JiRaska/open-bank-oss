// SPDX-License-Identifier: Apache-2.0
'use client'

import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { StatusBadge } from '@/components/ui'
import { DOMAIN_GROUPS, domainLabel, isDomainId } from '@/lib/governance/domains'
import type { ComponentTestPosture, EvidenceKind, TestCaseHistory, TestIntelligenceReport } from '@/lib/types/test-intelligence'
import { readTestSelection, testCell, testScopeCounts, testSelectionUrl, TEST_KINDS, trustworthyTestRunUrl, type TestSelection } from '@/lib/test-intelligence-workspace'
import { testIntelligenceCollectionUnavailable } from '@/components/testing/TestIntelligenceFlow'
import { TestCaseDetail } from './TestCaseDetail'
import { KIND_HELP, kindLabel, stateLabel, stateTone } from './labels'
import styles from './TestWorkspace.module.css'

const PAGE_SIZE = 25
const DEFAULT_KINDS: EvidenceKind[] = ['unit', 'integration', 'contract', 'e2e']
const initial: TestSelection = { section: 'components', component: '', kind: '', test: '' }

type WorkspaceProps = {
  report: TestIntelligenceReport
  renderRuns: (openTest: (item: TestCaseHistory) => void) => ReactNode
  learning: ReactNode
  attention: (openComponent: (component: string, kind: EvidenceKind | '') => void, openRuns: () => void) => ReactNode
  freshness: ReactNode
}

export function TestIntelligenceWorkspace({ report, renderRuns, learning, attention, freshness }: WorkspaceProps) {
  const { t, language } = useLanguage()
  const [selection, setSelection] = useState<TestSelection>(initial)
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [expandedKinds, setExpandedKinds] = useState(false)
  const [catalog, setCatalog] = useState<Record<string, { domain: string; label: string }>>({})
  useEffect(() => {
    const read = () => { setSelection(readTestSelection(window.location.search)); setQuery(''); setPage(0) }
    const timer = window.setTimeout(read, 0)
    window.addEventListener('popstate', read)
    return () => { window.clearTimeout(timer); window.removeEventListener('popstate', read) }
  }, [])
  useEffect(() => {
    const request = new AbortController()
    void fetch('/api/catalog/services', { signal: request.signal }).then(response => response.ok ? response.json() : null).then(value => {
      if (!Array.isArray(value?.services) || request.signal.aborted) return
      const entries: Record<string, { domain: string; label: string }> = {}
      for (const item of value.services) {
        if (typeof item?.name === 'string' && /^openbank-[a-z0-9-]+$/.test(item.name) && isDomainId(item.dataDomain)) {
          entries[item.name] = { domain: item.dataDomain, label: typeof item.apiTitle === 'string' ? item.apiTitle.slice(0, 120) : item.name.replace(/^openbank-/, '').replace(/-service$/, '').replaceAll('-', ' ') }
        }
      }
      setCatalog(entries)
    }).catch(() => {})
    return () => request.abort()
  }, [])

  function navigate(patch: Partial<TestSelection>) {
    const next = { ...selection, ...patch }
    window.history.pushState(null, '', testSelectionUrl(next, window.location.search))
    setSelection(next)
    setPage(0)
  }
  function openTest(item: TestCaseHistory) { navigate({ section: 'components', component: item.component, kind: item.kind, test: item.fingerprint }) }
  const unavailable = testIntelligenceCollectionUnavailable(report)
  const components = useMemo(() => report.components.filter(item => `${item.component} ${catalog[item.component]?.label ?? ''}`.toLocaleLowerCase().includes(query.toLocaleLowerCase())), [report.components, query, catalog])
  const groups = DOMAIN_GROUPS.map(domain => ({ domain, rows: components.filter(item => (catalog[item.component]?.domain ?? (isDomainId(item.domain) ? item.domain : 'unclassified')) === domain) })).filter(group => group.rows.length)
  const component = report.components.find(item => item.component === selection.component)
  const selectedTest = report.testCases.find(item => item.fingerprint === selection.test && (!selection.component || item.component === selection.component) && (!selection.kind || item.kind === selection.kind))
  const tests = report.testCases.filter(item => item.component === selection.component && (!selection.kind || item.kind === selection.kind))
    .filter(item => `${item.name} ${item.classname}`.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
  const kinds = expandedKinds ? TEST_KINDS : DEFAULT_KINDS
  const pages = Math.max(1, Math.ceil((component ? tests.length : components.length) / PAGE_SIZE))
  const safePage = Math.min(page, pages - 1)
  const selectedKinds = component?.evidence.filter(item => !selection.kind || item.kind === selection.kind) ?? []
  const { failed: scopeFailed, stale: scopeStale, missing: scopeMissing } = testScopeCounts(report)
  function displayName(item: ComponentTestPosture) { return catalog[item.component]?.label ?? item.component.replace(/^openbank-/, '').replace(/-service$/, '').replaceAll('-', ' ') }
  const when = (value: string | null) => value && Number.isFinite(Date.parse(value)) ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : t('Neuvedeno', 'Not recorded')

  function pagination(total: number) {
    return <div className={styles.pagination}>
      <span>{t(`Zobrazeno ${total ? safePage * PAGE_SIZE + 1 : 0}–${Math.min(total, (safePage + 1) * PAGE_SIZE)} z ${total}`, `Showing ${total ? safePage * PAGE_SIZE + 1 : 0}–${Math.min(total, (safePage + 1) * PAGE_SIZE)} of ${total}`)}</span>
      <div><button type="button" className="btn btn-secondary btn-sm" disabled={safePage === 0} onClick={() => setPage(safePage - 1)}>{t('Předchozí', 'Previous')}</button>{' '}<button type="button" className="btn btn-secondary btn-sm" disabled={safePage + 1 >= pages} onClick={() => setPage(safePage + 1)}>{t('Další', 'Next')}</button></div>
    </div>
  }

  return <div className={styles.root}>
    <div className={styles.context} aria-label={t('Rozsah testovacích důkazů', 'Test evidence scope')}>
      <span>{t('Rozsah: poslední doložené výsledky jednotlivých komponent; nejde o jediný společný běh.', 'Scope: latest retained results per component; not a single shared run.')}</span>
      <span>{t('Report sesbírán', 'Report collected')}: {when(report.collectedAt)}</span>
    </div>
    {freshness}
    <nav className={styles.nav} aria-label={t('Části Test Intelligence', 'Test Intelligence sections')}>
      {(['components', 'attention', 'runs', 'learn'] as const).map((section, index) => <button type="button" className={`btn btn-sm ${selection.section === section ? 'btn-primary' : 'btn-secondary'}`} key={section} aria-current={selection.section === section ? 'page' : undefined} onClick={() => { setQuery(''); navigate({ section, component: '', kind: '', test: '' }) }}>{[t('Komponenty', 'Components'), t('K řešení', 'Needs attention'), t('Běhy a historie', 'Runs and history'), t('Jak testujeme', 'How testing works')][index]}</button>)}
    </nav>
    {unavailable ? <p className={styles.quiet} role="status">{t('Úplný report není dostupný. Chybějící data se nepovažují za úspěšný výsledek.', 'The complete report is unavailable. Missing data is not a passing result.')}</p> : null}
    {selection.section === 'components' && <>
      {!selection.component && <>
        <div className={styles.summary} aria-label={t('Souhrn komponent', 'Component summary')}>
          <span>{t('Komponenty', 'Components')}: <strong>{report.components.length}</strong></span>
          <span>{t('Se selháním', 'With failures')}: <strong>{scopeFailed}</strong></span>
          <span>{t('S historickými výsledky', 'With historical results')}: <strong>{scopeStale}</strong></span>
          <span>{t('Bez úplného ověření', 'With missing verification')}: <strong>{scopeMissing}</strong></span>
        </div>
        <div className={styles.toolbar}>
          <label className={styles.field}>{t('Najít komponentu', 'Find a component')}<input className={styles.input} value={query} onChange={event => { setQuery(event.target.value); setPage(0) }} /></label>
          <button type="button" className="btn btn-secondary btn-sm" aria-pressed={expandedKinds} onClick={() => setExpandedKinds(!expandedKinds)}>{expandedKinds ? t('Základní vrstvy', 'Core layers') : t('Všechny typy důkazů', 'All evidence types')}</button>
        </div>
        <section className={styles.section} aria-label={t('Matice důkazů komponent', 'Component evidence matrix')}>
          <h2 className={styles.heading}>{t('Komponentní matice', 'Component matrix')}</h2>
          <div className={`${styles.tableWrap} ${styles.matrixWrap}`} role="region" aria-label={t('Posuvná komponentní matice', 'Scrollable component matrix')} tabIndex={0}><table className={styles.table}>
            <thead><tr><th scope="col">{t('Komponenta', 'Component')}</th>{kinds.map(kind => <th scope="col" key={kind}>{kindLabel(kind, language)}</th>)}<th scope="col">{t('Platnost pro běžící sestavu', 'Running-build applicability')}</th></tr></thead>
            <tbody>{groups.map(group => {
              const pageNames = new Set(groups.flatMap(item => item.rows).slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE).map(item => item.component))
              const rows = group.rows.filter(item => pageNames.has(item.component))
              if (!rows.length) return null
              return <GroupRows key={group.domain} title={domainLabel(group.domain, language)} colSpan={kinds.length + 2}>{rows.map(item => <tr key={item.component}>
                <td><button type="button" className={styles.link} onClick={() => { setQuery(''); navigate({ component: item.component, kind: '', test: '' }) }}>{displayName(item)}</button><div className={styles.secondary}>{item.component}{item.moneyPath ? ` · ${t('peněžní tok', 'money path')}` : ''}</div></td>
                {kinds.map(kind => { const cell = testCell(report, item, kind); return <td key={kind} data-component={item.component} data-evidence-kind={kind}><button type="button" className={styles.cellButton} aria-label={`${displayName(item)} · ${kindLabel(kind, language)} · ${stateLabel(cell.state, language)}`} onClick={() => { setQuery(''); navigate({ component: item.component, kind, test: '' }) }}><StatusBadge status={cell.state} tone={stateTone(cell.state)} label={stateLabel(cell.state, language)} withDot /></button></td> })}
                <td><span className={styles.secondary}>{item.running?.match === true ? t('Přiřazeno k běžící sestavě', 'Attributed to running build') : item.running?.match === false ? t('Jiná sestava', 'Different build') : t('Shoda sestavy neověřena', 'Build match unverified')}</span>{item.running?.latestEvidenceAt ? <div className={styles.secondary}>{when(item.running.latestEvidenceAt)}</div> : null}</td>
              </tr>)}</GroupRows>
            })}</tbody>
          </table>{components.length === 0 ? <div className={styles.empty}>{t('Žádná komponenta neodpovídá hledání.', 'No component matches your search.')}</div> : null}</div>
          {pagination(components.length)}
          <p className={styles.quiet}>{t('Klikněte na výsledek pro odpovídající testy a důkazy. Historický výsledek nepotvrzuje současnou sestavu. Souhrnné počty komponent se mohou překrývat.', 'Open a result for its tests and evidence. Historical results do not confirm the current build. Component summary counts can overlap.')}</p>
        </section>
      </>}
      {selection.component && !component ? <div className={styles.empty}><p>{t('Komponenta z odkazu v tomto reportu není. Dostupné výsledky zůstávají nezměněné.', 'The linked component is not present in this report. Available results are unchanged.')}</p><button type="button" className="btn btn-secondary" onClick={() => navigate({ component: '', kind: '', test: '' })}>{t('Zpět do matice', 'Back to matrix')}</button></div> : null}
      {component && <>
        {selectedTest ? <TestCaseDetail key={`${selectedTest.fingerprint}-${selectedTest.lastObservedAt}-${report.collectedAt}-${language}`} testCase={selectedTest} collectedAt={report.collectedAt} onBack={() => navigate({ test: '' })} /> : <section className={styles.section} aria-label={t('Testy vybrané komponenty', 'Selected component tests')}>
          <div><button type="button" className="btn btn-secondary btn-sm" onClick={() => { setQuery(''); navigate({ component: '', kind: '', test: '' }) }}>{t('Zpět do matice', 'Back to matrix')}</button></div>
          <h2 className={styles.heading}>{displayName(component)}{selection.kind ? ` · ${kindLabel(selection.kind, language)}` : ''}</h2>
          {selection.test ? <p role="status" className={styles.quiet}>{t('Konkrétní test z odkazu není v uchovaném výřezu reportu. Nejde o důkaz, že se neprovedl.', 'The linked test is not in the retained report slice. This does not prove that it did not run.')}</p> : null}
          {selection.kind ? <p className={styles.quiet}>{KIND_HELP[selection.kind][language === 'cs' ? 0 : 1]}</p> : null}
          <div className={styles.toolbar}><label className={styles.field}>{t('Najít test', 'Find a test')}<input className={styles.input} value={query} onChange={event => { setQuery(event.target.value); setPage(0) }} /></label><label className={styles.field}>{t('Typ důkazu', 'Evidence type')}<select className={styles.input} value={selection.kind} onChange={event => { setQuery(''); navigate({ kind: event.target.value as EvidenceKind | '', test: '' }) }}><option value="">{t('Všechny typy', 'All types')}</option>{TEST_KINDS.map(kind => <option key={kind} value={kind}>{kindLabel(kind, language)}</option>)}</select></label></div>
          <div className={styles.tableWrap}><table className={styles.table}><thead><tr><th>{t('Test a jeho záměr', 'Test and declared intent')}</th><th>{t('Poslední výsledek', 'Last outcome')}</th><th>{t('Kdy', 'Observed')}</th></tr></thead><tbody>{tests.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE).map(item => <tr key={item.fingerprint}><td><button type="button" className={styles.link} onClick={() => openTest(item)}>{item.name.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ')}</button></td><td>{stateLabel(item.lastState, language)}{item.state === 'flaky' ? <div className={styles.secondary}>{t('Pozorována nestabilita', 'Instability observed')}</div> : null}</td><td>{when(item.lastObservedAt)}</td></tr>)}</tbody></table>{tests.length === 0 ? <div className={styles.empty}>{t('V tomto výřezu nemáme jednotlivé testy. Souhrnné důkazy níže zůstávají dostupné; chybějící detail není nulový počet provedených testů.', 'Individual tests are not retained in this slice. Aggregate evidence remains available below; missing detail is not zero executed tests.')}</div> : null}</div>
          {pagination(tests.length)}
          <h3 className={styles.heading}>{t('Souhrnné důkazy komponenty', 'Component-level evidence')}</h3>
          <div className={styles.tableWrap}><table className={styles.table}><thead><tr><th>{t('Typ', 'Type')}</th><th>{t('Výsledek', 'Outcome')}</th><th>{t('Zdroj a rozsah', 'Source and scope')}</th><th>{t('Kdy', 'Observed')}</th></tr></thead><tbody>{selectedKinds.map((item, index) => <tr key={`${item.kind}-${index}`}><td>{kindLabel(item.kind, language)}</td><td>{stateLabel(item.state, language)}</td><td>{item.source}{item.counts ? <div className={styles.secondary}>{t(`${item.counts.executed} provedeno · ${item.counts.failed} selhalo · ${item.counts.skipped} přeskočeno`, `${item.counts.executed} executed · ${item.counts.failed} failed · ${item.counts.skipped} skipped`)}</div> : null}{trustworthyTestRunUrl(item.run?.url) ? <div><a href={trustworthyTestRunUrl(item.run?.url)!} target="_blank" rel="noreferrer">{t('Otevřít zdrojový běh', 'Open source run')}</a></div> : null}</td><td>{when(item.observedAt)}</td></tr>)}</tbody></table>{selectedKinds.length === 0 ? <div className={styles.empty}>{t('Pro tento typ nemáme doložený výsledek.', 'No recorded result for this type.')}</div> : null}</div>
        </section>}
      </>}
    </>}
    {selection.section === 'attention' && attention((component, kind) => { setQuery(''); navigate({ section: 'components', component, kind, test: '' }) }, () => navigate({ section: 'runs', component: '', kind: '', test: '' }))}
    {selection.section === 'runs' && renderRuns(openTest)}
    {selection.section === 'learn' && <section className={styles.section}>
      <h2 className={styles.heading}>{t('Od otázky k ověřitelnému výsledku', 'From a question to a verifiable result')}</h2>
      <p className={styles.quiet}>{t('Test ověřuje konkrétní očekávání za konkrétních podmínek. Zelený test nepotvrzuje všechny vlastnosti systému. Pokrytí kódu měří spuštěné řádky, nikoli pravděpodobnost správnosti.', 'A test checks a specific expectation under specific conditions. A passing test does not establish every property of the system. Code coverage measures executed lines, not the probability of correctness.')}</p>
      <div className={styles.learnGrid}>{TEST_KINDS.map(kind => <article key={kind}><h3 className={styles.heading}>{kindLabel(kind, language)}</h3><p className={styles.quiet}>{KIND_HELP[kind][language === 'cs' ? 0 : 1]}</p></article>)}</div>
      <p className={styles.quiet}>{t('AI vysvětluje dostupné podklady, rozlišuje fakta od hypotéz a navrhuje další ověření. Nemění původní výsledek ani schválení nasazení.', 'AI explains available sources, separates facts from hypotheses and suggests further verification. It does not change the original outcome or deployment approval.')}</p>
      <details className={styles.details}><summary>{t('Architektura ověřování a podrobné schopnosti platformy', 'Assurance architecture and detailed platform capabilities')}</summary>{learning}</details>
    </section>}
    {report.warnings.length > 0 && <details className={styles.details}><summary>{t(`Úplnost dat a omezení reportu (${report.warnings.length})`, `Data completeness and report limitations (${report.warnings.length})`)}</summary><ul>{report.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></details>}
  </div>
}

function GroupRows({ title, colSpan, children }: { title: string; colSpan: number; children: ReactNode }) {
  return <><tr className={styles.group}><th scope="rowgroup" colSpan={colSpan}>{title}</th></tr>{children}</>
}
