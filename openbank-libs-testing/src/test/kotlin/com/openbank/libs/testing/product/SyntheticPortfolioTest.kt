// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.product

import com.openbank.libs.product.ir.EventKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class SyntheticPortfolioTest {

    private val shape = PortfolioShape(
        "EUR",
        LocalDate.of(2027, 1, 1),
        LocalDate.of(2029, 12, 31),
        listOf(BigDecimal("1000.00"), BigDecimal("10000.00")),
    )

    @Test
    fun `the same seed regenerates the same portfolio and carries the seed`() {
        val a = SyntheticPortfolio.generate(42L, 300, shape)
        val b = SyntheticPortfolio.generate(42L, 300, shape)
        assertThat(b).isEqualTo(a)
        assertThat(a.seed).isEqualTo(42L)
        assertThat(SyntheticPortfolio.generate(43L, 300, shape)).isNotEqualTo(a)
    }

    @Test
    fun `the portfolio covers boundaries, the leap day, month ends and every behaviour class`() {
        val p = SyntheticPortfolio.generate(1L, 500, shape)
        val balances = p.accounts.map { it.initialState.balance }
        assertThat(balances).anyMatch {
            it.compareTo(BigDecimal("999.99")) == 0 ||
                it.compareTo(BigDecimal("1000.01")) == 0
        }
        assertThat(balances).anyMatch { it > BigDecimal("100000.00") }
        assertThat(balances).allMatch { it.signum() >= 0 && it.scale() == 2 }
        assertThat(p.accounts).anyMatch { it.initialState.openedOn == LocalDate.of(2028, 2, 29) }
        assertThat(p.accounts).anyMatch { it.initialState.openedOn.isBefore(shape.from) }
        assertThat(p.accounts).anyMatch { it.postings.isEmpty() }
        val postings = p.accounts.flatMap { it.postings }
        assertThat(postings).anyMatch { it.on == LocalDate.of(2028, 2, 29) && it.posting.kind == EventKind.DEPOSIT }
        assertThat(postings.map { it.posting.kind }.toSet())
            .contains(EventKind.DEPOSIT, EventKind.WITHDRAWAL, EventKind.CARD_PAYMENT)
        assertThat(postings).allMatch { !it.on.isAfter(shape.to) && it.posting.amount.signum() > 0 }
    }

    @Test
    fun `an empty portfolio and a negative size`() {
        assertThat(SyntheticPortfolio.generate(1L, 0, shape).accounts).isEmpty()
        assertThatThrownBy {
            SyntheticPortfolio.generate(1L, -1, shape)
        }.isInstanceOf(IllegalArgumentException::class.java)
        val overdraft = SyntheticPortfolio.generate(
            3L,
            200,
            shape.copy(overdraftAllowed = true, tierBoundaries = emptyList()),
        )
        assertThat(overdraft.accounts).hasSize(200)
    }
}
