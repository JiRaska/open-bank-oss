// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.integration

import com.openbank.document.it.PostgresRedisTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ListenerMaterial
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
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
import java.util.UUID

/**
 * #12511: the 8443 listener (`client-auth: required` in %prod) must not turn a trusted client
 * certificate into an authenticated caller. Production listener shape, real HTTP, both ports.
 */
@QuarkusTest
@TestProfile(DocumentListenerAuthParityIT.Profile::class)
@QuarkusTestResource(DocumentPartyBrowsePolicyIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DocumentListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/documents/templates?limit=1"

    @Test
    fun `trusted certificate without bearer cannot publish a document template`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val request = HttpRequest.newBuilder(
            URI.create("https://localhost:$port/api/v1/documents/templates/${UUID.randomUUID()}/publish"),
        ).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build()
        val response = HttpClient.newBuilder()
            .sslContext(ListenerMaterial.sslContext(true))
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(request, HttpResponse.BodyHandlers.discarding())

        assertThat(response.statusCode()).isEqualTo(401)
    }

    @Test
    fun `unannotated API route requires bearer on mTLS`() = assertUnannotatedRoute("https")

    @Test
    fun `unannotated API route requires bearer on plain HTTP`() = assertUnannotatedRoute("http")

    @Test
    fun `public metadata remains available without bearer on both listeners`() {
        val config = ConfigProvider.getConfig()
        val plain = config.getValue("quarkus.http.test-port", Int::class.java)
        val tls = config.getValue("quarkus.http.test-ssl-port", Int::class.java)
        for (path in listOf("/api/v1/info", "/api/v1/config")) {
            assertThat(get("http://localhost:$plain$path", false)).`as`("plain $path").isEqualTo(200)
            assertThat(get("https://localhost:$tls$path", true)).`as`("TLS $path").isEqualTo(200)
        }
    }

    @Test
    fun `management health remains available without bearer`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.management.test-port", Int::class.java)
        for (path in listOf("/q/health/live", "/q/health/ready")) {
            assertThat(get("http://localhost:$port$path", false)).`as`("management $path").isEqualTo(200)
        }
    }

    private fun get(url: String, tls: Boolean): Int {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .apply { if (tls) sslContext(ListenerMaterial.sslContext(true)) }
            .build()
        return client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()
    }

    private fun assertUnannotatedRoute(scheme: String) {
        val sslPort = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val plainPort = ConfigProvider.getConfig().getValue("quarkus.http.test-port", Int::class.java)
        val tlsClient = HttpClient.newBuilder().sslContext(ListenerMaterial.sslContext(true)).build()
        val plainClient = HttpClient.newHttpClient()
        fun status(client: HttpClient, scheme: String, port: Int, bearer: String?): Int {
            val builder = HttpRequest.newBuilder(URI.create("$scheme://localhost:$port/api/v1/listener-auth-probe"))
                .timeout(Duration.ofSeconds(10))
            if (bearer != null) builder.header("Authorization", "Bearer $bearer")
            return client.send(builder.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        }

        val client = if (scheme == "https") tlsClient else plainClient
        val port = if (scheme == "https") sslPort else plainPort
        val bearer = ListenerMaterial.bearer()
        assertThat(status(client, scheme, port, bearer)).isEqualTo(200)
        assertThat(status(client, scheme, port, null)).isEqualTo(401)
    }

    class Profile : ProductionListenerProfile() {
        override fun extraOverrides() = mapOf("quarkus.management.enabled" to "true")
    }
}

/** Test-only unannotated route: exercises the HTTP permission itself, without a method role gate. */
@Path("/api/v1/listener-auth-probe")
class DocumentListenerAuthProbe {
    @GET
    fun read(): Response = Response.ok().build()
}
