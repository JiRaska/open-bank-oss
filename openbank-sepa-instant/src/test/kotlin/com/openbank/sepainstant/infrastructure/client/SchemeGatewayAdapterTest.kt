// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.libs.iso20022.Pacs002Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.PaymentStatusReport
import com.openbank.sepainstant.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepainstant.domain.model.SctInstPayment
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SchemeGatewayAdapterTest {
    private val client = mockk<ClearingSimulatorClient>()
    private val adapter = SchemeGatewayAdapter(client, ownBankBic = "OPBKCZ22")

    private val fixedNow = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC)

    private fun payment(creditorBic: String? = "BNPAFRPPXXX") = SctInstPayment(
        idempotencyKey = "idem-1",
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        creditorBic = creditorBic,
        amount = BigDecimal("12.34"),
        remittanceInfo = "Invoice 1",
        endToEndId = "E2E-0001",
        executionTimeoutAt = null,
        settledAt = null,
        recalledAt = null,
        recallReason = null,
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private fun pacs002(status: PaymentStatus, reason: String? = null): String = Pacs002Builder().build(
        PaymentStatusReport(
            messageId = "SIM-STS-1",
            creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 0, 0, 0, ZoneOffset.UTC),
            originalEndToEndId = "E2E-0001",
            originalTransactionId = null,
            status = status,
            reasonCode = reason,
            additionalInfo = null,
        ),
    )

    @Test
    fun `an ACSC response is accepted`() {
        every { client.submitCreditTransfer(any()) } returns Uni.createFrom().item(pacs002(PaymentStatus.ACSC))
        val outcome = adapter.submit(payment()).await().indefinitely()
        assertThat(outcome.accepted).isTrue()
        assertThat(outcome.reasonCode).isNull()
    }

    @Test
    fun `an RJCT response is rejected with its reason code`() {
        every { client.submitCreditTransfer(any()) } returns
            Uni.createFrom().item(pacs002(PaymentStatus.RJCT, reason = "AC04"))
        val outcome = adapter.submit(payment()).await().indefinitely()
        assertThat(outcome.accepted).isFalse()
        assertThat(outcome.reasonCode).isEqualTo("AC04")
    }

    @Test
    fun `a missing creditor BIC is rejected RC01 without calling the gateway`() {
        val outcome = adapter.submit(payment(creditorBic = null)).await().indefinitely()
        assertThat(outcome.accepted).isFalse()
        assertThat(outcome.reasonCode).isEqualTo("RC01")
    }

    @Test
    fun `a gateway failure fails closed`() {
        every { client.submitCreditTransfer(any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))
        assertThatThrownBy { adapter.submit(payment()).await().indefinitely() }
            .isInstanceOf(SchemeGatewayUnavailableException::class.java)
    }
}
