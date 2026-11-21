// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.mcp.infrastructure.customer.DpopTestProofs
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate

/** Shared shape of the trusted / untrusted forwarding ITs: real tenants, only the trust list differs. */
object ProxyForwardingSupport {
    const val CUSTOMERS = "https://kc.test.example/realms/openbank-customers"
    const val EDGE_HEADER = "X-Envoy-External-Address"

    fun realTenants(trustedProxies: String): Map<String, String> = mapOf(
        "quarkus.oidc.tenant-enabled" to "true",
        "quarkus.oidc.auth-server-url" to "",
        "quarkus.devservices.enabled" to "false",
        "quarkus.oidc.customers.auth-server-url" to "",
        "quarkus.oidc.public-key" to OidcItKeys.publicKeyConfig("staff"),
        "quarkus.oidc.token.issuer" to "https://kc.test.example/realms/openbank",
        "quarkus.oidc.resolve-tenants-with-issuer" to "true",
        "quarkus.oidc.customers.tenant-enabled" to "true",
        "quarkus.oidc.customers.public-key" to OidcItKeys.publicKeyConfig("customers"),
        // The production switch (application.yaml) and the deployment's trust list.
        "MCP_PROXY_FORWARDING" to "true",
        "quarkus.http.proxy.trusted-proxies" to trustedProxies,
        "mcp.edge.client-address-header" to EDGE_HEADER,
        "mcp.customer.enabled" to "true",
        "mcp.customer.shared-store" to "memory",
        "mcp.customer.issuer" to CUSTOMERS,
        "mcp.customer.allowlist" to "classpath:test-mcp-client-allowlist.yaml",
    )

    /** A real customers-realm token, DPoP-bound, with a proof for the PUBLISHED resource, sent via the edge. */
    fun forwardedToolsList(sub: String): ValidatableResponse {
        val dpopKey = DpopTestProofs.newKey()
        val claims = JwtClaims()
        claims.setClaim("cnf", mapOf("jkt" to DpopTestProofs.jkt(dpopKey)))
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
        val token = jws.compactSerialization
        return given().contentType("application/json")
            .header("Authorization", "DPoP $token")
            .header("DPoP", DpopTestProofs.proof(dpopKey, htu = DpopTestProofs.RESOURCE, token = token))
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "mcp.test.example")
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .post("/mcp").then()
    }

    fun addressKey(forwardedFor: String? = null, edge: List<String> = emptyList()): String =
        probe("/test-probe/client-address-key", forwardedFor, edge)

    /** `HttpServerRequest.remoteAddress()` after Quarkus' forwarding. */
    fun forwardedRemote(forwardedFor: String? = null): String =
        probe("/test-probe/client-address-key/forwarded-remote", forwardedFor, emptyList())

    private fun probe(path: String, forwardedFor: String?, edge: List<String>): String {
        val req = given()
        forwardedFor?.let { req.header("X-Forwarded-For", it) }
        edge.forEach { req.header(EDGE_HEADER, it) }
        return req.get(path).then().statusCode(200).extract().asString()
    }
}
