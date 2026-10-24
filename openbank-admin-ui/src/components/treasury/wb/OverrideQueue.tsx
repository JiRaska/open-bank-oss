// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// The four-eyes queue of cash-flow overrides awaiting a second person (ADR-0344 D4), across deals.
// Read-only here: each row opens the deal, whose cash-flow ledger carries the approve/reject
// controls and the proposer check. A deployment without the queue endpoint says so.
import { useEffect, useState } from 'react'
import Link from 'next/link'
import { z } from 'zod'
import { wbGet, wbUrl, type WbRead } from './client'
import { cashFlowOverrideSchema, type CashFlowOverride } from './types'
import { ReadFailure, Section, td, tdNum, th, useFmt } from './ui'

const queueSchema = z.array(cashFlowOverrideSchema).max(500)

export function OverrideQueue() {
  const { t, money, dateTime } = useFmt()
  const [result, setResult] = useState<WbRead<CashFlowOverride[]> | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await wbGet(wbUrl('/cash-flow-overrides', { status: 'PENDING', limit: '100' }), queueSchema)
      if (!cancelled) setResult(res)
    })()
    return () => { cancelled = true }
  }, [])

  return (
    <Section title={t('Úpravy peněžních toků čekající na schválení', 'Cash-flow overrides awaiting approval')} id="override-queue">
      {result === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
      {result && !result.ok && <ReadFailure result={result} what={t('Fronta úprav', 'The override queue')} />}
      {result?.ok && result.data.length === 0 && (
        <p style={{ fontSize: 13 }} data-testid="override-queue-empty">{t('Žádná úprava nečeká na schválení.', 'No override is awaiting approval.')}</p>
      )}
      {result?.ok && result.data.length > 0 && (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }} data-testid="override-queue">
          <thead>
            <tr>
              {[t('Obchod', 'Deal'), t('Nové datum', 'New date'), t('Nová částka', 'New amount'), t('Důvod', 'Reason'), t('Navrhl', 'Proposed by'), t('Kdy', 'When')].map(h => (
                <th key={h} scope="col" style={th}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {result.data.map(o => (
              <tr key={o.overrideId} style={{ borderTop: '1px solid var(--border)' }}>
                <td style={td}>{o.dealId ? <Link href={`/treasury/deals/${encodeURIComponent(o.dealId)}`}>{t('Otevřít obchod', 'Open deal')}</Link> : '—'}</td>
                <td style={td}>{o.paymentDate ?? '—'}</td>
                <td style={tdNum}>{o.amount == null ? '—' : money(Number(o.amount))}</td>
                <td style={td}>{o.reason}</td>
                <td style={td}>{o.proposedBy}{o.proposedByType === 'AI_AGENT' ? ` (${t('AI agent', 'AI agent')})` : ''}</td>
                <td style={td}>{dateTime(o.proposedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Section>
  )
}
