// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.pension.application.onboarding.TransferService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.Principal
import java.util.UUID

class ReceivingProviderResourceTest {
    private val transfers = mockk<TransferService>()
    private val identity = mockk<SecurityIdentity>()
    private val resource = ReceivingProviderResource().apply {
        transfers = this@ReceivingProviderResourceTest.transfers
        identity = this@ReceivingProviderResourceTest.identity
    }
    private val request = TransferOutRequest(UUID.randomUUID(), "P2", "Provider", "C-1")

    @Test
    fun `plain principal cannot impersonate a provider`() {
        every { identity.principal } returns Principal { "service-account-pension-provider-P2" }
        denied()
    }

    @Test
    fun `foreign client cannot reuse provider username`() {
        jwt("foreign", "service-account-pension-provider-P2", "subject")
        denied()
    }

    @Test
    fun `provider client without subject is denied`() {
        jwt("pension-provider-P2", "service-account-pension-provider-P2", "")
        denied()
    }

    @Test
    fun `human username on provider client is denied`() {
        jwt("pension-provider-P2", "human", "subject")
        denied()
    }

    @Test
    fun `bound provider reaches transfer service with requested counterparty`() {
        jwt("pension-provider-P2", "service-account-pension-provider-P2", "subject")
        val reached = IllegalStateException("test transfer service reached")
        coEvery { transfers.requestTransferOut(any()) } throws reached
        val actual = assertThrows<IllegalStateException> { runBlocking { resource.requestOut("key", request) } }
        assertThat(actual).isSameAs(reached)
        coVerify(exactly = 1) {
            transfers.requestTransferOut(
                match {
                    it.contractId == request.contractId &&
                        it.receiving.providerId == "P2" &&
                        it.partyId == null &&
                        it.challengeId == null
                },
            )
        }
    }

    private fun denied() {
        assertThrows<ForbiddenException> { runBlocking { resource.requestOut("key", request) } }
        coVerify(exactly = 0) { transfers.requestTransferOut(any()) }
    }

    private fun jwt(client: String, username: String, subject: String) {
        val principal = mockk<JsonWebToken>()
        every { principal.name } returns "service-account-pension-provider-P2"
        every { principal.subject } returns subject
        every { principal.getClaim<Any?>("azp") } returns client
        every { principal.getClaim<Any?>("preferred_username") } returns username
        every { identity.principal } returns principal
    }
}
