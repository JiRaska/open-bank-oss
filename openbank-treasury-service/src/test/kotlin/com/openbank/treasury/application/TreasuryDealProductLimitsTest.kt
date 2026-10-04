// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.treasury.application.port.`in`.DraftDealCommand
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.usecase.TreasuryDealService
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.ProductLimit
import com.openbank.treasury.domain.model.ProductLimitBreachedException
import com.openbank.treasury.domain.model.ProductLimitPolicy
import com.openbank.treasury.domain.model.ProductType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

class TreasuryDealProductLimitsTest {

    private val monday = DealFixtures.MONDAY
    private val clock: Clock = Clock.fixed(monday.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private val deals = InMemoryDeals()
    private val ledger = RecordingLedger()
    private val cps = object : CounterpartyRepository {
        val all = listOf(
            DealFixtures.bankA,
            Counterparty("CNB", "ČNB", CounterpartyKind.CENTRAL_BANK, mapOf("CZK" to BigDecimal("1E12")), false),
        )
        override suspend fun findById(id: String) = all.find { it.id == id }
        override suspend fun list() = all
    }
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private var productLimits: ProductLimitPolicy = DealFixtures.permissiveProductLimits
    private val service get() =
        TreasuryDealService(
            deals, cps, ledger, mapper, clock, FxMidRatePort.NONE, FxRateTolerance.DISABLED, true, null, productLimits,
        )

    private fun cmd(
        principal: String = "100000.00",
        product: ProductType = ProductType.MM_PLACEMENT,
        cp: String = "SIMBK-A",
        maturity: LocalDate? = monday.plusDays(7),
        value: LocalDate = monday,
        currency: String = "CZK",
    ) = DraftDealCommand(product, cp, currency, BigDecimal(principal), BigDecimal("4.00"), null, value, maturity, null)

    private suspend fun book(c: DraftDealCommand = cmd()): Deal {
        val d = service.draft(c, DealFixtures.dealer)
        service.submit(d.id, DealFixtures.dealer)
        return service.approve(d.id, DealFixtures.approver)
    }

    @Test
    fun `ADR-0315 D4 - a product mandate tightened after submission refuses approval and saves nothing`(): Unit =
        runBlocking {
            val d = service.draft(cmd(principal = "500000.00"), DealFixtures.dealer)
            service.submit(d.id, DealFixtures.dealer)
            productLimits = ProductLimitPolicy(
                listOf(ProductLimit(ProductType.MM_PLACEMENT, mapOf("CZK" to BigDecimal("400000.00")), 366)),
            )
            assertThatThrownBy { runBlocking { service.approve(d.id, DealFixtures.approver) } }
                .isInstanceOf(ProductLimitBreachedException::class.java)
                .hasMessageContaining("principal 500000.00 CZK exceeds the product maximum 400000.00 CZK")
            assertThat(deals.findById(d.id)!!.state).isEqualTo(DealState.PENDING_APPROVAL)
            assertThat(deals.events).isEmpty()
        }

    @Test
    fun `ADR-0315 D4 - a counterparty override does not cover a product-limit breach`(): Unit = runBlocking {
        val d = service.draft(cmd(principal = "1500000.00"), DealFixtures.dealer)
        assertThat(service.submit(d.id, DealFixtures.dealer).limitCheck!!.breached).isTrue()
        service.overrideLimit(d.id, "desk head sign-off", DealFixtures.seniorApprover)
        productLimits = ProductLimitPolicy(
            listOf(ProductLimit(ProductType.MM_PLACEMENT, mapOf("EUR" to BigDecimal("1E9")), null)),
        )
        assertThatThrownBy { runBlocking { service.approve(d.id, DealFixtures.approver) } }
            .isInstanceOf(ProductLimitBreachedException::class.java)
            .hasMessageContaining("may not be dealt in CZK")
    }

    @Test
    fun `ADR-0315 D4 - the booked event records the product limit the booking was evaluated against`(): Unit =
        runBlocking {
            productLimits = ProductLimitPolicy(
                listOf(ProductLimit(ProductType.MM_PLACEMENT, mapOf("CZK" to BigDecimal("900000.00")), 30)),
            )
            book()
            assertThat(deals.events.single().payload)
                .contains(
                    "\"productLimit\":{\"decision\":\"WITHIN_LIMIT\",\"maxPrincipal\":900000.00,\"maxTenorDays\":30}",
                )
        }

    @Test
    fun `ADR-0315 D4 - a deal outside its product mandate cannot be submitted`(): Unit = runBlocking {
        productLimits = ProductLimitPolicy(
            listOf(ProductLimit(ProductType.MM_PLACEMENT, mapOf("CZK" to BigDecimal("1E9")), 5)),
        )
        val d = service.draft(cmd(), DealFixtures.dealer)
        assertThatThrownBy { runBlocking { service.submit(d.id, DealFixtures.dealer) } }
            .isInstanceOf(ProductLimitBreachedException::class.java)
            .hasMessageContaining("tenor 7 days exceeds the product maximum 5 days")
        assertThat(deals.findById(d.id)!!.state).isEqualTo(DealState.DRAFT)
    }
}
