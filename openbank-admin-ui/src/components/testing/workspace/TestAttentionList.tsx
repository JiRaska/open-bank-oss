// SPDX-License-Identifier: Apache-2.0
'use client'
import { useMemo, useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { EvidenceKind, EvidenceState, TestIntelligenceReport } from '@/lib/types/test-intelligence'
import { TEST_KINDS } from '@/lib/test-intelligence-workspace'
import { kindLabel, stateLabel } from './labels'
import styles from './TestWorkspace.module.css'

export function TestAttentionList({ report, onOpen, onOpenRuns }: {
  report: TestIntelligenceReport; onOpen: (component: string, kind: EvidenceKind | '') => void; onOpenRuns: () => void
}) {
  const { language, t } = useLanguage()
  const [query, setQuery] = useState('')
  const [limit, setLimit] = useState(25)
  const rows = useMemo(() => {
    const items = new Map<string, { id: string; component: string; kind: string; state: EvidenceState; reason: string }>()
    const priority: Record<EvidenceState, number> = { failed: 0, blocked: 1, unknown: 2, 'not-run': 3, stale: 4, skipped: 5, passed: 6 }
    for (const control of report.requiredControls ?? []) {
      if (control.kind === 'synthetic' && report.syntheticJourneys.some(item => control.id === `synthetic:${item.id}`)) continue
      if (control.kind === 'performance' && report.performance.some(item => item.component === control.component)) continue
      if (control.state !== 'passed') items.set(`${control.component}:${control.kind}`, { id: control.id, component: control.component ?? '', kind: control.kind, state: control.state, reason: control.blocker ?? control.reason ?? '' })
    }
    for (const component of report.components) for (const item of component.evidence) {
      if (item.state !== 'passed' && (!items.has(`${component.component}:${item.kind}`) || priority[item.state] < priority[items.get(`${component.component}:${item.kind}`)!.state])) items.set(`${component.component}:${item.kind}`, { id: `${component.component}:${item.kind}`, component: component.component, kind: item.kind, state: item.state, reason: item.detail ?? item.source })
    }
    for (const item of report.performance) if (item.state !== 'passed' || item.plan?.blocker) {
      const key = `${item.component ?? ''}:performance:${item.id}`
      items.set(key, { id: key, component: item.component ?? '', kind: 'performance', state: item.state === 'passed' ? 'blocked' : item.state, reason: item.plan?.blocker ?? item.source })
    }
    for (const item of report.syntheticJourneys) if (item.state !== 'passed' || item.status === 'planned') items.set(`synthetic:${item.id}`, { id: item.title, component: '', kind: 'synthetic', state: item.status === 'planned' ? 'blocked' : item.state, reason: item.blocker ?? item.falsifies ?? '' })
    return [...items.values()].sort((left, right) => priority[left.state] - priority[right.state] || left.component.localeCompare(right.component))
  }, [report])
  const filtered = rows.filter(item => `${item.component} ${item.kind} ${item.id}`.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
  return <section className={styles.section} aria-label={t('Co vyžaduje pozornost', 'What needs attention')}>
    <h2 className={styles.heading}>{t('Co vyžaduje pozornost', 'What needs attention')}</h2>
    <p className={styles.quiet}>{t('Nejprve selhání, potom blokátory a chybějící ověření. Položka není automaticky incident; podrobnosti vysvětlují její rozsah.', 'Failures first, then blockers and missing verification. An item is not automatically an incident; its details explain the scope.')}</p>
    <label className={styles.field}>{t('Najít položku', 'Find an item')}<input className={styles.input} value={query} onChange={event => { setQuery(event.target.value); setLimit(25) }} /></label>
    <div className={styles.tableWrap}><table className={styles.table}><thead><tr><th>{t('Komponenta nebo scénář', 'Component or scenario')}</th><th>{t('Ověření', 'Verification')}</th><th>{t('Stav', 'State')}</th><th>{t('Důvod a další krok', 'Reason and next step')}</th></tr></thead><tbody>{filtered.slice(0, limit).map(item => <tr key={`${item.component}:${item.id}`}>
      <td><button type="button" className={styles.link} onClick={() => report.components.some(component => component.component === item.component) ? onOpen(item.component, TEST_KINDS.includes(item.kind as EvidenceKind) ? item.kind as EvidenceKind : '') : onOpenRuns()}>{item.component || item.id}</button></td>
      <td>{TEST_KINDS.includes(item.kind as EvidenceKind) ? kindLabel(item.kind as EvidenceKind, language) : item.kind === 'coverage' ? t('Pokrytí kódu', 'Code coverage') : t('Testovací prostředí', 'Test runtime')}</td>
      <td>{stateLabel(item.state, language)}</td>
      <td><details><summary>{t('Proč je tu a co ověřit', 'Why it is here and what to inspect')}</summary><p className={styles.quiet}>{item.reason}</p></details></td>
    </tr>)}</tbody></table>{filtered.length === 0 ? <p className={styles.empty}>{t('Žádná položka v dostupném rozsahu neodpovídá hledání. Úplnost reportu je uvedena samostatně.', 'No item in the available scope matches. Report completeness is shown separately.')}</p> : null}</div>
    <div className={styles.pagination}><span>{t(`Zobrazeno ${Math.min(limit, filtered.length)} z ${filtered.length} položek`, `Showing ${Math.min(limit, filtered.length)} of ${filtered.length} items`)}</span>{filtered.length > limit ? <button type="button" className="btn btn-secondary btn-sm" onClick={() => setLimit(limit + 25)}>{t('Zobrazit dalších 25', 'Show 25 more')}</button> : null}</div>
  </section>
}
