// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ECB payment statistics (ECB/2013/43).

import {
  ReportSourceError, activeAccounts, amount, completedTransactions, count, currencyCells, gap, isPayment, malformed, mapLimit, num,
  offsetRoster, round2, rows, str, sumByCurrency, template, text, within,
  type GeneratedCell, type GeneratedTemplate, type GenerationConfig, type ReportSourceClient, type Row,
} from '@/lib/regulatory/generators/core'

/** ECB payment statistics — annual: accounts, cards, cashless payments, ATMs. */
export async function ecbPayments(client: ReportSourceClient, period: string, start: string, end: string, config: GenerationConfig): Promise<GeneratedTemplate[]> {
  const [accounts, cardsRaw, transactions] = await Promise.all([
    activeAccounts(client),
    client.get('card-issuance-service', '/api/v1/cards'),
    completedTransactions(client, start, end),
  ])
  const cards = rows('card-issuance-service', Array.isArray(cardsRaw) ? cardsRaw : (cardsRaw as Row)?.cards)
  const liveCards = cards.filter(c => ['ACTIVE', 'SUSPENDED'].includes(str(c, 'status') ?? ''))
  const cashless = transactions.filter(tx => isPayment(tx) && str(tx, 'rail') !== 'CASH' && str(tx, 'rail') !== 'INTERNAL')
  const byType = (type: string) => liveCards.filter(c => (str(c, 'cardType') ?? '').toUpperCase().includes(type)).length

  return [
    template('PAYM-01', period, [
      count('r0010', 'Počet platebních účtů (aktivní)', accounts.length),
      count('r0020', 'Počet karet (aktivní a pozastavené)', liveCards.length),
      count('r0021', 'z toho debetní', byType('DEBIT')),
      count('r0022', 'z toho kreditní', byType('CREDIT')),
      config.atmCount !== undefined
        ? count('r0030', 'Počet ATM', config.atmCount)
        : gap('r0030', 'Počet ATM', 'ks', 'platforma neeviduje ATM síť; nastavte REGULATORY_ATM_COUNT'),
    ]),
    template('PAYM-02', period, [
      count('r0100', 'Počet bezhotovostních plateb', cashless.length),
      ...currencyCells('r', 'Objem bezhotovostních plateb', sumByCurrency(cashless, 'amount', 'currencyCode'), 200),
      count('r0400', 'z toho karetní transakce', cashless.filter(tx => str(tx, 'rail') === 'CARD').length),
      count('r0410', 'z toho úhrady (SEPA, domácí, SWIFT)', cashless.filter(tx => ['SEPA_CT', 'SEPA_INST', 'DOMESTIC', 'SWIFT'].includes(str(tx, 'rail') ?? '')).length),
    ]),
  ]
}
