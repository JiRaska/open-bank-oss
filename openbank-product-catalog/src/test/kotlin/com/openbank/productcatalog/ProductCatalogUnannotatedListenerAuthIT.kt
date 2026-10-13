// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ListenerMaterial
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

/** #12511: production listener settings must protect a future unannotated API route. */
@QuarkusTest
@TestProfile(ProductCatalogListenerAuthParityIT.Profile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products_listener_it")],
)
class ProductCatalogUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/product-catalog-listener-auth-probe"

    @Test
    fun `public service metadata remains reachable without bearer on both listeners`() {
        val config = ConfigProvider.getConfig()
        val plain = config.getValue("quarkus.http.test-port", Int::class.java)
        val tls = config.getValue("quarkus.http.test-ssl-port", Int::class.java)
        assertThat(get("http://localhost:$plain/api/v1/info", false)).isEqualTo(200)
        assertThat(get("https://localhost:$tls/api/v1/info", true)).isEqualTo(200)
        assertThat(get("http://localhost:$plain/api/v1/config", false)).isEqualTo(200)
        assertThat(get("https://localhost:$tls/api/v1/config", true)).isEqualTo(200)
    }

    @Test
    fun `management liveness remains reachable without bearer`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.management.test-port", Int::class.java)
        assertThat(get("http://localhost:$port/q/health/live", false)).isEqualTo(200)
    }

    private fun get(url: String, tls: Boolean): Int {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .apply { if (tls) sslContext(ListenerMaterial.sslContext(true)) }
            .build()
        return client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()
    }
}

/** Test-only route with no class or method security annotation. */
@Path("/api/v1/product-catalog-listener-auth-probe")
class ProductCatalogListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
