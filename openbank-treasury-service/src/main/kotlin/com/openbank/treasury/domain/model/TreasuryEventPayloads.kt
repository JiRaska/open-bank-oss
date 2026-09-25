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
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.booked.v1"
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
    /** Null when the deal was reversed from BOOKED, before anything had posted. */
    val ledgerJournalId: UUID?,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "treasury.deal.reversed.v1"
    }
}
