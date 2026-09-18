// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.usecase.CreatePaymentProposalDraftOutcome
import com.openbank.domestic.application.usecase.DomesticPaymentProposalDraftService
import com.openbank.domestic.infrastructure.rest.dto.CreateDomesticPaymentRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.container.ContainerRequestContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class DomesticPaymentProposalResourceTest {
    private val drafts = mockk<DomesticPaymentProposalDraftService>()
    private val resource = DomesticPaymentProposalResource(drafts)
    private val maker = UUID.fromString("00000000-0000-0000-0000-000000000101")
    private val requestContext = mockk<ContainerRequestContext>(relaxed = true)

    @Test
    fun `operator token cannot spoof the maker header even with payment role`(): Unit = runBlocking {
        resource.identity = identity("another-client", "operator")

        val response = resource.createDraft(request(), maker.toString(), "key-1", requestContext)

        assertThat(response.status).isEqualTo(403)
        coVerify(exactly = 0) { drafts.create(any(), any()) }
    }

    @Test
    fun `edge identity without a valid human maker fails closed`(): Unit = runBlocking {
        resource.identity = identity("openbank-edge", "service-account-openbank-edge")

        val response = resource.createDraft(request(), "not-a-party-id", "key-1", requestContext)

        assertThat(response.status).isEqualTo(403)
        coVerify(exactly = 0) { drafts.create(any(), any()) }
    }

    @Test
    fun `trusted edge sends the human maker to the authoritative draft service`(): Unit = runBlocking {
        resource.identity = identity("openbank-edge", "service-account-openbank-edge")
        coEvery { drafts.create(maker, any()) } returns CreatePaymentProposalDraftOutcome.Refused

        val response = resource.createDraft(request(), maker.toString(), "key-1", requestContext)

        assertThat(response.status).isEqualTo(403)
        coVerify {
            drafts.create(
                maker,
                match { it.actorId == null && it.idempotencyKey == "key-1" && !it.synthetic },
            )
        }
    }

    private fun identity(azp: String, username: String): SecurityIdentity {
        val jwt = mockk<JsonWebToken> {
            every { getClaim<String>("azp") } returns azp
            every { getClaim<String>("preferred_username") } returns username
        }
        return mockk { every { principal } returns jwt }
    }

    private fun request() = CreateDomesticPaymentRequest(
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Owner",
        creditorAccountNumber = "9876543210",
        creditorBankCode = "0100",
        creditorName = "Supplier",
        amount = BigDecimal("1500.00"),
        currency = "CZK",
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = "STANDARD",
        statementLabel = null,
        endToEndId = null,
    )
}
