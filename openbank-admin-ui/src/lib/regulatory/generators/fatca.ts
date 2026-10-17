// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// FATCA, assembled by tax-reporting-service (ADR-0340).

import {
  ReportSourceError, activeAccounts, amount, completedTransactions, count, currencyCells, gap, isPayment, malformed, mapLimit, num,
  offsetRoster, round2, rows, str, sumByCurrency, template, text, within,
  type GeneratedCell, type GeneratedTemplate, type GenerationConfig, type ReportSourceClient, type Row,
} from '@/lib/regulatory/generators/core'

const TAX_CATALOGUE = 'cz-tax-information-exchange'

/** FATCA — assembled by tax-reporting-service (ADR-0340); this reads or assembles the return. */
export async function fatca(client: ReportSourceClient, period: string, config: GenerationConfig): Promise<GeneratedTemplate[]> {
  const service = 'tax-reporting-service'
  const find = async () => rows(service, await client.get(service, '/api/v1/statutory-returns'))
    .filter(r => str(r, 'catalogueId') === TAX_CATALOGUE && str(r, 'returnCode') === 'FATCA' && str(r, 'period') === period && str(r, 'entityId') === config.taxEntityId)
    .sort((a, b) => (num(b, 'revision') ?? 0) - (num(a, 'revision') ?? 0))[0]

  let ret = await find()
  if (!ret) {
    const assembled = await client.request(service, 'POST', '/api/v1/statutory-returns/assemble', {
      body: { catalogueId: TAX_CATALOGUE, returnCode: 'FATCA', entityId: config.taxEntityId, period },
    })
    if (assembled.status === 200 || assembled.status === 201) ret = assembled.body as Row
    else if (assembled.status === 409) ret = await find()
    else if (assembled.status === 503) throw new ReportSourceError('error', service, 'no data source bound for the FATCA return (503)')
    else throw new ReportSourceError(assembled.status === 401 || assembled.status === 403 ? 'unauthorized' : 'error', service, `assemble HTTP ${assembled.status}`)
  }
  if (!ret) malformed(service, 'FATCA return neither listed nor assembled')
  const id = str(ret, 'id') ?? malformed(service, 'return without id')
  const lines = rows(service, await client.get(service, `/api/v1/statutory-returns/${id}/lines`), 'lines')
  const datapoints = (ret.datapoints && typeof ret.datapoints === 'object' ? ret.datapoints : {}) as Row

  const header = template('FATCA-00', period, [
    text('r0010', 'c0010', 'Revize výkazu / stav', `${num(ret, 'revision') ?? '—'} / ${str(ret, 'status') ?? '—'}`),
    text('r0020', 'c0010', 'Otisk obsahu (SHA-256)', str(ret, 'contentHash')),
    text('r0030', 'c0010', 'Termín podání', str(ret, 'dueDate')),
    count('r0040', 'Počet US osob', num(datapoints, 'reportable_persons') ?? 0),
    count('r0050', 'Počet hlášených účtů', num(datapoints, 'reportable_accounts') ?? 0),
    { rowRef: 'r0060', colRef: 'c0010', value: round2(num(datapoints, 'total_period_end_balance') ?? 0), currency: 'CZK', label: 'Zůstatky celkem (součet napříč měnami — rozpad v řádcích)' },
    { rowRef: 'r0070', colRef: 'c0010', value: round2(num(datapoints, 'total_interest_credited') ?? 0), currency: 'CZK', label: 'Připsané úroky celkem (součet napříč měnami — rozpad v řádcích)' },
  ])
  const detail = lines.map((line, i) => {
    const n = String(i + 1).padStart(3, '0')
    const currency = str(line, 'currency') ?? 'CZK'
    const balance = num(line, 'period_end_balance')
    const interest = num(line, 'interest_credited')
    return [
      text(`l${n}`, 'name', `#${n} · Jméno US osoby`, str(line, 'holder_name')),
      text(`l${n}`, 'party', `#${n} · Party`, str(line, 'party_ref')),
      text(`l${n}`, 'account', `#${n} · Účet`, str(line, 'account_ref')),
      text(`l${n}`, 'tin', `#${n} · TIN (US daňové číslo)`, str(line, 'tin'), 'TIN není v daňovém profilu klienta uveden'),
      balance === null ? gap(`l${n}`, `#${n} · Zůstatek účtu`, currency, 'zůstatek chybí') : { rowRef: `l${n}`, colRef: 'balance', value: round2(balance), currency, label: `#${n} · Zůstatek účtu` },
      interest === null ? gap(`l${n}`, `#${n} · Příjmy z US zdrojů (úroky)`, currency, 'úrok chybí') : { rowRef: `l${n}`, colRef: 'income', value: round2(interest), currency, label: `#${n} · Příjmy (připsané úroky)` },
    ]
  })
  return [header, template('FATCA-01', period, detail.length ? detail.flat() : [text('l000', 'none', 'Hlášené účty', 'Za období není žádný účet US osoby')])]
}
