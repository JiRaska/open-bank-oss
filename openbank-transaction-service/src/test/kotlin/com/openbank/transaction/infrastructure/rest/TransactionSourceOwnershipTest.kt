// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.transaction.application.port.`in`.TransactionUseCase
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.SecurityContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class TransactionSourceOwnershipTest {

    @Test
    fun `untrusted source claims are rejected before calling the use case`(): Unit = runBlocking {
        val useCase = mockk<TransactionUseCase>()
        val resource = TransactionResource(useCase, mockk(), mockk(), mockk())
        val request = InitiateTransactionRequest(
            idempotencyKey = "synthetic-source-ownership",
            type = "DEBIT",
            sourceAccountId = UUID.randomUUID(),
            amount = BigDecimal("12.34"),
            currencyCode = "CZK",
            valueDate = "2026-01-06",
            rail = "DOMESTIC",
            originatingPaymentId = UUID.randomUUID(),
        )
        val securityContext = mockk<SecurityContext>()
        for ((principal, role, client) in listOf(
            Triple("operator-test", false, "openbank-domestic-payment"),
            Triple("service-account-other-payment", true, "openbank-domestic-payment"),
            Triple(DOMESTIC_PRINCIPAL, true, "other-payment"),
            Triple(DOMESTIC_PRINCIPAL, false, "openbank-domestic-payment"),
            Triple(DOMESTIC_PRINCIPAL, true, null),
            Triple(DOMESTIC_PRINCIPAL, true, 42),
        )) {
            resource.identity = identity(principal, role, client)
            assertThat(runCatching { resource.initiateTransaction(request, securityContext) }.exceptionOrNull())
                .isInstanceOf(ForbiddenException::class.java)
        }
        resource.identity = identity(DOMESTIC_PRINCIPAL, true, "openbank-domestic-payment")
        for (invalidShape in listOf(
            request.copy(rail = "SEPA_CT"),
            request.copy(type = "CREDIT"),
            request.copy(sourceAccountId = null),
        )) {
            assertThat(runCatching { resource.initiateTransaction(invalidShape, securityContext) }.exceptionOrNull())
                .isInstanceOf(ForbiddenException::class.java)
        }
        coVerify(exactly = 0) { useCase.initiateTransaction(any()) }
    }

    private fun identity(name: String, apiRole: Boolean, client: Any?): SecurityIdentity {
        val jwt = mockk<JsonWebToken>()
        every { jwt.name } returns name
        every { jwt.getClaim<Any>("azp") } returns client
        return mockk<SecurityIdentity>().also {
            every { it.principal } returns jwt
            every { it.hasRole("ROLE_API") } returns apiRole
        }
    }

    private companion object {
        const val DOMESTIC_PRINCIPAL = "service-account-openbank-domestic-payment"
    }
}
