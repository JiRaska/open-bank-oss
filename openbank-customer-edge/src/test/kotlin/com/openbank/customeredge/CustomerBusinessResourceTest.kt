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
    fun `claim binds the token party, never a body-supplied one, and encodes the token`() {
        val upstream = mockk<UpstreamClient>()
        val url = slot<String>()
        val body = slot<String>()
        every { upstream.post(capture(url), any(), capture(body), any()) } returns Response.ok().build()

        resource(upstream).claim("tok/with space")

        assertThat(url.captured).isEqualTo("$kyb/api/v1/kyb/invitations/tok%2Fwith+space/claim")
        assertThat(body.captured).isEqualTo("""{"partyId":"$caller"}""")
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
