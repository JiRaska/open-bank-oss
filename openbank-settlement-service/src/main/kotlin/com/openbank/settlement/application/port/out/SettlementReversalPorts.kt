// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.port.out

import java.util.UUID

/**
 * Compensation ports for the ADR-0101 P3 settlement saga (issue #6037).
 *
 * Declared in their own file rather than alongside [DebitPort]/[CreditPort] in `SettlementPorts.kt`
 * only to stay disjoint from the two settlement PRs in flight (#6036, #5723) that both edit that
 * file; there is no design reason to keep them apart.
 *
 * **Why these are separate ports rather than a `reverse()` on [DebitPort]/[CreditPort]:** the
 * reversal of a debit is a *credit* and vice-versa, so folding them into the forward ports would
 * put a credit behind an interface named `DebitPort`. Keeping them separate also keeps the
 * reference-id namespaces visibly distinct, which is the whole idempotency mechanism (below).
 *
 * ## Idempotency
 *
 * balance-service deduplicates money movements durably on the primary key
 * `(account_id, currency, reference_id, operation)` of its `balance_movement` table
 * (`V8__balance_movement_idempotency.sql`), applying the mutation and the marker row in one
 * transaction. A duplicate is answered 200 with the already-moved balance and `applied = false`,
 * not an error. There is no `Idempotency-Key` header on that API, and balance-service does not use
 * the shared `openbank-libs-domain` [com.openbank.libs.idempotency.IdempotencyStore] — the
 * reference id *is* the idempotency key.
 *
 * Each reversal therefore carries a reference id that is a pure function of the settlement id, so a
 * Temporal activity retry re-sends the identical key and balance-service swallows it. The reversal
 * ids are deliberately in a **different namespace** from the forward ids
 * (`settlement-debit-reversal-<id>` vs `settlement-debit-<id>`): sharing the forward id would rely
 * on `operation` alone to separate the two rows, which works today but silently couples the
 * reversal's idempotency to a detail of the counterparty's primary key.
 */
interface ReverseDebitPort {
    /**
     * Return the payer's debited funds by issuing the opposite movement — a **credit** of the same
     * amount and currency to `payerAccountId`, keyed `settlement-debit-reversal-<settlementId>`.
     *
     * Throws if balance-service refuses or is unreachable; the caller must not record a successful
     * reversal in that case.
     */
    suspend fun reverseDebit(settlementId: UUID)
}

interface ReverseCreditPort {
    /**
     * Take back the payee's credited funds by issuing the opposite movement — a **debit** of the
     * same amount and currency from `payeeAccountId`, keyed
     * `settlement-credit-reversal-<settlementId>`.
     *
     * **This call can legitimately fail and no retry will fix it.** balance-service enforces
     * `booked - amount >= overdraftFloor` on every debit, so if the payee has already moved the
     * credited funds out of the account the reversal is refused (422). That is a true fact about
     * the world — funds that have left cannot be clawed back through this API — and it must be
     * recorded as such rather than smoothed into a success. See
     * [com.openbank.settlement.domain.model.SettlementStatus.REVERSAL_FAILED].
     */
    suspend fun reverseCredit(settlementId: UUID)
}
