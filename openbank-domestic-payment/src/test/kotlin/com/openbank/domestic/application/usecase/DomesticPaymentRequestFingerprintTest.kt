// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticTransferScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class DomesticPaymentRequestFingerprintTest {
    private val debtorAccountId = UUID.fromString("00000000-0000-0000-0000-000000000101")
    private val actorId = UUID.fromString("00000000-0000-0000-0000-000000000102")
    private val delegationId = UUID.fromString("00000000-0000-0000-0000-000000000103")
    private val reservationId = UUID.fromString("00000000-0000-0000-0000-000000000104")

    @Test
    fun `cosmetic whitespace case and decimal scale normalize to one fingerprint`() {
        val first = command()
        val equivalent = first.copy(
            debtorName = "Alice Example",
            amount = BigDecimal("1500.0000"),
            currency = "CZK",
            variableSymbol = "2026001",
            specificSymbol = "   ",
            messageForPayee = "Utility bill",
            actorScope = "https://issuer.example\u001f$actorId",
        )

        assertThat(DomesticPaymentRequestFingerprint.sha256(equivalent))
            .isEqualTo(DomesticPaymentRequestFingerprint.sha256(first))
    }

    @Test
    fun `every security-sensitive binding changes the fingerprint`() {
        val original = command()
        val originalFingerprint = DomesticPaymentRequestFingerprint.sha256(original)

        val mutations = listOf(
            original.copy(amount = BigDecimal("1500.01")),
            original.copy(creditorAccountNumber = "1111111111"),
            original.copy(actorId = UUID.randomUUID()),
            original.copy(actorScope = "https://other-issuer.example\u001f$actorId"),
            original.copy(delegationId = UUID.randomUUID()),
            original.copy(reservationId = UUID.randomUUID()),
            original.copy(synthetic = true),
        )

        assertThat(mutations.map(DomesticPaymentRequestFingerprint::sha256))
            .allSatisfy { assertThat(it).isNotEqualTo(originalFingerprint) }
            .doesNotHaveDuplicates()
    }

    @Test
    fun `nullable values are length framed and cannot collide with customer delimiters`() {
        val absent = command().copy(messageForPayee = null, statementLabel = "a|b")
        val present = command().copy(messageForPayee = "a", statementLabel = "b")

        assertThat(DomesticPaymentRequestFingerprint.sha256(absent))
            .isNotEqualTo(DomesticPaymentRequestFingerprint.sha256(present))
    }

    @Test
    fun `ignored client transfer scope does not change the fingerprint`() {
        val original = command().copy(transferScope = DomesticTransferScope.EXTERNAL)
        val changedIgnoredHint = original.copy(transferScope = DomesticTransferScope.OWN_ACCOUNTS)

        assertThat(DomesticPaymentRequestFingerprint.sha256(changedIgnoredHint))
            .isEqualTo(DomesticPaymentRequestFingerprint.sha256(original))
    }

    private fun command() = CreateDomesticPaymentCommand(
        idempotencyKey = "idem-fingerprint-1",
        debtorAccountId = debtorAccountId,
        debtorAccountNumber = " 1234567890 ",
        debtorBankCode = " 0800 ",
        debtorName = " Alice Example ",
        creditorAccountNumber = " 9876543210 ",
        creditorBankCode = " 0100 ",
        creditorName = " Brno Utility ",
        amount = BigDecimal("1500.00"),
        currency = " czk ",
        variableSymbol = " 2026001 ",
        specificSymbol = null,
        constantSymbol = " 0308 ",
        messageForPayee = " Utility bill ",
        priority = DomesticPaymentPriority.URGENT,
        technicalAccountCode = null,
        statementLabel = " Monthly settlement ",
        endToEndId = " ",
        actorId = actorId,
        actorScope = " https://issuer.example\u001f$actorId ",
        delegationId = delegationId,
        reservationId = reservationId,
    )
}
