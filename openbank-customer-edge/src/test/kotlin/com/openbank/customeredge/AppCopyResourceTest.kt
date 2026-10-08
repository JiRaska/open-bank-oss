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
    fun `profile-change introductions always gain the fixed review and confirm instruction`() {
        every { upstream.get(any(), null) } returns Response.ok(
            """{"styleVersion":3,"uiMessages":{"cs.send.profileChanged":"Změnili jste profil.","en.send.profileChanged":"Your profile changed.","cs.so.err.profileChanged":"Jste v jiném profilu.","en.so.err.profileChanged":"You switched profiles."}}""",
        ).build()
        val response = resource.copy()
        assertThat(response.status).isEqualTo(200)
        val messages = ObjectMapper()
            .valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.entity)
            .path("messages")
        assertThat(messages.path("cs.send.profileChanged").asText())
            .isEqualTo("Změnili jste profil. Zkontrolujte platbu a potvrďte ji znovu.")
        assertThat(messages.path("en.send.profileChanged").asText())
            .isEqualTo("Your profile changed. Review and confirm the payment again.")
        assertThat(messages.path("cs.so.err.profileChanged").asText())
            .isEqualTo("Jste v jiném profilu. Zkontrolujte příkaz a potvrďte ho znovu.")
        assertThat(messages.path("en.so.err.profileChanged").asText())
            .isEqualTo("You switched profiles. Review and confirm the order again.")
    }

    @Test
    fun `omitted overrides stay omitted so bundled complete fallback is not duplicated`() {
        every { upstream.get(any(), null) } returns Response.ok("""{"styleVersion":3,"uiMessages":{}}""").build()
        val response = resource.copy()
        val messages = ObjectMapper()
            .valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.entity)
            .path("messages")
        assertThat(messages.size()).isZero()
    }

    @Test
    fun `malformed safety introduction never reaches the public copy`() {
        listOf("<script>", "Your profile changed. Review and confirm the payment again.", " ").forEach { lead ->
            every { upstream.get(any(), null) } returns Response.ok(
                ObjectMapper().writeValueAsString(
                    mapOf("styleVersion" to 3, "uiMessages" to mapOf("en.send.profileChanged" to lead)),
                ),
            ).build()
            assertThat(resource.copy().status).isEqualTo(502)
        }
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
