// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.integration

import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ListenerMaterial
import com.openbank.sdd.it.PostgresTestResource
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

/** An unannotated future SDD API still requires a bearer on both listeners. */
@QuarkusTest
@TestProfile(SddListenerAuthParityIT.Profile::class)
@QuarkusTestResource(SddBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class SddUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/sdd/listener-auth-probe"

    @Test
    fun `public service metadata remains reachable without bearer on both listeners`() {
        val config = ConfigProvider.getConfig()
        val plain = config.getValue("quarkus.http.test-port", Int::class.java)
        val tls = config.getValue("quarkus.http.test-ssl-port", Int::class.java)
        for (path in listOf("/api/v1/info", "/api/v1/config")) {
            assertThat(get("http://localhost:$plain$path", false)).`as`("plain $path").isEqualTo(200)
            assertThat(get("https://localhost:$tls$path", true)).`as`("TLS $path").isEqualTo(200)
        }
    }

    @Test
    fun `management readiness remains reachable without bearer`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.management.test-port", Int::class.java)
        assertThat(get("http://localhost:$port/q/health/ready", false)).isEqualTo(200)
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
}

/** Test-only route deliberately has no class or method security annotation. */
@Path("/api/v1/sdd/listener-auth-probe")
class SddListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
