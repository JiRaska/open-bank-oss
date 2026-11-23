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
 * - **Quarkus OIDC, before McpEndpoint, for a `Bearer` credential.** A token signed by the customers
 *   realm fails the staff tenant's signature check, and a customers-issuer token signed by the staff
 *   key fails its issuer check. Both answer 401 without reaching the endpoint (no audit row).
 * - **McpEndpoint, for a `DPoP` credential.** The staff tenant reads only `Bearer`, so a
 *   `Authorization: DPoP <token>` request reaches the endpoint with NO identity. The endpoint's
 *   staff-root customer-credential refusal (#17086, `respondCustomerOnStaffRoot`) answers it 401
 *   `invalid_token`; before it existed this was served 200 as anonymous. Channel-state-independent
 *   coverage of that refusal is `McpStaffRootCustomerCredentialIT`.
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
    fun `a real customers-realm Bearer token is refused 401 by the staff tenant before the endpoint`() {
        recorder.clear()
        val token = sign(OidcItKeys.pair("customers").private, CUSTOMERS, "c0ffee00-0000-0000-0000-0000000000f3")
        // The endpoint's challenge is absent: this 401 is Quarkus OIDC's, not the staff-root refusal.
        val challenge = call(token).statusCode(401).extract().header("WWW-Authenticate").orEmpty()
        assertThat(challenge).doesNotContain("customer credentials are accepted only at")
        // No audit row: the request never reached McpEndpoint.
        assertThat(recorder.events).isEmpty()
    }

    @Test
    fun `a DPoP-scheme customers token reaches the endpoint unread and is refused, not served anonymously`() {
        val token = sign(OidcItKeys.pair("customers").private, CUSTOMERS, "c0ffee00-0000-0000-0000-0000000000f5")
        // Measured: the staff tenant only reads `Bearer`, so this request reaches McpEndpoint with NO
        // identity. Without the endpoint's staff-root refusal it was answered 200 on the anonymous path.
        call(token, scheme = "DPoP").statusCode(401)
            .header(
                "WWW-Authenticate",
                org.hamcrest.Matchers.containsString("customer credentials are accepted only at"),
            )
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
