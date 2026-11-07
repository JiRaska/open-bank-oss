// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BusinessPaymentBatchDraftAuthTest {
    private val store = mockk<BusinessPaymentBatchDraftStore>()
    private val jwt = mockk<JsonWebToken>()
    private val identity = mockk<SecurityIdentity>()
    private val resource = BusinessPaymentBatchDraftResource(store, ObjectMapper()).apply {
        this.identity = this@BusinessPaymentBatchDraftAuthTest.identity
    }

    @Test
    fun `only edge service token can name a company header`(): Unit = runBlocking {
        every { identity.principal } returns jwt
        every { jwt.getClaim<String>("preferred_username") } returns "service-account-other"
        every { jwt.getClaim<String>("azp") } returns "other-client"
        assertThrows(ForbiddenException::class.java) {
            runBlocking { resource.list(UUID.randomUUID(), 0, 20) }
        }
        coVerify(exactly = 0) { store.list(any<UUID>(), any<Int>(), any<Int>()) }
    }

    @Test
    fun `accepted edge identity scopes list to supplied verified company`(): Unit = runBlocking {
        val company = UUID.randomUUID()
        every { identity.principal } returns jwt
        every { jwt.getClaim<String>("preferred_username") } returns "service-account-openbank-edge"
        every { jwt.getClaim<String>("azp") } returns "openbank-edge"
        coEvery { store.list(company, 0, 20) } returns emptyList()
        assertEquals(200, resource.list(company, 0, 20).status)
    }
}
