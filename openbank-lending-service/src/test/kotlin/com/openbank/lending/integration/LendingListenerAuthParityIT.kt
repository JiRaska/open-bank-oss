// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ListenerMaterial
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
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
@TestProfile(LendingListenerAuthParityIT.Profile::class)
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_listener_it")],
)
class LendingListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/lending/approvals?limit=1"

    @Test
    fun `a trusted client certificate cannot disburse without a bearer`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val request = HttpRequest.newBuilder(
            URI.create("https://localhost:$port/api/v1/lending/applications/${UUID.randomUUID()}/disburse"),
        ).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build()
        val response = HttpClient.newBuilder().sslContext(ListenerMaterial.sslContext(true))
            .connectTimeout(Duration.ofSeconds(10)).build()
            .send(request, HttpResponse.BodyHandlers.discarding())

        assertThat(response.statusCode()).isEqualTo(401)
    }

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

    class Profile : ProductionListenerProfile() {
        override fun extraOverrides() = mapOf("quarkus.management.enabled" to "true")
    }
}
