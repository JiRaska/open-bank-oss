// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.SchemeGatewayUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.iso20022.Pacs002Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.PaymentStatusReport
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SchemeGatewayAdapterTest {
    private val client = mockk<ClearingSimulatorClient>()
    private val adapter = SchemeGatewayAdapter(
        client,
        ownBankBic = "OPBKCZ22",
        ownBankCode = "2000",
    ).also { it.self = it }

    @Test
    fun `ACSC response maps to accepted=true`(): Unit = runBlocking {
        every { client.submitCreditTransfer(any()) } returns Uni.createFrom().item(pacs002(PaymentStatus.ACSC))

        val outcome = adapter.submit(payment())

        assertThat(outcome.accepted).isTrue()
        assertThat(outcome.reasonCode).isNull()
    }

    @Test
    fun `RJCT with AM05 maps to accepted=false reasonCode AM05`(): Unit = runBlocking {
        every { client.submitCreditTransfer(any()) } returns Uni.createFrom().item(pacs002(PaymentStatus.RJCT, "AM05"))

        val outcome = adapter.submit(payment())

        assertThat(outcome.accepted).isFalse()
        assertThat(outcome.reasonCode).isEqualTo("AM05")
    }

    @Test
    fun `gateway failure wraps in SchemeGatewayUnavailableException`() {
        every { client.submitCreditTransfer(any()) } returns Uni.createFrom().failure(RuntimeException("timeout"))

        assertThatThrownBy { runBlocking { adapter.submit(payment()) } }
            .isInstanceOf(SchemeGatewayUnavailableException::class.java)
    }

    @Test
    fun `bbanToIban embeds bank codes in pacs008 CZ IBANs`(): Unit = runBlocking {
        every { client.submitCreditTransfer(any()) } answers { call ->
            val xml = call.invocation.args[0] as String
            assertThat(xml).containsPattern("CZ[0-9]{2}20000000001234567890") // debtor bankCode=2000
            assertThat(xml).containsPattern("CZ[0-9]{2}08000000009876543210") // creditor bankCode=0800
            Uni.createFrom().item(pacs002(PaymentStatus.ACSC))
        }

        adapter.submit(payment())
    }

    private fun payment() = DomesticPayment(
        id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        idempotencyKey = "test", status = DomesticPaymentStatus.VALIDATED,
        debtorAccountId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
        debtorAccountNumber = "1234567890", debtorBankCode = "2000",
        debtorName = "Alice", creditorAccountNumber = "9876543210",
        creditorBankCode = "0800", creditorName = "Bob",
        amount = BigDecimal("100.00"), currency = "CZK",
        variableSymbol = "123", specificSymbol = null, constantSymbol = null,
        messageForPayee = null, priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null, statementLabel = null,
        endToEndId = "E2E-DOM-001", rejectReason = null, rejectDetail = null,
        submittedAt = null, settledAt = null,
        createdAt = Instant.now(), updatedAt = Instant.now(),
    )

    private fun pacs002(status: PaymentStatus, reason: String? = null): String = Pacs002Builder().build(
        PaymentStatusReport(
            messageId = "SIM-STS-1",
            creationDateTime = OffsetDateTime.now(ZoneOffset.UTC),
            originalEndToEndId = "E2E-DOM-001",
            originalTransactionId = null,
            status = status,
            reasonCode = reason,
            additionalInfo = null,
        ),
    )
}
