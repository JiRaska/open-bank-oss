// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.libs.testing.openapi.OpenApiWireValidator
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The published `openapi.yaml` must describe what the REAL customer-edge endpoints put on the wire
 * (#13210). `oasdiff` compares a spec with its own previous version and never with the
 * implementation, so a response schema that was wrong from its first commit stays wrong forever
 * with every gate green.
 *
 * Drives `GET /customer/v1/balances/{accountId}` and `GET /customer/v1/accounts` over real HTTP
 * (RestAssured) through the real resource, with `UpstreamClient` (the one seam to account-/balance-/delegation-service) replaced by a
 * path-routed stub via `QuarkusMock` — no extra Quarkus restart, so no extra heap in the shared suite JVM —
 * then validates each response body against the operation's own 200 schema taken from
 * `openapi.yaml` (libs-testing's `OpenApiWireValidator`: networknt JSON Schema validator, the one product-catalog / libs-domain use).
 *
 * The upstream fixtures are the shapes the upstream services actually serialise:
 * - balance-service `BalanceResource.getBalances` answers `mapOf("balances" to <domain Balance list>)`;
 * - account-service `AccountResource.listAccounts` answers a `CursorPage` (`data` + `pagination`).
 *
 * Sabotage-checked: with the spec reverted to the bare-array balance schema the first test fails.
 */
@QuarkusTest
@TestSecurity(user = "customer:$WIRE_PARTY_ID", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = WIRE_PARTY_ID)])
class OpenApiWireContractIT {

    private val mapper = ObjectMapper()

    /** upstream path (no query) -> JSON body; anything unrouted answers 404 like a real service would. */
    private val routes = mutableMapOf<String, String>()

    @BeforeEach
    fun installUpstreamStub() {
        routes.clear()
        routes["/api/v1/accounts/$WIRE_ACCOUNT_ID"] =
            """{"id":"$WIRE_ACCOUNT_ID","accountNumber":"CZ6508000000192000145399","accountType":"CURRENT",
                "partyId":"$WIRE_PARTY_ID","productId":"$WIRE_PRODUCT_ID","currencyCode":"CZK","status":"ACTIVE",
                "openedAt":"2024-01-01T00:00:00Z","closedAt":null,"goalName":null,"nickname":null}"""
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any<String>(), any<String>()) } answers {
            val body = routes[java.net.URI(firstArg<String>()).path]
            if (body == null) {
                Response.status(404).entity("""{"error":"not found"}""").build()
            } else {
                Response.ok(body).build()
            }
        }
        QuarkusMock.installMockForType(upstream, UpstreamClient::class.java)
    }

    @Test
    fun `GET balances accountId validates against its published 200 schema`() {
        routes["/api/v1/balances/$WIRE_ACCOUNT_ID"] =
            """{"balances":[
                {"id":"$WIRE_PRODUCT_ID","accountId":"$WIRE_ACCOUNT_ID","currency":"CZK","bookedAmount":1500.50,
                 "availableAmount":1400.50,"reservedAmount":100.00,"pendingAmount":0,
                 "updatedAt":"2026-10-17T10:15:30+02:00","version":7,"arrangedOverdraftLimit":0,
                 "notYetEffectiveCredit":0,"effectiveAvailableAmount":1400.50}]}"""

        val body = RestAssured.given().get("/customer/v1/balances/$WIRE_ACCOUNT_ID")
            .then().statusCode(200).extract().asString()

        assertConforms(body, "/balances/{accountId}")
        // Control: the wire really is the wrapped object, so a vacuous schema cannot pass this.
        assertThat(mapper.readTree(body).path("balances").isArray).isTrue()
    }

    @Test
    fun `GET accounts validates against its published 200 schema`() {
        routes["/api/v1/accounts"] =
            """{"data":[{"id":"$WIRE_ACCOUNT_ID","accountNumber":"CZ6508000000192000145399",
                "accountType":"CURRENT","partyId":"$WIRE_PARTY_ID","productId":"$WIRE_PRODUCT_ID",
                "currencyCode":"CZK","status":"ACTIVE","openedAt":"2024-01-01T00:00:00Z","closedAt":null}],
                "pagination":{"limit":20,"hasNextPage":false}}"""

        val body = RestAssured.given().get("/customer/v1/accounts")
            .then().statusCode(200).extract().asString()

        assertConforms(body, "/accounts")
        assertThat(mapper.readTree(body).path("data").isArray).isTrue()
    }

    @Test
    fun `the validator rejects a body of the wrong shape - negative control`() {
        val errors = validate(""""not a balance document"""", "/balances/{accountId}")
        assertThat(errors).isNotEmpty()
    }

    private fun assertConforms(body: String, specPath: String) {
        val errors = validate(body, specPath)
        assertThat(errors).describedAs("response of $specPath against openapi.yaml").isEmpty()
    }

    private fun validate(body: String, specPath: String): List<String> =
        OpenApiWireValidator().violations(body, specPath)
}

internal const val WIRE_PARTY_ID = "55555555-5555-4555-8555-555555555555"
internal const val WIRE_ACCOUNT_ID = "66666666-6666-4666-8666-666666666666"
internal const val WIRE_PRODUCT_ID = "77777777-7777-4777-8777-777777777777"
