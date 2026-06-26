// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Camt054BuilderTest {
    private val builder = Camt054Builder()
    private val validator = Iso20022Validator.forSchema(Camt054Builder.SCHEMA_RESOURCE)

    private fun notification(currency: String = "EUR", iban: String = "FR1420041010050500013M02606") =
        DebitCreditNotification(
            messageId = "OB-NTF-0001",
            creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 17, 0, 0, ZoneOffset.UTC),
            notificationId = "NTF-0001",
            accountIban = iban,
            entryReference = "ENTRY-0001",
            amount = BigDecimal("12.34"),
            currency = currency,
            direction = CreditDebitIndicator.CRDT,
            bookingDate = LocalDate.of(2026, 6, 22),
            endToEndId = "E2E-0001",
        )

    @Test
    fun `a booked credit notification validates against the vendored XSD`() {
        val xml = builder.build(notification())
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:camt.054.001.08")
        assertThat(xml).contains("<CdtDbtInd>CRDT</CdtDbtInd>")
        assertThat(xml).contains("<Cd>BOOK</Cd>")
        assertThat(xml).contains("Ccy=\"EUR\"")
        assertThat(xml).contains("<EndToEndId>E2E-0001</EndToEndId>")
        assertThat(xml).contains("<Dt>2026-06-22</Dt>")
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `a malformed IBAN is rejected by XSD validation`() {
        val result = validator.validate(builder.build(notification(iban = "not-an-iban")))
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }

    @Test
    fun `a non-ISO currency is rejected by XSD validation`() {
        val result = validator.validate(builder.build(notification(currency = "E")))
        assertThat(result).isInstanceOf(Iso20022ValidationResult.Invalid::class.java)
    }
}
