// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.contribution

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Who paid a contribution (ADR-0334 §1). */
enum class ContributionSource { PARTICIPANT, EMPLOYER, STATE, TRANSFER_IN }

/** How the money arrived. */
enum class ContributionChannel { STANDING_ORDER, DIRECT_DEBIT, BANK_TRANSFER, EMPLOYER_BATCH, STATE_INCENTIVE }

/**
 * One credited contribution to a contract (ADR-0334 S3). Immutable and append-only; the
 * [paymentId] is the idempotency key — the same payment can never credit twice, which the store
 * enforces with a unique index rather than a read-then-write check.
 *
 * [taxYear] and [period] derive from [valueDate]: tax years are calendar years in every pack
 * shipped so far; a pack with a different tax year would add a field, not a code path.
 */
data class Contribution(
    val id: UUID,
    val contractId: UUID,
    val paymentId: String,
    val source: ContributionSource,
    val channel: ContributionChannel,
    val amount: BigDecimal,
    val currency: String,
    val valueDate: LocalDate,
    val employerPartyId: UUID? = null,
    val subscriptionOrderId: String? = null,
    val receivedAt: Instant,
) {
    init {
        require(paymentId.isNotBlank()) { "paymentId must not be blank" }
        require(amount.signum() > 0) { "a contribution must be positive" }
        require(currency.length == ISO_CURRENCY_LENGTH) { "currency must be an ISO 4217 code" }
        require((source == ContributionSource.EMPLOYER) == (employerPartyId != null)) {
            "an employer contribution, and only one, names its employer"
        }
    }

    val taxYear: Int get() = valueDate.year
    val period: YearMonth get() = YearMonth.from(valueDate)

    private companion object {
        const val ISO_CURRENCY_LENGTH = 3
    }
}

/**
 * A payment received on the provider's collection account, before it is attributed to a contract.
 * [reference] is the contract payment reference the payer quoted (the variable-symbol equivalent).
 */
data class IncomingPayment(
    val paymentId: String,
    val amount: BigDecimal,
    val currency: String,
    val valueDate: LocalDate,
    val reference: String?,
    val channel: ContributionChannel,
    val payerAccount: String? = null,
) {
    init {
        require(paymentId.isNotBlank()) { "paymentId must not be blank" }
        require(amount.signum() > 0) { "a payment must be positive" }
    }
}

enum class UnmatchedReason { NO_REFERENCE, UNKNOWN_REFERENCE, CONTRACT_NOT_ACCEPTING, CURRENCY_MISMATCH, EMPLOYER_LINE }

enum class UnmatchedStatus { OPEN, ASSIGNED, RETURNED }

/**
 * A payment no contract could be found for, parked for an operator (ADR-0334 S3). It leaves the
 * queue only by an explicit operator decision — assigned to a contract, or returned to the payer —
 * and the decision names who made it.
 */
data class UnmatchedPayment(
    val id: UUID,
    val payment: IncomingPayment,
    val reason: UnmatchedReason,
    val status: UnmatchedStatus,
    val createdAt: Instant,
    val resolvedContractId: UUID? = null,
    val resolvedBy: String? = null,
    val resolvedAt: Instant? = null,
) {
    fun assign(contractId: UUID, actor: String, at: Instant): UnmatchedPayment {
        check(status == UnmatchedStatus.OPEN) { "unmatched payment $id is already $status" }
        return copy(status = UnmatchedStatus.ASSIGNED, resolvedContractId = contractId, resolvedBy = actor, resolvedAt = at)
    }

    fun returnToPayer(actor: String, at: Instant): UnmatchedPayment {
        check(status == UnmatchedStatus.OPEN) { "unmatched payment $id is already $status" }
        return copy(status = UnmatchedStatus.RETURNED, resolvedBy = actor, resolvedAt = at)
    }
}

/** One line of an employer's bulk contribution file: which contract, how much. */
data class EmployerBatchLine(val contractReference: String, val amount: BigDecimal) {
    init {
        require(contractReference.isNotBlank()) { "line contractReference must not be blank" }
        require(amount.signum() > 0) { "line amount must be positive" }
    }
}

enum class EmployerLineOutcome { CREDITED, DUPLICATE, UNMATCHED }

data class EmployerLineResult(val lineNo: Int, val contractReference: String, val amount: BigDecimal, val outcome: EmployerLineOutcome)

/**
 * An employer's bulk file covering one payment (ADR-0334 S3). The lines must add up to the
 * payment — a file that does not reconcile to the money is refused whole, because crediting part
 * of it would leave the remainder attributable to nobody.
 */
data class EmployerBatch(
    val employerPartyId: UUID,
    val payment: IncomingPayment,
    val lines: List<EmployerBatchLine>,
) {
    init {
        require(lines.isNotEmpty()) { "an employer batch must have at least one line" }
        val total = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.amount }
        require(total.compareTo(payment.amount) == 0) {
            "employer batch lines total $total but the payment is ${payment.amount}"
        }
    }

    /** Per-line idempotency key: stable for the same payment and line position. */
    fun linePaymentId(lineNo: Int): String = "${payment.paymentId}#$lineNo"
}

/** How a participant pays regularly; set up through standing-order-service or sdd-service. */
enum class MandateKind { STANDING_ORDER, DIRECT_DEBIT }
