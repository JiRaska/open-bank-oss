// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ListenerMaterial
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
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

/**
 * #12511: the 8443 listener (`client-auth: required` in %prod) must not turn a trusted client
 * certificate into an authenticated caller. Production listener shape, real HTTP, both ports.
 */
@QuarkusTest
@TestProfile(RiskListenerAuthParityIT.Profile::class)
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/risk/snapshots/00000000-0000-0000-0000-00000000c0de/positions"

    // No such id: 404 is the answer the authenticated route gives (authz runs advisory in tests).
    override val authenticatedStatus = 404

    @Test
    fun `public metadata remains reachable without bearer on both listeners`() {
        val config = ConfigProvider.getConfig()
        val plain = config.getValue("quarkus.http.test-port", Int::class.java)
        val tls = config.getValue("quarkus.http.test-ssl-port", Int::class.java)
        for (path in listOf("/api/v1/info", "/api/v1/config")) {
            assertThat(get("http://localhost:$plain$path", false)).describedAs(path).isEqualTo(200)
            assertThat(get("https://localhost:$tls$path", true)).describedAs(path).isEqualTo(200)
        }
    }

    @Test
    fun `management health remains reachable without bearer`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.management.test-port", Int::class.java)
        assertThat(get("http://localhost:$port/q/health/live", false)).isEqualTo(200)
        // A missing local Kafka broker may legitimately degrade readiness, but auth must not gate it.
        assertThat(get("http://localhost:$port/q/health/ready", false)).isIn(200, 503)
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
        override fun extraOverrides(): Map<String, String> = mapOf(
            "quarkus.http.test-port" to "39221",
            "quarkus.http.test-ssl-port" to "39222",
            "quarkus.management.enabled" to "true",
            "quarkus.management.test-port" to "39223",
        )
    }
}
