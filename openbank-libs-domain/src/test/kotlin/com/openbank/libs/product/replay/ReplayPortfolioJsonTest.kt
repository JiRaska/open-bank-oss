// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.replay

import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.PostingDirection
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class ReplayPortfolioJsonTest {

    private fun parse(json: String) = ReplayPortfolioJson.parse(IrJson.parse(json))

    @Test
    fun `golden steps and dated postings read to the same accounts`() {
        val steps = parse(
            """{"zone":"Europe/Prague","accounts":[{"id":"a","state":{"currency":"EUR","balance":"100.00","openedOn":"2026-01-01"},
            "steps":[{"at":"2026-01-10T23:30:00Z","hook":"prePosting","posting":{"kind":"WITHDRAWAL","direction":"DEBIT","amount":"150.00"}},
                     {"at":"2026-01-31T23:00:00Z","hook":"scheduled","tag":"FEE_MAINT"}]}]}""",
        )
        val postings = parse(
            """{"zone":"Europe/Prague","accounts":[{"id":"a","state":{"currency":"EUR","balance":"100.00","openedOn":"2026-01-01"},
            "postings":[{"on":"2026-01-11","kind":"WITHDRAWAL","direction":"DEBIT","amount":"150.00"}]}]}""",
        )
        // 23:30Z on the 10th is the 11th in Prague: the step's day is its calendar day in the portfolio zone.
        assertThat(steps.portfolio).isEqualTo(postings.portfolio)
        assertThat(steps.zone).isEqualTo(ZoneId.of("Europe/Prague"))
        val movement = steps.portfolio.accounts.single().postings.single()
        assertThat(movement.on).isEqualTo(LocalDate.parse("2026-01-11"))
        assertThat(movement.posting.kind).isEqualTo(EventKind.WITHDRAWAL)
        assertThat(movement.posting.direction).isEqualTo(PostingDirection.DEBIT)
        // A scheduled tick is not a movement, but it is the horizon the fixture observes.
        assertThat(steps.firstDay).isEqualTo(LocalDate.parse("2026-01-01"))
        assertThat(steps.lastDay).isEqualTo(LocalDate.parse("2026-02-01"))
        assertThat(postings.lastDay).isEqualTo(LocalDate.parse("2026-01-11"))
    }

    @Test
    fun `unknown keys, exponents and over-precise amounts are refused with the JSON path`() {
        fun account(state: String, extra: String = "") =
            """{"accounts":[{"id":"a","state":{"currency":"EUR","openedOn":"2026-01-01",$state}$extra}]}"""
        listOf(
            account(""""balance":"1E+20"""") to "portfolio.accounts[0].state.balance",
            account(""""balance":"0.0000000000001"""") to "portfolio.accounts[0].state.balance",
            account(""""balance":"1.00","colour":"red"""") to "unknown keys [colour]",
            account(
                """"balance":"1.00"""",
                ""","postings":[{"on":"2026-01-02","kind":"BRIBE","direction":"DEBIT","amount":"1"}]""",
            ) to
                "kind",
            """{"accounts":[]}""" to "must not be empty",
        ).forEach { (json, message) ->
            assertThatThrownBy {
                parse(json)
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(message)
        }
        // A decoded JSON number is bounded BEFORE it is rendered.
        assertThatThrownBy {
            ReplayPortfolioJson.amount(BigDecimal("1E+999999"), "x")
        }.hasMessageContaining("plain decimal")
        assertThat(ReplayPortfolioJson.amount(BigDecimal("12.50"), "x")).isEqualByComparingTo("12.5")
    }
}
