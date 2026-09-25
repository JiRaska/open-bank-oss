// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.ProductType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

object DealFixtures {
    val NOW: Instant = Instant.parse("2026-09-21T09:00:00Z")

    /** A Monday. */
    val MONDAY: LocalDate = LocalDate.parse("2026-09-21")
    val FRIDAY: LocalDate = LocalDate.parse("2026-09-25")

    val dealer = Actor("dana.dealer", ActorType.HUMAN)
    val approver = Actor("adam.approver", ActorType.HUMAN)
    val agent = Actor("agent:treasury-drafter", ActorType.AI_AGENT)
    val serviceAccount = Actor("service-account-openbank-services", ActorType.SERVICE)

    val bankA = Counterparty(
        id = "SIMBK-A",
        name = "Sandbox Interbank Alpha (synthetic)",
        kind = CounterpartyKind.BANK,
        limits = mapOf("CZK" to BigDecimal("1000000.00"), "EUR" to BigDecimal("50000.00")),
        synthetic = true,
    )

    fun placement(
        principal: String = "100000.00",
        rate: String = "4.25",
        currency: String = "CZK",
        maturity: LocalDate? = LocalDate.parse("2026-10-21"),
        product: ProductType = ProductType.MM_PLACEMENT,
        counterparty: String = "SIMBK-A",
        by: Actor = dealer,
    ): Deal = Deal.draft(
        id = UUID.fromString("0191c0de-0000-7000-8000-000000000001"),
        product = product,
        counterpartyId = counterparty,
        currency = currency,
        principal = BigDecimal(principal),
        rate = BigDecimal(rate),
        tradeDate = MONDAY,
        valueDate = MONDAY,
        maturityDate = maturity,
        actor = by,
        at = NOW,
        rationale = if (by.type == ActorType.AI_AGENT) "test rationale" else null,
    )

    fun withinLimit(deal: Deal) = LimitCheck.of(bankA, deal, BigDecimal.ZERO)
}
