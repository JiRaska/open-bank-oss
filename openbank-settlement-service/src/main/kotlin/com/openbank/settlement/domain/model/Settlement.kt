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
     * A settlement journal **exists in the general ledger** and was not reversed, because
     * settlement-service cannot reverse a journal (see
     * `SettlementActivitiesImpl.reverseBookToLedger`). The GL owes a manual correcting entry.
     * Distinct from [LEDGER_REVERSED], which is what the old stub wrote while doing nothing.
     */
    LEDGER_REVERSAL_UNSUPPORTED,

    /**
     * The ledger compensation ran, asked the ledger whether a journal exists for this settlement,
     * and was told **none does** — so there is nothing to reverse and the general ledger is clean.
     *
     * This is a *no-op*, not a success and not an unsupported reversal, and it has its own value
     * for the same reason [REVERSAL_FAILED] does. Folding it into either neighbour would be the
     * `PushResult.skipped()` shape once more: `LEDGER_REVERSAL_UNSUPPORTED` would summon an
     * operator to correct a GL entry that was never posted, and a plain success would claim a
     * reversal that never happened. It is transient by construction — the workflow rejects the
     * settlement immediately afterwards — so a row resting here means the workflow died.
     */
    LEDGER_NOT_POSTED,

    /**
     * The ledger compensation could **not establish** whether a journal exists — the lookup itself
     * failed (ledger unreachable, or answering an error). The truthful answer is "unknown", and
     * that is a third fact, not a rounding of the other two: reporting
     * [LEDGER_REVERSAL_UNSUPPORTED] would send an operator to correct an entry that may not exist,
     * and reporting [LEDGER_NOT_POSTED] would assert a clean GL nobody checked.
     *
     * Recorded with a **retryable** failure, unlike [LEDGER_REVERSAL_UNSUPPORTED]: an unreachable
     * ledger is the one case here a retry can genuinely resolve.
     */
    LEDGER_STATE_UNKNOWN,

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
