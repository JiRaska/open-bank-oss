// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.mcp.infrastructure.customer.DpopTestProofs
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate
import org.junit.jupiter.api.Test
import java.security.PrivateKey

/**
 * Tenant resolution with REAL signed tokens and real OIDC tenants (no token-source swap): the staff
 * default tenant and the customers tenant each verify against their own key, and
 * `quarkus.oidc.resolve-tenants-with-issuer` selects between them by `iss`.
 *
 * Measured fact this pins: Quarkus's issuer resolver answers "no tenant" for an issuer it does not
 * know, and the request then FALLS BACK to the default (staff) tenant. What keeps a foreign or
 * forged issuer out is that tenant's own signature AND issuer check — so both are asserted, with a
 * third-issuer token signed by the STAFF key (would pass a signature-only check) and a
 * customers-issuer token signed by the staff key (a forged tenant selection).
 */
@QuarkusTest
@TestProfile(McpCustomerTenantResolutionIT.RealTenants::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpCustomerTenantResolutionIT {

    class RealTenants : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.oidc.tenant-enabled" to "true",
            // Empty = unset (SmallRye): no discovery against a Keycloak, the tenants verify locally.
            "quarkus.oidc.auth-server-url" to "",
            // An unset auth-server-url would otherwise start a Keycloak Dev Services container, and this
            // profile's distinct config then starts the rest (Kafka) too. Postgres comes from the
            // test resource, not Dev Services, so none is needed.
            "quarkus.devservices.enabled" to "false",
            "quarkus.oidc.customers.auth-server-url" to "",
            "quarkus.oidc.public-key" to OidcItKeys.publicKeyConfig("staff"),
            "quarkus.oidc.token.issuer" to "https://kc.test.example/realms/openbank",
            "quarkus.oidc.resolve-tenants-with-issuer" to "true",
            "quarkus.oidc.customers.tenant-enabled" to "true",
            "quarkus.oidc.customers.public-key" to OidcItKeys.publicKeyConfig("customers"),
            // The edge forwards the external scheme and host, so the tenant's own DPoP check sees the
            // published resource URL; this is the deployment shape the two htu checks need to agree.
            "quarkus.http.proxy.proxy-address-forwarding" to "true",
            "quarkus.http.proxy.allow-x-forwarded" to "true",
            "quarkus.http.proxy.enable-forwarded-host" to "true",
            // Only the test client's own loopback hop is a trusted proxy (ProxyForwardingGuard).
            "quarkus.http.proxy.trusted-proxies" to TRUSTED_LOOPBACK,
            "mcp.customer.enabled" to "true",
            "mcp.customer.issuer" to "https://kc.test.example/realms/openbank-customers",
            "mcp.customer.allowlist" to "classpath:test-mcp-client-allowlist.yaml",
        )
    }

    private fun sign(key: PrivateKey, iss: String, sub: String = "agent:test-agent", cnfJkt: String? = null): String {
        val claims = JwtClaims()
        cnfJkt?.let { claims.setClaim("cnf", mapOf("jkt" to it)) }
        claims.issuer = iss
        claims.subject = sub
        claims.setAudience("openbank-mcp-service")
        claims.setStringClaim("consent_id", "11111111-1111-1111-1111-111111111111")
        claims.setStringClaim("azp", "mcp-local-test")
        claims.issuedAt = NumericDate.now()
        claims.setExpirationTimeMinutesInTheFuture(5f)
        val jws = JsonWebSignature()
        jws.payload = claims.toJson()
        jws.algorithmHeaderValue = AlgorithmIdentifiers.RSA_USING_SHA256
        jws.key = key
        return jws.compactSerialization
    }

    private fun list(token: String) = given().contentType(ContentType.JSON)
        .header("Authorization", "Bearer $token")
        .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
        .post("/mcp").then()

    private val staffKey get() = OidcItKeys.pair("staff").private

    @Test
    fun `control - a staff token signed by the staff key is accepted by the real staff tenant`() {
        val tools = list(
            sign(staffKey, STAFF),
        ).statusCode(200).extract().jsonPath().getList<String>("result.tools.name")
        assertThat(tools).isNotEmpty
    }

    @Test
    fun `a token from a third issuer signed by an unknown key is refused`() {
        list(sign(OidcItKeys.pair("third").private, THIRD)).statusCode(401)
    }

    @Test
    fun `a third-issuer token signed by the STAFF key is refused - the fallback tenant checks iss`() {
        list(sign(staffKey, THIRD)).statusCode(401)
    }

    @Test
    fun `a customers-issuer token signed by the staff key is refused by the customers tenant`() {
        list(sign(staffKey, CUSTOMERS, sub = "c0ffee00-0000-0000-0000-000000000001")).statusCode(401)
    }

    @Test
    fun `a token signed by the real customers key passes the tenant and reaches the customer branch`() {
        val dpopKey = DpopTestProofs.newKey()
        val token = sign(
            OidcItKeys.pair("customers").private,
            CUSTOMERS,
            sub = "c0ffee00-0000-0000-0000-000000000001",
            cnfJkt = DpopTestProofs.jkt(dpopKey),
        )
        // Bound to the URL THIS request hits, so the tenant's own DPoP check (authorization-scheme
        // DPoP) passes; the customer branch then checks htu against the PUBLISHED resource and refuses.
        val proof = DpopTestProofs.proof(dpopKey, htu = "http://localhost:${RestAssured.port}/mcp", token = token)
        val challenge = given().contentType(ContentType.JSON)
            .header("Authorization", "DPoP $token")
            .header("DPoP", proof)
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .post("/mcp").then()
            .statusCode(401)
            .extract().header("WWW-Authenticate")
        // Only the customer branch emits resource_metadata; a tenant-level rejection would not.
        assertThat(challenge).startsWith("DPoP").contains("invalid_dpop_proof").contains("resource_metadata=")
    }

    @Test
    fun `a real customers token with a proof for the published resource is ADMITTED end to end`() {
        val dpopKey = DpopTestProofs.newKey()
        val token = sign(
            OidcItKeys.pair("customers").private,
            CUSTOMERS,
            sub = "c0ffee00-0000-0000-0000-000000000005",
            cnfJkt = DpopTestProofs.jkt(dpopKey),
        )
        given().contentType(ContentType.JSON)
            .header("Authorization", "DPoP $token")
            .header("DPoP", DpopTestProofs.proof(dpopKey, htu = DpopTestProofs.RESOURCE, token = token))
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "mcp.test.example")
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .post("/mcp").then()
            .statusCode(200)
            .body("result.tools.size()", org.hamcrest.Matchers.equalTo(0))
    }

    private companion object {
        const val STAFF = "https://kc.test.example/realms/openbank"
        const val CUSTOMERS = "https://kc.test.example/realms/openbank-customers"
        const val THIRD = "https://idp.attacker.example/realms/openbank"
        const val TRUSTED_LOOPBACK = "127.0.0.1,0:0:0:0:0:0:0:1"
    }
}
