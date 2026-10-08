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
}
