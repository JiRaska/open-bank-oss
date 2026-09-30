// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.openbank.treasury.domain.model.Mt940Parser
import com.openbank.treasury.domain.model.StatementDirection
import com.openbank.treasury.infrastructure.iso20022.Camt053Parser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.LocalDate

class Mt940ParserTest {

    private val fixture = String(NostroFixtures.mt940())

    private fun parse(text: String) = Mt940Parser.parse(text.toByteArray())

    /** A minimal envelope-free statement; [lines] replaces the :61:/:86: block. */
    private fun minimal(
        lines: String = ":61:2609250925C10,00NTRFREF1\n",
        opening: String = ":60F:C260924EUR100,00",
        closing: String = ":62F:C260925EUR110,00",
    ) = ":20:REF\n:25:CZ8299990000000000001002\n:28C:1/1\n$opening\n$lines$closing\n"

    @Test
    fun `the fixture maps onto the same statement the camt053 fixture produces`() {
        val mt = Mt940Parser.parse(NostroFixtures.mt940())
        val camt = Camt053Parser.parse(NostroFixtures.xml())

        assertThat(mt.statementId).isEqualTo("SYNTH-940-0925/00268/001")
        assertThat(mt.iban).isEqualTo(camt.iban)
        assertThat(mt.currency).isEqualTo("CZK")
        assertThat(mt.openingDate).isEqualTo(camt.openingDate)
        assertThat(mt.statementDate).isEqualTo(camt.statementDate)
        assertThat(mt.openingBalance).isEqualByComparingTo(camt.openingBalance)
        assertThat(mt.closingBalance).isEqualByComparingTo(camt.closingBalance)
        assertThat(mt.entries.map { Triple(it.amount.toPlainString(), it.direction, it.bookingDate) })
            .containsExactlyElementsOf(
                camt.entries.map {
                    Triple(it.amount.toPlainString(), it.direction, it.bookingDate)
                },
            )
        assertThat(mt.entries.map { it.sequence }).containsExactly(1, 2, 3)
    }

    @Test
    fun `NONREF falls through to the servicing reference, then to the 86 narrative`() {
        val s = parse(minimal(":61:2609250925C10,00NTRFNONREF\n:86:first line\nsecond line\n"))
        assertThat(s.entries.single().reference).isEqualTo("first line second line")
        assertThat(parse(fixture).entries.map { it.reference })
            .containsExactly("SYNTH-SVCR-0001", "SYNTH-SVCR-0002", "SYNTH-SVCR-0003")
        assertThat(parse(minimal()).entries.single().reference).isEqualTo("REF1")
    }

    @Test
    fun `a reversal flips the direction - RC is money out, RD money in`() {
        val s = parse(
            minimal(
                ":61:2609250925RC10,00NTRFA\n:61:2609250925RD10,00NTRFB\n:61:2609250925C10,00NTRFC\n",
                closing = ":62F:C260925EUR110,00",
            ),
        )
        assertThat(s.entries.map { it.direction })
            .containsExactly(StatementDirection.DBIT, StatementDirection.CRDT, StatementDirection.CRDT)
    }

    @Test
    fun `a debit balance is negative and a funds code is accepted`() {
        val s = parse(minimal(":61:2609250925DR10,00NTRFX\n", ":60F:D260924EUR100,00", ":62F:D260925EUR110,"))
        assertThat(s.openingBalance).isEqualByComparingTo("-100")
        assertThat(s.closingBalance).isEqualByComparingTo("-110")
        assertThat(s.entries.single().amount).isEqualByComparingTo(BigDecimal.TEN)
    }

    @Test
    fun `the entry date wins over the value date, and a New Year crossing takes the nearest year`() {
        val s = parse(
            ":20:R\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C251230EUR0,\n" +
                ":61:2601021231C5,NTRFX\n:62F:C260102EUR5,\n",
        )
        assertThat(s.entries.single().bookingDate).isEqualTo(LocalDate.parse("2025-12-31"))
    }

    @Test
    fun `no envelope, LF line endings and no 61 lines at all are fine`() {
        val s = parse(minimal(lines = "", closing = ":62F:C260925EUR100,00"))
        assertThat(s.entries).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", // empty
            "not an mt940 at all",
            ":20:REF\n:25:CZ8299990000000000001002\n:28C:1\n:62F:C260925EUR100,00\n", // no :60F:
            ":20:A\n:20:B\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C260924EUR1,\n:62F:C260925EUR1,\n", // two :20:
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60M:C260924EUR1,\n:60F:C260924EUR1,\n:62F:C260925EUR1,\n",
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C260924EUR1,\n:62F:C260925USD1,\n", // ccy mismatch
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:X260924EUR1,\n:62F:C260925EUR1,\n", // bad mark
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C261324EUR1,\n:62F:C260925EUR1,\n", // month 13
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C260924EUR1.00\n:62F:C260925EUR1,\n", // decimal point
            ":20:A\n:25:not-an-iban!\n:28C:1\n:60F:C260924EUR1,\n:62F:C260925EUR1,\n",
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C260924EUR1,\n:61:garbage\n:62F:C260925EUR1,\n",
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:86:orphan\n:60F:C260924EUR1,\n:62F:C260925EUR1,\n",
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C260924EUR1,\n:99:unknown\n:62F:C260925EUR1,\n",
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:62F:C260925EUR1,\n:60F:C260924EUR1,\n", // order
            "stray text\n:20:A\n", // text before the first field
            "{4:\n:20:A\n", // unterminated block 4
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C260924EUR1,\n:61:2609250925C0,NTRFX\n:62F:C260925EUR1,\n",
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C260924EUR1,\n:61:2609250925C1,NTRFX\n:62F:C260925EUR1,\n",
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C260924EUR0,\n:61:2609200920C1,NTRFX\n:62F:C260925EUR1,\n",
            ":20:A\n:25:CZ8299990000000000001002\n:28C:1\n:60F:C260924EUR1234567890123456,\n:62F:C260925EUR1,\n",
        ],
    )
    fun `malformed input is an IllegalArgumentException (400), never anything else`(text: String) {
        assertThatThrownBy { parse(text) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a NUL, another control character or invalid UTF-8 is refused`() {
        assertThatThrownBy { parse(minimal().replace("REF1", "RE\u0000F")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("control character")
        assertThatThrownBy { parse(minimal().replace("REF1", "RE\u0007F")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val bad = minimal().toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        assertThatThrownBy { Mt940Parser.parse(bad) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("UTF-8")
    }

    @Test
    fun `an oversized body is refused before it is read`() {
        assertThatThrownBy { Mt940Parser.parse(ByteArray(Mt940Parser.MAX_BYTES + 1) { 'A'.code.toByte() }) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("larger than")
    }
}
