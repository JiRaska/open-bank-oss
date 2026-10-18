// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.incentive.integration

import com.openbank.incentive.it.IncentivePostgresTestResource
import com.openbank.incentive.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The observable distributed shape of the offer creation below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the offer row and the
 * INSERT of its `incentive.offer.created.v1` event into `incentive_outbox` happened *inside*
 * the request's trace. [IncentiveRestContractIT] proves the HTTP lifecycle behaves; the
 * relational half of the outbox guarantee is asserted by reading the rows back over JDBC
 * there. The trace half is what a support engineer searching Tempo for "why did this offer
 * reach campaign-service" actually sees. A row/event pair that escaped the request context
 * still answers 201 and is invisible to every assertion this module already had, but lands in
 * traces of its own — the offer exists without an observable cause.
 */
@QuarkusTest
@QuarkusTestResource(IncentiveTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(IncentivePostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class IncentiveTraceContractIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("incentive-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-contract", roles = ["ROLE_OPERATOR"])
    fun `creating an offer keeps the request, its row INSERT and its outbox INSERT in one trace`() {
        val effectiveFrom = Instant.now().minusSeconds(60)
        val expiresAt = Instant.now().plusSeconds(86_400)

        Given {
            contentType("application/json")
            body(
                """{
                    "name":"trace-contract-offer",
                    "version":1,
                    "productScope":["current-account"],
                    "effectiveFrom":"$effectiveFrom",
                    "expiresAt":"$expiresAt",
                    "totalLimit":10,
                    "perPartyLimit":1,
                    "stackingPolicy":"STACKABLE"
                }""",
            )
        } When {
            post("/api/v1/incentives/offers")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/incentives/offers")
            .requiresAttribute("POST /api/v1/incentives/offers", "http.response.status_code")
            .requiresAttribute("INSERT incentive_offer", "db.sql.table")
            .requiresAttribute("INSERT incentive_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/incentives/offers",
                "INSERT incentive_offer",
                "INSERT incentive_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("incentive-create-offer")
    }
}
