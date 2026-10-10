// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.transfer

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.domain.pack.TransferRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.util.UUID

enum class TransferDirection { IN, OUT }

/** Who asked for a transfer-out: the participant themselves, or the receiving provider on their behalf. */
enum class TransferOrigin { PARTICIPANT, RECEIVING_PROVIDER }

/**
 * Transfer lifecycle (ADR-0334 §4 step 3). The two directions share the status vocabulary but not
 * the edges: each direction has its own transition table, so an IN request can never be "settled"
 * and an OUT request can never "receive funds".
 */
enum class TransferStatus {
    /** A receiving provider asked; nothing moves until the participant SCA-consents. */
    AWAITING_CONSENT,
    REQUESTED,
    SENT,
    ACCEPTED,
    FUNDS_RECEIVED,
    VALUATED,
    SETTLED,
    COMPLETED,
    REJECTED,
    TIMED_OUT,
    CANCELLED,
    FAILED,
    ;

    val terminal: Boolean get() = this in setOf(COMPLETED, REJECTED, TIMED_OUT, CANCELLED, FAILED)

    companion object {
        private val IN_EDGES: Map<TransferStatus, Set<TransferStatus>> = mapOf(
            REQUESTED to setOf(SENT, CANCELLED, FAILED),
            // Funds may arrive without a prior explicit acceptance message.
            SENT to setOf(ACCEPTED, FUNDS_RECEIVED, REJECTED, TIMED_OUT, CANCELLED),
            ACCEPTED to setOf(FUNDS_RECEIVED, REJECTED, TIMED_OUT, CANCELLED),
            FUNDS_RECEIVED to setOf(COMPLETED),
        )
        private val OUT_EDGES: Map<TransferStatus, Set<TransferStatus>> = mapOf(
            AWAITING_CONSENT to setOf(REQUESTED, CANCELLED),
            REQUESTED to setOf(VALUATED, REJECTED),
            VALUATED to setOf(SETTLED, FAILED),
            SETTLED to setOf(COMPLETED),
        )

        fun canMove(direction: TransferDirection, from: TransferStatus, to: TransferStatus): Boolean {
            val edges = if (direction == TransferDirection.IN) IN_EDGES else OUT_EDGES
            return to in edges[from].orEmpty()
        }
    }
}

/** What a failed transfer's compensation undid. */
enum class Compensation { NONE, CONTRACT_CLOSED, CEDING_CANCELLED_AND_CONTRACT_CLOSED, REDEMPTION_REVERSED }

data class Counterparty(val providerId: String, val providerName: String, val contractNumber: String) {
    init {
        require(providerId.isNotBlank()) { "counterparty providerId must not be blank" }
        require(contractNumber.isNotBlank()) { "counterparty contractNumber must not be blank" }
    }
}

/** One incentive received in one year — what moves with the contract when the pack says it does. */
data class IncentiveHistoryEntry(val incentiveId: String, val year: Int, val amount: BigDecimal) {
    init {
        require(incentiveId.isNotBlank()) { "incentiveId must not be blank" }
        require(amount.signum() >= 0) { "incentive history amounts must not be negative" }
    }
}

/** What the ceding provider sends with the money. */
data class FundsArrival(
    val amount: BigDecimal,
    val currency: String,
    val originalStartDate: LocalDate,
    val incentiveHistory: List<IncentiveHistoryEntry>,
) {
    init {
        require(amount.signum() > 0) { "transferred amount must be positive" }
    }
}

/**
 * A transfer between providers, in either direction (ADR-0334 §1 `TransferRequest`).
 *
 * Immutable; [moveTo] consults the direction's own transition table. [version] is the optimistic
 * lock the repository compares, because a counterparty callback and a workflow timer can race.
 */
data class TransferRequest(
    val id: UUID,
    val direction: TransferDirection,
    val origin: TransferOrigin,
    val contractId: UUID,
    val partyId: UUID,
    val counterparty: Counterparty,
    val currency: String,
    val signatureRef: String?,
    val status: TransferStatus,
    val deadline: LocalDate,
    val counterpartyReference: String? = null,
    val grossAmount: BigDecimal? = null,
    val fee: BigDecimal? = null,
    val netAmount: BigDecimal? = null,
    val originalStartDate: LocalDate? = null,
    val incentiveHistory: List<IncentiveHistoryEntry> = emptyList(),
    val redemptionRef: String? = null,
    val failureReason: String? = null,
    val compensation: Compensation = Compensation.NONE,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(status != TransferStatus.COMPLETED || netAmount != null) { "a completed transfer carries its amount" }
        require(!status.terminal || status == TransferStatus.COMPLETED || failureReason != null) {
            "a failed, rejected, timed-out or cancelled transfer carries its reason"
        }
    }

    /** The participant's SCA consent to a provider-initiated transfer-out. */
    fun consented(signatureRef: String, now: Instant): TransferRequest {
        require(signatureRef.isNotBlank()) { "consent must be SCA-signed" }
        return moveTo(TransferStatus.REQUESTED, now).copy(signatureRef = signatureRef)
    }

    fun sent(reference: String, now: Instant): TransferRequest =
        moveTo(TransferStatus.SENT, now).copy(counterpartyReference = reference)

    fun accepted(now: Instant): TransferRequest = moveTo(TransferStatus.ACCEPTED, now)

    fun fundsReceived(arrival: FundsArrival, carriesIncentiveHistory: Boolean, now: Instant): TransferRequest {
        require(arrival.currency == currency) { "transferred currency must be $currency" }
        return moveTo(TransferStatus.FUNDS_RECEIVED, now).copy(
            grossAmount = arrival.amount,
            fee = BigDecimal.ZERO,
            netAmount = arrival.amount,
            originalStartDate = arrival.originalStartDate,
            incentiveHistory = if (carriesIncentiveHistory) arrival.incentiveHistory else emptyList(),
        )
    }

    fun valuated(gross: BigDecimal, fee: BigDecimal, now: Instant): TransferRequest {
        require(gross.signum() >= 0 && fee.signum() >= 0) { "valuation and fee must not be negative" }
        return moveTo(TransferStatus.VALUATED, now).copy(
            grossAmount = gross,
            fee = fee,
            netAmount = (gross - fee).max(BigDecimal.ZERO),
        )
    }

    fun settled(redemptionRef: String, now: Instant): TransferRequest =
        moveTo(TransferStatus.SETTLED, now).copy(redemptionRef = redemptionRef)

    fun completed(incentiveHistory: List<IncentiveHistoryEntry>, now: Instant): TransferRequest {
        val next = moveTo(TransferStatus.COMPLETED, now)
        return if (direction == TransferDirection.OUT) next.copy(incentiveHistory = incentiveHistory) else next
    }

    fun failed(status: TransferStatus, reason: String, compensation: Compensation, now: Instant): TransferRequest {
        require(status.terminal && status != TransferStatus.COMPLETED) { "$status is not a failure outcome" }
        checkMove(status)
        // One copy: the failure status and the reason it requires change together.
        return copy(status = status, updatedAt = now, failureReason = reason, compensation = compensation)
    }

    private fun checkMove(target: TransferStatus) = check(TransferStatus.canMove(direction, status, target)) {
        "transfer $direction $status -> $target is not allowed"
    }

    private fun moveTo(target: TransferStatus, now: Instant): TransferRequest {
        checkMove(target)
        return copy(status = target, updatedAt = now)
    }

    companion object {
        @Suppress("LongParameterList")
        fun request(
            direction: TransferDirection,
            origin: TransferOrigin,
            contractId: UUID,
            partyId: UUID,
            counterparty: Counterparty,
            currency: String,
            signatureRef: String?,
            deadline: LocalDate,
            now: Instant,
        ): TransferRequest {
            require(origin == TransferOrigin.RECEIVING_PROVIDER || !signatureRef.isNullOrBlank()) {
                "a participant's transfer request must be SCA-signed"
            }
            require(origin == TransferOrigin.PARTICIPANT || direction == TransferDirection.OUT) {
                "only a transfer-out can be requested by a receiving provider"
            }
            // A provider's request never carries the participant's consent: it waits for it.
            val initial = if (origin ==
                TransferOrigin.RECEIVING_PROVIDER
            ) {
                TransferStatus.AWAITING_CONSENT
            } else {
                TransferStatus.REQUESTED
            }
            return TransferRequest(
                id = Ids.newId(),
                direction = direction,
                origin = origin,
                contractId = contractId,
                partyId = partyId,
                counterparty = counterparty,
                currency = currency,
                signatureRef = if (origin == TransferOrigin.RECEIVING_PROVIDER) null else signatureRef,
                status = initial,
                deadline = deadline,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}

/** Pack-driven transfer terms: deadline and fee (ADR-0334 §3 "transfer rules"). */
object TransferTerms {

    private const val MONEY_SCALE = 2

    fun deadline(rules: TransferRules, today: LocalDate): LocalDate {
        val days = requireNotNull(rules.deadlineDays) { "the pack declares no transfer deadline" }
        return today.plusDays(days.toLong())
    }

    /**
     * The fee is the pack's `maxFee` while the contract is younger than `freeAfterMonths`, and zero
     * after; a pack with no `maxFee` charges nothing. Never more than the value transferred.
     */
    fun fee(rules: TransferRules, contractStart: LocalDate?, value: BigDecimal, today: LocalDate): BigDecimal {
        val max = rules.maxFee ?: return BigDecimal.ZERO.setScale(MONEY_SCALE)
        val freeAfter = rules.freeAfterMonths
        val ageMonths = contractStart?.let { Period.between(it, today).toTotalMonths() } ?: 0
        val fee = if (freeAfter != null && ageMonths >= freeAfter) BigDecimal.ZERO else max
        return fee.min(value).setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)
    }
}
