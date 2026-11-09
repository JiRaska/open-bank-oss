// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.gsft

import com.openbank.tax.domain.model.TaxValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class InsuredClaimsTest {
    private val day = LocalDate.of(2026, 11, 9)
    private val rates = FxRates(day, mapOf("EUR" to BigDecimal("25.000"), "USD" to BigDecimal("23.000")))
    private val limitCzk = BigDecimal("2500000.00")

    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private fun person(id: UUID, exclusion: ExclusionReason? = null, synthetic: Boolean = false) =
        Depositor(id, exclusion, synthetic)

    private fun account(
        principal: String,
        vararg holders: Holding,
        currency: String = "CZK",
        interest: String = "0",
        synthetic: Boolean = false,
    ) = DepositAccount(
        UUID.randomUUID(),
        "CZ6508000000192000145399",
        currency,
        BigDecimal(principal),
        BigDecimal(interest),
        holders.toList(),
        synthetic,
    )

    private fun sole(id: UUID) = Holding(id, null)

    @Test
    fun `sums principal and interest across a depositor's accounts`() {
        val result = InsuredClaims.assemble(
            day,
            listOf(person(alice)),
            listOf(account("1000", sole(alice), interest = "10.50"), account("2000", sole(alice))),
            rates,
        )
        val claim = result.claims.single()
        assertThat(claim.eligibleBalanceCzk).isEqualByComparingTo("3010.50")
        assertThat(claim.compensationCzk).isEqualByComparingTo("3010.50")
        assertThat(claim.accountIds).hasSize(2)
    }

    @Test
    fun `caps compensation at the CZK equivalent of EUR 100 000 on the relevant day`() {
        val result = InsuredClaims.assemble(day, listOf(person(alice)), listOf(account("3000000", sole(alice))), rates)
        assertThat(result.coverLimitCzk).isEqualByComparingTo(limitCzk)
        assertThat(result.claims.single().eligibleBalanceCzk).isEqualByComparingTo("3000000")
        assertThat(result.claims.single().compensationCzk).isEqualByComparingTo(limitCzk)
    }

    @Test
    fun `converts foreign currency at the rate of the relevant day`() {
        val result = InsuredClaims.assemble(
            day,
            listOf(person(alice)),
            listOf(account("100", sole(alice), currency = "USD")),
            rates,
        )
        assertThat(result.claims.single().eligibleBalanceCzk).isEqualByComparingTo("2300.00")
    }

    @Test
    fun `fails on a missing rate instead of skipping the account`() {
        assertThatThrownBy {
            InsuredClaims.assemble(
                day,
                listOf(person(alice)),
                listOf(account("100", sole(alice), currency = "GBP")),
                rates,
            )
        }.isInstanceOf(TaxValidationException::class.java).hasMessageContaining("GBP")
    }

    @Test
    fun `refuses rates of another day`() {
        assertThatThrownBy {
            InsuredClaims.assemble(day, listOf(person(alice)), emptyList(), rates.copy(day = day.minusDays(1)))
        }.isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `splits a joint account equally when no shares are declared`() {
        val result = InsuredClaims.assemble(
            day,
            listOf(person(alice), person(bob)),
            listOf(account("1000", sole(alice), sole(bob))),
            rates,
        )
        assertThat(
            result.claims.map {
                it.eligibleBalanceCzk
            },
        ).allSatisfy { assertThat(it).isEqualByComparingTo("500.00") }
    }

    @Test
    fun `splits a joint account by declared shares`() {
        val result = InsuredClaims.assemble(
            day,
            listOf(person(alice), person(bob)),
            listOf(account("1000", Holding(alice, BigDecimal("0.75")), Holding(bob, BigDecimal("0.25")))),
            rates,
        )
        assertThat(result.claims.first { it.partyId == alice }.eligibleBalanceCzk).isEqualByComparingTo("750.00")
        assertThat(result.claims.first { it.partyId == bob }.eligibleBalanceCzk).isEqualByComparingTo("250.00")
    }

    @Test
    fun `rejects shares that do not sum to one`() {
        assertThatThrownBy { account("1000", Holding(alice, BigDecimal("0.5")), Holding(bob, BigDecimal("0.4"))) }
            .isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `drops synthetic depositors and accounts and counts them`() {
        val result = InsuredClaims.assemble(
            day,
            listOf(person(alice), person(bob, synthetic = true)),
            listOf(
                account("1000", sole(bob)),
                account("500", sole(alice), synthetic = true),
                account("200", sole(alice)),
            ),
            rates,
        )
        assertThat(result.claims.map { it.partyId }).containsExactly(alice)
        assertThat(result.claims.single().eligibleBalanceCzk).isEqualByComparingTo("200.00")
        assertThat(result.droppedSyntheticDepositors).isEqualTo(1)
        assertThat(result.droppedSyntheticAccounts).isEqualTo(1)
    }

    @Test
    fun `excludes an ineligible depositor and records why`() {
        val result = InsuredClaims.assemble(
            day,
            listOf(person(alice), person(bob, ExclusionReason.CREDIT_INSTITUTION)),
            listOf(account("1000", sole(bob)), account("10", sole(alice))),
            rates,
        )
        assertThat(result.claims.map { it.partyId }).containsExactly(alice)
        assertThat(result.excludedDepositors).containsEntry(ExclusionReason.CREDIT_INSTITUTION, 1)
    }

    @Test
    fun `an excluded co-holder does not inflate the eligible holder's share`() {
        val result = InsuredClaims.assemble(
            day,
            listOf(person(alice), person(bob, ExclusionReason.PUBLIC_AUTHORITY)),
            listOf(account("1000", sole(alice), sole(bob))),
            rates,
        )
        assertThat(result.claims.single().eligibleBalanceCzk).isEqualByComparingTo("500.00")
    }

    @Test
    fun `a negative balance yields no compensation`() {
        val result = InsuredClaims.assemble(day, listOf(person(alice)), listOf(account("-50", sole(alice))), rates)
        assertThat(result.claims.single().compensationCzk).isEqualByComparingTo("0")
    }

    @Test
    fun `fails when an account holder is not among the depositors`() {
        assertThatThrownBy { InsuredClaims.assemble(day, emptyList(), listOf(account("1", sole(alice))), rates) }
            .isInstanceOf(TaxValidationException::class.java)
    }
}
