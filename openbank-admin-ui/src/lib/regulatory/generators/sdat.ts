// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// SDAT (ČNB statistical reporting, vyhláška č. 314/2013 Sb.): PLAT payments and VKLA deposits.

import {
  ReportSourceError, activeAccounts, amount, completedTransactions, count, currencyCells, gap, isPayment, malformed, mapLimit, num,
  offsetRoster, round2, rows, str, sumByCurrency, template, text, within,
  type GeneratedCell, type GeneratedTemplate, type GenerationConfig, type ReportSourceClient, type Row,
} from '@/lib/regulatory/generators/core'

/** SDAT PLAT — payment statistics: counts and volumes by rail and currency for one month. */
export async function sdatPayments(client: ReportSourceClient, period: string, start: string, end: string): Promise<GeneratedTemplate[]> {
  const transactions = await completedTransactions(client, start, end)
  const payments = transactions.filter(isPayment)
  const byRail = (...rails: string[]) => payments.filter(tx => rails.includes(str(tx, 'rail') ?? 'UNKNOWN'))
  const sepa = byRail('SEPA_CT', 'SEPA_INST')
  const domestic = byRail('DOMESTIC')
  const crossBorder = byRail('SWIFT')
  const card = byRail('CARD')
  const internal = byRail('INTERNAL')
  const other = payments.length - sepa.length - domestic.length - crossBorder.length - card.length - internal.length

  const counts = template('PLAT-01', period, [
    count('r0010', 'Počet transakcí (dokončené, vč. poplatků a úroků)', transactions.length),
    count('r0020', 'Počet plateb celkem', payments.length),
    count('r0030', 'Počet SEPA plateb (SCT + SCT Inst)', sepa.length),
    count('r0031', 'z toho SEPA Instant', byRail('SEPA_INST').length),
    count('r0040', 'Počet domácích plateb', domestic.length),
    count('r0050', 'Počet přeshraničních plateb (SWIFT)', crossBorder.length),
    count('r0060', 'Počet karetních transakcí', card.length),
    count('r0070', 'Počet interních převodů', internal.length),
    count('r0080', 'Počet ostatních / nezařazených plateb', other),
  ])
  const czk = sumByCurrency(payments, 'amount', 'currencyCode')
  const volumes = template('PLAT-02', period, czk.size === 0
    ? [amount('r0100', 'Objem plateb (CZK)', 0, 'CZK')]
    : [
        ...currencyCells('r', 'Objem plateb', czk, 100),
        ...currencyCells('r', 'Objem SEPA plateb', sumByCurrency(sepa, 'amount', 'currencyCode'), 300),
        ...currencyCells('r', 'Objem domácích plateb', sumByCurrency(domestic, 'amount', 'currencyCode'), 500),
        ...currencyCells('r', 'Objem přeshraničních plateb', sumByCurrency(crossBorder, 'amount', 'currencyCode'), 700),
      ])
  return [counts, volumes]
}

interface AccountPosition {
  currency: string
  balance: number
  rate: number | null
}

/** SDAT VKLA — deposit statistics at month end: balances by currency, accounts, weighted rate. */
export async function sdatAccounts(client: ReportSourceClient, period: string, end: string): Promise<GeneratedTemplate[]> {
  const accounts = await activeAccounts(client)
  const positions = await mapLimit(accounts, 8, async (account): Promise<AccountPosition> => {
    const id = str(account, 'id')
    const currency = str(account, 'currencyCode')
    if (!id || !currency) malformed('account-service', 'account without id/currencyCode')
    const balance = await client.request('balance-service', 'GET', `/api/v1/balances/${id}/${currency}`, { query: { asOf: end } })
    let booked = 0
    if (balance.status === 200) {
      booked = num(balance.body as Row, 'currentBalance') ?? malformed('balance-service', 'balance without currentBalance')
    } else if (balance.status !== 404) {
      // 404 = no balance row: the account has never booked a movement — genuinely zero.
      throw new ReportSourceError(balance.status === 401 || balance.status === 403 ? 'unauthorized' : 'error', 'balance-service', `HTTP ${balance.status}`)
    }
    const rate = await client.request('interest-service', 'GET', `/api/v1/interest/accounts/${id}/effective-rate`, { query: { asOf: end } })
    let annualRate: number | null = null
    if (rate.status === 200) annualRate = num(rate.body as Row, 'annualRate')
    else if (rate.status === 204 || rate.status === 404) annualRate = 0 // no rate configuration applies: non-interest-bearing
    else throw new ReportSourceError(rate.status === 401 || rate.status === 403 ? 'unauthorized' : 'error', 'interest-service', `HTTP ${rate.status}`)
    return { currency, balance: booked, rate: annualRate }
  })

  const deposits = new Map<string, number>()
  const overdrafts = new Map<string, number>()
  for (const p of positions) {
    const target = p.balance >= 0 ? deposits : overdrafts
    target.set(p.currency, (target.get(p.currency) ?? 0) + Math.abs(p.balance))
  }
  if (!deposits.has('CZK')) deposits.set('CZK', 0)
  if (!deposits.has('EUR')) deposits.set('EUR', 0)

  const rated = positions.filter(p => p.balance > 0)
  const unresolved = rated.filter(p => p.rate === null)
  const rateCells: GeneratedCell[] = []
  const byCurrency = new Map<string, { weight: number; weighted: number }>()
  for (const p of rated) {
    if (p.rate === null) continue
    const acc = byCurrency.get(p.currency) ?? { weight: 0, weighted: 0 }
    acc.weight += p.balance
    acc.weighted += p.balance * p.rate
    byCurrency.set(p.currency, acc)
  }
  if (unresolved.length > 0) {
    rateCells.push(gap('r0300', 'Průměrná úroková sazba (vážená zůstatkem)', '%',
      `${unresolved.length} účtů má variabilní sazbu bez vyřešené hodnoty annualRate v interest-service`))
  } else {
    let i = 0
    for (const [currency, acc] of [...byCurrency.entries()].sort()) {
      rateCells.push({ rowRef: `r${String(300 + i * 10).padStart(4, '0')}`, colRef: 'c0010', value: acc.weight > 0 ? round2(acc.weighted / acc.weight * 100) : 0, currency: '%', label: `Průměrná úroková sazba vkladů v ${currency} (vážená zůstatkem)` })
      i++
    }
    if (rateCells.length === 0) rateCells.push({ rowRef: 'r0300', colRef: 'c0010', value: 0, currency: '%', label: 'Průměrná úroková sazba vkladů (vážená zůstatkem)' })
  }

  return [
    template('VKLA-01', period, [
      ...currencyCells('r', 'Celkové vklady', deposits, 100),
      ...currencyCells('r', 'Debetní zůstatky (přečerpání)', overdrafts, 200),
    ]),
    template('VKLA-02', period, [
      count('r0010', 'Počet aktivních účtů', accounts.length),
      count('r0020', 'z toho s kladným zůstatkem', rated.length),
      ...rateCells,
    ]),
  ]
}
