// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerGameResource
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomerGameResourceTest {
    private val party = UUID.randomUUID()
    private val upstream = mockk<UpstreamClient>()
    private val parties = mockk<CustomerPartyResolver> { every { resolve(any()) } returns party }
    private val resource = CustomerGameResource(upstream, parties, ObjectMapper(), "https://engagement.test")

    @Test
    fun `catalogue uses resolved party and projects only game fields`() {
        every {
            upstream.get("https://engagement.test/api/v1/game/levels?partyId=$party&beta=true", party.toString())
        } returns
            Response.ok(
                """{"revision":2,"actor":"internal","levels":[{"level":{"id":"embankment","theme":"embankment","order":0,"titleCs":"N","titleEn":"E","state":"LIVE","requirement":{"kind":"NONE"},"private":"secret"},"unlocked":true,"completed":false}]}""",
            ).build()
        val response = resource.levels(true, null)
        val json = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.entity)
        assertThat(response.status).isEqualTo(200)
        assertThat(json["levels"][0]["id"].asText()).isEqualTo("embankment")
        assertThat(json.toString()).doesNotContain("secret", "internal", party.toString())
    }

    @Test
    fun `completion never accepts a client party and forwards beta only as a presentation hint`() {
        every { upstream.post(any(), party.toString(), "{}", any()) } returns Response.noContent().build()
        assertThat(resource.complete("charles_bridge", true, null).status).isEqualTo(204)
        verify {
            upstream.post(
                "https://engagement.test/api/v1/game/levels/charles_bridge/completion?partyId=$party&beta=true",
                party.toString(),
                "{}",
                null,
            )
        }
        assertThat(resource.complete("../admin", true, null).status).isEqualTo(400)
    }

    @Test
    fun `malformed and unavailable upstreams do not become an unlocked catalogue`() {
        for (body in listOf("{}", "not json", """{"revision":1,"levels":[{}]}""")) {
            every { upstream.get(any(), any()) } returns Response.ok(body).build()
            assertThat(resource.levels(false, null).status).isEqualTo(502)
        }
        every { upstream.get(any(), any()) } returns Response.status(500).entity("private").build()
        val response = resource.levels(false, null)
        assertThat(response.status).isEqualTo(503)
        assertThat(response.entity.toString()).doesNotContain("private")
    }
}
