// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Optional

class McpNativeConnectResourceTest {
    private val mapper = jacksonObjectMapper()
    private val upstream = mockk<UpstreamClient>()
    private val proofs = mockk<McpNativePasskeyProofService>()

    @Test
    fun `handoff claim sends only the exact digest plus verified own party identity`() {
        every { upstream.postToService(ORIGIN, "$BASE/claim", PARTY, any(), HANDOFF_DIGEST) } answers {
            val request = mapper.readTree(arg<String>(3))
            assertThat(request["tokenSha256"].asText()).isEqualTo(HANDOFF_DIGEST)
            assertThat(request["keycloakSubject"].asText()).isEqualTo(SUBJECT)
            assertThat(request.toString()).doesNotContain(HANDOFF)
            Response.ok("""{"requestRef":"$REQUEST"}""").build()
        }
        val reply = resource().claim(null, McpNativeConnectResource.ClaimRequest(HANDOFF))
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.getHeaderString("Cache-Control")).isEqualTo("no-store")
        val json = mapper.readTree(mapper.writeValueAsString(reply.entity))
        assertThat(json["requestRef"].asText()).isEqualTo(REQUEST)
        val expected = openApi().path("paths").path("/mcp/connect-requests-v2/native-handoff/claim").path("post")
            .path("responses").path("200").path("content").path("application/json").path("schema").path("required")
        assertThat(expected.map { it.asText() }).containsExactlyElementsOf(json.fieldNames().asSequence().toList())
    }

    @Test
    fun `selected authority digest binds both proof operations and completed response is empty`() {
        selected()
        every { proofs.begin(any()) } answers {
            assertHostedBinding(firstArg())
            AuthenticationChallengeDto("A".repeat(43), "example.test")
        }
        val edge = resource()
        assertThat(edge.begin(null, REQUEST).status).isEqualTo(200)
        every { proofs.complete(any(), ASSERTION) } answers {
            val binding = firstArg<McpNativePasskeyBinding>()
            assertHostedBinding(binding)
            McpNativePasskeyProof(binding, "3".repeat(64), 1, 2)
        }
        every {
            upstream.postToService(ORIGIN, "$BASE/$REQUEST/mark-verified", PARTY, any(), REQUEST)
        } answers {
            val request = mapper.readTree(arg<String>(3))
            assertThat(request["contextSha256"].asText()).isEqualTo(CONTEXT)
            assertThat(request["keycloakSubject"].asText()).isEqualTo(SUBJECT)
            assertThat(request.size()).isEqualTo(2)
            Response.noContent().build()
        }
        val completion = edge.complete(null, REQUEST, ASSERTION)
        assertThat(completion.status).isEqualTo(204)
        assertThat(completion.entity).isNull()
        verify(exactly = 2) { upstream.postToService(ORIGIN, "$BASE/$REQUEST/selected-binding", PARTY, any(), REQUEST) }
    }

    @Test
    fun `disabled or wrong app sessions do not invoke the authority`() {
        assertThat(resource(enabled = false).claim(null, null).status).isEqualTo(404)
        assertThat(resource(azp = "assistant").begin(null, REQUEST).status).isEqualTo(403)
        assertThat(resource(actor = true).begin(null, REQUEST).status).isEqualTo(403)
        assertThat(resource(tokenAudience = "openbank-app").begin(null, REQUEST).status).isEqualTo(403)
        assertThat(resource().begin(PARTY, REQUEST).status).isEqualTo(403)
        assertThat(resource().claim(null, McpNativeConnectResource.ClaimRequest("invalid")).status).isEqualTo(400)
        verify(exactly = 0) { upstream.postToService(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `wrong authorization reference or refused proof never records bank verification`() {
        selected("9".repeat(64))
        assertThat(resource().begin(null, REQUEST).status).isEqualTo(503)
        verify(exactly = 0) { proofs.begin(any()) }
        selected()
        every { proofs.complete(any(), ASSERTION) } returns null
        val refused = resource().complete(null, REQUEST, ASSERTION)
        assertThat(refused.status).isEqualTo(403)
        assertThat(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(refused.entity).toString())
            .contains("Passkey proof refused")
        verify(exactly = 0) { upstream.postToService(ORIGIN, "$BASE/$REQUEST/mark-verified", PARTY, any(), REQUEST) }
    }

    private fun selected(ref: String = REQUEST) {
        every {
            upstream.postToService(ORIGIN, "$BASE/$REQUEST/selected-binding", PARTY, any(), REQUEST)
        } answers {
            assertThat(mapper.readTree(arg<String>(3))["keycloakSubject"].asText()).isEqualTo(SUBJECT)
            Response.ok(
                """{"requestRef":"$ref","contextSha256":"$CONTEXT","clientId":"synthetic-assistant",
                    "parBinding":"confidential_client","parJkt":null,"expiresAtEpochSecond":1800000000}""",
            ).build()
        }
    }

    @Test
    fun `native app rejection uses bounded metric reasons and the same response`() {
        val metrics = SimpleMeterRegistry()
        val cases = listOf(
            "ACTING_FOR" to resource(metrics = metrics).claim(PARTY, null),
            "ACTOR" to resource(actor = true, metrics = metrics).claim(null, null),
            "CLIENT" to resource(azp = "assistant", metrics = metrics).claim(null, null),
            "AUDIENCE" to resource(tokenAudience = "openbank-app", metrics = metrics).claim(null, null),
            "SUBJECT" to resource(tokenSubject = "", metrics = metrics).claim(null, null),
            "PARTY" to resource(party = null, metrics = metrics).claim(null, null),
        )
        val name = "openbank.edge.mcp.native.app.rejected"
        for ((reason, response) in cases) {
            assertThat(response.status).isEqualTo(403)
            assertThat(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.entity).toString())
                .contains("Your own bank app session is required")
            assertThat(metrics.get(name).tag("reason", reason).counter().count()).isEqualTo(1.0)
        }
        assertThat(metrics.meters.flatMap { it.id.tags }.map { it.key }.distinct()).containsExactly("reason")
        verify(exactly = 0) { upstream.postToService(any(), any(), any(), any(), any()) }
    }

    private fun assertHostedBinding(binding: McpNativePasskeyBinding) {
        assertThat(binding.partyId.toString()).isEqualTo(PARTY)
        assertThat(binding.keycloakUserId).isEqualTo(SUBJECT)
        assertThat(binding.requestRef).isEqualTo(REQUEST)
        assertThat(binding.contextSha256).isEqualTo(CONTEXT)
        assertThat(binding.parBinding).isEqualTo(McpNativeParBinding.ConfidentialClient)
    }

    private fun resource(
        enabled: Boolean = true,
        azp: String = "openbank-edge-webauthn",
        tokenAudience: String = "openbank-edge",
        actor: Boolean = false,
        tokenSubject: String = SUBJECT,
        party: String? = PARTY,
        metrics: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): McpNativeConnectResource {
        val jwt = mockk<JsonWebToken> {
            every { getClaim<Any?>("azp") } returns azp
            every { containsClaim("act") } returns actor
            every { audience } returns setOf(tokenAudience)
            every { subject } returns tokenSubject
            every { getClaim<String?>("party_id") } returns party
        }
        return McpNativeConnectResource(
            upstream,
            proofs,
            jwt,
            mapper,
            metrics,
            enabled,
            Optional.of(URI.create(ORIGIN)),
        )
    }

    private fun openApi() = requireNotNull(javaClass.classLoader.getResourceAsStream("openapi.yaml")).use {
        ObjectMapper(YAMLFactory()).readTree(it)
    }

    private companion object {
        const val ORIGIN = "https://delegation.test"
        const val BASE = "/api/v1/delegations/mcp-connect/customer/native-handoffs"
        const val PARTY = "80c16597-3f4a-44eb-8fcf-395bdef9ee06"
        const val SUBJECT = "native-customer"
        const val HANDOFF_DIGEST = "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"
        val REQUEST = "1".repeat(64)
        val CONTEXT = "2".repeat(64)
        val HANDOFF = "A".repeat(43)
        val ASSERTION = AuthCompleteRequestDto("AA", "AA", "AA", "AA", "AA")
    }
}
