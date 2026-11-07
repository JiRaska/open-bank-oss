// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The observable distributed shape of loan application submission.
 *
 * `POST /api/v1/lending/applications` persists the `loan_application` row and its
 * `credit.application.transition` "application submitted" `lending_outbox` evidence row. That
 * evidence row is what downstream consumers reconcile the origination against, so the two INSERTs
 * ARE the lending money write at this stage — every later step of the loan journey is built on this
 * row existing. The spans asserted here are the service's OWN: Quarkus' OpenTelemetry
 * auto-instrumentation produces them for the real HTTP request and the real Postgres writes;
 * `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a contract can read what an
 * OTLP collector would receive. Nothing asserted here is created by the test, so every assertion
 * can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says the application row and its evidence
 * row happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the rows and still answers
 * 201, is invisible to every presence assertion this module already had (LendingOutboxWriteIT
 * proves the rows land; nothing there proves they landed in the caller's trace), and lands in a
 * trace of its own.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); application submission is this service's first and
 * most load-bearing write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(LendingTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
@TestProfile(LendingTraceContractIT.LendingTraceProfile::class)
class LendingTraceContractIT {

    /**
     * The outgoing channel goes in-memory (and Kafka devservices stay off) for the same reason as
     * in [LendingOutboxWriteIT]: the test JVM has no broker, and the profile's own boot would
     * otherwise ask SmallRye for one.
     */
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap().also {
                it["quarkus.kafka.devservices.enabled"] = "false"
            }

        override fun stop() = InMemoryConnector.clear()
    }

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off); the one
     * addition is port 0: a profile's own boot re-binds the fixed default test port 8081 before the
     * outgoing instance has released it (`QuarkusBindException` — measured in billing's
     * `BillingOutboxAtomicityIT` and pinned the same way in this module's
     * `LendingCloseParticipationSchedulerCronIT`); port 0 lets Quarkus pick a free one and rewrites
     * the property so RestAssured still finds it.
     */
    class LendingTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.http.test-port" to "0",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it-proposer", roles = ["ROLE_LENDING_OFFICER"])
    fun `submitting a loan application keeps the request and both money rows in one trace`() {
        Given {
            contentType("application/json")
            body(
                """
                {"partyId":"${UUID.randomUUID()}","requestedAmount":{"amount":"10000.00","currency":{"code":"EUR"}},
                "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/lending/applications")
        } Then {
            statusCode(201)
            body("id", notNullValue())
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/lending/applications")
            .requiresAttribute("POST /api/v1/lending/applications", "http.response.status_code")
            .requiresAttribute("INSERT loan_application", "db.sql.table")
            .requiresAttribute("INSERT lending_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/lending/applications",
                "INSERT loan_application",
                "INSERT lending_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("lending-application-submit")
    }
}
