// SPDX-License-Identifier: Apache-2.0
'use client'

import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, Sparkles } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { StatusBadge } from '@/components/ui'
import type { TestCaseHistory } from '@/lib/types/test-intelligence'
import { testDefinitionUrl, trustworthyTestRunUrl } from '@/lib/test-intelligence-workspace'
import { KIND_HELP, kindLabel, stateLabel, stateTone } from './labels'
import styles from './TestWorkspace.module.css'

export function TestCaseDetail({ testCase, collectedAt, onBack }: {
  testCase: TestCaseHistory; collectedAt: string; onBack: () => void
}) {
  const { t, language } = useLanguage()
  const [explanation, setExplanation] = useState<{ text: string; model: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [unavailable, setUnavailable] = useState(false)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  const run = testCase.lastRun
  const runUrl = trustworthyTestRunUrl(run?.url)
  const sourceUrl = testDefinitionUrl(testCase.component, testCase.testDefinitionPath, run?.commit)
  const date = (value: string) => Number.isFinite(Date.parse(value))
    ? new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
    : t('Neuvedeno', 'Not recorded')

  async function explain() {
    if (busy) return
    const request = new AbortController()
    controller.current = request
    setBusy(true)
    setUnavailable(false)
    try {
      const response = await fetch('/api/test-intelligence/explain', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: request.signal,
        body: JSON.stringify({ fingerprint: testCase.fingerprint, collectedAt, language }),
      })
      const value = await response.json()
      if (!response.ok || value.fingerprint !== testCase.fingerprint || value.collectedAt !== collectedAt || typeof value.explanation !== 'string' || !value.explanation.trim()) throw new Error('explanation unavailable')
      if (!request.signal.aborted) setExplanation({ text: value.explanation, model: typeof value.model === 'string' ? value.model : '' })
    } catch { if (!request.signal.aborted) setUnavailable(true) }
    finally { if (!request.signal.aborted) setBusy(false) }
  }

  return <section className={styles.section} aria-label={t('Srozumitelný detail testu', 'Understand this test')}>
    <div className={styles.toolbar}>
      <button type="button" className="btn btn-secondary btn-sm" onClick={onBack}><ArrowLeft size={14} aria-hidden="true" />{t('Zpět na testy komponenty', 'Back to component tests')}</button>
      <span className={styles.secondary}>{testCase.component} · {kindLabel(testCase.kind, language)}</span>
    </div>
    <div>
      <h2 className={styles.heading}>{testCase.name.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ')}</h2>
      <p className={styles.quiet}>{KIND_HELP[testCase.kind][language === 'cs' ? 0 : 1]}</p>
      <p className={styles.secondary}>{t('Název vyjadřuje záměr autora. Zaznamenaný výsledek a dostupné důkazy jsou uvedeny samostatně níže.', 'The name describes the author’s intent. The recorded outcome and available evidence are listed separately below.')}</p>
    </div>
    <dl className={styles.facts}>
      <div><dt>{t('Zaznamenaný výsledek', 'Recorded outcome')}</dt><dd><StatusBadge status={testCase.lastState} tone={stateTone(testCase.lastState)} label={stateLabel(testCase.lastState, language)} withDot /></dd></div>
      <div><dt>{t('Poslední pozorování', 'Last observation')}</dt><dd>{date(testCase.lastObservedAt)}</dd></div>
      <div><dt>{t('Uchované běhy tohoto testu', 'Retained observations of this test')}</dt><dd>{testCase.observations}</dd></div>
      <div><dt>{t('Průměrná doba uchovaných běhů', 'Average retained runtime')}</dt><dd>{testCase.averageDurationMs} ms</dd></div>
      <div><dt>{t('Stabilita v uchované historii', 'Stability in retained history')}</dt><dd>{testCase.state === 'flaky' ? t('Pozorována nestabilita', 'Instability observed') : testCase.state === 'stale' ? t('Historický výsledek', 'Historical result') : stateLabel(testCase.lastState, language)}</dd></div>
      <div><dt>{t('Vlastník testu', 'Test owner')}</dt><dd>{testCase.owner}</dd></div>
    </dl>
    <section className={styles.section} aria-label={t('Co test skutečně provedl', 'What the test actually recorded')}>
      <h3 className={styles.heading}>{t('Co máme skutečně doloženo', 'What is actually recorded')}</h3>
      <p className={styles.quiet}>{t('Report obsahuje výsledek a dobu běhu. Jednotlivé kroky, vstupní hodnoty a rozdíl očekávání proti pozorování zde nebyly uchovány. Nevydáváme název testu ani AI vysvětlení za záznam těchto kroků.', 'The report records an outcome and runtime. Individual steps, input values and expected-versus-actual assertions were not retained here. Neither the test name nor an AI explanation is a recording of those steps.')}</p>
      {run && runUrl ? <div className={styles.context}><a href={runUrl} target="_blank" rel="noreferrer">{t('Otevřít přesný běh testu', 'Open this test’s exact run')} · {run.id} / {run.attempt}</a><span>{t('Sestava', 'Commit')}: <code>{run.commit}</code></span></div>
        : <p className={styles.quiet}>{t('Tento starší report nemá přiřazený přesný běh jednotlivého testu. Souhrnný běh komponenty jej nenahrazuje.', 'This older report has no exact per-test run attribution. A component-level run does not substitute for it.')}</p>}
      {sourceUrl ? <a href={sourceUrl} target="_blank" rel="noreferrer">{t('Definice testu v testované sestavě', 'Test definition at the tested commit')}</a> : null}
      {testCase.retryFlaky ? <p className={styles.quiet}>{t(`Úspěchu předcházelo ${testCase.failedAttemptCount ?? 0} neúspěšných pokusů. Výsledek po opakování není stabilní úspěch na první pokus.`, `Success followed ${testCase.failedAttemptCount ?? 0} failed attempts. Passing after retry is not a stable first-attempt pass.`)}</p> : null}
    </section>
    <div className={styles.section}>
      <div><button type="button" className="btn btn-primary btn-sm" onClick={() => void explain()} disabled={busy} aria-busy={busy}><Sparkles size={14} aria-hidden="true" />{busy ? t('Připravuji vysvětlení…', 'Preparing explanation…') : t('Vysvětlit výsledek s AI', 'Explain this result with AI')}</button></div>
      <p className={styles.secondary}>{t('Na vyžádání předá omezená metadata testu stávajícímu řízenému asistentovi. Vstupní data, logy a screenshoty se neposílají.', 'On request, sends bounded test metadata to the existing governed assistant. Input data, logs and screenshots are not sent.')}</p>
      {unavailable && <p role="status" className={styles.quiet}>{t('AI vysvětlení je nyní nedostupné nebo se důkaz mezitím změnil. Původní výsledek zůstává dostupný; můžete obnovit report a zkusit to znovu.', 'The AI explanation is unavailable or the evidence has changed. The original result remains available; refresh the report and try again.')}</p>}
      {explanation && <aside className={styles.explanation} aria-label={t('AI interpretace důkazů', 'AI interpretation of evidence')} aria-live="polite">
        <strong>{t('AI interpretace · původní verdikt se nemění', 'AI interpretation · original verdict unchanged')}</strong>
        <p>{explanation.text}</p>
        <div className={styles.secondary}>{t('Podklady', 'Sources')}: {testCase.fingerprint} · {date(testCase.lastObservedAt)}{explanation.model ? ` · ${explanation.model}` : ''}</div>
      </aside>}
    </div>
    <details className={styles.details}><summary>{t('Technické podklady a historie', 'Technical evidence and history')}</summary>
      <p className={styles.secondary}>{testCase.classname} · {testCase.name}</p>
      <p className={styles.secondary}>{testCase.testDefinitionPath ?? t('Zdrojový soubor nebyl zaznamenán.', 'Source file was not recorded.')}</p>
      <p className={styles.secondary}>{t('Identita testu', 'Test identity')}: {testCase.fingerprint}</p>
      {testCase.verifies?.length ? <p className={styles.secondary}>{t('Deklarované požadavky', 'Declared requirements')}: {testCase.verifies.join(', ')}</p> : null}
      {testCase.recentRuns?.length ? <div className={styles.tableWrap}><table className={styles.table}><thead><tr><th>{t('Běh', 'Run')}</th><th>{t('Výsledek', 'Outcome')}</th><th>{t('Doba', 'Duration')}</th><th>{t('Kdy', 'Observed')}</th></tr></thead><tbody>{testCase.recentRuns.map((item, index) => <tr key={`${item.run.id}-${item.run.attempt}-${index}`}><td>{trustworthyTestRunUrl(item.run.url) ? <a href={trustworthyTestRunUrl(item.run.url)!} target="_blank" rel="noreferrer">{item.run.id} / {item.run.attempt}</a> : item.run.id}</td><td>{stateLabel(item.state, language)}</td><td>{item.durationMs} ms</td><td>{date(item.run.observedAt)}</td></tr>)}</tbody></table></div> : null}
    </details>
  </section>
}
