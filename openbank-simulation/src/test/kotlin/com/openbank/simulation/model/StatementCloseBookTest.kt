// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** Deterministic unit coverage for [StatementCloseBook]'s running-state mechanics (issue #667). */
class StatementCloseBookTest {

    private val key = AccountCurrency(UUID.randomUUID(), "CZK")
    private val fallback = BigDecimal("10000.00")

    @Test
    fun `before any close, opening balance is the fallback and next sequence is 1`() {
        val book = StatementCloseBook()

        assertThat(book.openingBalanceOf(key, fallback)).isEqualByComparingTo(fallback)
        assertThat(book.netAtLastCloseOf(key)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(book.nextSequenceOf(key)).isEqualTo(1L)
    }

    @Test
    fun `advance carries the closing balance forward as the next opening balance and bumps the sequence`() {
        val book = StatementCloseBook()

        book.advance(key, closingBalance = BigDecimal("10050.00"), cumulativeNet = BigDecimal("50.00"))

        assertThat(book.openingBalanceOf(key, fallback)).isEqualByComparingTo(BigDecimal("10050.00"))
        assertThat(book.netAtLastCloseOf(key)).isEqualByComparingTo(BigDecimal("50.00"))
        assertThat(book.nextSequenceOf(key)).isEqualTo(2L)

        book.advance(key, closingBalance = BigDecimal("10075.00"), cumulativeNet = BigDecimal("75.00"))

        assertThat(book.openingBalanceOf(key, fallback)).isEqualByComparingTo(BigDecimal("10075.00"))
        assertThat(book.nextSequenceOf(key)).isEqualTo(3L)
    }

    @Test
    fun `decision and persisted are recorded independently per attempt`() {
        val book = StatementCloseBook()
        val reconciledAttempt = StatementCloseKey(key.accountId, key.currency, "attempt-A")
        val mismatchAttempt = StatementCloseKey(key.accountId, key.currency, "attempt-B")

        book.recordDecision(reconciledAttempt, wasReconciled = true)
        book.recordPersisted(reconciledAttempt, wasPersisted = true)
        book.recordDecision(mismatchAttempt, wasReconciled = false)
        book.recordPersisted(mismatchAttempt, wasPersisted = false)

        assertThat(book.attempts()).containsExactlyInAnyOrder(reconciledAttempt, mismatchAttempt)
        assertThat(book.wasReconciled(reconciledAttempt)).isTrue()
        assertThat(book.wasPersisted(reconciledAttempt)).isTrue()
        assertThat(book.wasReconciled(mismatchAttempt)).isFalse()
        assertThat(book.wasPersisted(mismatchAttempt)).isFalse()
    }
}
