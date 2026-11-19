// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate
import org.junit.jupiter.api.Test
import java.security.PrivateKey

/**
 * ADR-0383 D1 with the customer channel OFF in the PRODUCTION tenant shape: real signed tokens, the
 * staff tenant only, `resolve-tenants-with-issuer=false` and the customers tenant disabled (what
 * `MCP_CUSTOMER_ENABLED=false` yields). Which layer refuses a customers-realm token:
 *
 * - **Quarkus OIDC, before McpEndpoint.** A token signed by the customers realm fails the staff
 *   tenant's signature check, and a customers-issuer token signed by the staff key fails its issuer
 *   check. Both answer 401 without reaching the endpoint, so no audit row is written.
 * - **McpEndpoint's channel-off refusal** is therefore a backstop: it is reached only if the staff
 *   tenant were misconfigured to accept such a token (e.g. issuer check off), which
 *   `McpCustomerChannelOffIT` simulates with a substituted token source.
 */
@QuarkusTest
@TestProfile(McpCustomerChannelOffTenantIT.StaffTenantOnly::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpCustomerChannelOffTenantIT {

    class StaffTenantOnly : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.oidc.tenant-enabled" to "true",
            "quarkus.oidc.auth-server-url" to "",
            "quarkus.devservices.enabled" to "false",
            "quarkus.oidc.public-key" to OidcItKeys.publicKeyConfig("staff"),
            "quarkus.oidc.token.issuer" to STAFF,
            "quarkus.oidc.resolve-tenants-with-issuer" to "false",
            "quarkus.oidc.customers.tenant-enabled" to "false",
            "quarkus.oidc.customers.auth-server-url" to "",
            "mcp.customer.enabled" to "false",
            "mcp.customer.issuer" to CUSTOMERS,
        )
    }

    @Inject
    lateinit var recorder: RecordingAuditEventPublisher

    private fun sign(key: PrivateKey, iss: String, sub: String): String {
        val claims = JwtClaims()
        claims.issuer = iss
        claims.subject = sub
        claims.setAudience("openbank-mcp-service")
        claims.setStringClaim("azp", "mcp-local-test")
        claims.issuedAt = NumericDate.now()
        claims.setExpirationTimeMinutesInTheFuture(5f)
        val jws = JsonWebSignature()
        jws.payload = claims.toJson()
        jws.algorithmHeaderValue = AlgorithmIdentifiers.RSA_USING_SHA256
        jws.key = key
        return jws.compactSerialization
    }

    private fun call(token: String, scheme: String = "Bearer") = given().contentType(ContentType.JSON)
        .header("Authorization", "$scheme $token")
        .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_accounts","arguments":{}}}""")
        .post("/mcp").then()

    @Test
    fun `a real customers-realm token is refused 401 by the staff tenant before the endpoint`() {
        recorder.clear()
        val token = sign(OidcItKeys.pair("customers").private, CUSTOMERS, "c0ffee00-0000-0000-0000-0000000000f3")
        call(token).statusCode(401)
        call(token, scheme = "DPoP").statusCode(401)
        // No audit row: the request never reached McpEndpoint.
        assertThat(recorder.events).isEmpty()
    }

    @Test
    fun `a customers-issuer token signed by the staff key is refused 401 by the issuer check`() {
        recorder.clear()
        call(sign(OidcItKeys.pair("staff").private, CUSTOMERS, "c0ffee00-0000-0000-0000-0000000000f4")).statusCode(401)
        assertThat(recorder.events).isEmpty()
    }

    @Test
    fun `control - a staff token still reaches the endpoint`() {
        call(sign(OidcItKeys.pair("staff").private, STAFF, "agent:test-agent")).statusCode(200)
    }

    private companion object {
        const val STAFF = "https://kc.test.example/realms/openbank"
        const val CUSTOMERS = "https://kc.test.example/realms/openbank-customers"
    }
}
