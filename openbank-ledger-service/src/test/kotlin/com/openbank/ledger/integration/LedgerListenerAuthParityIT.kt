// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresRedpandaTestResource
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

/**
 * #12511: the 8443 listener (`client-auth: required` in %prod) must not turn a trusted client
 * certificate into an authenticated caller. Production listener shape, real HTTP, both ports.
 */
@QuarkusTest
@TestProfile(LedgerListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class LedgerListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/journals/trial-balance?asOf=2026-01-01"

    @Test
    fun `trusted client certificate without bearer cannot post a journal`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val request = HttpRequest.newBuilder(URI.create("https://localhost:$port/api/v1/journals"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        val response = HttpClient.newBuilder()
            .sslContext(ListenerMaterial.sslContext(true))
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(request, HttpResponse.BodyHandlers.discarding())

        assertThat(response.statusCode()).isEqualTo(401)
    }

    @Test
    fun `an unannotated API route requires bearer on the mTLS listener`() = assertUnannotatedRoute("https")

    @Test
    fun `an unannotated API route requires bearer on the plain HTTP listener`() = assertUnannotatedRoute("http")

    private fun assertUnannotatedRoute(scheme: String) {
        fun response(scheme: String, bearer: String?): Int {
            val portKey = if (scheme == "https") "quarkus.http.test-ssl-port" else "quarkus.http.test-port"
            val port = ConfigProvider.getConfig().getValue(portKey, Int::class.java)
            val request = HttpRequest.newBuilder(URI.create("$scheme://localhost:$port/api/v1/ledger/auth-probe"))
                .timeout(Duration.ofSeconds(10))
                .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                .GET()
                .build()
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .apply { if (scheme == "https") sslContext(ListenerMaterial.sslContext(true)) }
                .build()
            return client
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode()
        }

        assertThat(response(scheme, ListenerMaterial.bearer()))
            .`as`("$scheme test-only unannotated route is served with a valid bearer")
            .isEqualTo(200)
        assertThat(response(scheme, null))
            .`as`("$scheme test-only unannotated route must reject a request without bearer")
            .isEqualTo(401)
    }

    class Profile : ProductionListenerProfile()
}

/** Test-only route models a future endpoint accidentally missing a method security annotation. */
@Path("/api/v1/ledger/auth-probe")
class LedgerUnannotatedAuthProbe {
    @GET
    fun read(): Response = Response.ok().build()
}
