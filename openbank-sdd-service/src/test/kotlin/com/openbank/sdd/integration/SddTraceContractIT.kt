// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.sdd.it.PostgresTestResource
import com.openbank.sdd.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of registering a debtor mandate (money path, ADR-0036).
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real request and the real Postgres write; [com.openbank.sdd.it.TraceContractRecorder] only
 * adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * mandate happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop (Mutiny `runSubscriptionOn`, a fire-and-forget `Uni`) — still writes
 * the row and still answers 201, and is invisible to every assertion this module already had, but
 * it lands in a trace of its own and this fails.
 */
@QuarkusTest
@QuarkusTestResource(SddTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class SddTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = ACTOR_ID),
            Claim(key = "preferred_username", value = ACTOR_ID),
        ],
    )
    fun `registering a mandate keeps the HTTP request and its INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """
                {
                  "accountId": "${UUID.randomUUID()}",
                  "debtorIban": "CZ6508000000192000145399",
                  "creditorIdentifier": "CZ98ZZZ${UUID.randomUUID().toString().take(8)}",
                  "umr": "UMR-${UUID.randomUUID().toString().take(UMR_SUFFIX_LENGTH)}",
                  "scheme": "CORE",
                  "sequenceType": "FRST",
                  "creditorName": "Energie a.s.",
                  "debtorName": "Jan Novak",
                  "signatureDate": "2026-01-15"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sdd/mandates")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/sdd/mandates")
            .requiresAttribute("POST /api/v1/sdd/mandates", "http.response.status_code")
            .requiresAttribute("INSERT sdd_mandate", "db.sql.table")
            .requiresSameTrace("POST /api/v1/sdd/mandates", "INSERT sdd_mandate")
            .hasNoErrorSpan()
            .verifiedAs("sdd-mandate-register")
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("sdd-events-out").toMutableMap()
            props.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("sdd-collection-authorised-in"))
            props["quarkus.kafka.devservices.enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"
        const val UMR_SUFFIX_LENGTH = 8
    }
}
