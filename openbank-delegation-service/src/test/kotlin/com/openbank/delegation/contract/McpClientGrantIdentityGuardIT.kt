// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import com.openbank.delegation.contract.DelegationMcpClientGrantPactFolderProviderVerificationTest.Companion.CLIENT
import com.openbank.delegation.contract.DelegationMcpClientGrantPactFolderProviderVerificationTest.Companion.JKT
import com.openbank.delegation.contract.DelegationMcpClientGrantPactFolderProviderVerificationTest.Companion.PARTY
import com.openbank.delegation.integration.SpendReservationOutboxWriteIT
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test

/**
 * The negative half of mcp-service's resolve contract (ADR-0279 #3, ADR-0383 D3): a caller that is
 * NOT mcp-service's own service-account is refused by [com.openbank.delegation.infrastructure.rest.McpClientGrantResource]'s
 * in-code identity guard, so the pact replay stays meaningful even if authz stopped being enforced.
 * Kept out of the pact class because its `@BeforeEach` takes a `PactVerificationContext`, which only
 * the pact template can supply. Same test profile, so Quarkus reuses the booted application.
 * The mcp-service control proves the 403 comes from the identity guard, not a missing route, and
 * the tokenless case proves the route refuses an unauthenticated caller 401 rather than reaching
 * the handler (the `@RolesAllowed` on the resource; without it this answered 403).
 */
@QuarkusTest
@TestProfile(DelegationMcpClientGrantPactFolderProviderVerificationTest.ResolverDouble::class)
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class McpClientGrantIdentityGuardIT {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    private fun resolve() = given().port(testPort.toInt()).contentType(ContentType.JSON)
        .body("""{"grantorPartyId":"$PARTY","clientId":"$CLIENT","keyThumbprint":"$JKT"}""")
        .post("/api/v1/delegations/mcp-client/resolve")
        .then()

    @Test
    fun `a request with no token is refused 401`() {
        resolve().statusCode(401)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `another service-account holding ROLE_API is still refused 403`() {
        resolve().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `a caller that is not mcp-service is refused 403`() {
        resolve().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-mcp", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"),
            Claim(key = "azp", value = "openbank-mcp"),
            Claim(key = "preferred_username", value = "service-account-openbank-mcp"),
        ],
    )
    fun `mcp-service itself is admitted`() {
        resolve().statusCode(200)
    }

    private fun authorizeBalanceRead(proofKey: String = "0".repeat(64)) = given().port(testPort.toInt())
        .contentType(ContentType.JSON)
        .body(
            mapOf(
                "partyId" to PARTY.toString(),
                "clientId" to CLIENT,
                "keyThumbprint" to JKT,
                "accountId" to "d3d3d3d3-0000-4000-8000-000000000003",
                "proofKey" to proofKey,
            ),
        )
        .post("/api/v1/delegations/mcp-client/authorize-balance-read")
        .then()

    @Test
    fun `balance issuer endpoint requires authentication`() {
        authorizeBalanceRead().statusCode(401)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-mcp", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"),
            Claim(key = "azp", value = "openbank-mcp"),
            Claim(key = "preferred_username", value = "service-account-openbank-mcp"),
        ],
    )
    fun `MCP lookup service may not authorize balance issuance`() {
        authorizeBalanceRead().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-mcp-read-issuer", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"),
            Claim(key = "azp", value = "openbank-mcp-read-issuer"),
            Claim(key = "preferred_username", value = "service-account-openbank-mcp-read-issuer"),
        ],
    )
    fun `dedicated issuer reaches balance request validation`() {
        authorizeBalanceRead("invalid").statusCode(400)
    }

    private fun checkBalanceGrant(client: String = CLIENT) = given().port(testPort.toInt())
        .contentType(ContentType.JSON)
        .body(
            mapOf(
                "grantId" to "d3d3d3d3-0000-4000-8000-000000000004",
                "partyId" to PARTY.toString(),
                "clientId" to client,
                "accountId" to "d3d3d3d3-0000-4000-8000-000000000003",
            ),
        )
        .post("/api/v1/delegations/mcp-client/check-balance-grant")
        .then()

    @Test
    fun `balance grant check requires authentication`() {
        checkBalanceGrant().statusCode(401)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-mcp-read-issuer", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"),
            Claim(key = "azp", value = "openbank-mcp-read-issuer"),
            Claim(key = "preferred_username", value = "service-account-openbank-mcp-read-issuer"),
        ],
    )
    fun `issuer cannot perform balance-service grant rechecks`() {
        checkBalanceGrant().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-balance-mcp-reader", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"),
            Claim(key = "azp", value = "openbank-balance-mcp-reader"),
            Claim(key = "preferred_username", value = "service-account-openbank-balance-mcp-reader"),
        ],
    )
    fun `dedicated balance service reaches grant check request validation`() {
        checkBalanceGrant("").statusCode(400)
    }
}
