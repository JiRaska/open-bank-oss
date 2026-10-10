// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.contribution

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Bounds every money amount entering S3 is held to: positive, at most two decimals, below a
 * ceiling no single pension payment plausibly reaches. A payment outside them is refused at the
 * edge of the domain, not stored and reconciled later.
 */
object MoneyBounds {
    val MAX: BigDecimal = BigDecimal("1000000000")
    const val MAX_SCALE = 2
    private val CURRENCY = Regex("^[A-Z]{3}$")

    fun requireAmount(amount: BigDecimal, what: String) {
        require(amount.signum() > 0) { "$what must be positive" }
        require(amount.stripTrailingZeros().scale() <= MAX_SCALE) { "$what must have at most $MAX_SCALE decimals" }
        require(amount < MAX) { "$what must be below $MAX" }
    }

    fun requireCurrency(currency: String) = require(CURRENCY.matches(currency)) { "currency must be an ISO 4217 code" }

    fun requireToken(value: String, what: String, max: Int) {
        require(value.isNotBlank()) { "$what must not be blank" }
        require(value.length <= max) { "$what must be at most $max characters" }
    }
}

/** Who paid a contribution (ADR-0334 §1). */
enum class ContributionSource { PARTICIPANT, EMPLOYER, STATE, TRANSFER_IN }

/** How the money arrived. */
enum class ContributionChannel {
    STANDING_ORDER,
    DIRECT_DEBIT,
    BANK_TRANSFER,
    EMPLOYER_BATCH,
    STATE_INCENTIVE,
    TRANSFER,
}

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
        MoneyBounds.requireToken(paymentId, "paymentId", PAYMENT_ID_MAX)
        MoneyBounds.requireAmount(amount, "contribution amount")
        MoneyBounds.requireCurrency(currency)
        require((source == ContributionSource.EMPLOYER) == (employerPartyId != null)) {
            "an employer contribution, and only one, names its employer"
        }
    }

    val taxYear: Int get() = valueDate.year
    val period: YearMonth get() = YearMonth.from(valueDate)
}

/** Column widths of V3; checked here so an oversized value is a 400, not a 500 from Postgres. */
const val PAYMENT_ID_MAX = 128
const val REFERENCE_MAX = 64

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
        // Employer lines derive `paymentId#lineNo`, so the base id leaves room for the suffix.
        MoneyBounds.requireToken(paymentId, "paymentId", PAYMENT_ID_MAX)
        MoneyBounds.requireAmount(amount, "payment amount")
        MoneyBounds.requireCurrency(currency)
        reference?.let { require(it.length <= REFERENCE_MAX) { "reference must be at most $REFERENCE_MAX characters" } }
        payerAccount?.let {
            require(it.length <= REFERENCE_MAX) { "payerAccount must be at most $REFERENCE_MAX characters" }
        }
    }
}

enum class UnmatchedReason {
    NO_REFERENCE,
    UNKNOWN_REFERENCE,
    CONTRACT_NOT_ACCEPTING,
    CURRENCY_MISMATCH,
    EMPLOYER_LINE,
    EMPLOYER_NOT_AUTHORISED,
}

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
        return copy(
            status = UnmatchedStatus.ASSIGNED,
            resolvedContractId = contractId,
            resolvedBy = actor,
            resolvedAt = at,
        )
    }

    fun returnToPayer(actor: String, at: Instant): UnmatchedPayment {
        check(status == UnmatchedStatus.OPEN) { "unmatched payment $id is already $status" }
        return copy(status = UnmatchedStatus.RETURNED, resolvedBy = actor, resolvedAt = at)
    }
}

/** One line of an employer's bulk contribution file: which contract, how much. */
data class EmployerBatchLine(val contractReference: String, val amount: BigDecimal) {
    init {
        MoneyBounds.requireToken(contractReference, "line contractReference", REFERENCE_MAX)
        MoneyBounds.requireAmount(amount, "line amount")
    }
}

enum class EmployerLineOutcome { CREDITED, DUPLICATE, UNMATCHED }

data class EmployerLineResult(
    val lineNo: Int,
    val contractReference: String,
    val amount: BigDecimal,
    val outcome: EmployerLineOutcome,
)

/**
 * An employer's bulk file covering one payment (ADR-0334 S3). The lines must add up to the
 * payment — a file that does not reconcile to the money is refused whole, because crediting part
 * of it would leave the remainder attributable to nobody.
 */
data class EmployerBatch(val employerPartyId: UUID, val payment: IncomingPayment, val lines: List<EmployerBatchLine>) {
    init {
        require(lines.isNotEmpty()) { "an employer batch must have at least one line" }
        require(lines.size <= MAX_LINES) { "an employer batch carries at most $MAX_LINES lines" }
        require(payment.paymentId.length <= PAYMENT_ID_MAX - LINE_SUFFIX_ROOM) {
            "employer batch paymentId is too long"
        }
        val total = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.amount }
        require(total.compareTo(payment.amount) == 0) {
            "employer batch lines total $total but the payment is ${payment.amount}"
        }
    }

    /** Per-line idempotency key: stable for the same payment and line position. */
    fun linePaymentId(lineNo: Int): String = "${payment.paymentId}#$lineNo"

    companion object {
        const val MAX_LINES = 5000
        private const val LINE_SUFFIX_ROOM = 6
    }
}

/** How a participant pays regularly; set up through standing-order-service or sdd-service. */
enum class MandateKind { STANDING_ORDER, DIRECT_DEBIT }
