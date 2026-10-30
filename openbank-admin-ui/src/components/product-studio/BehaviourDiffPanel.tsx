// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef, useState } from 'react'
import { Activity } from 'lucide-react'
import { CatalogV2Error, catalogV2Operation, type ProductRevision } from '@/lib/product-catalog-v2'
import { behaviourDiffVerdict, samplePortfolio, type BehaviourDiffVerdict } from '@/lib/behaviour-diff'

/** A backtest replays every account-day; give it longer than the default catalog read timeout. */
const BACKTEST_TIMEOUT_MS = 60_000

/**
 * ADR-0352 §11.4 / phase 5b: the behavioural diff shown next to the role approval buttons, so nobody
 * approves a reprice without seeing which accounts move by how much. The catalog runs the shared
 * ProductBehaviourDiff (published revision vs this one) and this panel renders its document as-is.
 * It is read-only: it records nothing, and approving stays the approval panel's job.
 */
export function BehaviourDiffPanel({ revision, t }: {
  revision: ProductRevision | undefined
  t: (cs: string, en: string) => string
}) {
  const behaviour = revision?.content.behaviour as { currency?: string } | null | undefined
  const today = new Date().toISOString().slice(0, 10)
  const [portfolio, setPortfolio] = useState('')
  const [from, setFrom] = useState(`${today.slice(0, 4)}-01-01`)
  const [to, setTo] = useState(today)
  const [verdict, setVerdict] = useState<BehaviourDiffVerdict | null>(null)
  const [evidence, setEvidence] = useState<{ approval: boolean; golden: string | null } | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  // The revision a result belongs to. A backtest still running when the operator switches revision must
  // never land under the new one: an approver would read another revision's diff as this one's.
  const currentKey = useRef('')
  const revisionKey = revision ? `${revision.id}:${revision.revision}` : ''

  useEffect(() => {
    currentKey.current = revisionKey
    const task = window.setTimeout(() => {
      setPortfolio(samplePortfolio(behaviour?.currency ?? 'EUR', `${today.slice(0, 4)}-01-01`))
      setVerdict(null)
      setEvidence(null)
      setMessage('')
      setBusy(false)
    }, 0)
    return () => window.clearTimeout(task)
  }, [revisionKey]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!revision || !behaviour) return null

  /**
   * `golden`: no portfolio, so the server replays the product's own golden portfolio over its default
   * window — the only run that counts as the approver having seen the change (ADR-0352 §7). `custom`:
   * the portfolio and dates below, an exploration the server never records as evidence.
   */
  const run = async (mode: 'golden' | 'custom') => {
    let parsed: unknown = undefined
    if (mode === 'custom') {
      try { parsed = JSON.parse(portfolio) } catch { setMessage(t('Portfolio není validní JSON', 'Portfolio is not valid JSON')); return }
    }
    setBusy(true); setMessage(''); setVerdict(null); setEvidence(null)
    const requestedFor = revisionKey
    const stale = () => currentKey.current !== requestedFor
    try {
      const result = await catalogV2Operation('behaviourDiffRevisionV2', {
        pathParameters: { offeringId: revision.offeringId, revisionId: revision.id },
        body: mode === 'golden' ? {} : { portfolio: parsed as Record<string, unknown>, from, to },
        signal: AbortSignal.timeout(BACKTEST_TIMEOUT_MS),
      })
      if (stale()) return
      setVerdict(behaviourDiffVerdict(result.diff))
      setEvidence({ approval: result.approvalEvidence === true, golden: result.goldenPortfolio ?? null })
    } catch (error) {
      if (stale()) return
      if (error instanceof CatalogV2Error && error.status === 404) {
        setMessage(t('Není publikovaná revize, se kterou by šlo porovnat.', 'There is no published revision to compare against.'))
      } else if (error instanceof CatalogV2Error && error.status === 409) {
        setMessage(t('Pro tento produkt není definované schvalovací portfolio.', 'No approval portfolio is defined for this product.'))
      } else if (error instanceof CatalogV2Error && error.status === 400) {
        setMessage(t('Backtest odmítnut:', 'Backtest refused:') + ` ${error.message}`)
      } else {
        setMessage(t('Backtest se nepodařilo spustit.', 'The backtest could not be run.'))
      }
    } finally { if (!stale()) setBusy(false) }
  }

  return (
    <section aria-label={t('Behaviorální diff', 'Behavioural diff')} data-testid="behaviour-diff-panel">
      <h3><Activity size={14} aria-hidden /> {t('Behaviorální diff proti publikované revizi', 'Behavioural diff against the published revision')}</h3>
      <p>{t('Stejný výpočet jako obp diff --behavioural: portfolio se přehraje pod oběma revizemi. Schválení odemyká jen backtest nad zlatým portfoliem produktu, které určuje server.',
        'The same computation as obp diff --behavioural: the portfolio is replayed under both revisions. Only a backtest over the product\'s server-defined golden portfolio unlocks approval.')}</p>
      <button type="button" className="btn btn-primary" onClick={() => void run('golden')} disabled={busy}>
        {busy ? t('Počítám…', 'Running…') : t('Spustit schvalovací backtest', 'Run approval backtest')}
      </button>
      <details>
        <summary>{t('Prozkoumat vlastní portfolio (neodemyká schválení)', 'Explore your own portfolio (does not unlock approval)')}</summary>
        <label>{t('Portfolio (JSON)', 'Portfolio (JSON)')}
          <textarea className="input" aria-label={t('Portfolio (JSON)', 'Portfolio (JSON)')} value={portfolio} onChange={e => setPortfolio(e.target.value)} rows={8} spellCheck={false} />
        </label>
        <label>{t('Od', 'From')} <input className="input" type="date" aria-label={t('Od', 'From')} value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>{t('Do', 'To')} <input className="input" type="date" aria-label={t('Do', 'To')} value={to} onChange={e => setTo(e.target.value)} /></label>
        <button type="button" className="btn btn-secondary" onClick={() => void run('custom')} disabled={busy || !portfolio.trim()}>
          {t('Prozkoumat s tímto portfoliem', 'Explore with this portfolio')}
        </button>
      </details>
      {evidence && <p data-testid="behaviour-diff-evidence">{evidence.approval
        ? t('Zaznamenáno jako váš doklad pro schválení (zlaté portfolio ', 'Recorded as your approval evidence (golden portfolio ') + `${evidence.golden ?? ''})`
        : t('Jen průzkum — neodemyká schválení.', 'Exploration only — this does not unlock approval.')}</p>}
      {message && <p role="status">{message}</p>}
      {verdict && <DiffView verdict={verdict} t={t} />}
    </section>
  )
}

function DiffView({ verdict, t }: { verdict: BehaviourDiffVerdict; t: (cs: string, en: string) => string }) {
  if (verdict.kind === 'unknown-schema') {
    return <p role="alert" data-testid="behaviour-diff-error">{t('Neznámé schéma diffu; výsledek nelze zobrazit.', 'Unknown diff schema; the result cannot be shown.')}</p>
  }
  if (verdict.kind === 'invalid') {
    return <p role="alert" data-testid="behaviour-diff-error">{t('Přehrání nezachovalo peníze. Jde o vadu interpretu, ne o výsledek — neschvalujte.', 'A replay did not conserve money. This is an interpreter defect, not a result — do not approve.')}</p>
  }
  const { diff } = verdict
  const hashes = <small>{diff.revisionA.revisionHash.slice(0, 12)}… → {diff.revisionB.revisionHash.slice(0, 12)}… · {diff.range.from} – {diff.range.to}</small>
  if (verdict.kind === 'identical') {
    return <div data-testid="behaviour-diff-identical"><p>{t('Beze změny chování na tomto portfoliu.', 'No behavioural change on this portfolio.')}</p>{hashes}</div>
  }
  const ids = (list: string[]) => list.length ? list.join(', ') : t('žádné', 'none')
  return (
    <div data-testid="behaviour-diff-changed">
      <p>{t('Změněné účty', 'Accounts changed')}: <b>{diff.changedAccounts} / {diff.portfolio.accounts}</b>
        {' · '}{t('Dopad na klienty (netto)', 'Customer outcome (net)')}: <b>{diff.aggregate.outcome.a} → {diff.aggregate.outcome.b} ({diff.aggregate.outcome.delta})</b></p>
      <p data-testid="behaviour-diff-sign-changed">{t('Změna znaménka', 'Sign changed')} ({diff.signChanged.length}): <b>{ids(diff.signChanged)}</b></p>
      <p data-testid="behaviour-diff-threshold">{t('Nad prahem', 'Over threshold')} {diff.materialityThreshold} ({diff.thresholdCrossed.length}): <b>{ids(diff.thresholdCrossed)}</b></p>
      {hashes}
      <table aria-label={t('Změněné účty', 'Changed accounts')} data-testid="behaviour-diff-accounts">
        <thead><tr>
          <th>{t('Účet', 'Account')}</th><th>{t('Dopad A', 'Outcome A')}</th><th>{t('Dopad B', 'Outcome B')}</th><th>Δ</th>
          <th>{t('Zůstatek A', 'Balance A')}</th><th>{t('Zůstatek B', 'Balance B')}</th><th>{t('Odmítnuto A/B', 'Rejected A/B')}</th>
        </tr></thead>
        <tbody>{diff.accounts.map(row => <tr key={row.account}>
          <td><code>{row.account}</code>{row.signChanged && <> · {t('znaménko', 'sign')}</>}{row.thresholdCrossed && <> · {t('práh', 'threshold')}</>}</td>
          <td>{row.outcome.a}</td><td>{row.outcome.b}</td><td>{row.outcome.delta}</td>
          <td>{row.finalBalance.a}</td><td>{row.finalBalance.b}</td><td>{row.rejectedPostings.a} / {row.rejectedPostings.b}</td>
        </tr>)}</tbody>
      </table>
      {diff.accounts.filter(row => row.posted.length > 0 || row.instructionCounts.length > 0).map(row =>
        <details key={row.account} data-testid={`behaviour-diff-account-${row.account}`}>
          <summary><code>{row.account}</code>: {t('které pravidlo účet posunulo', 'which rule moved this account')}</summary>
          <ul>
            {row.posted.map(p => <li key={`p:${p.ruleId}:${p.accountingClass}`}><code>{p.ruleId}</code> {p.accountingClass}: {p.a.net} → {p.b.net} (Δ {p.deltaNet})</li>)}
            {row.instructionCounts.map(c => <li key={`c:${c.ruleId}:${c.kind}`}><code>{c.ruleId}</code> {c.kind}: {c.a} → {c.b} (Δ {c.delta})</li>)}
          </ul>
        </details>)}
      <table aria-label={t('Zaúčtování podle pravidla', 'Postings by rule')} data-testid="behaviour-diff-posted">
        <thead><tr><th>ruleId</th><th>{t('Účetní třída', 'Accounting class')}</th><th>{t('Netto A', 'Net A')}</th><th>{t('Netto B', 'Net B')}</th><th>Δ</th></tr></thead>
        <tbody>{diff.aggregate.posted.map(row => <tr key={`${row.ruleId}:${row.accountingClass}`}>
          <td><code>{row.ruleId}</code></td><td>{row.accountingClass}</td><td>{row.a.net}</td><td>{row.b.net}</td><td>{row.deltaNet}</td>
        </tr>)}</tbody>
      </table>
      <table aria-label={t('Počty instrukcí podle pravidla', 'Instruction counts by rule')} data-testid="behaviour-diff-counts">
        <thead><tr><th>ruleId</th><th>{t('Druh', 'Kind')}</th><th>A</th><th>B</th><th>Δ</th></tr></thead>
        <tbody>{diff.aggregate.instructionCounts.map(row => <tr key={`${row.ruleId}:${row.kind}`}>
          <td><code>{row.ruleId}</code></td><td>{row.kind}</td><td>{row.a}</td><td>{row.b}</td><td>{row.delta}</td>
        </tr>)}</tbody>
      </table>
    </div>
  )
}
