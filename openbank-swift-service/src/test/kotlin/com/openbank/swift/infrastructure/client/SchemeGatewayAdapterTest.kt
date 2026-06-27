// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.libs.iso20022.Pacs002Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.PaymentStatusReport
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SchemeGatewayAdapterTest {
    private val client = mockk<ClearingSimulatorClient>()
    private val adapter = SchemeGatewayAdapter(client).also { it.self = it }

    @Test
    fun `ACSC response maps to accepted=true and rawMt is populated`(): Unit = runBlocking {
        every { client.submitCreditTransfer(any()) } returns Uni.createFrom().item(pacs002(PaymentStatus.ACSC))

        val outcome = adapter.submit(message())

        assertThat(outcome.accepted).isTrue()
        assertThat(outcome.rawMt).isNotBlank()
    }

    @Test
    fun `RJCT with RC01 maps to accepted=false and rawMt is still populated`(): Unit = runBlocking {
        every { client.submitCreditTransfer(any()) } returns Uni.createFrom().item(pacs002(PaymentStatus.RJCT, "RC01"))

        val outcome = adapter.submit(message())

        assertThat(outcome.accepted).isFalse()
        assertThat(outcome.reasonCode).isEqualTo("RC01")
        assertThat(outcome.rawMt).isNotBlank()
    }

    @Test
    fun `gateway failure wraps in SchemeGatewayUnavailableException`() {
        every { client.submitCreditTransfer(any()) } returns Uni.createFrom().failure(RuntimeException("timeout"))

        assertThatThrownBy { runBlocking { adapter.submit(message()) } }
            .isInstanceOf(SchemeGatewayUnavailableException::class.java)
    }

    @Test
    fun `OUR charge code maps to pacs008 with DEBT ChargeBearer`(): Unit = runBlocking {
        every { client.submitCreditTransfer(any()) } answers { call ->
            val xml = call.invocation.args[0] as String
            assertThat(xml).contains("<ChrgBr>DEBT</ChrgBr>")
            Uni.createFrom().item(pacs002(PaymentStatus.ACSC))
        }

        adapter.submit(message(chargeCode = "OUR"))
    }

    @Test
    fun `SHA charge code maps to SHAR`(): Unit = runBlocking {
        every { client.submitCreditTransfer(any()) } answers { call ->
            val xml = call.invocation.args[0] as String
            assertThat(xml).contains("<ChrgBr>SHAR</ChrgBr>")
            Uni.createFrom().item(pacs002(PaymentStatus.ACSC))
        }

        adapter.submit(message(chargeCode = "SHA"))
    }

    private fun message(chargeCode: String = "SHA") = SwiftMessage(
        id = UUID.fromString("55555555-5555-5555-5555-555555555555"),
        idempotencyKey = "test", messageType = SwiftMessageType.MT103,
        senderBic = "ABCDEFGH", receiverBic = "IJKLMNOP",
        transactionReference = "TRX-001", relatedReference = null,
        valueDate = "20260622", currency = "EUR", amountMinorUnits = 100_00L,
        orderingCustomerAccount = "DE89370400440532013000", orderingCustomerAccountId = null,
        orderingCustomerName = "Alice",
        beneficiaryAccount = "GB33BUKB20201555555555", beneficiaryName = "Bob",
        remittanceInfo = "Invoice 1", chargeCode = chargeCode,
        priority = SwiftPriority.NORMAL, status = SwiftStatus.VALIDATED,
        rawMt = null, ackReceivedAt = null, rejectionReason = null,
        createdAt = Instant.now(), updatedAt = Instant.now(),
    )

    private fun pacs002(status: PaymentStatus, reason: String? = null): String = Pacs002Builder().build(
        PaymentStatusReport(
            messageId = "SIM-STS-1",
            creationDateTime = OffsetDateTime.now(ZoneOffset.UTC),
            originalEndToEndId = "TRX-001",
            originalTransactionId = null,
            status = status,
            reasonCode = reason,
            additionalInfo = null,
        ),
    )
}
