// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.mcp.infrastructure.customer.CustomerCapabilities
import com.openbank.mcp.infrastructure.customer.DelegationGrantClient
import com.openbank.mcp.infrastructure.customer.ResolveMcpClientGrantRequest
import com.openbank.mcp.infrastructure.customer.ResolveMcpClientGrantResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract for the ADR-0383 D3 grant lookup every customer-channel call depends on:
 * delegation-service `POST /api/v1/delegations/mcp-client/resolve`. Committed to
 * `pacts/openbank-mcp-service-openbank-delegation-service.json` and replayed on every PR by
 * delegation-service's `DelegationMcpClientGrantPactFolderProviderVerificationTest` (`@PactFolder`).
 *
 * The expected path is a LITERAL and only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests"). Two interactions, both branches of the fail-closed lookup: an active
 * grant for the exact triple, and no grant — an EMPTY list with 200, which is the refusal the cache
 * must never store.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-delegation-service", pactVersion = PactSpecVersion.V3)
class DelegationGrantResolvePactConsumerTest {

    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeGrantPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE_ACTIVE)
        .uponReceiving("POST resolve the active MCP_CLIENT grants for a party, client and DPoP key")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(REQUEST))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.minArrayLike("grants", 1) { g ->
                    g.uuid("grantId", UUID.fromString(GRANT_ID))
                    // Value: only ACCOUNT grants are kept by DelegationGrantSource.
                    g.stringValue("resourceType", "ACCOUNT")
                    g.uuid("resourceId", UUID.fromString(ACCOUNT_ID))
                    g.array("capabilities") { a -> a.stringValue(CustomerCapabilities.READ_BALANCES) }
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun noGrantPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE_NONE)
        .uponReceiving("POST resolve with no active MCP_CLIENT grant for the triple")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(REQUEST))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { o -> o.array("grants") { } }.build())
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeGrantPact")
    fun `an active grant names the account and its capabilities`(mockServer: MockServer) {
        val response = mapper.readValue<ResolveMcpClientGrantResponse>(post(mockServer))
        val grant = response.grants!!.single()
        assertThat(grant.resourceType).isEqualTo("ACCOUNT")
        assertThat(grant.resourceId).isEqualTo(UUID.fromString(ACCOUNT_ID))
        assertThat(grant.capabilities).containsExactly(CustomerCapabilities.READ_BALANCES)
    }

    @Test
    @PactTestFor(pactMethod = "noGrantPact")
    fun `no grant is an empty list, not an error`(mockServer: MockServer) {
        assertThat(mapper.readValue<ResolveMcpClientGrantResponse>(post(mockServer)).grants).isEmpty()
    }

    private fun post(mockServer: MockServer): String {
        assertThat(
            clientDerivedPath(),
        ).describedAs("DelegationGrantClient's @Path drifted from the pact").isEqualTo(EXPECTED_PATH)
        return given().baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(REQUEST))
            .post(clientDerivedPath())
            .then().statusCode(200).extract().asString()
    }

    companion object {
        const val CONSUMER = "openbank-mcp-service"
        const val PROVIDER = "openbank-delegation-service"
        const val STATE_ACTIVE = "an active MCP_CLIENT grant exists for the pact party, client and key"
        const val STATE_NONE = "no MCP_CLIENT grant exists for the pact party, client and key"
        const val PARTY_ID = "d3d3d3d3-0000-4000-8000-000000000001"
        const val GRANT_ID = "d3d3d3d3-0000-4000-8000-000000000002"
        const val ACCOUNT_ID = "d3d3d3d3-0000-4000-8000-000000000003"
        const val CLIENT_ID = "mcp-local-reference"
        const val JKT = "0000000000000000000000000000000000000000000"

        /** LITERAL, retyped from delegation-service's `McpClientGrantResource`. */
        const val EXPECTED_PATH = "/api/v1/delegations/mcp-client/resolve"

        val REQUEST = ResolveMcpClientGrantRequest(UUID.fromString(PARTY_ID), CLIENT_ID, JKT)

        fun clientDerivedPath(): String {
            val base = DelegationGrantClient::class.java.getAnnotation(Path::class.java).value
            val method = DelegationGrantClient::class.java.methods.single { it.name == "resolve" }
                .getAnnotation(Path::class.java).value
            return base + method
        }
    }
}
