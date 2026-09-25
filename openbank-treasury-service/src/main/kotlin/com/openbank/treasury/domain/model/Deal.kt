// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * MVP products (ADR-0315 D2). The ADR lists more; these three are the money-market core.
 *
 * - [MM_PLACEMENT]: the bank LENDS to another bank (an asset, consumes that bank's credit limit).
 * - [MM_BORROWING]: the bank BORROWS from another bank (a liability, consumes no credit limit).
 * - [CNB_DEPOSIT_FACILITY]: overnight deposit at the Czech National Bank (an asset, CZK only).
 */
enum class ProductType(val isAsset: Boolean) {
    MM_PLACEMENT(isAsset = true),
    MM_BORROWING(isAsset = false),
    CNB_DEPOSIT_FACILITY(isAsset = true),
}

/**
 * ADR-0315 D2 lifecycle, MVP subset: `CONFIRMED` is folded into `SETTLED` because the simulated
 * counterparty confirms and settles in one step (ADR-0315 D9). `REJECTED` is not a state: a
 * rejection sends the deal back to `DRAFT` with the reason recorded on its timeline.
 */
enum class DealState { DRAFT, PENDING_APPROVAL, BOOKED, SETTLED, MATURED, CANCELLED, REVERSED }

/** Who acts. Derived from the authenticated principal, never from a request body. */
enum class ActorType { HUMAN, AI_AGENT, SERVICE, SYSTEM }

data class Actor(val id: String, val type: ActorType) {
    init {
        require(id.isNotBlank()) { "actor id must not be blank" }
    }

    companion object {
        /** The in-process simulated counterparty set (ADR-0315 D9) that settles and matures deals. */
        val SIMULATED_MARKET = Actor("system:simulated-market", ActorType.SYSTEM)

        /**
         * Same convention as libs' AuthorizeInterceptor: an agent presents a `sub` prefixed
         * `agent:` (ADR-0031); a Keycloak client-credentials token is `service-account-<client>`.
         */
        fun fromPrincipalName(name: String): Actor = Actor(
            id = name,
            type = when {
                name.startsWith("agent:") -> ActorType.AI_AGENT
                name.startsWith("service-account-") -> ActorType.SERVICE
                else -> ActorType.HUMAN
            },
        )
    }
}

/** Raised when approver == creator (or submitter). Mapped to 422. */
class FourEyesViolationException(message: String) : RuntimeException(message)

/** Raised when a non-human principal attempts a step only a person may take. Mapped to 403. */
class ActorNotPermittedException(message: String) : RuntimeException(message)

/** Raised when a counterparty limit would be exceeded. Mapped to 422. */
class LimitBreachedException(val check: LimitCheck) :
    RuntimeException(
        "counterparty ${check.counterpartyId} limit breached: exposure ${check.exposureAfter} " +
            "> limit ${check.limit} ${check.currency}",
    )

/** One entry of a deal's timeline — who moved it from where to where, and why. */
data class DealTransition(
    val from: DealState?,
    val to: DealState,
    val actor: Actor,
    val at: Instant,
    val note: String? = null,
)

/**
 * A money-market deal (ADR-0315 D2/D3/D4). Immutable: every transition returns a new instance
 * with one more [DealTransition].
 *
 * Interest convention: **ACT/360**, simple interest paid at maturity — the CZK (PRIBOR) and EUR
 * (EURIBOR/€STR) money-market convention. `rate` is an annual percentage (4.25 means 4.25 % p.a.).
 */
@Suppress("TooManyFunctions")
data class Deal(
    val id: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val rate: BigDecimal,
    val tradeDate: LocalDate,
    val valueDate: LocalDate,
    val maturityDate: LocalDate,
    val state: DealState,
    val createdBy: Actor,
    val createdAt: Instant,
    val updatedAt: Instant,
    val submittedBy: Actor? = null,
    val approvedBy: Actor? = null,
    val limitCheck: LimitCheck? = null,
    /** An AI agent's inputs and rationale for a draft it proposed (ADR-0315 D10); null for humans. */
    val rationale: String? = null,
    val history: List<DealTransition> = emptyList(),
) {
    init {
        require(currency in SUPPORTED_CURRENCIES) { "currency must be one of $SUPPORTED_CURRENCIES" }
        require(principal.signum() > 0) { "principal must be positive" }
        require(principal.scale() <= 2) { "principal has at most 2 decimal places" }
        require(rate.signum() >= 0) { "rate must not be negative" }
        require(rate <= MAX_RATE) { "rate is an annual percentage and must not exceed $MAX_RATE" }
        require(!valueDate.isBefore(tradeDate)) { "valueDate must not precede tradeDate" }
        require(maturityDate.isAfter(valueDate)) { "maturityDate must be after valueDate" }
        if (product == ProductType.CNB_DEPOSIT_FACILITY) {
            require(currency == CZK) { "the ČNB deposit facility is CZK only" }
            require(counterpartyId == CNB_COUNTERPARTY_ID) {
                "the ČNB deposit facility's counterparty is $CNB_COUNTERPARTY_ID"
            }
            require(maturityDate == DayCount.nextBusinessDay(valueDate)) {
                "the ČNB deposit facility is overnight: maturityDate must be the next business day"
            }
        } else {
            require(counterpartyId != CNB_COUNTERPARTY_ID) { "interbank products cannot face the central bank" }
        }
    }

    /** ACT/360 day count between value and maturity date. */
    val days: Long get() = ChronoUnit.DAYS.between(valueDate, maturityDate)

    /** Interest at maturity, ACT/360, rounded half-up to the currency's 2 minor units. */
    val interest: BigDecimal get() = DayCount.act360Interest(principal, rate, valueDate, maturityDate)

    fun submit(actor: Actor, check: LimitCheck, at: Instant): Deal {
        requireHuman(actor, "submit")
        requireState(DealState.DRAFT, "submit")
        return transition(DealState.PENDING_APPROVAL, actor, at, limitNote(check))
            .copy(submittedBy = actor, limitCheck = check)
    }

    /**
     * Four-eyes booking (ADR-0315 D3). Enforced HERE, not only in OPA: a policy that is not
     * enforcing would otherwise silently allow self-approval. Order matters — the agent check
     * runs first, so an agent is refused as an agent even when it is also the creator.
     */
    fun approve(actor: Actor, check: LimitCheck, at: Instant): Deal {
        requireHuman(actor, "approve")
        requireState(DealState.PENDING_APPROVAL, "approve")
        if (actor.id == createdBy.id) {
            throw FourEyesViolationException("four-eyes: the approver must not be the deal's creator")
        }
        if (actor.id == submittedBy?.id) {
            throw FourEyesViolationException("four-eyes: the approver must not be the deal's submitter")
        }
        // ADR-0315 D4: a breach blocks booking. The senior-approver override is not built yet.
        if (check.breached) throw LimitBreachedException(check)
        return transition(DealState.BOOKED, actor, at, limitNote(check)).copy(approvedBy = actor, limitCheck = check)
    }

    fun reject(actor: Actor, reason: String, at: Instant): Deal {
        requireHuman(actor, "reject")
        requireState(DealState.PENDING_APPROVAL, "reject")
        require(reason.isNotBlank()) { "a rejection needs a reason" }
        return transition(DealState.DRAFT, actor, at, "rejected: $reason").copy(submittedBy = null, limitCheck = null)
    }

    fun cancel(actor: Actor, at: Instant): Deal {
        requireHuman(actor, "cancel")
        check(state == DealState.DRAFT || state == DealState.PENDING_APPROVAL) {
            "only a DRAFT or PENDING_APPROVAL deal can be cancelled (deal is $state); a booked deal is reversed"
        }
        return transition(DealState.CANCELLED, actor, at, null)
    }

    fun settle(actor: Actor, today: LocalDate, at: Instant): Deal {
        requireHumanOrSystem(actor, "settle")
        requireState(DealState.BOOKED, "settle")
        check(!valueDate.isAfter(today)) { "deal cannot settle before its value date $valueDate" }
        return transition(DealState.SETTLED, actor, at, null)
    }

    fun mature(actor: Actor, today: LocalDate, at: Instant): Deal {
        requireHumanOrSystem(actor, "mature")
        requireState(DealState.SETTLED, "mature")
        check(!maturityDate.isAfter(today)) { "deal cannot mature before its maturity date $maturityDate" }
        return transition(DealState.MATURED, actor, at, null)
    }

    /**
     * A booked or settled deal is undone by reversal. From SETTLED the settlement journal is
     * offset in the ledger; from BOOKED nothing had posted. A matured deal is final in the MVP.
     * Four-eyes applies: the creator cannot reverse their own deal.
     */
    fun reverse(actor: Actor, reason: String, at: Instant): Deal {
        requireHuman(actor, "reverse")
        check(state == DealState.BOOKED || state == DealState.SETTLED) {
            "only a BOOKED or SETTLED deal can be reversed (deal is $state)"
        }
        require(reason.isNotBlank()) { "a reversal needs a reason" }
        if (actor.id == createdBy.id) {
            throw FourEyesViolationException("four-eyes: the deal's creator must not reverse it")
        }
        return transition(DealState.REVERSED, actor, at, "reversed: $reason")
    }

    /** True while the deal still consumes its counterparty's credit limit. */
    val consumesLimit: Boolean
        get() = product.isAsset && state in setOf(DealState.PENDING_APPROVAL, DealState.BOOKED, DealState.SETTLED)

    private fun transition(to: DealState, actor: Actor, at: Instant, note: String?) = copy(
        state = to,
        updatedAt = at,
        history = history + DealTransition(from = state, to = to, actor = actor, at = at, note = note),
    )

    private fun requireState(expected: DealState, action: String) =
        check(state == expected) { "cannot $action a deal in state $state (expected $expected)" }

    private fun requireHuman(actor: Actor, action: String) {
        if (actor.type != ActorType.HUMAN) {
            throw ActorNotPermittedException("a ${actor.type} principal may not $action a treasury deal")
        }
    }

    private fun requireHumanOrSystem(actor: Actor, action: String) {
        if (actor.type != ActorType.HUMAN && actor.type != ActorType.SYSTEM) {
            throw ActorNotPermittedException("a ${actor.type} principal may not $action a treasury deal")
        }
    }

    private fun limitNote(check: LimitCheck) =
        "limit ${check.limit} ${check.currency}, exposure after ${check.exposureAfter}, headroom ${check.headroomAfter}"

    companion object {
        const val CZK = "CZK"
        const val EUR = "EUR"
        val SUPPORTED_CURRENCIES = setOf(CZK, EUR)
        const val CNB_COUNTERPARTY_ID = "CNB"
        private val MAX_RATE = BigDecimal("100")

        /**
         * A new draft. A human dealer or an AI agent may draft (ADR-0315 D3); a service account or
         * the system may not. An agent's draft must carry its rationale (ADR-0315 D10).
         */
        @Suppress("LongParameterList")
        fun draft(
            id: UUID,
            product: ProductType,
            counterpartyId: String,
            currency: String,
            principal: BigDecimal,
            rate: BigDecimal,
            tradeDate: LocalDate,
            valueDate: LocalDate,
            maturityDate: LocalDate?,
            actor: Actor,
            at: Instant,
            rationale: String? = null,
        ): Deal {
            if (actor.type != ActorType.HUMAN && actor.type != ActorType.AI_AGENT) {
                throw ActorNotPermittedException("a ${actor.type} principal may not draft a treasury deal")
            }
            if (actor.type == ActorType.AI_AGENT) {
                require(!rationale.isNullOrBlank()) { "an agent-drafted deal must carry its rationale (ADR-0315 D10)" }
            }
            val maturity = when {
                product == ProductType.CNB_DEPOSIT_FACILITY -> DayCount.nextBusinessDay(valueDate)
                maturityDate == null -> DayCount.nextBusinessDay(valueDate) // overnight
                else -> maturityDate
            }
            return Deal(
                id = id,
                product = product,
                counterpartyId = counterpartyId,
                currency = currency,
                principal = principal,
                rate = rate,
                tradeDate = tradeDate,
                valueDate = valueDate,
                maturityDate = maturity,
                state = DealState.DRAFT,
                createdBy = actor,
                createdAt = at,
                updatedAt = at,
                rationale = rationale,
                history = listOf(DealTransition(from = null, to = DealState.DRAFT, actor = actor, at = at)),
            )
        }
    }
}

/**
 * Day-count and calendar helpers.
 *
 * **No holiday calendar yet**: a business day is Monday-Friday. Czech and TARGET2 holidays are
 * not modelled, so an "overnight" deal struck the day before a holiday matures on the holiday.
 */
object DayCount {
    private const val DAYS_IN_YEAR_ACT360 = 360
    private const val PERCENT = 100
    private const val MONEY_SCALE = 2
    private const val WORK_SCALE = 12

    fun nextBusinessDay(date: LocalDate): LocalDate {
        var d = date.plusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d
    }

    /** principal × rate/100 × days/360, half-up to 2 dp. */
    fun act360Interest(principal: BigDecimal, ratePercent: BigDecimal, from: LocalDate, to: LocalDate): BigDecimal {
        val days = BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to))
        return principal.multiply(ratePercent).multiply(days)
            .divide(BigDecimal.valueOf((PERCENT * DAYS_IN_YEAR_ACT360).toLong()), WORK_SCALE, RoundingMode.HALF_UP)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
    }
}
