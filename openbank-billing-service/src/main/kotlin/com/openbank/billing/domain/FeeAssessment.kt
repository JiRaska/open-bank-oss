// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.domain

import com.openbank.libs.product.WaiveReason
import java.math.BigDecimal
import java.time.Instant

/**
 * A fee definition as the billing service needs it for assessment — the subset of the
 * product-catalog `Fee` that drives charging. (Billing keeps its own type rather than
 * depending on the catalog's domain, per service-per-bounded-context.)
 */
data class BillableFee(
    val feeId: String,
    val name: String,
    val type: String,
    val amount: BigDecimal,
    val currency: String,
    val waivable: Boolean,
    val waiveCondition: String?,
)

/**
 * The outcome of assessing one [BillableFee] for one account in one billing cycle (ADR-0143).
 * The [idempotencyKey] carries the **feeId** dimension so several fees on the same
 * account/cycle/currency never collapse to one key (which would silently under-charge).
 */
data class AssessedFee(
    val cycleId: String,
    val accountId: String,
    val feeId: String,
    val name: String,
    val currency: String,
    val chargedAmount: BigDecimal,
    val waived: Boolean,
    val reason: WaiveReason,
    val postingStatus: PostingStatus = PostingStatus.NOT_APPLICABLE,
    val journalId: java.util.UUID? = null,
    val reversalJournalId: java.util.UUID? = null,
    val reversalReason: String? = null,
    /**
     * When the ledger confirmed this charge as [PostingStatus.POSTED] (ADR-0248). `null` until
     * then. Backs the PAD Art. 5 annual fee-summary aggregation's year filter — the year a fee is
     * counted in is the year it was actually posted, not the (potentially earlier) cycle/assessment
     * date, matching `AssessedFeeEntity.postedAt` one-for-one.
     */
    val postedAt: Instant? = null,
) {
    val idempotencyKey: String get() = "fee-$cycleId-$accountId-$feeId-$currency"

    /** Distinct from [idempotencyKey] so the ledger never collapses a reversal replay with the charge. */
    val reversalIdempotencyKey: String get() = "fee-reversal-$cycleId-$accountId-$feeId-$currency"
}

/**
 * A request to post a single fee as a balanced ledger journal (ADR-0143). The actual posting
 * (ledger REST client + outbox) is phase 2c; this is the command the posting leg will carry.
 */
data class FeeJournalCommand(
    val idempotencyKey: String,
    val cycleId: String,
    val accountId: String,
    val feeId: String,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
)

/**
 * A request to post the compensating (reversing) journal for an already-POSTED [AssessedFee]
 * (ADR-0143 phase 2e). [idempotencyKey] is distinct from the original charge's
 * [AssessedFee.idempotencyKey] (`"fee-reversal-{cycleId}-{accountId}-{feeId}-{currency}"`) so the
 * ledger's own idempotency store never collapses a reversal into a replay of the original charge.
 * [originalIdempotencyKey] threads back to the [AssessedFee] being reversed.
 */
data class FeeReversalCommand(
    val idempotencyKey: String,
    val originalIdempotencyKey: String,
    val cycleId: String,
    val accountId: String,
    val feeId: String,
    val amount: BigDecimal,
    val currency: String,
    val reason: String,
)

/**
 * The lifecycle of one [AssessedFee]'s ledger posting (ADR-0143 phase 2c/2e). A waived or
 * zero-amount fee never posts a journal and stays [NOT_APPLICABLE]. A chargeable fee starts
 * [PENDING] the moment its outbox row commits atomically with the assessment (ADR-0143 step 2),
 * moves to [POSTED] once the outbox dispatcher's ledger call succeeds (journalId recorded), or
 * [FAILED] once the outbox row is exhausted (terminal DEAD, `OutboxFailurePolicy`) without ever
 * reaching the ledger — an operator-visible signal distinct from "still in flight". A POSTED fee
 * that is later found to be wrongly charged moves to [REVERSAL_PENDING] the moment its
 * compensating-journal outbox row commits (ADR-0143 phase 2e), then to [REVERSED] once that
 * journal is confirmed posted — terminal, and itself never reversible again.
 */
enum class PostingStatus { NOT_APPLICABLE, PENDING, POSTED, FAILED, REVERSAL_PENDING, REVERSED }

/**
 * The result of assessing all of an account's fees for a cycle. [skipped] is set (with
 * [skipReason]) when the account's [com.openbank.libs.product.FeeContext] could not be
 * resolved — the billing service refuses to charge on absent inputs (fail-closed, ADR-0143).
 */
data class BillingAssessment(
    val cycleId: String,
    val accountId: String,
    val currency: String,
    val skipped: Boolean,
    val skipReason: String?,
    val assessedFees: List<AssessedFee>,
) {
    /** Balanced fee journals to post — only the chargeable (non-waived, non-zero) fees. */
    fun journalCommands(): List<FeeJournalCommand> = assessedFees
        .filter { it.chargedAmount > BigDecimal.ZERO }
        .map { fee ->
            FeeJournalCommand(
                idempotencyKey = fee.idempotencyKey,
                cycleId = fee.cycleId,
                accountId = fee.accountId,
                feeId = fee.feeId,
                amount = fee.chargedAmount,
                currency = fee.currency,
                description = "Fee charge: ${fee.name}",
            )
        }
}
