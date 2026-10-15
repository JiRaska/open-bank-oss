// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.TraceContractProfile
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The observable distributed shape of the money-path write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres write; [com.openbank.lending.it.TraceContractRecorder]
 * only adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would
 * receive. Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * loan application happened *inside* the request's trace. A write that escaped the request
 * context — an un-propagated reactive hop, a fire-and-forget executor — still writes the row and
 * still answers 201, is invisible to every assertion this module already had (including the
 * outbox presence proofs, which say nothing about which trace the write landed in), and lands in
 * a trace of its own.
 *
 * Kafka rides the in-memory connector (no broker container in this IT) and the outbox dispatcher
 * is off (its background sends are spans this contract never asked about), both as in
 * [LendingOutboxWriteIT].
 */
@QuarkusTest
@TestProfile(TraceContractProfile::class)
@QuarkusTestResource(LendingTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class LendingTraceContractIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-contract-it", roles = ["ROLE_LENDING_OFFICER"])
    fun `applying for a loan keeps the HTTP request and its loan_application INSERT in one trace`() {
        val response = Given {
            contentType("application/json")
            body(
                """
                {"partyId":"${UUID.randomUUID()}","requestedAmount":{"amount":"10000.00","currency":{"code":"EUR"}},
                "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/lending/applications")
        }
        assertThat(response.statusCode)
            .describedAs("POST /api/v1/lending/applications: %s", response.body.asString())
            .isEqualTo(201)

        exporter.contract()
            .requiresSpan("POST /api/v1/lending/applications")
            .requiresAttribute("POST /api/v1/lending/applications", "http.response.status_code")
            .requiresAttribute("INSERT loan_application", "db.sql.table")
            .requiresSameTrace("POST /api/v1/lending/applications", "INSERT loan_application")
            .hasNoErrorSpan()
            .verifiedAs("lending-application-apply")
    }
}
