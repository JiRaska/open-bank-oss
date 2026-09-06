// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerBusinessResource
import com.openbank.customeredge.infrastructure.rest.PartyMergeResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** The initiator/signer is the token's HUMAN on every route; a body naming someone else never reaches kyb-service. */
class CustomerBusinessResourceTest {

    private val caller = UUID.randomUUID()
    private val stranger = UUID.randomUUID()
    private val kyb = "http://kyb-service.kyb.svc:8157"

    private fun resource(upstream: UpstreamClient): CustomerBusinessResource {
        val merge = mockk<PartyMergeResolver> { every { resolve(any()) } answers { firstArg() } }
        return CustomerBusinessResource(upstream, merge).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns caller.toString()
                every { subject } returns caller.toString()
            }
            objectMapper = ObjectMapper()
            kybServiceUrl = kyb
        }
    }

    @Test
    fun `start fills in the initiator from the token and forwards the rest of the body`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val body = slot<String>()
        val party = slot<String>()
        every { upstream.post(capture(url), capture(party), capture(body), any()) } returns Response.status(201).build()

        val response = resource(upstream).start("""{"scheme":"CZ_ICO","identifier":"45274649"}""")

        assertThat(response.status).isEqualTo(201)
        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/cases")
        assertThat(party.captured).isEqualTo(caller.toString())
        assertThat(body.captured).contains("\"initiatorPartyId\":\"$caller\"")
        assertThat(body.captured).contains("\"identifier\":\"45274649\"")
    }

    @Test
    fun `start refuses a body naming another initiator before upstream`() {
        val upstream = mockk<UpstreamClient>()
        val response = resource(
            upstream,
        ).start("""{"scheme":"CZ_ICO","identifier":"45274649","initiatorPartyId":"$stranger"}""")
        assertThat(response.status).isEqualTo(403)
        io.mockk.verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `claim binds the token party, never a body-supplied one`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val body = slot<String>()
        every { upstream.post(capture(url), any(), capture(body), any()) } returns Response.ok().build()

        resource(upstream).claim("inv_9f3ab21c-4d0e")

        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/invitations/inv_9f3ab21c-4d0e/claim")
        assertThat(body.captured).isEqualTo("""{"partyId":"$caller"}""")
    }

    @Test
    fun `claim refuses a token that is not opaque and URL-safe, before upstream`() {
        // Encoding it would also be safe, and that is exactly the reasoning CodeQL reported as an
        // SSRF finding on this path: the guarantee lived two indirections away, in UpstreamClient's
        // host allowlist. A malformed token is now a 400 here, at the edge.
        // libs-runtime maps IllegalArgumentException to 400 — never a service-local mapper (#526).
        val upstream = mockk<UpstreamClient>()

        listOf("tok/with space", "http://evil.example", "short", "a".repeat(129))
            .forEach { bad ->
                assertThatThrownBy { resource(upstream).claim(bad) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }

        io.mockk.verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `schemes refuses a country filter that is not an alpha-2 code`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()

        assertThatThrownBy { resource(upstream).schemes("../../etc") }
            .isInstanceOf(IllegalArgumentException::class.java)

        // Blank still means "no filter", which is what an absent query parameter has always meant.
        resource(upstream).schemes("")
        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/schemes")
        resource(upstream).schemes("CZ")
        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/schemes?country=CZ")
    }

    @Test
    fun `mine is scoped to the token party on the upstream query`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        every { upstream.get(capture(url), any()) } returns Response.ok("[]").build()
        resource(upstream).mine()
        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/cases?partyId=$caller")
    }
}
