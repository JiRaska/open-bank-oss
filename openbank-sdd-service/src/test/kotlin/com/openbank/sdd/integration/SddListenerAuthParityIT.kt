// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ListenerMaterial
import com.openbank.libs.testing.mtls.ProductionListenerProfile
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
 * #12511: the TLS listener (`client-auth: required` in %prod) must not turn a trusted client
 * certificate into an authenticated caller. Production listener shape, real HTTP, both ports.
 */
@QuarkusTest
@TestProfile(SddListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresTestResource::class)
class SddListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/sdd/mandates/recent?limit=1"

    @Test
    fun `a trusted client certificate without a bearer cannot register a mandate`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val request = HttpRequest.newBuilder(URI.create("https://localhost:$port/api/v1/sdd/mandates"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        val response = HttpClient.newBuilder()
            .sslContext(ListenerMaterial.sslContext(withClientCertificate = true))
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(request, HttpResponse.BodyHandlers.discarding())

        assertThat(response.statusCode()).isEqualTo(401)
    }

    // No OPA sidecar in the test: authz runs advisory so the authenticated path reaches the route
    // (sdd's application.yaml defaults enforce to true, which fails closed with 503 without a PDP).
    class Profile : ProductionListenerProfile() {
        override fun extraOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }
}
