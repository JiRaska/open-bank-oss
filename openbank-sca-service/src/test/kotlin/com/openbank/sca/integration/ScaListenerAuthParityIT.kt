// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
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
@TestProfile(ScaListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/sca/listener-auth-probe"

    @Test
    fun `a trusted client certificate without a bearer cannot consume a challenge`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val request = HttpRequest.newBuilder(
            URI.create("https://localhost:$port/api/v1/sca/challenges/${UUID.randomUUID()}/consume"),
        ).timeout(Duration.ofSeconds(10))
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

    class Profile : ProductionListenerProfile()
}

/** A future SCA business endpoint without a role annotation must still require bearer auth. */
@Path("/api/v1/sca/listener-auth-probe")
class ScaListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
