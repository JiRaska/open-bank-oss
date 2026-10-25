// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Real CDI + HTTP regression; one event loop makes a parked authorization bridge observable. */
@QuarkusTest
@TestProfile(PartyReactiveGuardIT.Profile::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class PartyReactiveGuardIT {
    @Inject
    lateinit var pdp: ReactiveGuardPdp

    @Inject
    lateinit var mapper: ObjectMapper

    @TestHTTPResource
    lateinit var baseUri: URI

    @ParameterizedTest
    @CsvSource("uni,true", "stage,true", "uni,false", "stage,false")
    @TestSecurity(user = "reactive-guard-caller", roles = ["ROLE_OPERATOR"])
    @Timeout(20)
    fun `delayed authorization preserves request context and denies before the target`(shape: String, allow: Boolean) {
        pdp.reset()
        val marker = "guard-$shape-$allow"
        HttpClient.newHttpClient().use { client ->
            val response = client.sendAsync(request(shape, marker), HttpResponse.BodyHandlers.ofString())
            try {
                val query = pdp.started.get(3, TimeUnit.SECONDS)
                assertThat(query.principal.id).isEqualTo("reactive-guard-caller")
                assertThat(query.attributes["idempotency-key"]).isEqualTo(marker)
                assertThat(response).isNotDone()
                assertThat(pdp.invocations.get()).isZero()

                // This runs on the only event loop while the first request is awaiting its PDP.
                val heartbeat = client.send(request("heartbeat", marker), HttpResponse.BodyHandlers.ofString())
                assertThat(heartbeat.statusCode()).isEqualTo(200)

                pdp.decision.complete(allow)
                val completed = response.get(3, TimeUnit.SECONDS)
                assertThat(completed.statusCode()).isEqualTo(if (allow) 200 else 403)
                assertThat(pdp.invocations.get()).isEqualTo(if (allow) 1 else 0)
                if (allow) {
                    val body = mapper.readTree(completed.body())
                    assertThat(body.path("caller").asText()).isEqualTo("reactive-guard-caller")
                    assertThat(body.path("marker").asText()).isEqualTo(marker)
                    assertThat(body.path("sameContext").asBoolean()).isTrue()
                    assertThat(body.path("eventLoop").asBoolean()).isTrue()
                }
            } finally {
                // An old blocking bridge must fail boundedly and still release its waiting request.
                pdp.decision.complete(false)
                response.cancel(true)
            }
        }
    }

    private fun request(path: String, marker: String): HttpRequest = HttpRequest.newBuilder()
        .uri(baseUri.resolve("/test/reactive-guard/$path"))
        .timeout(Duration.ofSeconds(5))
        .header("Idempotency-Key", marker)
        .GET()
        .build()

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "true",
            "quarkus.vertx.event-loops-pool-size" to "1",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(ReactiveGuardPdp::class.java)
    }
}
