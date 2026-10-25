// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.reference

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReferencePayloadsTest {
    private fun invalid(block: ThrowingCallable) =
        assertThatThrownBy(block).isInstanceOf(IllegalArgumentException::class.java)

    private val index = IndexPayload("PRIBOR_3M", "CZK", "3M", "ACT/360", 2, "CERTIS", IndexSource.FEED)

    @Test
    fun `an index validates its conventions and names its fixing calendar`() {
        index.validate()
        assertThat(index.references()).containsExactly(ReferenceRef(ReferenceKind.CALENDAR, "CERTIS"))
        invalid { index.copy(tenor = "3Q").validate() }
        invalid { index.copy(dayCount = "ACT/366").validate() }
        invalid { index.copy(fixingLagBusinessDays = -1).validate() }
        invalid { index.copy(currency = "czk").validate() }
    }

    @Test
    fun `a calendar date cannot be both added and removed`() {
        val d = LocalDate.parse("2026-12-31")
        invalid { CalendarPayload("CERTIS", BaseCalendar.CERTIS, listOf(d), listOf(d)).validate() }
    }

    @Test
    fun `a counterparty checks LEI, country and limits`() {
        val cp = CounterpartyPayload(
            "SIMBK-A",
            "Bank A",
            ReferenceCounterpartyKind.BANK,
            null,
            "CZ",
            mapOf(
                "CZK" to BigDecimal("1.00"),
            ),
        )
        cp.validate()
        invalid { cp.copy(lei = "SHORT").validate() }
        invalid { cp.copy(countryCode = "CZE").validate() }
        invalid { cp.copy(limits = mapOf("CZK" to BigDecimal("-1"))).validate() }
        assertThat(
            cp.copy(nettingAgreementId = "NET-1").references(),
        ).containsExactly(ReferenceRef(ReferenceKind.NETTING, "NET-1"))
    }

    @Test
    fun `an SSI is keyed counterparty-colon-currency and SWIFT needs a BIC`() {
        val ssi = SsiPayload("SIMBK-A", "EUR", SettlementRail.SEPA, "TEST-ACCOUNT-0001")
        ssi.validate()
        assertThat(ssi.key()).isEqualTo("SIMBK-A:EUR")
        invalid { ssi.copy(rail = SettlementRail.SWIFT).validate() }
        ssi.copy(rail = SettlementRail.SWIFT, bic = "ABCDCZPP").validate()
    }

    @Test
    fun `a security checks the ISIN check digit and coupon terms by type`() {
        val bond = SecurityPayload(
            "XS0000000017", "Example bond", "ISSUER-1", SecurityType.BOND, "USD",
            LocalDate.parse("2020-01-01"), LocalDate.parse("2030-01-01"), BigDecimal("1000"),
            BigDecimal("2.5"), "ANNUAL", "30/360", "FOLLOWING", "TARGET2",
        )
        bond.validate()
        invalid { bond.copy(isin = "XS0000000018").validate() }
        invalid { bond.copy(couponRate = null).validate() }
        invalid { bond.copy(type = SecurityType.TREASURY_BILL).validate() }
        bond.copy(type = SecurityType.TREASURY_BILL, couponRate = null, couponFrequency = null).validate()
        assertThat(bond.references()).contains(ReferenceRef(ReferenceKind.COUNTERPARTY, "ISSUER-1"))
    }

    @Test
    fun `a trading book is FVTPL and cannot be its own parent`() {
        invalid { BookPayload("TB", null, "t", BookPurpose.TRADING_BOOK, Ifrs9Category.AMORTISED_COST).validate() }
        invalid { BookPayload("BB", "BB", "b", BookPurpose.BANKING_BOOK, Ifrs9Category.AMORTISED_COST).validate() }
        BookPayload("BB-2", "BB", "b", BookPurpose.BANKING_BOOK, Ifrs9Category.FVOCI).validate()
    }

    @Test
    fun `buckets are contiguous from day zero with only the last open`() {
        BucketSetPayload("S", "s", listOf(Bucket("a", 0, 7), Bucket("b", 7, null))).validate()
        invalid { BucketSetPayload("S", "s", listOf(Bucket("a", 1, 7))).validate() }
        invalid { BucketSetPayload("S", "s", listOf(Bucket("a", 0, 7), Bucket("b", 8, null))).validate() }
        invalid { BucketSetPayload("S", "s", listOf(Bucket("a", 0, null), Bucket("b", 7, null))).validate() }
        invalid { BucketSetPayload("S", "s", emptyList()).validate() }
    }

    @Test
    fun `netting and regulatory codes`() {
        invalid { NettingPayload("N", "CP", emptyList(), true).validate() }
        invalid { NettingPayload("N", "CP", listOf("CZK", "CZK"), true).validate() }
        assertThat(RegulatoryCodePayload(RegulatoryList.LCR, "L1", "Level 1").key()).isEqualTo("LCR:L1")
    }

    @Test
    fun `an unknown kind is a 400`() {
        invalid { ReferenceKind.fromWire("curve") }
        assertThat(ReferenceKind.fromWire("bucket-set")).isEqualTo(ReferenceKind.BUCKET_SET)
    }
}
