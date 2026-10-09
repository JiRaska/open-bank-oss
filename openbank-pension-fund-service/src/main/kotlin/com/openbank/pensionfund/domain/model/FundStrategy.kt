// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One fund's share of a strategy, with the band outside which the holding is rebalanced. */
data class AllocationTarget(
    val fundId: UUID,
    val weight: BigDecimal,
    val lowerBand: BigDecimal,
    val upperBand: BigDecimal,
) {
    init {
        require(weight.signum() >= 0 && weight <= BigDecimal.ONE) { "weight must be within [0, 1]" }
        require(lowerBand.signum() >= 0 && upperBand <= BigDecimal.ONE) { "bands must be within [0, 1]" }
        require(lowerBand <= weight && weight <= upperBand) { "weight must lie inside its rebalancing band" }
    }

    fun isOutsideBand(actualWeight: BigDecimal): Boolean = actualWeight < lowerBand || actualWeight > upperBand
}

/**
 * A lifecycle step: applies while the participant has at least [minYearsToRetirement] years left,
 * until a step with a higher threshold takes over.
 */
data class GlidePathStep(val minYearsToRetirement: Int, val allocations: List<AllocationTarget>) {
    init {
        require(minYearsToRetirement >= 0) { "minYearsToRetirement must not be negative" }
        Allocations.validate(allocations)
    }
}

object Allocations {
    fun validate(allocations: List<AllocationTarget>) {
        require(allocations.isNotEmpty()) { "an allocation needs at least one fund" }
        require(allocations.map { it.fundId }.toSet().size == allocations.size) { "a fund may appear only once" }
        val total = allocations.fold(BigDecimal.ZERO) { acc, a -> acc + a.weight }
        require(total.compareTo(BigDecimal.ONE) == 0) { "allocation weights must sum to exactly 1, got $total" }
    }
}

enum class StrategyStatus { ACTIVE, CLOSED }

/**
 * A fund strategy: a static target allocation, or a lifecycle strategy whose allocation follows a
 * glide path by years to retirement.
 */
data class FundStrategy(
    val id: UUID,
    val name: String,
    val allocations: List<AllocationTarget>,
    val glidePath: List<GlidePathStep>,
    val status: StrategyStatus,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "strategy name must not be blank" }
        Allocations.validate(allocations)
        if (glidePath.isNotEmpty()) {
            require(glidePath.map { it.minYearsToRetirement }.toSet().size == glidePath.size) {
                "glide-path thresholds must be distinct"
            }
            require(glidePath.any { it.minYearsToRetirement == 0 }) {
                "a glide path must have a step for 0 years to retirement, so every participant maps to a step"
            }
        }
    }

    val isLifecycle: Boolean get() = glidePath.isNotEmpty()

    val fundIds: Set<UUID>
        get() = (allocations + glidePath.flatMap { it.allocations }).map { it.fundId }.toSet()

    /** The target allocation for a participant with [yearsToRetirement] left (negative = already past). */
    fun allocationFor(yearsToRetirement: Int): List<AllocationTarget> {
        if (!isLifecycle) return allocations
        val years = maxOf(yearsToRetirement, 0)
        return glidePath.filter { it.minYearsToRetirement <= years }.maxBy { it.minYearsToRetirement }.allocations
    }

    /** Funds whose actual weight has drifted outside their band and must be rebalanced. */
    fun fundsOutsideBand(actualWeights: Map<UUID, BigDecimal>, yearsToRetirement: Int): List<UUID> =
        allocationFor(yearsToRetirement)
            .filter { it.isOutsideBand(actualWeights[it.fundId] ?: BigDecimal.ZERO) }
            .map { it.fundId }

    fun apply(change: StrategyChange, now: Instant): FundStrategy {
        check(status == StrategyStatus.ACTIVE) { "strategy $id is closed" }
        check(change.strategyId == id) { "change ${change.id} belongs to another strategy" }
        return copy(
            allocations = change.proposedAllocations,
            glidePath = change.proposedGlidePath,
            version = version + 1,
            updatedAt = now,
        )
    }
}

enum class StrategyChangeStatus { PENDING_APPROVAL, APPROVED, REJECTED, APPLIED }

/** Raised when the approver of a maker-checker step is the maker. */
class FourEyesViolationException(message: String) : RuntimeException(message)

/**
 * A governed change to a strategy's allocation (ADR-0334 §1: governance approval and mandatory
 * participant notification before effect).
 *
 * Two independent controls: a second person must approve ([approve] refuses the submitter), and
 * the change cannot apply before [effectiveDate], which must leave at least the configured notice
 * period after participants were notified — the notification date is the approval date, because
 * an unapproved change is not something participants can be told about.
 */
data class StrategyChange(
    val id: UUID,
    val strategyId: UUID,
    val proposedAllocations: List<AllocationTarget>,
    val proposedGlidePath: List<GlidePathStep>,
    val reason: String,
    val effectiveDate: LocalDate,
    val submittedBy: String,
    val submittedAt: Instant,
    val status: StrategyChangeStatus,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    val participantNotificationDate: LocalDate? = null,
    val appliedAt: Instant? = null,
) {
    init {
        require(reason.isNotBlank()) { "a strategy change needs a reason" }
        require(submittedBy.isNotBlank()) { "submittedBy must not be blank" }
        Allocations.validate(proposedAllocations)
    }

    fun approve(approver: String, now: Instant, today: LocalDate, minimumNoticeDays: Long): StrategyChange {
        check(status == StrategyChangeStatus.PENDING_APPROVAL) { "change $id is $status, not awaiting approval" }
        if (approver == submittedBy) throw FourEyesViolationException("the submitter of change $id cannot approve it")
        check(!effectiveDate.isBefore(today.plusDays(minimumNoticeDays))) {
            "effective date $effectiveDate no longer leaves the $minimumNoticeDays-day participant notice period"
        }
        return copy(
            status = StrategyChangeStatus.APPROVED,
            decidedBy = approver,
            decidedAt = now,
            participantNotificationDate = today,
        )
    }

    fun reject(approver: String, now: Instant): StrategyChange {
        check(status == StrategyChangeStatus.PENDING_APPROVAL) { "change $id is $status, not awaiting approval" }
        if (approver == submittedBy) throw FourEyesViolationException("the submitter of change $id cannot reject it")
        return copy(status = StrategyChangeStatus.REJECTED, decidedBy = approver, decidedAt = now)
    }

    fun isDue(today: LocalDate): Boolean = status == StrategyChangeStatus.APPROVED && !today.isBefore(effectiveDate)

    fun markApplied(now: Instant, today: LocalDate): StrategyChange {
        check(status == StrategyChangeStatus.APPROVED) { "change $id is $status, only an approved change applies" }
        check(isDue(today)) { "change $id is not effective before $effectiveDate" }
        return copy(status = StrategyChangeStatus.APPLIED, appliedAt = now)
    }

    companion object {
        @Suppress("LongParameterList")
        fun submit(
            id: UUID,
            strategyId: UUID,
            proposedAllocations: List<AllocationTarget>,
            proposedGlidePath: List<GlidePathStep>,
            reason: String,
            effectiveDate: LocalDate,
            submittedBy: String,
            now: Instant,
            today: LocalDate,
            minimumNoticeDays: Long,
        ): StrategyChange {
            require(!effectiveDate.isBefore(today.plusDays(minimumNoticeDays))) {
                "effectiveDate must be at least $minimumNoticeDays days ahead to leave the participant notice period"
            }
            return StrategyChange(
                id = id,
                strategyId = strategyId,
                proposedAllocations = proposedAllocations,
                proposedGlidePath = proposedGlidePath,
                reason = reason,
                effectiveDate = effectiveDate,
                submittedBy = submittedBy,
                submittedAt = now,
                status = StrategyChangeStatus.PENDING_APPROVAL,
            )
        }
    }
}
