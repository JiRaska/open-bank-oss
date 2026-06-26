// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.libs.iso20022.Pacs002Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.PaymentStatusReport
import com.openbank.sepa.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import io.mockk.every
import io.mockk.mockk
import io.quarkus.oidc.client.OidcClient
import io.quarkus.oidc.client.Tokens
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
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
    private val oidcClient = mockk<OidcClient> {
        every { tokens } returns Uni.createFrom().item(mockk<Tokens> { every { accessToken } returns "test-token" })
    }
    private val oidcClientInstance = mockk<Instance<OidcClient>> { every { get() } returns oidcClient }
    private val adapter = SchemeGatewayAdapter(client, ownBankBic = "OPBKCZ22", oidcClient = oidcClientInstance)
        .also { it.self = it }

    private fun payment(creditorBic: String? = "BNPAFRPPXXX") = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-1",
        type = SepaPaymentType.SCT,
        status = SepaPaymentStatus.VALIDATED,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        creditorBic = creditorBic,
        amount = BigDecimal("12.34"),
        currency = "EUR",
        remittanceInfo = "Invoice 1",
        endToEndId = "E2E-0001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.now(),
        completedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun pacs002(status: PaymentStatus, reason: String? = null): String = Pacs002Builder().build(
        PaymentStatusReport(
            messageId = "SIM-STS-1",
            creationDateTime = OffsetDateTime.now(ZoneOffset.UTC),
            originalEndToEndId = "E2E-0001",
            originalTransactionId = null,
            status = status,
            reasonCode = reason,
            additionalInfo = null,
        ),
    )

    @Test
    fun `an ACSC response is accepted`() {
        every { client.submitCreditTransfer(any(), any()) } returns Uni.createFrom().item(pacs002(PaymentStatus.ACSC))
        runBlocking {
            val outcome = adapter.submit(payment())
            assertThat(outcome.accepted).isTrue()
            assertThat(outcome.reasonCode).isNull()
        }
    }

    @Test
    fun `an RJCT response is rejected with its reason code`() {
        every { client.submitCreditTransfer(any(), any()) } returns
            Uni.createFrom().item(pacs002(PaymentStatus.RJCT, reason = "AC04"))
        runBlocking {
            val outcome = adapter.submit(payment())
            assertThat(outcome.accepted).isFalse()
            assertThat(outcome.reasonCode).isEqualTo("AC04")
        }
    }

    @Test
    fun `a missing creditor BIC is rejected RC01 without calling the gateway`() {
        runBlocking {
            val outcome = adapter.submit(payment(creditorBic = null))
            assertThat(outcome.accepted).isFalse()
            assertThat(outcome.reasonCode).isEqualTo("RC01")
        }
    }

    @Test
    fun `a gateway failure fails closed`() {
        every { client.submitCreditTransfer(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))
        assertThatThrownBy { runBlocking { adapter.submit(payment()) } }
            .isInstanceOf(SchemeGatewayUnavailableException::class.java)
    }

    @Test
    fun `an unparseable pacs_002 response fails closed`() {
        every { client.submitCreditTransfer(any(), any()) } returns Uni.createFrom().item("<not-a-pacs002/>")
        assertThatThrownBy { runBlocking { adapter.submit(payment()) } }
            .isInstanceOf(SchemeGatewayUnavailableException::class.java)
    }
}
