// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ListenerMaterial
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * #12511: the 8443 listener (`client-auth: required` in %prod) must not turn a trusted client
 * certificate into an authenticated caller. Production listener shape, real HTTP, both ports.
 */
@QuarkusTest
@TestProfile(ProductCatalogListenerAuthParityIT.Profile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products_listener_it")],
)
class ProductCatalogListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/products"

    @Test
    fun `a trusted client certificate cannot create a product without a bearer`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val request = HttpRequest.newBuilder(URI.create("https://localhost:$port/api/v1/products"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        val client = HttpClient.newBuilder()
            .sslContext(ListenerMaterial.sslContext(withClientCertificate = true))
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
            .`as`("8443, client certificate, no bearer: POST /api/v1/products")
            .isEqualTo(401)
    }

    @Test
    fun `an unannotated API route still requires a bearer on both listeners`() {
        val sslPort = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val httpPort = ConfigProvider.getConfig().getValue("quarkus.http.test-port", Int::class.java)
        val bearer = ListenerMaterial.bearer()
        val tlsClient = HttpClient.newBuilder()
            .sslContext(ListenerMaterial.sslContext(withClientCertificate = true))
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        val plainClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        fun status(client: HttpClient, uri: String, token: String?): Int {
            val request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .GET()
                .build()
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        }

        val path = "/api/v1/listener-auth-probe"
        assertThat(status(tlsClient, "https://localhost:$sslPort$path", null)).isEqualTo(401)
        assertThat(status(plainClient, "http://localhost:$httpPort$path", null)).isEqualTo(401)
        assertThat(status(tlsClient, "https://localhost:$sslPort$path", bearer)).isEqualTo(200)
        assertThat(status(plainClient, "http://localhost:$httpPort$path", bearer)).isEqualTo(200)
    }

    class Profile : ProductionListenerProfile()
}

/** Deliberately unannotated: only the HTTP permission can protect this test-only route. */
@Path("/api/v1/listener-auth-probe")
class UnannotatedCatalogProbeResource {
    @GET
    fun get(): Response = Response.ok().build()
}
