// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.AppCopyResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class AppCopyResourceTest {
    private val upstream = mockk<UpstreamClient>()
    private val resource = AppCopyResource(upstream, ObjectMapper(), Optional.of("https://communication.test"))

    @Test
    fun `public projection exposes only approved messages and version`() {
        every { upstream.get(any(), null) } returns Response.ok(
            """{"styleVersion":2,"uiMessages":{"cs.status.loading":"Hledám."},"tone":"internal","maker":"private"}""",
        ).build()
        val response = resource.copy()
        assertThat(response.status).isEqualTo(200)
        val json = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.entity)
        assertThat(json.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("version", "messages")
        assertThat(json.path("messages").path("cs.status.loading").asText()).isEqualTo("Hledám.")
        verify { upstream.get("https://communication.test/api/v1/personas/customer-copilot/published", null) }
    }

    @Test
    fun `outages and malformed responses never replace known good copy`() {
        listOf(Response.status(503).build(), Response.ok("broken").build(), Response.ok("{}").build()).forEach {
            every { upstream.get(any(), null) } returns it
            assertThat(resource.copy().status).isBetween(500, 599)
        }
    }

    @Test
    fun `retirement explicitly clears cached messages`() {
        every { upstream.get(any(), null) } returns Response.status(404).build()
        val response = resource.copy()
        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(mapOf("version" to 0, "messages" to emptyMap<String, String>()))
    }

    @Test
    fun `unconfigured service is unavailable without calling upstream`() {
        assertThat(AppCopyResource(upstream, ObjectMapper(), Optional.empty()).copy().status).isEqualTo(503)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }
}
