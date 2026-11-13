// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LoadSheddingPilotProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.load-shedding.enabled" to "true",
        "quarkus.load-shedding.initial-limit" to "2",
        "quarkus.load-shedding.max-limit" to "2",
        "quarkus.load-shedding.priority.enabled" to "false",
        "openbank.rate-limit.enabled" to "false",
        "quarkus.http.http2" to "false",
    )
}

/** Test-only route: holds admitted work briefly without blocking the event loop. */
@Path("/__test/load-shedding")
class LoadSheddingProbeResource {
    @GET
    @PermitAll
    fun slow(): Uni<String> = Uni.createFrom().item("ok")
        .onItem().delayIt().by(Duration.ofMillis(WORK_MILLIS))

    private companion object {
        const val WORK_MILLIS = 750L
    }
}

@QuarkusTest
@TestProfile(LoadSheddingPilotProfile::class)
class LoadSheddingPilotIT {
    @TestHTTPResource("/__test/load-shedding")
    lateinit var endpoint: URI

    @Test
    fun `overload is rejected and admission recovers after active work completes`() {
        assertThat(ConfigProvider.getConfig().getValue("openbank.rate-limit.enabled", Boolean::class.java)).isFalse()
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET().build()
            val pending = (1..CONCURRENT_REQUESTS).map {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            }
            CompletableFuture.allOf(*pending.toTypedArray()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val statuses = pending.map { it.join().statusCode() }
            assertThat(statuses).contains(200, 503).allMatch { it == 200 || it == 503 }
            assertThat(pending.map { it.join().version() }).containsOnly(HttpClient.Version.HTTP_1_1)
            val recovered = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertThat(recovered.statusCode()).isEqualTo(200)
            assertThat(recovered.body()).isEqualTo("ok")
        }
    }

    private companion object {
        const val CONCURRENT_REQUESTS = 12
        const val TIMEOUT_SECONDS = 15L
    }
}
