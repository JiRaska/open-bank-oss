// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import io.restassured.config.RestAssuredConfig
import io.restassured.config.SSLConfig
import io.restassured.specification.RequestSpecification
import org.eclipse.microprofile.config.ConfigProvider

/**
 * The customer channel is served only on the mTLS listener (#16847), so every customer-channel IT
 * talks to it over real TLS 1.3 with [GatewayMtlsMaterial]'s certificates. [listener] is the
 * listener shape (client auth REQUIRED is build time, hence a profile); [spec] a request presenting
 * the named client identity ("gateway" / "other"), or none.
 */
object GatewayTls {
    /** Literals only: profile overrides load in another classloader. Also under `%test.`. */
    fun listener(): Map<String, String> = mapOf(
        "quarkus.http.ssl.client-auth" to "required",
        "quarkus.http.insecure-requests" to "enabled",
    ).flatMap { (k, v) -> listOf(k to v, "%test.$k" to v) }.toMap()

    val resources: List<QuarkusTestProfile.TestResourceEntry>
        get() = listOf(QuarkusTestProfile.TestResourceEntry(GatewayMtlsMaterial::class.java))

    private val sslPort get() = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)

    fun spec(identity: String? = "gateway"): RequestSpecification {
        var ssl = SSLConfig.sslConfig().allowAllHostnames()
            .trustStore(GatewayMtlsMaterial.trustStore().toString(), GatewayMtlsMaterial.password)
            .trustStoreType("PKCS12")
        if (identity != null) {
            ssl = ssl.keyStore(GatewayMtlsMaterial.clientStore(identity).toString(), GatewayMtlsMaterial.password)
                .keystoreType("PKCS12")
        }
        return given().config(RestAssuredConfig.config().sslConfig(ssl)).baseUri("https://localhost").port(sslPort)
    }
}
