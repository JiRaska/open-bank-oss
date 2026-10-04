// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.DealFixtures.NOW
import com.openbank.treasury.domain.DealFixtures.approver
import com.openbank.treasury.domain.DealFixtures.bankA
import com.openbank.treasury.domain.DealFixtures.dealer
import com.openbank.treasury.domain.DealFixtures.placement
import com.openbank.treasury.domain.DealFixtures.withinProduct
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.ProductLimit
import com.openbank.treasury.domain.model.ProductLimitBreach.Rule
import com.openbank.treasury.domain.model.ProductLimitBreachedException
import com.openbank.treasury.domain.model.ProductLimitPolicy
import com.openbank.treasury.domain.model.ProductType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** ADR-0315 D4 product limits: the pure policy, and the aggregate enforcing it at submit and approval. */
class ProductLimitPolicyTest {
    private val placementLimit =
        ProductLimit(ProductType.MM_PLACEMENT, mapOf("CZK" to BigDecimal("500000.00")), 31)
    private val policy = ProductLimitPolicy(listOf(placementLimit))

    @Test
    fun `a deal within every rule passes`() {
        val check = policy.evaluate(placement(principal = "500000.00"))
        assertThat(check.breached).isFalse()
        assertThat(check.limit).isEqualTo(placementLimit)
    }

    @Test
    fun `principal one minor unit over the maximum breaches`() {
        val check = policy.evaluate(placement(principal = "500000.01"))
        assertThat(check.breaches.map { it.rule }).containsExactly(Rule.MAX_PRINCIPAL)
        assertThat(check.describe()).contains("500000.01 CZK exceeds the product maximum 500000.00 CZK")
    }

    @Test
    fun `a currency with no maximum is not permitted at all`() {
        val check = policy.evaluate(placement(currency = "EUR", principal = "1.00"))
        assertThat(check.breaches.map { it.rule }).containsExactly(Rule.CURRENCY_NOT_PERMITTED)
    }

    @Test
    fun `tenor over the maximum breaches, at the maximum it does not`() {
        // MONDAY 2026-09-21 + 30 days (2026-10-21) = 30 days; +32 = 32 days.
        assertThat(policy.evaluate(placement()).breached).isFalse()
        val long = placement(maturity = DealFixtures.MONDAY.plusDays(32))
        assertThat(policy.evaluate(long).breaches.map { it.rule }).containsExactly(Rule.MAX_TENOR)
    }

    @Test
    fun `several breaches are all reported`() {
        val check = policy.evaluate(placement(principal = "600000.00", maturity = DealFixtures.MONDAY.plusDays(60)))
        assertThat(check.breaches.map { it.rule }).containsExactly(Rule.MAX_PRINCIPAL, Rule.MAX_TENOR)
    }

    @Test
    fun `fail-closed - a product with no declared limit is not permitted`() {
        val check = policy.evaluate(placement(product = ProductType.MM_BORROWING))
        assertThat(check.breaches.map { it.rule }).containsExactly(Rule.PRODUCT_NOT_PERMITTED)
        assertThat(check.limit).isNull()
    }

    @Test
    fun `a product may declare only one limit, and limits must be positive`() {
        assertThatThrownBy { ProductLimitPolicy(listOf(placementLimit, placementLimit)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProductLimit(ProductType.MM_PLACEMENT, mapOf("CZK" to BigDecimal.ZERO), null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ProductLimit(ProductType.MM_PLACEMENT, mapOf("CZK" to BigDecimal.ONE), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `submit refuses a breach and the deal stays DRAFT`() {
        val d = placement(principal = "600000.00")
        assertThatThrownBy { d.submit(dealer, LimitCheck.of(bankA, d, BigDecimal.ZERO), NOW, policy.evaluate(d)) }
            .isInstanceOf(ProductLimitBreachedException::class.java)
        assertThat(d.state).isEqualTo(DealState.DRAFT)
    }

    @Test
    fun `approve re-checks - a breach at approval refuses booking even though submit passed`() {
        val d = placement(principal = "400000.00")
        val pending = d.submit(dealer, LimitCheck.of(bankA, d, BigDecimal.ZERO), NOW, policy.evaluate(d))
        val tightened = ProductLimitPolicy(
            listOf(placementLimit.copy(maxPrincipal = mapOf("CZK" to BigDecimal("300000.00")))),
        )
        assertThatThrownBy {
            pending.approve(approver, LimitCheck.of(bankA, d, BigDecimal.ZERO), NOW, tightened.evaluate(pending))
        }.isInstanceOf(ProductLimitBreachedException::class.java)
            .hasMessageContaining("exceeds the product maximum 300000.00 CZK")
        val booked = pending.approve(approver, LimitCheck.of(bankA, d, BigDecimal.ZERO), NOW, policy.evaluate(pending))
        assertThat(booked.state).isEqualTo(DealState.BOOKED)
    }

    @Test
    fun `four-eyes is reported before the product limit - the self-approver learns the rule they broke`() {
        val d = placement(principal = "400000.00")
        val pending = d.submit(dealer, LimitCheck.of(bankA, d, BigDecimal.ZERO), NOW, withinProduct)
        val breach = ProductLimitPolicy(emptyList()).evaluate(pending)
        assertThatThrownBy { pending.approve(dealer, LimitCheck.of(bankA, d, BigDecimal.ZERO), NOW, breach) }
            .isInstanceOf(FourEyesViolationException::class.java)
    }

    @Test
    fun `FX spot is limited on its foreign principal`() {
        val fxPolicy = ProductLimitPolicy(
            listOf(ProductLimit(ProductType.FX_SPOT, mapOf("EUR" to BigDecimal("10000.00")), null)),
        )
        assertThat(fxPolicy.evaluate(DealFixtures.fxSpot()).breached).isFalse()
        assertThat(fxPolicy.evaluate(DealFixtures.fxSpot(eur = "10000.01")).breaches.map { it.rule })
            .containsExactly(Rule.MAX_PRINCIPAL)
    }
}
