// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.`in`.AuthorizationUseCase
import com.openbank.account.application.port.`in`.PaymentProposalDecision
import com.openbank.account.application.port.`in`.PaymentProposalOutcome
import com.openbank.libs.domain.money.Money
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PaymentProposalAuthorizationResourceTest {
    private val accountId = UUID.randomUUID()
    private val makerId = UUID.randomUUID()
    private val grantId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val useCase: AuthorizationUseCase = mockk()
    private val resource = PaymentProposalAuthorizationResource(useCase)

    @Test
    fun `allowed maker decision exposes grant evidence but no execution token`(): Unit = runBlocking {
        coEvery { useCase.authorizePaymentProposal(accountId, makerId, Money.of("100.00", "CZK")) } returns
            PaymentProposalDecision(PaymentProposalOutcome.ALLOWED, grantId, ownerId)

        val response = resource.check(accountId, makerId, "100.00", "CZK")
        assertThat(response.status).isEqualTo(200)

        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any>
        assertThat(body).containsEntry("authorized", true)
            .containsEntry("delegationId", grantId.toString())
            .containsEntry("grantorPartyId", ownerId.toString())
        assertThat(body).doesNotContainKeys("paymentId", "reservationId")
    }

    @Test
    fun `refusal does not reveal grant evidence`(): Unit = runBlocking {
        coEvery { useCase.authorizePaymentProposal(accountId, makerId, any()) } returns
            PaymentProposalDecision(PaymentProposalOutcome.LIMIT_EXCEEDED)

        val response = resource.check(accountId, makerId, "100.00", "CZK")

        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any>
        assertThat(body).containsEntry("authorized", false).containsEntry("outcome", "LIMIT_EXCEEDED")
        assertThat(body).doesNotContainKeys("delegationId", "grantorPartyId")
    }

    @Test
    fun `missing amount is rejected before authority lookup`(): Unit = runBlocking {
        val error = runCatching { resource.check(accountId, makerId, null, "CZK") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { useCase.authorizePaymentProposal(any(), any(), any()) }
    }

    @Test
    fun `zero and negative amounts are rejected before authority lookup`(): Unit = runBlocking {
        assertThat(resource.check(accountId, makerId, "0.00", "CZK").status).isEqualTo(400)
        assertThat(resource.check(accountId, makerId, "-1.00", "CZK").status).isEqualTo(400)
        coVerify(exactly = 0) { useCase.authorizePaymentProposal(any(), any(), any()) }
    }
}
