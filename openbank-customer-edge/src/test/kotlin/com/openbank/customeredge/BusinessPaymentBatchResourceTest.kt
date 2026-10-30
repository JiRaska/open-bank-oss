// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.rest.ActingForResolver
import com.openbank.customeredge.infrastructure.rest.BusinessPaymentBatchResource
import com.openbank.customeredge.infrastructure.rest.PartyMergeResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BusinessPaymentBatchResourceTest {
    private val upstream = mockk<UpstreamClient>()
    private val acting = mockk<ActingForResolver>()
    private val merged = mockk<PartyMergeResolver>()
    private val jwt = mockk<JsonWebToken>()
    private val resource = BusinessPaymentBatchResource(upstream, acting, merged, true).apply {
        this.jwt = this@BusinessPaymentBatchResourceTest.jwt
        domesticUrl = "http://localhost:8116"
    }
    private val human = UUID.randomUUID()
    private val company = UUID.randomUUID()

    private fun humanToken() {
        every { jwt.getClaim<String>("party_id") } returns human.toString()
        every { jwt.subject } returns human.toString()
        every { jwt.claimNames } returns setOf("party_id", "sub", "azp")
        every { jwt.getClaim<Any?>("azp") } returns "openbank-app"
        every { jwt.getClaim<Any?>("preferred_username") } returns null
        every { merged.resolve(human) } returns human
    }

    @Test
    fun `missing or expired acting-for is refused before backend access`() {
        humanToken()
        assertThrows(ForbiddenException::class.java) { resource.list(null, 0, 20) }
        every { acting.resolve(human, company.toString()) } throws ForbiddenException("expired mandate")
        assertThrows(ForbiddenException::class.java) { resource.list(company.toString(), 0, 20) }
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `live company mandate binds backend scope`() {
        humanToken()
        every { acting.resolve(human, company.toString()) } returns company
        every { acting.profilesOf(human) } returns listOf(mapOf("partyId" to company, "partyType" to "COMPANY"))
        every { upstream.get(any(), company.toString()) } returns Response.ok("{}").build()
        assertEquals(200, resource.list(company.toString(), 0, 20).status)
        verify(exactly = 1) {
            upstream.get("http://localhost:8116/api/v1/business-payment-batches?page=0&size=20", company.toString())
        }
    }

    @Test
    fun `non-company acting-for is refused`() {
        humanToken()
        every { acting.resolve(human, company.toString()) } returns company
        every { acting.profilesOf(human) } returns listOf(mapOf("partyId" to company, "partyType" to "INDIVIDUAL"))
        assertThrows(ForbiddenException::class.java) { resource.list(company.toString(), 0, 20) }
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `machine notes and malformed identity fail before mandate or backend access`() {
        val badClaims = listOf(
            "client_id" to null, "client_id" to "", "client_id" to 123,
            "clientId" to null, "clientId" to "batch-client",
            "preferred_username" to null, "preferred_username" to 123,
            "preferred_username" to "", "preferred_username" to "service-account-client",
            "azp" to "openbank-synthetic-retail", "azp" to null, "azp" to 123,
        )
        badClaims.forEach { (claim, value) ->
            humanToken()
            every { jwt.claimNames } returns setOf("party_id", "sub", "azp", claim)
            every { jwt.getClaim<Any?>(claim) } returns value
            assertThrows(ForbiddenException::class.java) { resource.list(company.toString(), 0, 20) }
        }
        verify(exactly = 0) { acting.resolve(any(), any()) }
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `missing subject and unreadable claims are not human evidence`() {
        humanToken()
        every { jwt.subject } returns ""
        assertThrows(ForbiddenException::class.java) { resource.list(company.toString(), 0, 20) }
        humanToken()
        every { jwt.claimNames } throws IllegalStateException("unreadable claims")
        assertThrows(ForbiddenException::class.java) { resource.list(company.toString(), 0, 20) }
        humanToken()
        every { jwt.getClaim<Any?>("azp") } throws IllegalStateException("unreadable client")
        assertThrows(ForbiddenException::class.java) { resource.list(company.toString(), 0, 20) }
        verify(exactly = 0) { acting.resolve(any(), any()) }
        verify(exactly = 0) { upstream.get(any(), any()) }
    }
}
