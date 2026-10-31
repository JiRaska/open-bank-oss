// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import com.openbank.libs.security.SecurityTelemetry
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The same value the sandbox Deployment sets (gitops customer-edge.yaml). Literal on purpose. */
class HoneyEndpointProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "openbank.security.honey.paths" to "/customer/v1/internal/debug,/customer/v1/admin/export",
    )
}

/**
 * ADR-0357: a hit on a configured honey path, through the real HTTP stack, needs no credentials,
 * increments `openbank_security_honeytoken_hits_total` (a series that had never existed), and is
 * answered by the SAME 404 as any other missing route. A unit test of the filter cannot show it
 * runs at all: before `@PreMatching` RESTEasy answered an unrouted path itself and never called a
 * post-matching filter, so this test failed on the counter (measured 2026-10-31).
 */
@QuarkusTest
@TestProfile(HoneyEndpointProfile::class)
class HoneyEndpointIT {
    @TestHTTPResource("/customer/v1/internal/debug")
    lateinit var honey: URI

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    fun `an unauthenticated hit on a honey path is a 404 and is counted`() {
        val before = hits()
        val response = HttpClient.newHttpClient().use { client ->
            client.send(
                HttpRequest.newBuilder(honey).header("X-Forwarded-For", "203.0.113.7").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(hits()).isEqualTo(before + 1.0)
        // Indistinguishable from a route that does not exist.
        val control = HttpClient.newHttpClient().use { client ->
            client.send(
                HttpRequest.newBuilder(honey.resolve("/customer/v1/no-such-route")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        assertThat(response.statusCode()).isEqualTo(control.statusCode())
        assertThat(codeOf(response.body())).isEqualTo(codeOf(control.body())).isEqualTo("NOT_FOUND")
    }

    private fun codeOf(body: String): String? = Regex("\"code\":\"([A-Z_]+)\"").find(body)?.groupValues?.get(1)

    private fun hits(): Double =
        registry.find(SecurityTelemetry.HONEYTOKEN_HITS).tag("path", "customer/v1/internal/debug").counter()?.count()
            ?: 0.0
}
