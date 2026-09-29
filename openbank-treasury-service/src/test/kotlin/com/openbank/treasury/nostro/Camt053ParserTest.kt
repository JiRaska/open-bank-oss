// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.openbank.treasury.domain.model.StatementDirection
import com.openbank.treasury.infrastructure.iso20022.Camt053Parser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class Camt053ParserTest {

    @Test
    fun `parses the synthetic fixture - balances, booked entries and references`() {
        val s = Camt053Parser.parse(NostroFixtures.xml())

        assertThat(s.statementId).isEqualTo("SYNTH-STMT-20260925-CZK")
        assertThat(s.iban).isEqualTo(NostroFixtures.IBAN)
        assertThat(s.currency).isEqualTo("CZK")
        assertThat(s.statementDate).isEqualTo(NostroFixtures.DATE)
        assertThat(s.openingBalance).isEqualByComparingTo("1000000.00")
        assertThat(s.closingBalance).isEqualByComparingTo("1155000.00")
        // The PDNG entry is not a booked movement and must not be read.
        assertThat(s.entries).hasSize(3)
        assertThat(s.entries.map { it.reference })
            .containsExactly(NostroFixtures.INBOUND_TX.toString(), "SYNTH-SVCR-0002", "SYNTH-SVCR-0003")
        assertThat(s.entries.map { it.direction })
            .containsExactly(StatementDirection.CRDT, StatementDirection.DBIT, StatementDirection.CRDT)
        assertThat(s.entries[1].amount).isEqualByComparingTo(BigDecimal("100000.00"))
    }

    @Test
    fun `a statement that does not foot is refused as a 400`() {
        val tampered = String(NostroFixtures.xml()).replace(">250000.00<", ">250001.00<").toByteArray()
        assertThatThrownBy { Camt053Parser.parse(tampered) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not foot")
    }

    @Test
    fun `a DOCTYPE is refused - no XXE`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/hosts">]>""" +
            "<Document>&x;</Document>"
        assertThatThrownBy { Camt053Parser.parse(xxe.toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DOCTYPE")
    }

    @Test
    fun `malformed XML, a missing balance and two statements are each a 400`() {
        val noClosing = String(NostroFixtures.xml()).replace("<Cd>CLBD</Cd>", "<Cd>ITBD</Cd>").toByteArray()
        val twoStmts = String(NostroFixtures.xml()).replace("</Stmt>", "</Stmt><Stmt><Id>X</Id></Stmt>").toByteArray()
        listOf("not xml".toByteArray(), noClosing, twoStmts).forEach { bad ->
            assertThatThrownBy { Camt053Parser.parse(bad) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `DtTm on balances and booking dates contributes its date as written`() {
        val s = Camt053Parser.parse(NostroFixtures.eurXml())

        assertThat(s.currency).isEqualTo("EUR")
        // 23:59:59+02:00 is 21:59:59Z the same day; the date written is the one taken either way,
        // and it is never shifted into another zone.
        assertThat(s.statementDate).isEqualTo(NostroFixtures.DATE)
        assertThat(
            s.entries.map {
                it.bookingDate
            },
        ).containsExactly(NostroFixtures.DATE.minusDays(1), NostroFixtures.DATE)
        assertThat(s.firstDate).isEqualTo(NostroFixtures.DATE.minusDays(1))

        val offsetLess = String(NostroFixtures.eurXml()).replace("2026-09-24T09:15:00+02:00", "2026-09-24T09:15:00")
        assertThat(Camt053Parser.parse(offsetLess.toByteArray()).entries[0].bookingDate)
            .isEqualTo(NostroFixtures.DATE.minusDays(1))

        val garbage = String(NostroFixtures.eurXml()).replace("2026-09-24T09:15:00+02:00", "yesterday")
        assertThatThrownBy {
            Camt053Parser.parse(garbage.toByteArray())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an amount the store cannot hold exactly is a 400 - more than 4 decimals or 15 integer digits`() {
        val xml = String(NostroFixtures.xml())
        val fourDecimals = xml.replace(">5000.00<", ">5000.0000<")
        assertThat(Camt053Parser.parse(fourDecimals.toByteArray()).entries[2].amount).isEqualByComparingTo("5000")

        val fiveDecimals = xml.replace(">5000.00<", ">5000.00001<").replace(">1155000.00<", ">1155000.00001<")
        assertThatThrownBy { Camt053Parser.parse(fiveDecimals.toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decimal places")

        val balanceTooFine = xml.replace(">1000000.00<", ">1000000.00000<")
        assertThatThrownBy { Camt053Parser.parse(balanceTooFine.toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decimal places")

        val sixteenDigits = xml.replace(">1000000.00<", ">1000000000000000.00<")
        assertThatThrownBy { Camt053Parser.parse(sixteenDigits.toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("integer digits")
    }

    @Test
    fun `a statement spanning more than 31 days is a 400`() {
        val wide = String(
            NostroFixtures.xml(),
        ).replaceFirst("<BookgDt><Dt>2026-09-25</Dt>", "<BookgDt><Dt>2026-08-20</Dt>")
        assertThatThrownBy { Camt053Parser.parse(wide.toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("more than 31 days")
    }
}
