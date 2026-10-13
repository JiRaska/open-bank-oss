// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
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

/**
 * #12511: a new, unannotated API route must still require a bearer on both listeners.
 * The production listener/auth settings come from application.yaml via ProductionListenerProfile.
 */
@QuarkusTest
@TestProfile(AccountListenerAuthParityIT.Profile::class)
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_listener_it")],
)
class AccountUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/account-listener-auth-probe"

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

/** Test-only route deliberately lacking both method and class security annotations. */
@Path("/api/v1/account-listener-auth-probe")
class AccountListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
