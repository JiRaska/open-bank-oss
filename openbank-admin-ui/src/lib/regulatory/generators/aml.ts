// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// FAÚ suspicious-transaction report (zákon č. 253/2008 Sb.).

import {
  ReportSourceError, amount, count, malformed, mapLimit, num, offsetRoster, round2, str, template, text, within, type GeneratedCell, type GeneratedTemplate, type GenerationConfig, type ReportSourceClient, type Row,
} from '@/lib/regulatory/generators/core'

const SAR_STATUSES = new Set(['ESCALATED', 'BLOCKED'])

/** FAÚ SAR — one line per AML case escalated or blocked as suspicious in the period. */
export async function amlSar(client: ReportSourceClient, period: string, start: string, end: string, config: GenerationConfig): Promise<GeneratedTemplate[]> {
  const cases = (await offsetRoster(client, 'aml-service', '/api/v1/aml/cases', {}, 200))
    .filter(c => SAR_STATUSES.has(str(c, 'status') ?? '') && within(str(c, 'decidedAt') ?? str(c, 'updatedAt'), start, end))
    .sort((a, b) => (str(a, 'screenedAt') ?? '').localeCompare(str(b, 'screenedAt') ?? ''))

  const amounts = await mapLimit(cases, 8, async (c): Promise<{ value: number; currency: string } | null> => {
    const txId = str(c, 'transactionId')
    if (!txId) return null
    const res = await client.request('transaction-service', 'GET', `/api/v1/transactions/${txId}`)
    if (res.status !== 200) throw new ReportSourceError(res.status === 401 || res.status === 403 ? 'unauthorized' : 'error', 'transaction-service', `HTTP ${res.status} for transaction ${txId}`)
    const value = num(res.body as Row, 'amount')
    const currency = str(res.body as Row, 'currencyCode')
    if (value === null || !currency) malformed('transaction-service', 'transaction without amount/currencyCode')
    return { value, currency }
  })

  const header = template('SAR-00', period, [
    count('r0010', 'Počet hlášených podezřelých obchodů', cases.length),
    text('r0020', 'c0010', 'Kontaktní osoba MLRO', config.mlroContact ?? null,
      'kontaktní osoba MLRO není nakonfigurována (REGULATORY_MLRO_CONTACT)'),
  ])
  const lines = cases.map((c, i) => {
    const n = String(i + 1).padStart(3, '0')
    const tx = amounts[i]
    const cells: GeneratedCell[] = [
      text(`l${n}`, 'case', `#${n} · ID případu`, str(c, 'id')),
      text(`l${n}`, 'customer', `#${n} · ID zákazníka`, str(c, 'customerReference') ?? str(c, 'partyId')),
      text(`l${n}`, 'party', `#${n} · Party ID`, str(c, 'partyId')),
      text(`l${n}`, 'alert', `#${n} · Popis podezřelé aktivity`, [str(c, 'alertCode'), str(c, 'alertDetail'), str(c, 'matchedEntity')].filter(Boolean).join(' — ') || null),
      text(`l${n}`, 'risk', `#${n} · Riziko / stav`, `${str(c, 'riskLevel') ?? '—'} / ${str(c, 'status') ?? '—'}`),
      tx
        ? { rowRef: `l${n}`, colRef: 'amount', value: round2(tx.value), currency: tx.currency, label: `#${n} · Výše transakce` }
        : text(`l${n}`, 'amount', `#${n} · Výše transakce`, 'bez vazby na transakci (případ na úrovni zákazníka)'),
      text(`l${n}`, 'detected', `#${n} · Datum zjištění`, str(c, 'screenedAt')),
      text(`l${n}`, 'decided', `#${n} · Rozhodnuto`, [str(c, 'decidedAt'), str(c, 'decidedBy')].filter(Boolean).join(' · ') || null),
      text(`l${n}`, 'reason', `#${n} · Odůvodnění`, str(c, 'decisionReason')),
    ]
    return cells
  })
  return [header, template('SAR-01', period, lines.length ? lines.flat() : [text('l000', 'none', 'Hlášené případy', 'V období nebyl žádný případ eskalován ani zablokován')])]
}
