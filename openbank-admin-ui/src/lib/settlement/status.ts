// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod'

export const settlementStatusSchema = z.enum([
  'PENDING', 'DEBITED', 'CREDITED', 'BOOKED', 'REJECTED', 'REVERSED', 'CREDITED_REVERSED',
  'REVERSAL_FAILED', 'BALANCE_STATE_UNKNOWN', 'LEDGER_REVERSAL_UNSUPPORTED',
  'LEDGER_NOT_POSTED', 'LEDGER_STATE_UNKNOWN', 'LEDGER_REVERSED',
])

export const settlementDetailsSchema = z.object({
  id: z.uuid(),
  payerAccountId: z.uuid(),
  payeeAccountId: z.uuid(),
  // Never pass financial decimals through Number, parseFloat or Intl number formatting.
  amount: z.string().regex(/^-?\d{1,15}(?:\.\d{1,4})?$/),
  currency: z.string().regex(/^[A-Z]{3}$/),
  status: settlementStatusSchema,
  createdAt: z.string().datetime({ offset: true }),
  updatedAt: z.string().datetime({ offset: true }),
})
export type SettlementDetails = z.infer<typeof settlementDetailsSchema>
export type SettlementStatus = z.infer<typeof settlementStatusSchema>

export const settlementStatusCopy: Record<SettlementStatus, readonly [string, string]> = {
  PENDING: ['Převod není dokončen.', 'The transfer is not complete.'],
  DEBITED: ['Převod není dokončen.', 'The transfer is not complete.'],
  CREDITED: ['Převod není dokončen.', 'The transfer is not complete.'],
  BOOKED: ['Převod je zaúčtovaný.', 'The transfer is booked.'],
  REJECTED: ['Převod byl odmítnut.', 'The transfer was rejected.'],
  REVERSED: ['Vrácení pohybů není potvrzeno jako dokončené.', 'Completion of the reversal is not confirmed.'],
  CREDITED_REVERSED: ['Vrácení pohybů není potvrzeno jako dokončené.', 'Completion of the reversal is not confirmed.'],
  REVERSAL_FAILED: ['Vrácení selhalo. Je nutná ruční rekonciliace.', 'Reversal failed. Manual reconciliation is required.'],
  BALANCE_STATE_UNKNOWN: ['Výsledek pohybu není znám. Před dalším zásahem ověřte zůstatky a původní reference.', 'The balance movement is uncertain. Verify balances and original references before further action.'],
  LEDGER_REVERSAL_UNSUPPORTED: ['Účetní zápis nebyl zrušen. Je nutná řízená oprava.', 'The journal was not reversed. A controlled correction is required.'],
  LEDGER_NOT_POSTED: ['Při kontrole nebyl nalezen účetní zápis. Převod není uzavřen.', 'No journal was found during the check. The transfer is not closed.'],
  LEDGER_STATE_UNKNOWN: ['Stav účetního zápisu není znám. Je nutná rekonciliace.', 'The journal state is uncertain. Reconciliation is required.'],
  LEDGER_REVERSED: ['Historický stav nepotvrzuje skutečné zrušení zápisu. Vyžaduje kontrolu.', 'This legacy state does not prove a real reversal. Verification is required.'],
}
