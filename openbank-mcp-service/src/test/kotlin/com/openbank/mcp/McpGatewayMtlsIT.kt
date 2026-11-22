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
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * The edge hop is trusted by its mTLS IDENTITY, never by IP (#16847, owner decision 2026-11-21),
 * over a REAL TLS 1.3 listener with client auth REQUIRED and the production `%prod` listener keys
 * (read from application.yaml, not restated). No `quarkus.http.proxy.*` switch is on.
 *
 * - DPoP: quarkus-oidc's own `htu` check (customers tenant) compares with `absoluteURI()`. On the
 *   TLS listener with the external Host passed through, a proof for the published URL is admitted
 *   on the customer root (`/customer/mcp`, DPoP-only); another Host or htu is refused at the tenant,
 *   and plain HTTP does not serve the customer root at all. Staff Bearer on `/mcp` is unaffected.
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

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> = GatewayTls.resources
    }

    /** A request over the 8443-shaped listener presenting [identity] ("gateway" / "other"), or no certificate. */
    private fun mtls(identity: String?): RequestSpecification = GatewayTls.spec(identity)

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

    private fun staffToken(): String {
        val claims = JwtClaims()
        claims.issuer = "https://kc.test.example/realms/openbank"
        claims.subject = "agent:test-agent"
        claims.setAudience("openbank-mcp-service")
        claims.setStringClaim("consent_id", "11111111-1111-1111-1111-111111111111")
        claims.setStringClaim("azp", "mcp-local-test")
        claims.issuedAt = NumericDate.now()
        claims.setExpirationTimeMinutesInTheFuture(5f)
        val jws = JsonWebSignature()
        jws.payload = claims.toJson()
        jws.algorithmHeaderValue = AlgorithmIdentifiers.RSA_USING_SHA256
        jws.key = OidcItKeys.pair("staff").private
        return jws.compactSerialization
    }

    /** tools/list, real customers token, proof for the PUBLISHED resource; [host] is what the edge passes on. */
    private fun publishedProofToolsList(
        spec: RequestSpecification,
        sub: String,
        host: String = PUBLISHED_HOST,
        body: String = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""",
    ) = DpopTestProofs.newKey().let { key ->
        val token = customerToken(sub, DpopTestProofs.jkt(key))
        spec.contentType("application/json")
            .header("Host", host)
            .header("Authorization", "DPoP $token")
            .header("DPoP", DpopTestProofs.proof(key, htu = DpopTestProofs.RESOURCE, token = token))
            // A forged forwarding claim: must change nothing, since no forwarding is configured.
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", PUBLISHED_HOST)
            .body(body)
            .post("/customer/mcp").then()
    }

    @Test
    fun `DPoP - a proof for the published URL is ADMITTED over the gateway mTLS hop with the external Host`() {
        publishedProofToolsList(mtls("gateway"), "c0ffee00-0000-0000-0000-000000000021")
            .statusCode(200)
            .body("result.tools.size()", equalTo(0))
        // Not vacuous: an anonymous caller also lists no tools, but only an ADMITTED customer gets
        // the customer branch's "Not available on this channel" (an anonymous one reads "Authorization unavailable").
        publishedProofToolsList(
            mtls("gateway"),
            "c0ffee00-0000-0000-0000-000000000025",
            body = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"x","arguments":{}}}""",
        ).statusCode(200).body("result.content[0].text", equalTo("Not available on this channel"))
    }

    /**
     * Must-reject cases (#16847). Measured 2026-11-21: under a `bearer` permission an
     * `Authorization: DPoP` request was never authenticated by the customers tenant and all of these
     * answered 200; the customer root's own `auth-mechanism: DPoP` permission (option b) is what
     * makes them fail at the tenant. Removing the `customer-dpop` permission from application.yaml
     * turns both tests red again.
     */
    @Test
    fun `DPoP - a proof for any URL but the published one is refused, even over the gateway hop`() {
        listOf(
            "https://evil.test.example/customer/mcp",
            "http://mcp.test.example/customer/mcp",
            "https://mcp.test.example/mcp",
        ).forEach { htu ->
            val key = DpopTestProofs.newKey()
            val token = customerToken("c0ffee00-0000-0000-0000-000000000022", DpopTestProofs.jkt(key))
            val status = mtls("gateway").contentType("application/json")
                .header("Host", PUBLISHED_HOST)
                .header("Authorization", "DPoP $token")
                .header("DPoP", DpopTestProofs.proof(key, htu = htu, token = token))
                .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                .post("/customer/mcp").then().extract().statusCode()
            assertThat(status).describedAs(htu).isEqualTo(401)
        }
    }

    @Test
    fun `DPoP - the published-URL proof with a foreign Host is refused, and plain HTTP is refused`() {
        assertThat(
            publishedProofToolsList(mtls("gateway"), "c0ffee00-0000-0000-0000-000000000024", host = "evil.test.example")
                .extract().statusCode(),
        ).describedAs("foreign Host over the gateway hop").isEqualTo(401)
        // 8150: the DPoP tenant refuses first (absoluteURI is http://, forged X-Forwarded-Proto ignored);
        // a proof that matched http:// would then meet CustomerMcpEndpoint's 404 (McpCustomerChannelIT).
        assertThat(publishedProofToolsList(given(), "c0ffee00-0000-0000-0000-000000000023").extract().statusCode())
            .describedAs("plain HTTP, forged X-Forwarded-Proto").isEqualTo(401)
    }

    /**
     * Review of #17004: the customer branch used to be chosen by the RAW path, so these spellings
     * matched [com.openbank.mcp.infrastructure.mcp.CustomerMcpEndpoint] (or a permission written for
     * another spelling) and took the staff dispatch: 200 `{"tools":[]}`, no DPoP challenge, no
     * customer refusal, throttle or audit. Every one must now be refused before any resource runs,
     * on the TLS listener with a valid customer DPoP call and with a staff Bearer, and on plain HTTP.
     * Reverting McpPathCanonicalFilter turns this red.
     */
    @Test
    fun `non-canonical spellings of either root are refused on both listeners, whatever the credential`() {
        NON_CANONICAL.forEach { path ->
            val key = DpopTestProofs.newKey()
            val token = customerToken("c0ffee00-0000-0000-0000-000000000031", DpopTestProofs.jkt(key))
            val dpop = mtls("gateway").urlEncodingEnabled(false).contentType("application/json")
                .header("Host", PUBLISHED_HOST)
                .header("Authorization", "DPoP $token")
                .header("DPoP", DpopTestProofs.proof(key, htu = DpopTestProofs.RESOURCE, token = token))
                .body(TOOLS_LIST).post(path).then().extract().statusCode()
            assertThat(dpop).describedAs("customer DPoP over mTLS: $path").isEqualTo(400)
            val staff = mtls("gateway").urlEncodingEnabled(false).contentType("application/json")
                .header("Authorization", "Bearer ${staffToken()}")
                .body(TOOLS_LIST).post(path).then().extract().statusCode()
            assertThat(staff).describedAs("staff Bearer over mTLS: $path").isEqualTo(400)
            val plain = given().urlEncodingEnabled(false).contentType("application/json")
                .header("Authorization", "Bearer ${staffToken()}")
                .body(TOOLS_LIST).post(path).then().extract().statusCode()
            assertThat(plain).describedAs("staff Bearer over plain HTTP: $path").isEqualTo(400)
            val anon = given().urlEncodingEnabled(false).contentType("application/json")
                .body(TOOLS_LIST).post(path).then().extract().statusCode()
            assertThat(anon).describedAs("anonymous over plain HTTP: $path").isEqualTo(400)
        }
    }

    /** Control for the case above: the canonical spellings are still served (not a blanket 400). */
    @Test
    fun `the canonical spellings are not refused by the path filter`() {
        val staff = given().contentType("application/json").header("Authorization", "Bearer ${staffToken()}")
            .body(TOOLS_LIST).post("/mcp").then().extract().statusCode()
        assertThat(staff).isEqualTo(200)
        publishedProofToolsList(mtls("gateway"), "c0ffee00-0000-0000-0000-000000000032").statusCode(200)
    }

    /** One HTTP/1.1 request written byte-for-byte (no client-side path normalisation); returns the status code. */
    private fun rawStatus(tls: Boolean, target: String, authorization: String?): Int {
        val port = ConfigProvider.getConfig()
            .getValue(if (tls) "quarkus.http.test-ssl-port" else "quarkus.http.test-port", Int::class.java)
        val socket: Socket = if (tls) {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(pkcs12(GatewayMtlsMaterial.clientStore("gateway")), GatewayMtlsMaterial.password.toCharArray())
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(pkcs12(GatewayMtlsMaterial.trustStore()))
            }
            SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
                .socketFactory.createSocket("localhost", port)
        } else {
            Socket("localhost", port)
        }
        socket.use { s ->
            s.soTimeout = RAW_TIMEOUT_MS
            val auth = authorization?.let { "Authorization: $it\r\n" }.orEmpty()
            val request = "POST $target HTTP/1.1\r\nHost: $PUBLISHED_HOST\r\n$auth" +
                "Content-Type: application/json\r\nContent-Length: ${TOOLS_LIST.length}\r\n" +
                "Connection: close\r\n\r\n$TOOLS_LIST"
            s.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val status = s.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
            return status.split(' ')[1].toInt()
        }
    }

    private fun pkcs12(path: java.nio.file.Path): KeyStore = KeyStore.getInstance("PKCS12").apply {
        Files.newInputStream(path).use { load(it, GatewayMtlsMaterial.password.toCharArray()) }
    }

    /**
     * Raw-socket probe for the spellings an HTTP client library rewrites before sending (`//`, and the
     * absolute-form request target). Both listeners; the staff control proves the raw client is served.
     */
    @Test
    fun `raw socket - empty segments and absolute-form targets are refused on both listeners`() {
        listOf(false, true).forEach { tls ->
            assertThat(rawStatus(tls, "/mcp", "Bearer ${staffToken()}")).describedAs("control tls=$tls").isEqualTo(200)
            RAW_NON_CANONICAL.forEach { target ->
                assertThat(rawStatus(tls, target, "Bearer ${staffToken()}"))
                    .describedAs("staff tls=$tls $target").isEqualTo(400)
                assertThat(rawStatus(tls, target, null)).describedAs("anon tls=$tls $target").isEqualTo(400)
            }
        }
    }

    @Test
    fun `staff - a real staff Bearer token on the staff root still works with the DPoP root beside it`() {
        val tools = given().contentType("application/json")
            .header("Authorization", "Bearer ${staffToken()}")
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .post("/mcp").then().statusCode(200).extract().jsonPath().getList<String>("result.tools.name")
        assertThat(tools).isNotEmpty
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
        const val TOOLS_LIST = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        const val RAW_TIMEOUT_MS = 10_000
        val RAW_NON_CANONICAL = listOf(
            "/customer//mcp",
            "//customer/mcp",
            "//mcp",
            "/mcp//",
            "http://$PUBLISHED_HOST/customer/mcp;a=b",
            "http://$PUBLISHED_HOST/customer//mcp",
            "http://$PUBLISHED_HOST/x/../mcp",
            "/customer\\mcp",
        )
        val NON_CANONICAL = listOf(
            "/customer/mcp;a=b",
            "/customer;a=b/mcp",
            "/customer/%6dcp",
            "/customer%2fmcp",
            "/customer/%2e/mcp",
            "/customer/./mcp",
            "/x/../customer/mcp",
            // "/customer//mcp" is not listed: RestAssured collapses `//` before sending (measured: it
            // arrived as /customer/mcp and was admitted); RAW_NON_CANONICAL sends it over a raw socket.
            "/mcp;a=b",
            "/%6dcp",
            "/./mcp",
            "/x/../mcp",
        )
    }
}
