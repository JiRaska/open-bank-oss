// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { z } from 'zod'

/** settlement-service `SettlementResponse.status` — the stable v1 vocabulary (openapi.yaml). */
export const settlementStatusSchema = z.enum([
  'PENDING', 'DEBITED', 'CREDITED', 'BOOKED', 'REJECTED', 'REVERSED', 'CREDITED_REVERSED',
  'REVERSAL_FAILED', 'LEDGER_REVERSAL_UNSUPPORTED', 'LEDGER_NOT_POSTED', 'LEDGER_STATE_UNKNOWN',
  'LEDGER_REVERSED',
])

export const settlementDetailsSchema = z.object({
  id: z.uuid(),
  payerAccountId: z.uuid(),
  payeeAccountId: z.uuid(),
  // Exact decimal text. Never pass it through Number, parseFloat or Intl number formatting.
  amount: z.string().regex(/^-?\d{1,15}(?:\.\d{1,4})?$/),
  currency: z.string().regex(/^[A-Z]{3}$/),
  status: settlementStatusSchema,
  createdAt: z.iso.datetime({ offset: true }),
  updatedAt: z.iso.datetime({ offset: true }),
  recoveryRequired: z.boolean(),
  recoveryReason: z.string().nullable(),
})
export type SettlementDetails = z.infer<typeof settlementDetailsSchema>
export type SettlementStatus = z.infer<typeof settlementStatusSchema>

/** Czech, English. Only BOOKED reads as complete; every compensation outcome says what it needs. */
export const settlementStatusCopy: Record<SettlementStatus, readonly [string, string]> = {
  PENDING: ['Převod není dokončen.', 'The transfer is not complete.'],
  DEBITED: ['Převod není dokončen.', 'The transfer is not complete.'],
  CREDITED: ['Převod není dokončen.', 'The transfer is not complete.'],
  BOOKED: ['Převod je zaúčtovaný.', 'The transfer is booked.'],
  REJECTED: ['Převod byl odmítnut a všechny pohyby byly vráceny.', 'The transfer was rejected and every movement was returned.'],
  REVERSED: ['Odepsání z účtu plátce bylo vráceno.', "The payer's debit was returned."],
  CREDITED_REVERSED: ['Připsání na účet příjemce bylo vráceno.', "The payee's credit was returned."],
  REVERSAL_FAILED: ['Vrácení selhalo, peníze zůstávají převedené. Je nutná ruční rekonciliace.', 'The reversal failed and the money is still moved. Manual reconciliation is required.'],
  LEDGER_REVERSAL_UNSUPPORTED: ['Účetní zápis nebyl zrušen. Je nutná řízená oprava.', 'The journal was not reversed. A controlled correcting entry is required.'],
  LEDGER_NOT_POSTED: ['V hlavní knize nebyl nalezen žádný zápis, oprava není potřeba.', 'The ledger holds no journal for this transfer, so no correction is owed.'],
  LEDGER_STATE_UNKNOWN: ['Stav účetního zápisu není znám. Je nutná rekonciliace.', 'The journal state is unknown. Reconciliation is required.'],
  LEDGER_REVERSED: ['Historický stav nepotvrzuje skutečné zrušení zápisu. Vyžaduje kontrolu.', 'This legacy state does not prove a real reversal. Verification is required.'],
}

export const recoveryCopy: readonly [string, string] = [
  'Výsledek pohybu na účtu není znám. Před dalším zásahem ověřte zůstatky a původní reference.',
  'A balance movement may have committed without a reliable result. Verify balances and original references before further action.',
]
