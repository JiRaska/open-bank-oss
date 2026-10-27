// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class Semt002ReaderTest {
    private val reader = Semt002Reader()
    private val validator = Iso20022Validator.forSchema(Semt002Reader.SCHEMA)
    private val fixture = requireNotNull(javaClass.getResource("/semt002/pension-co-2026-12-31.xml")).readText()

    @Test
    fun `the fixture conforms to the vendored semt_002_001_11 schema`() {
        assertThat(validator.validate(fixture)).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `the schema is not vacuous - an out-of-order element fails it`() {
        val swapped = fixture.replaceFirst("<SfkpgAcct>", "<SfkpgAcctX>").replaceFirst("</SfkpgAcct>", "</SfkpgAcctX>")
        assertThat(validator.validate(swapped)).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
        val badCfi = fixture.replaceFirst("DBFTFB", "DB1")
        assertThat(validator.validate(badCfi)).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }

    @Test
    fun `reads statement date, account and every holding`() {
        val s = reader.read(fixture)

        assertThat(s.statementId).isEqualTo("CUST-PS-20261231")
        assertThat(s.statementDate).isEqualTo(LocalDate.of(2026, 12, 31))
        assertThat(s.safekeepingAccount).isEqualTo("PSCO-OWN-0001")
        assertThat(s.holdings.map { it.isin })
            .containsExactly("CZ0001005037", "CZ0001006266", "IE00B4L5Y983", "CZ0008008018")
        val bond = s.holdings.first()
        assertThat(bond.cfi).isEqualTo("DBFTFB")
        assertThat(bond.quantityType).isEqualTo(HoldingQuantityType.FACE_AMOUNT)
        assertThat(bond.quantity).isEqualByComparingTo("10000000")
        assertThat(bond.holdingValue).isEqualByComparingTo("9875000.00")
        assertThat(bond.holdingValueCurrency).isEqualTo("CZK")
        assertThat(s.holdings[2].quantityType).isEqualTo(HoldingQuantityType.UNIT)
    }

    @Test
    fun `a statement with no balances is an empty holding, not an error`() {
        val empty = fixture.replace(Regex("(?s)<BalForAcct>.*</BalForAcct>"), "")
        assertThat(validator.validate(empty)).isEqualTo(Iso20022ValidationResult.Valid)
        assertThat(reader.read(empty).holdings).isEmpty()
    }

    @Test
    fun `Sgn false makes the holding value negative`() {
        val xml = fixture.replaceFirst("<Sgn>true</Sgn>", "<Sgn>false</Sgn>")
        assertThat(reader.read(xml).holdings.first().holdingValue).isEqualByComparingTo(BigDecimal("-9875000.00"))
    }

    @Test
    fun `DtTm contributes its date as written`() {
        val xml = fixture.replace("<Dt>2026-12-31</Dt>", "<DtTm>2026-12-31T23:30:00-05:00</DtTm>")
        assertThat(reader.read(xml).statementDate).isEqualTo(LocalDate.of(2026, 12, 31))
    }

    @Test
    fun `a delta statement is refused`() {
        assertRefused(fixture.replace("<Cd>COMP</Cd>", "<Cd>DELT</Cd>"), "COMP")
    }

    @Test
    fun `one page of a paginated report is refused`() {
        assertRefused(fixture.replace("<LastPgInd>true</LastPgInd>", "<LastPgInd>false</LastPgInd>"), "page")
    }

    @Test
    fun `a balance without an ISIN is refused`() {
        assertRefused(fixture.replaceFirst("<ISIN>CZ0001005037</ISIN>", ""), "ISIN")
    }

    @Test
    fun `the same ISIN twice is refused`() {
        assertRefused(fixture.replace("CZ0001006266", "CZ0001005037"), "more than once")
    }

    @Test
    fun `a balance without a quantity is refused`() {
        assertRefused(fixture.replaceFirst("<FaceAmt>10000000</FaceAmt>", ""), "quantity")
    }

    @Test
    fun `a missing safekeeping account is refused`() {
        assertRefused(fixture.replace("<Id>PSCO-OWN-0001</Id>", ""), "SfkpgAcct")
    }

    @Test
    fun `malformed XML, dates and numbers are parse exceptions (400), never other errors`() {
        assertRefused("<Document", "well-formed")
        assertRefused(fixture.replace("2026-12-31", "2026-13-31"), "date")
        assertRefused(fixture.replace("<Unit>12000</Unit>", "<Unit>12x</Unit>"), "number")
        assertThat(Semt002ParseException("x")).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a DOCTYPE is refused (XXE)`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
            fixture.substringAfter("?>")
        assertThatThrownBy { reader.read(xxe) }.isInstanceOf(Semt002ParseException::class.java)
    }

    private fun assertRefused(xml: String, fragment: String) {
        assertThatThrownBy { reader.read(xml) }
            .isInstanceOf(Semt002ParseException::class.java)
            .hasMessageContaining(fragment)
    }
}
