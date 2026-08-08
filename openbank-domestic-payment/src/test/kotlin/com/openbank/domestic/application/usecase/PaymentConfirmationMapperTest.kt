// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

class PaymentConfirmationMapperTest {

    @Test
    fun `templateCodeFor selects the EN code only for a case-insensitive en`() {
        assertThat(PaymentConfirmationMapper.templateCodeFor("en"))
            .isEqualTo(PaymentConfirmationMapper.TEMPLATE_CODE_EN)
        assertThat(PaymentConfirmationMapper.templateCodeFor("EN"))
            .isEqualTo(PaymentConfirmationMapper.TEMPLATE_CODE_EN)
        assertThat(PaymentConfirmationMapper.templateCodeFor(" en "))
            .isEqualTo(PaymentConfirmationMapper.TEMPLATE_CODE_EN)
        assertThat(
            PaymentConfirmationMapper.templateCodeFor("cs"),
        ).isEqualTo(PaymentConfirmationMapper.TEMPLATE_CODE_CS)
        assertThat(
            PaymentConfirmationMapper.templateCodeFor(null),
        ).isEqualTo(PaymentConfirmationMapper.TEMPLATE_CODE_CS)
        assertThat(PaymentConfirmationMapper.templateCodeFor("garbage"))
            .isEqualTo(PaymentConfirmationMapper.TEMPLATE_CODE_CS)
    }

    @Test
    fun `toConfirmationData rejects a payment that has not SETTLED`() {
        assertThatThrownBy {
            PaymentConfirmationMapper.toConfirmationData(payment(status = DomesticPaymentStatus.SENT_TO_CLEARING))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("SETTLED")
    }

    @Test
    fun `toConfirmationData maps every required field under the document namespace`() {
        val settledAt = Instant.parse("2026-08-01T10:15:30Z")
        val p = payment(
            status = DomesticPaymentStatus.SETTLED,
            settledAt = settledAt,
            variableSymbol = "123456",
        )

        val data = PaymentConfirmationMapper.toConfirmationData(p)

        @Suppress("UNCHECKED_CAST")
        val document = data["document"] as Map<String, Any?>
        assertThat(document["paymentReference"]).isEqualTo(p.id.toString())
        assertThat(document["endToEndId"]).isEqualTo(p.endToEndId)
        assertThat(document["executedAt"]).isEqualTo(settledAt.toString())
        assertThat(document["amount"]).isEqualTo("20.00")
        assertThat(document["currency"]).isEqualTo("CZK")
        assertThat(document["creditorName"]).isEqualTo(p.creditorName)
        assertThat(document["remittanceInfo"]).isEqualTo("123456")
        assertThat(document["status"]).isEqualTo("SETTLED")
        assertThat(document["scaEvidenceRef"]).isNull()
    }

    @Test
    fun `remittanceInfo falls back to messageForPayee when there is no variable symbol`() {
        val p = payment(
            status = DomesticPaymentStatus.SETTLED,
            variableSymbol = null,
            messageForPayee = "Rent August",
        )

        @Suppress("UNCHECKED_CAST")
        val document = PaymentConfirmationMapper.toConfirmationData(p)["document"] as Map<String, Any?>
        assertThat(document["remittanceInfo"]).isEqualTo("Rent August")
    }

    @Test
    fun `remittanceInfo is blank when neither variable symbol nor message is present`() {
        val p = payment(status = DomesticPaymentStatus.SETTLED, variableSymbol = null, messageForPayee = null)

        @Suppress("UNCHECKED_CAST")
        val document = PaymentConfirmationMapper.toConfirmationData(p)["document"] as Map<String, Any?>
        assertThat(document["remittanceInfo"]).isEqualTo("")
    }

    @Test
    fun `debtor and creditor IBANs are valid ISO 13616 mod-97 checksums`() {
        val p = payment(status = DomesticPaymentStatus.SETTLED)

        @Suppress("UNCHECKED_CAST")
        val document = PaymentConfirmationMapper.toConfirmationData(p)["document"] as Map<String, Any?>
        val debtorIban = document["debtorIban"] as String
        val creditorIban = document["creditorIban"] as String

        assertThat(debtorIban).startsWith("CZ").hasSize(24)
        assertThat(creditorIban).startsWith("CZ").hasSize(24)
        assertThat(ibanChecksumValid(debtorIban)).describedAs("debtorIban=%s", debtorIban).isTrue()
        assertThat(ibanChecksumValid(creditorIban)).describedAs("creditorIban=%s", creditorIban).isTrue()
    }

    /** Standard IBAN validation (ISO 7064 MOD 97-10): rearrange, letters -> digits, mod 97 == 1. */
    private fun ibanChecksumValid(iban: String): Boolean {
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        val numeric = rearranged.map { ch ->
            if (ch.isDigit()) ch.toString() else (ch.uppercaseChar() - 'A' + 10).toString()
        }
            .joinToString("")
        return BigInteger(numeric).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    private fun payment(
        status: DomesticPaymentStatus,
        settledAt: Instant? = Instant.parse("2026-08-01T10:00:00Z"),
        variableSymbol: String? = null,
        messageForPayee: String? = null,
    ) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-${UUID.randomUUID()}",
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Debtor",
        creditorAccountNumber = "0987654321",
        creditorBankCode = "2010",
        creditorName = "Creditor Name",
        amount = BigDecimal("20.00"),
        currency = "CZK",
        variableSymbol = variableSymbol,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = messageForPayee,
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.EXTERNAL,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOMS1234567890",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.parse("2026-08-01T09:00:00Z"),
        settledAt = settledAt,
        createdAt = Instant.parse("2026-08-01T08:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T10:00:00Z"),
    )
}
