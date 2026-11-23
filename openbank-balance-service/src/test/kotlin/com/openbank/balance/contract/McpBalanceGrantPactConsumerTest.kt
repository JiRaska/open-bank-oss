// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.infrastructure.client.McpBalanceGrantRequest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-delegation-service", pactVersion = PactSpecVersion.V3)
class McpBalanceGrantPactConsumerTest {

    @Pact(consumer = "openbank-balance-service", provider = "openbank-delegation-service")
    fun permit(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an active MCP balance grant matches the requested binding")
        .uponReceiving("recheck a certificate-bound MCP balance grant")
        .path(PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Idempotency-Key" to GRANT))
        .body("""{"grantId":"$GRANT","partyId":"$PARTY","clientId":"$CLIENT","accountId":"$ACCOUNT"}""")
        .willRespondWith()
        .status(204)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "permit")
    fun `active grant check requires no response payload`(mockServer: MockServer) {
        val request = McpBalanceGrantRequest(
            UUID.fromString(GRANT),
            UUID.fromString(PARTY),
            CLIENT,
            UUID.fromString(ACCOUNT),
        )
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .header("Idempotency-Key", request.grantId.toString())
            .body(ObjectMapper().writeValueAsString(request))
            .post(PATH)
            .then()
            .statusCode(204)
    }

    private companion object {
        const val PATH = "/api/v1/delegations/mcp-client/check-balance-grant"
        const val GRANT = "b4b4b4b4-0000-4000-8000-000000000001"
        const val PARTY = "b4b4b4b4-0000-4000-8000-000000000002"
        const val ACCOUNT = "b4b4b4b4-0000-4000-8000-000000000003"
        const val CLIENT = "mcp-local-reference"
    }
}
