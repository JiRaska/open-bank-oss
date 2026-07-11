// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import com.openbank.libs.domain.money.Money
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Property-based invariants for the double-entry core (ADR-0011 L1 — "Kotest property tests on
 * ledger arithmetic"). The example-based [JournalEntryTest] pins specific cases; this suite asserts
 * the invariants hold across hundreds of randomly generated multi-currency, multi-leg entries —
 * the cases a human would not hand-pick. Domain-only, so no Testcontainers / Docker is needed.
 */
class JournalEntryPropertyTest {

    private val userId = UUID.randomUUID()
    private val subAccountPool = listOf<UUID?>(null, UUID.randomUUID(), UUID.randomUUID())

    // Currencies with 2 fraction digits; amounts are generated in cents so scale is always 2 (<= 2),
    // never tripping Money's scale guard.
    private val currencyArb = Arb.element("CZK", "EUR", "USD", "GBP")
    private val amountArb = Arb.long(1L, 100_000_000L).map { BigDecimal(it).movePointLeft(2) }
    private val subAccountArb = Arb.element(subAccountPool)

    /** One economic leg: a currency, a positive amount, and an optional customer (sub-ledger) dimension. */
    private data class Posting(val currency: String, val amount: BigDecimal, val subAccount: UUID?)

    private val postingsArb: Arb<List<Posting>> =
        Arb.list(
            Arb.bind(currencyArb, amountArb, subAccountArb) { c, a, s -> Posting(c, a, s) },
            range = 1..6,
        )

    private fun line(side: JournalSide, amount: BigDecimal, currency: String, subAccountId: UUID?) = JournalLine(
        id = UUID.randomUUID(),
        journalId = UUID.randomUUID(),
        glAccountId = UUID.randomUUID(),
        side = side,
        amount = Money.of(amount, currency),
        fxRate = null,
        baseAmount = Money.of(amount, currency),
        sequence = 0,
        subAccountId = subAccountId,
    )

    /**
     * Builds a per-currency-balanced entry: every posting contributes an equal DEBIT and CREDIT in
     * its own currency, so the entry balances within each currency by construction. The customer
     * dimension is carried on the CREDIT (deposit-control) leg only, mirroring the real posting shape.
     */
    private fun balancedEntry(postings: List<Posting>, status: JournalStatus): JournalEntry {
        val lines = postings.flatMap { p ->
            listOf(
                line(JournalSide.DEBIT, p.amount, p.currency, subAccountId = null),
                line(JournalSide.CREDIT, p.amount, p.currency, subAccountId = p.subAccount),
            )
        }
        return JournalEntry(
            id = UUID.randomUUID(),
            entryNumber = 1L,
            transactionId = UUID.randomUUID(),
            entryDate = LocalDate.of(2026, 1, 15),
            valueDate = LocalDate.of(2026, 1, 15),
            description = "property entry",
            status = status,
            lines = lines,
            createdAt = Instant.now(),
            createdBy = userId,
            version = 0L,
        )
    }

    @Test
    fun `any per-currency-balanced entry constructs and balances within every currency`(): Unit = runBlocking {
        checkAll(300, postingsArb) { postings ->
            val entry = balancedEntry(postings, JournalStatus.PENDING)

            assertThat(entry.lines).hasSize(postings.size * 2)
            entry.lines.map { it.baseAmount.currency }.toSet().forEach { ccy ->
                val debits = entry.lines.filter { it.side == JournalSide.DEBIT && it.baseAmount.currency == ccy }
                    .sumOf { it.baseAmount.amount }
                val credits = entry.lines.filter { it.side == JournalSide.CREDIT && it.baseAmount.currency == ccy }
                    .sumOf { it.baseAmount.amount }
                assertThat(debits).isEqualByComparingTo(credits)
            }
        }
    }

    @Test
    fun `perturbing a single leg by one cent always breaks the balance`(): Unit = runBlocking {
        checkAll(300, postingsArb) { postings ->
            val balanced = balancedEntry(postings, JournalStatus.PENDING)
            val victim = balanced.lines.first()
            val bumped = victim.copy(
                amount = victim.amount + Money.of(BigDecimal("0.01"), victim.amount.currency.code),
                baseAmount = victim.baseAmount + Money.of(BigDecimal("0.01"), victim.baseAmount.currency.code),
            )
            val brokenLines = listOf(bumped) + balanced.lines.drop(1)

            assertThatThrownBy {
                balanced.copy(lines = brokenLines)
            }.isInstanceOf(LedgerValidationException::class.java)
                .hasMessageContaining("not balanced")
        }
    }

    @Test
    fun `reversing a posted entry flips every side and is itself balanced`(): Unit = runBlocking {
        checkAll(300, postingsArb) { postings ->
            val posted = balancedEntry(postings, JournalStatus.POSTED)
            // reverse() re-runs the JournalEntry init, so a non-balancing reversal could not construct.
            val reversal = posted.reverse(UUID.randomUUID(), userId)

            assertThat(reversal.lines).hasSize(posted.lines.size)
            assertThat(reversal.reversalOf).isEqualTo(posted.id)
            posted.lines.forEachIndexed { i, original ->
                val flipped = reversal.lines[i]
                assertThat(flipped.side).isNotEqualTo(original.side)
                assertThat(flipped.baseAmount).isEqualTo(original.baseAmount)
                assertThat(flipped.subAccountId).isEqualTo(original.subAccountId)
            }
        }
    }

    @Test
    fun `reversal negates every per-account booked delta`(): Unit = runBlocking {
        checkAll(300, postingsArb) { postings ->
            val posted = balancedEntry(postings, JournalStatus.POSTED)
            val reversal = posted.reverse(UUID.randomUUID(), userId)

            val original = posted.bookedDeltas().associate { (it.accountId to it.currency) to it.delta }
            val reversed = reversal.bookedDeltas().associate { (it.accountId to it.currency) to it.delta }

            assertThat(reversed.keys).isEqualTo(original.keys)
            original.forEach { (key, delta) ->
                assertThat(reversed.getValue(key)).isEqualByComparingTo(delta.negate())
            }
        }
    }

    @Test
    fun `booked deltas count only sub-ledger legs and are credit-positive`(): Unit = runBlocking {
        checkAll(300, postingsArb) { postings ->
            val entry = balancedEntry(postings, JournalStatus.POSTED)

            // Independent recompute: the credit (deposit-control) leg carries the dimension, so each
            // (subAccount, currency) accrues +amount. Net-zero groups are dropped by bookedDeltas().
            val expected = postings
                .filter { it.subAccount != null }
                .groupBy { it.subAccount!! to it.currency }
                .mapValues { (_, ps) -> ps.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount } }
                .filterValues { it.signum() != 0 }

            val actual = entry.bookedDeltas().associate { (it.accountId to it.currency) to it.delta }

            assertThat(actual.keys).isEqualTo(expected.keys)
            expected.forEach { (key, delta) ->
                assertThat(actual.getValue(key)).isEqualByComparingTo(delta)
            }
        }
    }
}
