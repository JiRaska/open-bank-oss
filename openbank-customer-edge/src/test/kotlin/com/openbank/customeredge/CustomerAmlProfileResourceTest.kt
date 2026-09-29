// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerAmlProfileResource
import com.openbank.customeredge.infrastructure.rest.PartyMergeResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomerAmlProfileResourceTest {

    private val human = UUID.randomUUID()
    private val survivor = UUID.randomUUID()
    private val stranger = UUID.randomUUID()
    private val partyBase = "http://party-service.party.svc:8111"
    private val upstream = mockk<UpstreamClient>()

    private fun resource(partyClaim: String? = human.toString(), merge: (UUID) -> UUID = { it }) =
        CustomerAmlProfileResource(
            upstream,
            mockk<PartyMergeResolver> { every { resolve(any()) } answers { merge(firstArg()) } },
        ).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns partyClaim
                every { subject } returns partyClaim
            }
            objectMapper = ObjectMapper()
            partyServiceUrl = partyBase
        }

    @Test
    fun `GET reads the token's own profile and passes the upstream answer through`() {
        every { upstream.get("$partyBase/api/v1/parties/$human/aml-profile", human.toString()) } returns
            Response.status(404).entity("""{"error":"AML_PROFILE_NOT_FOUND"}""").build()

        val response = resource().get()

        assertThat(response.status).isEqualTo(404)
        assertThat(response.entity as String).contains("AML_PROFILE_NOT_FOUND")
    }

    @Test
    fun `PUT forwards the declaration for the token's party and drops a body-supplied party id`() {
        val forwarded = slot<String>()
        every {
            upstream.put("$partyBase/api/v1/parties/$human/aml-profile", human.toString(), capture(forwarded))
        } returns
            Response.ok("""{"version":1}""").build()

        val response = resource().put(
            """{"partyId":"$stranger","id":"$stranger","usPerson":false,"tin":{"SK":"123"},"truthful":true}""",
        )

        assertThat(response.status).isEqualTo(200)
        val sent = ObjectMapper().readTree(forwarded.captured)
        assertThat(sent.has("partyId")).isFalse()
        assertThat(sent.has("id")).isFalse()
        assertThat(sent.path("tin").path("SK").asText()).isEqualTo("123")
        assertThat(sent.path("usPerson").asBoolean(true)).isFalse()
        assertThat(forwarded.captured).doesNotContain(stranger.toString())
    }

    @Test
    fun `a party-service 400 reaches the app unchanged`() {
        every { upstream.put(any(), any(), any()) } returns
            Response.status(400).entity("""{"message":"truthful must be true"}""").build()

        val response = resource().put("""{"truthful":false}""")

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity as String).contains("truthful must be true")
    }

    @Test
    fun `a body that is not a JSON object is a 400 and never reaches party-service`() {
        listOf(null, "", "[]", "not json", "42").forEach { body ->
            assertThat(resource().put(body).status).describedAs("body %s", body).isEqualTo(400)
        }
        verify(exactly = 0) { upstream.put(any(), any(), any()) }
    }

    @Test
    fun `a merged identity is followed to the surviving party`() {
        every { upstream.get("$partyBase/api/v1/parties/$survivor/aml-profile", survivor.toString()) } returns
            Response.ok("{}").build()

        assertThat(resource(merge = { survivor }).get().status).isEqualTo(200)
    }

    @Test
    fun `a token without a party UUID is refused`() {
        assertThatThrownBy { resource(partyClaim = null).get() }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { resource(partyClaim = "not-a-uuid").get() }.isInstanceOf(ForbiddenException::class.java)
    }
}
