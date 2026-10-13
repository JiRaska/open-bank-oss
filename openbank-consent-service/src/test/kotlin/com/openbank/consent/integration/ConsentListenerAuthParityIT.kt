// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ListenerMaterial
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
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

/** Real HTTP/TLS check of `%prod` listener auth, including a future unannotated business route. */
@QuarkusTest
@TestProfile(ConsentListenerAuthParityIT.Profile::class)
@QuarkusTestResource(ConsentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class ConsentListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/suppressions/listener-auth-probe"

    class Profile : ProductionListenerProfile()

    @Test
    fun `public metadata and management health remain accessible without bearer`() {
        given().get("/api/v1/info").then().statusCode(200)
        given().get("/api/v1/config").then().statusCode(200)
        given().get("/q/health/ready").then().statusCode(200)
    }

    @Test
    fun `collection roots refuse a certificate without a bearer`() {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-ssl-port", Int::class.java)
        val client = HttpClient.newBuilder().sslContext(ListenerMaterial.sslContext(true))
            .connectTimeout(Duration.ofSeconds(10)).build()
        for (root in listOf("/api/v1/consents", "/api/v1/suppressions")) {
            val request = HttpRequest.newBuilder(URI.create("https://localhost:$port$root"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            assertThat(status).`as`("cert-only POST $root").isEqualTo(401)
        }
    }
}

/** Test-only business resource deliberately lacking method and class security annotations. */
@Path("/api/v1/suppressions/listener-auth-probe")
class ConsentListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
