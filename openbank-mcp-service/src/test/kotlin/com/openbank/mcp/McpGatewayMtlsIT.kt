// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.mtls.ProductionListenerKeys
import com.openbank.mcp.infrastructure.customer.DpopTestProofs
import com.openbank.mcp.infrastructure.mcp.ClientAddressKey
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.config.RestAssuredConfig
import io.restassured.config.SSLConfig
import io.restassured.specification.RequestSpecification
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate
import org.junit.jupiter.api.Test

/**
 * The edge hop is trusted by its mTLS IDENTITY, never by IP (#16847, owner decision 2026-11-21),
 * over a REAL TLS 1.3 listener with client auth REQUIRED and the production `%prod` listener keys
 * (read from application.yaml, not restated). No `quarkus.http.proxy.*` switch is on.
 *
 * - DPoP: quarkus-oidc's own `htu` check (customers tenant) compares with `absoluteURI()`. On the
 *   TLS listener with the external Host passed through, a proof for the published URL is admitted;
 *   the identical request over plain HTTP, or with another Host, is refused AT THE TENANT.
 * - Audit key: the one edge header is honoured only from the pinned gateway SAN; another trusted
 *   fleet certificate, plain HTTP, and `X-Forwarded-For` everywhere leave the key on the TCP peer.
 */
@QuarkusTest
@TestProfile(McpGatewayMtlsIT.GatewayMtls::class)
@QuarkusTestResource(PostgresTestResource::class, initArgs = [ResourceArg(name = "db", value = "openbank_mcp_it")])
class McpGatewayMtlsIT {

    class GatewayMtls : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = (
            ProductionListenerKeys.read() + mapOf(
                "quarkus.oidc.tenant-enabled" to "true",
                "quarkus.oidc.auth-server-url" to "",
                "quarkus.devservices.enabled" to "false",
                "quarkus.oidc.customers.auth-server-url" to "",
                "quarkus.oidc.public-key" to OidcItKeys.publicKeyConfig("staff"),
                "quarkus.oidc.token.issuer" to "https://kc.test.example/realms/openbank",
                "quarkus.oidc.resolve-tenants-with-issuer" to "true",
                "quarkus.oidc.customers.tenant-enabled" to "true",
                "quarkus.oidc.customers.public-key" to OidcItKeys.publicKeyConfig("customers"),
                "mcp.edge.client-address-header" to EDGE_HEADER,
                "mcp.edge.gateway-identity" to GatewayMtlsMaterial.GATEWAY_SAN,
                "mcp.customer.enabled" to "true",
                "mcp.customer.issuer" to CUSTOMERS,
                "mcp.customer.allowlist" to "classpath:test-mcp-client-allowlist.yaml",
            )
            ).flatMap { (k, v) -> listOf(k to v, "%test.$k" to v) }.toMap()

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
            listOf(QuarkusTestProfile.TestResourceEntry(GatewayMtlsMaterial::class.java))
    }

    private val sslPort get() = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)

    /** A request over the 8443-shaped listener presenting [identity] ("gateway" / "other"), or no certificate. */
    private fun mtls(identity: String?): RequestSpecification {
        var ssl = SSLConfig.sslConfig().allowAllHostnames()
            .trustStore(GatewayMtlsMaterial.trustStore().toString(), GatewayMtlsMaterial.password)
        if (identity != null) {
            ssl = ssl.keyStore(GatewayMtlsMaterial.clientStore(identity).toString(), GatewayMtlsMaterial.password)
                .keystoreType("PKCS12")
        }
        return given().config(RestAssuredConfig.config().sslConfig(ssl.trustStoreType("PKCS12")))
            .baseUri("https://localhost").port(sslPort)
    }

    private fun customerToken(sub: String, jkt: String): String {
        val claims = JwtClaims()
        claims.setClaim("cnf", mapOf("jkt" to jkt))
        claims.issuer = CUSTOMERS
        claims.subject = sub
        claims.setAudience("openbank-mcp-service")
        claims.setStringClaim("azp", "mcp-local-test")
        claims.issuedAt = NumericDate.now()
        claims.setExpirationTimeMinutesInTheFuture(5f)
        val jws = JsonWebSignature()
        jws.payload = claims.toJson()
        jws.algorithmHeaderValue = AlgorithmIdentifiers.RSA_USING_SHA256
        jws.key = OidcItKeys.pair("customers").private
        return jws.compactSerialization
    }

    /** tools/list, real customers token, proof for the PUBLISHED resource; [host] is what the edge passes on. */
    private fun publishedProofToolsList(spec: RequestSpecification, sub: String, host: String = PUBLISHED_HOST) =
        DpopTestProofs.newKey().let { key ->
            val token = customerToken(sub, DpopTestProofs.jkt(key))
            spec.contentType("application/json")
                .header("Host", host)
                .header("Authorization", "DPoP $token")
                .header("DPoP", DpopTestProofs.proof(key, htu = DpopTestProofs.RESOURCE, token = token))
                // A forged forwarding claim: must change nothing, since no forwarding is configured.
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", PUBLISHED_HOST)
                .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                .post("/mcp").then()
        }

    @Test
    fun `DPoP - a proof for the published URL is ADMITTED over the gateway mTLS hop with the external Host`() {
        publishedProofToolsList(mtls("gateway"), "c0ffee00-0000-0000-0000-000000000021")
            .statusCode(200)
            .body("result.tools.size()", equalTo(0))
    }

    /**
     * BLOCKER, measured 2026-11-21 — these two are RED on purpose and keep this PR a draft.
     * Under the production listener shape (`%prod` bearer-only permission, `auth-mechanism: bearer`,
     * required by gate mtls-bearer-only) a request with `Authorization: DPoP` is NOT authenticated by
     * the customers tenant at all: a proof for `https://evil.test.example/mcp` answers 200, as do
     * plain HTTP and a foreign Host. Measured controls: with the permission removed, and with
     * `auth-mechanism: DPoP`, all of these are refused with a bare `DPoP` challenge (tenant htu
     * check) — but `DPoP` then stops authenticating staff `Bearer` tokens on the same paths, and the
     * permission is global to both listeners. Owner decision needed (see PR body).
     */
    @Test
    fun `DPoP - a proof for any URL but the published one is refused, even over the gateway hop`() {
        listOf("https://evil.test.example/mcp", "http://mcp.test.example/mcp", "https://mcp.test.example/other")
            .forEach { htu ->
                val key = DpopTestProofs.newKey()
                val token = customerToken("c0ffee00-0000-0000-0000-000000000022", DpopTestProofs.jkt(key))
                val status = mtls("gateway").contentType("application/json")
                    .header("Host", PUBLISHED_HOST)
                    .header("Authorization", "DPoP $token")
                    .header("DPoP", DpopTestProofs.proof(key, htu = htu, token = token))
                    .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                    .post("/mcp").then().extract().statusCode()
                assertThat(status).describedAs(htu).isEqualTo(401)
            }
    }

    @Test
    fun `DPoP - the published-URL proof over plain HTTP or with a foreign Host is refused at the tenant`() {
        assertThat(publishedProofToolsList(given(), "c0ffee00-0000-0000-0000-000000000023").extract().statusCode())
            .describedAs("plain HTTP, forged X-Forwarded-Proto").isEqualTo(401)
        assertThat(
            publishedProofToolsList(mtls("gateway"), "c0ffee00-0000-0000-0000-000000000024", host = "evil.test.example")
                .extract().statusCode(),
        ).describedAs("foreign Host over the gateway hop").isEqualTo(401)
    }

    @Test
    fun `the TLS listener refuses a client that presents no certificate`() {
        assertThatThrownBy { mtls(null).get("/test-probe/client-address-key") }.isInstanceOf(Exception::class.java)
    }

    private fun key(
        spec: RequestSpecification,
        edge: List<String> = emptyList(),
        forwardedFor: String? = null,
    ): String {
        edge.forEach { spec.header(EDGE_HEADER, it) }
        forwardedFor?.let { spec.header("X-Forwarded-For", it) }
        return spec.get("/test-probe/client-address-key").then().statusCode(200).extract().asString()
    }

    @Test
    fun `audit key - the edge header is honoured from the pinned gateway certificate`() {
        val direct = key(mtls("gateway"))
        assertThat(key(mtls("gateway"), edge = listOf("198.51.100.9")))
            .isEqualTo(ClientAddressKey.of("198.51.100.9"))
            .isNotEqualTo(direct)
        // A list, or the header twice, is not a single edge-set value.
        assertThat(key(mtls("gateway"), edge = listOf("203.0.113.66, 198.51.100.9"))).isEqualTo(direct)
        assertThat(key(mtls("gateway"), edge = listOf("203.0.113.66", "198.51.100.9"))).isEqualTo(direct)
        // IPv6 grouped by /64.
        assertThat(key(mtls("gateway"), edge = listOf("2001:db8:1:2::a")))
            .isEqualTo(key(mtls("gateway"), edge = listOf("2001:db8:1:2:ffff::b")))
            .isNotEqualTo(key(mtls("gateway"), edge = listOf("2001:db8:1:3::a")))
    }

    @Test
    fun `audit key - another TRUSTED fleet certificate cannot set the edge header (its CN even names the gateway)`() {
        val direct = key(mtls("other"))
        assertThat(key(mtls("other"), edge = listOf("198.51.100.9"))).isEqualTo(direct)
    }

    @Test
    fun `audit key - plain HTTP never honours the edge header or X-Forwarded-For`() {
        val direct = key(given())
        assertThat(key(given(), edge = listOf("198.51.100.9"))).isEqualTo(direct)
        assertThat(key(given(), forwardedFor = "203.0.113.66")).isEqualTo(direct)
        assertThat(key(mtls("gateway"), forwardedFor = "203.0.113.66")).isEqualTo(key(mtls("gateway")))
        assertThat(
            given().header(
                "X-Forwarded-For",
                "203.0.113.66",
            ).get("/test-probe/client-address-key/forwarded-remote").asString(),
        )
            .isNotEqualTo("203.0.113.66")
    }

    private companion object {
        const val CUSTOMERS = "https://kc.test.example/realms/openbank-customers"
        const val EDGE_HEADER = "X-Envoy-External-Address"
        const val PUBLISHED_HOST = "mcp.test.example"
    }
}
