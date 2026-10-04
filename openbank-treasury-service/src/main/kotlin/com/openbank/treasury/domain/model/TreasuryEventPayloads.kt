// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The producer's own claim of who emitted the event — audit-service records it verbatim rather
 * than deriving attribution from the topic name (#5256/#6035).
 */
private const val SOURCE_SERVICE = "treasury-service"

/**
 * One data class per event type: the ADR-0006 contract-agreement gate pairs each AsyncAPI message
 * in `openbank-contracts/openbank-treasury-service/asyncapi.yaml` with the class whose companion
 * declares that `EVENT_TYPE` and compares the constructor properties. Add an event here AND there.
 */
data class DealBooked(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val rate: BigDecimal,
    val valueDate: LocalDate,
    val maturityDate: LocalDate,
    val createdBy: String,
    val approvedBy: String,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
    /** FX_SPOT only (#10896): the bank's side on the foreign `currency`; null for money-market deals. */
    val fxSide: FxSide? = null,
    /** FX_SPOT only: the CZK leg (`principal` x `rate`); null for money-market deals. */
    val counterAmount: BigDecimal? = null,
    /**
     * ADR-0315 D4: the product limit the booking was evaluated against (a booked deal is always
     * within it). Additive and optional, so `v1` consumers are unaffected.
     */
    val productLimit: ProductLimitApplied? = null,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.booked.v1"
    }
}

/**
 * ADR-0315 D2: the counterparty's confirmation of the booked terms was received. Nothing has
 * posted. [simulated] is true when the in-process simulated counterparty set (ADR-0315 D9,
 * sandbox only) confirmed it rather than a back-office person — a SYNTHETIC confirmation.
 */
data class DealConfirmed(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val valueDate: LocalDate,
    val confirmedBy: String,
    val simulated: Boolean,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.confirmed.v1"
    }
}

data class DealSettled(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val valueDate: LocalDate,
    val maturityDate: LocalDate,
    val ledgerJournalId: UUID,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
    /** FX_SPOT only (#10896): the bank's side on the foreign `currency`; null for money-market deals. */
    val fxSide: FxSide? = null,
    /** FX_SPOT only: the CZK leg (`principal` x `rate`); null for money-market deals. */
    val counterAmount: BigDecimal? = null,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.settled.v1"
    }
}

data class DealMatured(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val interest: BigDecimal,
    val maturityDate: LocalDate,
    val ledgerJournalId: UUID,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.matured.v1"
    }
}

data class DealReversed(
    val dealId: UUID,
    val product: ProductType,
    val counterpartyId: String,
    val currency: String,
    val principal: BigDecimal,
    val reversedBy: String,
    val reason: String,
    /** Null when the deal was reversed from BOOKED or CONFIRMED, before anything had posted. */
    val ledgerJournalId: UUID?,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
    /** FX_SPOT only (#10896): the bank's side on the foreign `currency`; null for money-market deals. */
    val fxSide: FxSide? = null,
    /** FX_SPOT only: the CZK leg (`principal` x `rate`); null for money-market deals. */
    val counterAmount: BigDecimal? = null,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.reversed.v1"
    }
}

/**
 * ADR-0315 D7: an OPEN nostro reconciliation break reached the alert threshold (age in business
 * days AND amount). Emitted once per break. Carries no reference or narrative — a statement line's
 * remittance text can name a customer, and this topic is read by audit-service and the risk engine.
 */
data class NostroBreakAged(
    val breakId: UUID,
    val iban: String,
    val glCode: String,
    val currency: String,
    val side: BreakSide,
    val ourSide: Side,
    val amount: BigDecimal,
    val bookingDate: LocalDate,
    val firstSeenOn: LocalDate,
    val ageBusinessDays: Int,
    val thresholdDays: Int,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.nostro.break-aged.v1"
    }
}

/** The product-limit decision recorded on [DealBooked]: the mandate in the deal's currency, and that it held. */
data class ProductLimitApplied(val decision: String, val maxPrincipal: BigDecimal?, val maxTenorDays: Long?) {
    companion object {
        const val WITHIN = "WITHIN_LIMIT"

        fun of(limit: ProductLimit, currency: String) =
            ProductLimitApplied(WITHIN, limit.maxPrincipal[currency], limit.maxTenorDays)
    }
}
