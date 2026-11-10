// SPDX-License-Identifier: Apache-2.0
'use client'

import { Fragment, useEffect, useMemo, useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { StatusBadge } from '@/components/ui'
import { domainLabel } from '@/lib/governance/domains'
import { TEST_KINDS, trustworthyTestRunUrl } from '@/lib/test-intelligence-workspace'
import { defaultExpertSelection, expertRows, expertSelectionUrl, EXPERT_DOMAINS, EXPERT_PAGE_SIZE, readExpertSelection, type ExpertSelection, type ExpertRow, type TestComponentCatalog } from '@/lib/test-intelligence-expert'
import type { EvidenceKind, TestCaseHistory, TestIntelligenceReport } from '@/lib/types/test-intelligence'
import { kindLabel, stateLabel, stateTone } from './labels'
import styles from './ExpertEvidenceMatrix.module.css'

export interface ExpertEvidenceMatrixProps {
  report: TestIntelligenceReport
  catalog?: TestComponentCatalog
  onOpen: (component: string, kind: EvidenceKind | '') => void
  onOpenTest: (item: TestCaseHistory) => void
}

/** Explicitly opened specialist view: the common matrix and single-test delivery stay bounded. */
export function ExpertEvidenceMatrix({ report, catalog = {}, onOpen, onOpenTest }: ExpertEvidenceMatrixProps) {
  const { t, language } = useLanguage()
  const [selection, setSelection] = useState(defaultExpertSelection)
  const [shareStatus, setShareStatus] = useState('')
  useEffect(() => {
    const read = () => setSelection(readExpertSelection(window.location.search))
    const timer = window.setTimeout(read, 0)
    window.addEventListener('popstate', read)
    return () => { window.clearTimeout(timer); window.removeEventListener('popstate', read) }
  }, [])
  const rows = useMemo(() => expertRows(report, selection, catalog), [report, selection, catalog])
  const pages = Math.max(1, Math.ceil(rows.length / EXPERT_PAGE_SIZE))
  const page = Math.min(selection.page, pages - 1)
  const visible = rows.slice(page * EXPERT_PAGE_SIZE, (page + 1) * EXPERT_PAGE_SIZE)
  function change(patch: Partial<ExpertSelection>, drill = false) {
    const next = { ...selection, ...patch }
    if (!drill && !('page' in patch)) { next.page = 0; next.inspect = ''; next.kind = '' }
    window.history[drill ? 'pushState' : 'replaceState'](null, '', expertSelectionUrl(next, window.location.search))
    setSelection(next)
    setShareStatus('')
  }
  const when = (value: string | null | undefined) => value && Number.isFinite(Date.parse(value))
    ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : t('Neuvedeno', 'Not recorded')
  const badge = (state: ExpertRow['cells'][EvidenceKind]['state']) => <StatusBadge status={state} tone={stateTone(state)} label={stateLabel(state, language)} withDot />
  const inspect = (component: string, kind: EvidenceKind | '') => change({ inspect: component, kind, page }, true)

  return <section className={styles.root} aria-label={t('Expertní matice důkazů komponent', 'Expert component evidence matrix')}>
    <header className={styles.heading}>
      <div><h2>{t('Expertní matice důkazů', 'Expert evidence matrix')}</h2><p>{t('Všechny vrstvy vedle sebe. Otevřete buňku pro důkazy, přesné běhy a jednotlivé testy.', 'Every layer side by side. Open a cell for its evidence, exact runs and individual tests.')}</p></div>
      <button type="button" className="btn btn-secondary btn-sm" onClick={async () => {
        try { await navigator.clipboard.writeText(window.location.href); setShareStatus(t('Odkaz zkopírován.', 'Link copied.')) }
        catch { setShareStatus(t('Odkaz s filtry najdete v adresním řádku.', 'The filtered link is in your address bar.')) }
      }}>{t('Sdílet tento pohled', 'Share this view')}</button>
    </header>
    {shareStatus ? <p role="status" className={styles.quiet}>{shareStatus}</p> : null}
    <div className={styles.filters}>
      <label>{t('Najít komponentu', 'Find a component')}<input className="form-input" value={selection.query} maxLength={160} onChange={event => change({ query: event.target.value })} /></label>
      <label>{t('Doména', 'Domain')}<select className="form-select" value={selection.domain} onChange={event => change({ domain: event.target.value })}><option value="">{t('Všechny domény', 'All domains')}</option>{EXPERT_DOMAINS.map(domain => <option key={domain} value={domain}>{domainLabel(domain, language)}</option>)}</select></label>
      <label>{t('Zaměřit se na', 'Focus on')}<select className="form-select" value={selection.status} onChange={event => change({ status: event.target.value as ExpertSelection['status'] })}>{([
        ['all', t('Všechny komponenty', 'All components')], ['failed', t('Selhání', 'Failures')], ['missing', t('Chybějící ověření', 'Missing verification')], ['stale', t('Historické výsledky', 'Historical results')], ['mismatch', t('Jinou běžící sestavu', 'Different running build')],
      ] as const).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>{t('Řadit podle', 'Sort by')}<select className="form-select" value={selection.sort} onChange={event => change({ sort: event.target.value as ExpertSelection['sort'] })}>{([
        ['priority', t('Priority kontroly', 'Review priority')], ['component', t('Názvu komponenty', 'Component name')], ['coverage', t('Pokrytí od nejnižšího', 'Lowest coverage first')], ['oldest', t('Nejstaršího záznamu', 'Oldest observation')],
      ] as const).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
    </div>
    <div className={styles.tools}>
      <label className={styles.check}><input type="checkbox" checked={selection.moneyOnly} onChange={event => change({ moneyOnly: event.target.checked })} />{t('Jen peněžní toky', 'Money paths only')}</label>
      <details className={styles.columns}><summary>{t(`Sloupce důkazů (${selection.columns.length})`, `Evidence columns (${selection.columns.length})`)}</summary><div>{TEST_KINDS.map(kind => <label key={kind} className={styles.check}><input type="checkbox" checked={selection.columns.includes(kind)} disabled={selection.columns.length === 1 && selection.columns.includes(kind)} onChange={event => change({ columns: TEST_KINDS.filter(value => value === kind ? event.target.checked : selection.columns.includes(value)) })} />{kindLabel(kind, language)}</label>)}</div></details>
      <button type="button" className="btn btn-secondary btn-sm" onClick={() => change(defaultExpertSelection())}>{t('Obnovit výchozí pohled', 'Reset view')}</button>
      <span>{t(`${rows.length} z ${report.components.length} komponent`, `${rows.length} of ${report.components.length} components`)}</span>
    </div>
    <p className={styles.quiet}>{t('Priorita řadí selhání, chybějící ověření a historické výsledky; při shodě zvýrazní peněžní toky. Nejde o skóre ani povolení nasazení.', 'Priority puts failures, missing verification and historical results first; money paths break ties. This is neither a score nor release approval.')}</p>
    <div className={styles.scroller} role="region" aria-label={t('Posuvná expertní matice', 'Scrollable expert matrix')} tabIndex={0}>
      <table className={styles.matrix}>
        <caption className="sr-only">{t('Stavy důkazů jednotlivých komponent; kliknutím na buňku otevřete její podklady.', 'Evidence states per component; open a cell to inspect its sources.')}</caption>
        <thead><tr><th scope="col" className={styles.componentCell}>{t('Komponenta', 'Component')}</th>{selection.columns.map(kind => <th scope="col" key={kind}>{kindLabel(kind, language)}<code className={styles.secondary}>{kind}</code></th>)}<th scope="col">{t('Řádky Kover', 'Kover lines')}</th><th scope="col">{t('Běžící sestava', 'Running build')}</th></tr></thead>
        <tbody>{visible.map(row => {
          const component = row.component
          const expanded = selection.inspect === component.component
          const panelId = `expert-evidence-${component.component.replace(/[^a-zA-Z0-9_-]/g, '-')}`
          return <Fragment key={component.component}>
            <tr>
              <th scope="row" className={styles.componentCell}><button type="button" className={styles.link} onClick={() => onOpen(component.component, '')}>{component.component}</button>{row.label !== component.component ? <div className={styles.secondary}>{row.label}</div> : null}<div className={styles.secondary}>{domainLabel(row.domain as typeof EXPERT_DOMAINS[number], language)}{component.moneyPath ? ` · ${t('peněžní tok', 'money path')}` : ''}</div></th>
              {selection.columns.map(kind => {
                const cell = row.cells[kind]
                const dates = component.evidence.filter(item => item.kind === kind && item.observedAt && Number.isFinite(Date.parse(item.observedAt))).map(item => item.observedAt!).sort((a, b) => Date.parse(b) - Date.parse(a))
                return <td key={kind} data-component={component.component} data-evidence-kind={kind} data-selected={expanded && selection.kind === kind || undefined}>
                  <button type="button" className={styles.cell} aria-label={`${component.component} · ${kindLabel(kind, language)} · ${stateLabel(cell.state, language)}`} aria-expanded={expanded && selection.kind === kind} aria-controls={expanded ? panelId : undefined} onClick={() => inspect(component.component, kind)}>{badge(cell.state)}<span className={styles.secondary}>{when(dates[0])}</span><span className={styles.secondary}>{t(`Důkazy: ${cell.observations}`, `Observations: ${cell.observations}`)}</span></button>
                </td>
              })}
              <td><button type="button" className={styles.cell} aria-label={`${component.component} · ${t('Pokrytí kódu', 'Code coverage')}`} onClick={() => inspect(component.component, '')}><strong>{component.coverage.lines.percentage === null ? '—' : `${component.coverage.lines.percentage}%`}</strong>{badge(component.coverage.state)}</button></td>
              <td><button type="button" className={styles.cell} aria-expanded={expanded} aria-controls={expanded ? panelId : undefined} onClick={() => expanded ? change({ inspect: '', kind: '' }, true) : inspect(component.component, '')}><span>{component.running?.match === true ? t('Shodná sestava', 'Matching build') : component.running?.match === false ? t('Jiná sestava', 'Different build') : t('Shoda neověřena', 'Match unverified')}</span><code className={styles.secondary}>{component.running?.runningCommit?.slice(0, 8) ?? '—'}</code><span className={styles.secondary}>{t('Podklady', 'Evidence')} {expanded ? '−' : '+'}</span></button></td>
            </tr>
            {expanded ? <tr><td colSpan={selection.columns.length + 3} className={styles.detailCell}><ExpertInspector id={panelId} report={report} row={row} kind={selection.kind} onOpen={onOpen} onOpenTest={onOpenTest} onClose={() => change({ inspect: '', kind: '' }, true)} /></td></tr> : null}
          </Fragment>
        })}</tbody>
      </table>
      {!rows.length ? <p className={styles.empty}>{t('Žádná komponenta neodpovídá filtrům.', 'No component matches these filters.')}</p> : null}
    </div>
    <div className={styles.pagination}><span>{t(`Zobrazeno ${rows.length ? page * EXPERT_PAGE_SIZE + 1 : 0}–${Math.min(rows.length, (page + 1) * EXPERT_PAGE_SIZE)} z ${rows.length}`, `Showing ${rows.length ? page * EXPERT_PAGE_SIZE + 1 : 0}–${Math.min(rows.length, (page + 1) * EXPERT_PAGE_SIZE)} of ${rows.length}`)}</span><div><button type="button" className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => change({ page: page - 1, inspect: '', kind: '' })}>{t('Předchozí', 'Previous')}</button>{' '}<button type="button" className="btn btn-secondary btn-sm" disabled={page + 1 >= pages} onClick={() => change({ page: page + 1, inspect: '', kind: '' })}>{t('Další', 'Next')}</button></div></div>
    <p className={styles.quiet}>{t('Prošlo znamená uchovaný výsledek; shoda s běžící sestavou je samostatný údaj. Netýká se je výslovná deklarace reportu, nikoli úspěšný test. Souhrnné počty se mohou překrývat.', 'Passed describes retained evidence; running-build applicability is separate. Not applicable is an explicit report declaration, not a passing test. Summary counts can overlap.')}</p>
  </section>
}

function ExpertInspector({ id, report, row, kind, onOpen, onOpenTest, onClose }: {
  id: string; report: TestIntelligenceReport; row: ExpertRow; kind: EvidenceKind | ''; onClose: () => void
  onOpen: ExpertEvidenceMatrixProps['onOpen']; onOpenTest: ExpertEvidenceMatrixProps['onOpenTest']
}) {
  const { t, language } = useLanguage()
  const component = row.component
  const observations = component.evidence.filter(item => !kind || item.kind === kind)
  const tests = report.testCases.filter(item => item.component === component.component && (!kind || item.kind === kind))
  const controls = (report.requiredControls ?? []).filter(item => item.component === component.component && (!kind || item.kind === kind))
  const when = (value: string | null | undefined) => value && Number.isFinite(Date.parse(value)) ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : t('Neuvedeno', 'Not recorded')
  return <section id={id} className={styles.inspector} aria-label={`${t('Podklady', 'Evidence')}: ${component.component}${kind ? ` · ${kindLabel(kind, language)}` : ''}`}>
    <div className={styles.heading}><h3>{component.component}{kind ? ` · ${kindLabel(kind, language)}` : ''}</h3><button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>{t('Zavřít podklady', 'Close evidence')}</button></div>
    <dl className={styles.facts}>
      <div><dt>{t('Běžící commit', 'Running commit')}</dt><dd><code>{component.running?.runningCommit ?? t('Neověřen', 'Unverified')}</code></dd></div>
      <div><dt>{t('Commit důkazů', 'Evidence commit')}</dt><dd><code>{component.running?.evidenceCommit ?? component.evidenceSource?.commit ?? component.running?.snapshotCommit ?? t('Neověřen', 'Unverified')}</code></dd></div>
      <div><dt>{t('Nejnovější záznam', 'Latest observation')}</dt><dd>{when(row.latestAt)}</dd></div>
      <div><dt>{t('Pokrytí řádků', 'Line coverage')}</dt><dd>{component.coverage.lines.percentage === null ? t('Procento není doloženo', 'Percentage not recorded') : `${component.coverage.lines.percentage}% · ${component.coverage.lines.covered ?? '—'} ${t('pokryto', 'covered')} · ${component.coverage.lines.missed ?? '—'} ${t('nepokryto', 'missed')}`}</dd></div>
    </dl>
    <div className={styles.inspectorGrid}>
      <div><h4>{t('Zaznamenané důkazy', 'Recorded evidence')}</h4><div className={styles.observations}>{observations.slice(0, 25).map((item, index) => <article key={`${item.kind}-${index}`}>
        <strong>{kindLabel(item.kind, language)}</strong>{' '}<StatusBadge status={item.state} tone={stateTone(item.state)} label={stateLabel(item.state, language)} />
        <p className={styles.secondary}>{item.source} · {item.environment} · {when(item.observedAt)}</p>
        {item.counts ? <p className={styles.secondary}>{t(`${item.counts.executed} provedeno · ${item.counts.failed} selhalo · ${item.counts.skipped} přeskočeno`, `${item.counts.executed} executed · ${item.counts.failed} failed · ${item.counts.skipped} skipped`)}</p> : null}
        {trustworthyTestRunUrl(item.run?.url) ? <a href={trustworthyTestRunUrl(item.run?.url)!} target="_blank" rel="noreferrer">{t('Přesný běh důkazu', 'Exact evidence run')} · {item.run?.id}/{item.run?.attempt}</a> : null}
        {item.diagnostics?.map((artifact, index) => trustworthyTestRunUrl(artifact.url) ? <p key={index}><a href={trustworthyTestRunUrl(artifact.url)!} target="_blank" rel="noreferrer">{t('Diagnostický report běhu', 'Run diagnostic report')} · {artifact.name.slice(0, 120)}</a></p> : null)}
      </article>)}</div>{!observations.length ? <p className={styles.quiet}>{t('Pro tento výběr nejsou uchované důkazy. Prázdná buňka není úspěšný test.', 'No evidence is retained for this selection. An empty cell is not a passing test.')}</p> : null}{observations.length > 25 ? <p className={styles.quiet}>{t('Zobrazeno prvních 25 důkazů; další podklady jsou v detailu komponenty.', 'Showing the first 25 observations; further evidence is in the component detail.')}</p> : null}</div>
      <div><h4>{t('Jednotlivé testy a AI vysvětlení', 'Individual tests and AI explanations')}</h4><p className={styles.quiet}>{t(`Ve výřezu reportu: ${tests.length} testů. Název je záměr autora, podrobnosti ukazují skutečně uchované výsledky.`, `Retained in this slice: ${tests.length} tests. Names describe author intent; details show the results actually retained.`)}</p>
        <ul className={styles.testList}>{tests.slice(0, 5).map(item => <li key={`${item.kind}-${item.fingerprint}`}><button type="button" className={styles.link} onClick={() => onOpenTest(item)}>{item.name}</button><span className={styles.secondary}>{stateLabel(item.lastState, language)}</span></li>)}</ul>
        {!tests.length ? <p className={styles.quiet}>{t('Jednotlivé testy nejsou v tomto výřezu uchované. To neznamená, že se neprovedly.', 'Individual tests are not retained in this slice. This does not mean they did not run.')}</p> : null}
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => onOpen(component.component, kind)}>{t('Otevřít testy a úplné podklady', 'Open tests and full evidence')}</button>
        {controls.length ? <><h4>{t('Povinné kontroly', 'Required controls')}</h4><ul className={styles.controls}>{controls.slice(0, 8).map(item => <li key={item.id}><strong>{stateLabel(item.state, language)}</strong> · {item.reason}{item.blocker ? ` · ${item.blocker}` : ''}</li>)}</ul>{controls.length > 8 ? <p className={styles.quiet}>{t('Zobrazeno prvních 8 kontrol.', 'Showing the first 8 controls.')}</p> : null}</> : null}
      </div>
    </div>
  </section>
}
