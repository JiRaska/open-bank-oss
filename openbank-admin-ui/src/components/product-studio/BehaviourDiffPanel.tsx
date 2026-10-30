// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
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
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const task = window.setTimeout(() => {
      setPortfolio(samplePortfolio(behaviour?.currency ?? 'EUR', `${today.slice(0, 4)}-01-01`))
      setVerdict(null)
      setMessage('')
    }, 0)
    return () => window.clearTimeout(task)
  }, [revision?.id, revision?.revision]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!revision || !behaviour) return null

  const run = async () => {
    let parsed: unknown
    try { parsed = JSON.parse(portfolio) } catch { setMessage(t('Portfolio není validní JSON', 'Portfolio is not valid JSON')); return }
    setBusy(true); setMessage(''); setVerdict(null)
    try {
      const result = await catalogV2Operation('behaviourDiffRevisionV2', {
        pathParameters: { offeringId: revision.offeringId, revisionId: revision.id },
        body: { portfolio: parsed as Record<string, unknown>, from, to },
        signal: AbortSignal.timeout(BACKTEST_TIMEOUT_MS),
      })
      setVerdict(behaviourDiffVerdict(result.diff))
    } catch (error) {
      if (error instanceof CatalogV2Error && error.status === 404) {
        setMessage(t('Není publikovaná revize, se kterou by šlo porovnat.', 'There is no published revision to compare against.'))
      } else if (error instanceof CatalogV2Error && error.status === 400) {
        setMessage(t('Backtest odmítnut:', 'Backtest refused:') + ` ${error.message}`)
      } else {
        setMessage(t('Backtest se nepodařilo spustit.', 'The backtest could not be run.'))
      }
    } finally { setBusy(false) }
  }

  return (
    <section aria-label={t('Behaviorální diff', 'Behavioural diff')} data-testid="behaviour-diff-panel">
      <h3><Activity size={14} aria-hidden /> {t('Behaviorální diff proti publikované revizi', 'Behavioural diff against the published revision')}</h3>
      <p>{t('Stejný výpočet jako obp diff --behavioural: portfolio se přehraje pod oběma revizemi.',
        'The same computation as obp diff --behavioural: the portfolio is replayed under both revisions.')}</p>
      <label>{t('Portfolio (JSON)', 'Portfolio (JSON)')}
        <textarea className="input" aria-label={t('Portfolio (JSON)', 'Portfolio (JSON)')} value={portfolio} onChange={e => setPortfolio(e.target.value)} rows={8} spellCheck={false} />
      </label>
      <label>{t('Od', 'From')} <input className="input" type="date" aria-label={t('Od', 'From')} value={from} onChange={e => setFrom(e.target.value)} /></label>
      <label>{t('Do', 'To')} <input className="input" type="date" aria-label={t('Do', 'To')} value={to} onChange={e => setTo(e.target.value)} /></label>
      <button type="button" className="btn btn-secondary" onClick={() => void run()} disabled={busy || !portfolio.trim()}>
        {busy ? t('Počítám…', 'Running…') : t('Spustit backtest', 'Run backtest')}
      </button>
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
  return (
    <div data-testid="behaviour-diff-changed">
      <p>{t('Změněné účty', 'Accounts changed')}: <b>{diff.changedAccounts} / {diff.portfolio.accounts}</b>
        {' · '}{t('Dopad na klienty (netto)', 'Customer outcome (net)')}: <b>{diff.aggregate.outcome.a} → {diff.aggregate.outcome.b} ({diff.aggregate.outcome.delta})</b></p>
      <p>{t('Změna znaménka', 'Sign changed')}: <b>{diff.signChanged.length}</b>
        {' · '}{t('Nad prahem', 'Over threshold')} {diff.materialityThreshold}: <b>{diff.thresholdCrossed.length}</b></p>
      {hashes}
      <table>
        <thead><tr><th>ruleId</th><th>{t('Účetní třída', 'Accounting class')}</th><th>{t('Netto A', 'Net A')}</th><th>{t('Netto B', 'Net B')}</th><th>Δ</th></tr></thead>
        <tbody>{diff.aggregate.posted.map(row => <tr key={`${row.ruleId}:${row.accountingClass}`}>
          <td><code>{row.ruleId}</code></td><td>{row.accountingClass}</td><td>{row.a.net}</td><td>{row.b.net}</td><td>{row.deltaNet}</td>
        </tr>)}</tbody>
      </table>
    </div>
  )
}
