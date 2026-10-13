// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
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

/** An unannotated future domestic payment API still requires a bearer on both listeners. */
@QuarkusTest
@TestProfile(DomesticUnannotatedListenerAuthIT.Profile::class)
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_listener_it")],
)
class DomesticUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    class Profile : ProductionListenerProfile() {
        override fun extraOverrides(): Map<String, String> = mapOf(
            "quarkus.http.test-port" to "39211",
            "quarkus.http.test-ssl-port" to "39212",
            "quarkus.management.enabled" to "true",
            "quarkus.management.test-port" to "39213",
        )
    }

    override val path = "/api/v1/domestic/listener-auth-probe"

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
    fun `management readiness remains reachable without bearer`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.management.test-port", Int::class.java)
        assertThat(get("http://localhost:$port/q/health/ready", false)).isEqualTo(200)
    }

    @Test
    fun `existing payment list accepts the same signed bearer on both listeners`() {
        val config = ConfigProvider.getConfig()
        val plain = config.getValue("quarkus.http.test-port", Int::class.java)
        val tls = config.getValue("quarkus.http.test-ssl-port", Int::class.java)
        val bearer = ListenerMaterial.bearer()
        assertThat(get("http://localhost:$plain/api/v1/domestic-payments", false, bearer)).isEqualTo(200)
        assertThat(get("https://localhost:$tls/api/v1/domestic-payments", true, bearer)).isEqualTo(200)
    }

    private fun get(url: String, tls: Boolean, bearer: String? = null): Int {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .apply { if (tls) sslContext(ListenerMaterial.sslContext(true)) }
            .build()
        return client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .apply { bearer?.let { header("Authorization", "Bearer $it") } }.GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()
    }
}

/** Test-only route deliberately has no class or method security annotation. */
@Path("/api/v1/domestic/listener-auth-probe")
class DomesticListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
