// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.openbank.treasury.domain.model.MatchType
import com.openbank.treasury.domain.model.NostroMatcher
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.infrastructure.iso20022.Camt053Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class NostroMatcherTest {

    private val statement = Camt053Parser.parse(NostroFixtures.xml())

    private fun reconcile(
        lines: List<com.openbank.treasury.domain.model.LedgerNostroLine>,
        closing: String = "1149958.00",
    ) = NostroMatcher.match(statement, "1001", lines, BigDecimal("1000000.00"), BigDecimal(closing))

    @Test
    fun `exact matches on reference, unmatched listed on both sides, balances compared`() {
        val r = reconcile(NostroFixtures.ledgerLines())

        assertThat(r.matches.map { it.type }).containsExactly(MatchType.EXACT, MatchType.EXACT)
        assertThat(r.matches.map { it.entry.reference })
            .containsExactly(NostroFixtures.INBOUND_TX.toString(), "SYNTH-SVCR-0002")
        assertThat(r.unmatchedStatementEntries.map { it.reference }).containsExactly("SYNTH-SVCR-0003")
        assertThat(r.unmatchedLedgerLines.map { it.amount }).containsExactly(BigDecimal("42.00"))
        assertThat(r.openingDifference).isEqualByComparingTo("0")
        // statement 1 155 000 vs ledger 1 149 958: the 5 000 unbooked credit + the 42 fee.
        assertThat(r.closingDifference).isEqualByComparingTo("42.00".toBigDecimal().add("5000".toBigDecimal()))
        assertThat(r.reconciled).isFalse()
    }

    @Test
    fun `NEGATIVE - a tampered ledger amount is unmatched on both sides, never matched`() {
        val r = reconcile(NostroFixtures.ledgerLines(inboundAmount = "250000.01"))

        assertThat(r.matches.map { it.entry.reference }).containsExactly("SYNTH-SVCR-0002")
        assertThat(r.unmatchedStatementEntries.map { it.reference })
            .contains(NostroFixtures.INBOUND_TX.toString())
        assertThat(r.unmatchedLedgerLines.map { it.amount }).contains(BigDecimal("250000.01"))
    }

    @Test
    fun `NEGATIVE - the wrong side or the wrong date does not match`() {
        val wrongSide = NostroFixtures.line("250000.00", Side.CREDIT, tx = NostroFixtures.INBOUND_TX)
        val wrongDate = NostroFixtures.line(
            "250000.00",
            Side.DEBIT,
            tx = NostroFixtures.INBOUND_TX,
            date = NostroFixtures.DATE.minusDays(1),
        )
        listOf(wrongSide, wrongDate).forEach { line ->
            val r = reconcile(listOf(line))
            assertThat(r.matches).isEmpty()
            assertThat(r.unmatchedLedgerLines).containsExactly(line)
        }
    }

    @Test
    fun `amount+date fallback pairs a unique candidate, and refuses an ambiguous one`() {
        val unique = reconcile(listOf(NostroFixtures.line("5000.00", Side.DEBIT, description = "no ref")))
        assertThat(unique.matches.single().type).isEqualTo(MatchType.AMOUNT_DATE)

        val ambiguous = reconcile(
            listOf(NostroFixtures.line("5000.00", Side.DEBIT), NostroFixtures.line("5000.00", Side.DEBIT)),
        )
        assertThat(ambiguous.matches).isEmpty()
        assertThat(ambiguous.unmatchedLedgerLines).hasSize(2)
    }

    @Test
    fun `fully matched with equal balances is reconciled`() {
        val lines = NostroFixtures.ledgerLines().dropLast(1) +
            NostroFixtures.line("5000.00", Side.DEBIT, description = "credit SYNTH-SVCR-0003")
        val r = reconcile(lines, closing = "1155000.00")
        assertThat(r.reconciled).isTrue()
        assertThat(r.matches).hasSize(3)
    }

    @Test
    fun `NEGATIVE - a short reference is not EXACT against a longer token that merely contains it`() {
        // Statement ref SYNTH-SVCR-0002; the ledger description carries SYNTH-SVCR-00021, a
        // different reference. A substring test called this EXACT.
        val lines = listOf(
            NostroFixtures.line("100000.00", Side.CREDIT, description = "MM placement settled SYNTH-SVCR-00021"),
            NostroFixtures.line("100000.00", Side.CREDIT, description = "MM placement settled XSYNTH-SVCR-0002"),
        )
        val r = reconcile(lines)
        assertThat(r.matches.filter { it.type == MatchType.EXACT }).isEmpty()
    }

    @Test
    fun `a reference matches as a whole token - case, punctuation and multi-word references`() {
        val one = reconcile(
            listOf(NostroFixtures.line("100000.00", Side.CREDIT, description = "settled (synth-svcr-0002).")),
        )
        assertThat(one.matches.single().type).isEqualTo(MatchType.EXACT)

        val ustrd = statement.copy(
            entries = statement.entries.map {
                if (it.sequence ==
                    3
                ) {
                    it.copy(reference = "SYNTHETIC UNBOOKED CREDIT")
                } else {
                    it
                }
            },
        )
        val multi = NostroMatcher.match(
            ustrd,
            "1001",
            listOf(NostroFixtures.line("5000.00", Side.DEBIT, description = "re: synthetic unbooked credit")),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
        )
        assertThat(multi.matches.single().type).isEqualTo(MatchType.EXACT)
    }

    @Test
    fun `balances not stated - differences null, reconciled null when all matched, false when not`() {
        val eur = Camt053Parser.parse(NostroFixtures.eurXml())
        val matched = NostroMatcher.match(eur, "1002", NostroFixtures.eurLedgerLines(), null, null, "no EUR balance")
        assertThat(matched.matches).hasSize(2)
        assertThat(matched.openingDifference).isNull()
        assertThat(matched.closingDifference).isNull()
        assertThat(matched.reconciled).isNull()

        val broken = NostroMatcher.match(
            eur,
            "1002",
            NostroFixtures.eurLedgerLines().drop(1),
            null,
            null,
            "no EUR balance",
        )
        assertThat(broken.reconciled).isFalse()
    }
}
