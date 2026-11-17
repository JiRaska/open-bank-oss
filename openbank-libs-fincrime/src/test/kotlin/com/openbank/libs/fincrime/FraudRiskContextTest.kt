// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FraudRiskContextTest {

    private val iban = "DE89370400440532013000"

    @Test
    fun `spacing and case do not change the IBAN key`() {
        assertThat(CounterpartyRef.fromIban("de89 3704 0044 0532 0130 00"))
            .isEqualTo(CounterpartyRef.fromIban(iban))
        assertThat(CounterpartyRef.fromAccountNumber(" 19-2000145399 ", "0800"))
            .isEqualTo(CounterpartyRef.fromAccountNumber("19-2000145399", "08 00"))
    }

    @Test
    fun `different accounts give different keys`() {
        assertThat(CounterpartyRef.fromIban(iban)).isNotEqualTo(CounterpartyRef.fromIban("DE89370400440532013001"))
        assertThat(CounterpartyRef.fromAccountNumber("123", "0800"))
            .isNotEqualTo(CounterpartyRef.fromAccountNumber("123", "0100"))
    }

    @Test
    fun `the identifier is normalised and never printed`() {
        val known = CounterpartyRef.fromIban("de89 3704 0044 0532 0130 00")
        assertThat(known.account.scheme).isEqualTo(CounterpartyScheme.IBAN)
        assertThat(known.account.value).isEqualTo(iban)
        assertThat(known.toString()).doesNotContain("0532013000").contains("redacted")
        assertThat(CounterpartyRef.fromAccountNumber("19-2000145399", "0800").account.value)
            .isEqualTo("0800/19-2000145399")
        assertThat(known.account.hashCode()).isEqualTo(CounterpartyRef.fromIban(iban).account.hashCode())
    }

    @Test
    fun `blank or oversized identifiers are rejected`() {
        assertThatThrownBy { CounterpartyRef.fromIban("  ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CounterpartyRef.fromIban("X".repeat(65)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CounterpartyRef.fromAccountNumber("1", " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(CounterpartyAccount.of(CounterpartyScheme.IBAN, iban)).isNotEqualTo(iban)
    }

    @Test
    fun `Unknown carries its reason and compares by value`() {
        val unknown = CounterpartyRef.Unknown(UnknownCounterpartyReason.NOT_APPLICABLE_OWN_ACCOUNT)
        assertThat(unknown.reason).isEqualTo(UnknownCounterpartyReason.NOT_APPLICABLE_OWN_ACCOUNT)
        assertThat(unknown).isEqualTo(CounterpartyRef.Unknown(UnknownCounterpartyReason.NOT_APPLICABLE_OWN_ACCOUNT))
        assertThat(unknown).isNotEqualTo(CounterpartyRef.Unknown(UnknownCounterpartyReason.CARD_MERCHANT))
    }

    @Test
    fun `context validates its required fields and compares by value`() {
        val account = UUID.randomUUID()
        fun ctx(currency: String = "EUR") = FraudRiskContext(
            rail = "SEPA",
            amount = BigDecimal("10.00"),
            currency = currency,
            debtorAccountId = account,
            initiationMode = InitiationMode.CUSTOMER_PRESENT,
            counterparty = CounterpartyRef.fromIban(iban),
            evidence = FraudRiskEvidence(channel = InitiationChannel.WEB, vopResult = VopResult.MATCH),
        )
        assertThat(ctx()).isEqualTo(ctx())
        assertThatThrownBy { ctx(currency = "eur") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ctx().copy(rail = " ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `initiation modes are the closed published set`() {
        assertThat(InitiationMode.entries.map { it.name }).containsExactly(
            "CUSTOMER_PRESENT",
            "STANDING_INSTRUCTION",
            "MANDATE",
            "THIRD_PARTY_PROVIDER",
            "DELEGATE",
            "SYSTEM_RETRY",
        )
    }
}
