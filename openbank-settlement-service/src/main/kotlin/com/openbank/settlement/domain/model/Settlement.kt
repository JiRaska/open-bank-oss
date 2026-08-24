// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Settlement saga states.
 *
 * The three compensation outcomes below are deliberately **four** values, not one (issue #6037).
 * Until this was fixed, `reverseDebit`/`reverseCredit`/`reverseBookToLedger` wrote
 * `REVERSED`/`CREDITED_REVERSED`/`LEDGER_REVERSED` and moved no money at all, so the one thing an
 * operator could observe — the status column — asserted an unwind that had not happened. That is
 * the same shape as `PushResult.skipped()` carrying `success = true`: an outcome structurally
 * unable to report the thing that went wrong. A compensation that was *attempted and refused*, and
 * one that is *not implemented*, are different facts from a compensation that *worked*, and each
 * now has its own value.
 */
enum class SettlementStatus {
    PENDING,
    DEBITED,
    CREDITED,
    BOOKED,
    REJECTED,

    /** Payer debit reversed: balance-service accepted (or had already applied) the counter-credit. */
    REVERSED,

    /** Payee credit reversed: balance-service accepted (or had already applied) the counter-debit. */
    CREDITED_REVERSED,

    /**
     * A balance reversal was **attempted and did not succeed** — the money is still moved.
     * The common cause is a payee who has spent the credited funds, which balance-service refuses
     * with 422 and which no retry resolves. Recovery is a collections/dispute process.
     */
    REVERSAL_FAILED,

    /**
     * The ledger booking was **not** reversed because settlement-service cannot reverse a journal
     * (see `SettlementActivitiesImpl.reverseBookToLedger`). Distinct from [LEDGER_REVERSED], which
     * is what the old stub wrote while doing nothing.
     */
    LEDGER_REVERSAL_UNSUPPORTED,

    /**
     * Deprecated and **never written** since #6037. Retained so the value keeps its meaning for any
     * consumer that pinned the old enum; the code path that produced it only ever updated this
     * column and never reversed anything in the general ledger.
     */
    @Deprecated("Never produced; the stub that wrote it reversed nothing. See LEDGER_REVERSAL_UNSUPPORTED.")
    LEDGER_REVERSED,
}

data class Settlement(
    val id: UUID,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val status: SettlementStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)
