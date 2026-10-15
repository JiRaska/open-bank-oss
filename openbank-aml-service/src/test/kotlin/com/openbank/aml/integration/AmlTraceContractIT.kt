// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.integration

import com.openbank.aml.it.PostgresRedisTestResource
import com.openbank.aml.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
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
 * The observable distributed shape of the case creation below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the case row and the
 * INSERT of its AmlCaseCreated event into `aml_outbox` happened *inside* the request's trace.
 * The ADR-0050 guarantee (aggregate and its domain event commit atomically via the outbox) has
 * relational coverage in this module; the trace half is what a compliance engineer searching
 * Tempo for "where did this screening case come from" actually sees. A row/event pair that
 * escaped the request context still answers 201 and is invisible to every assertion this
 * module already had, but lands in traces of its own — the case exists without an observable
 * cause.
 */
@QuarkusTest
@QuarkusTestResource(AmlTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(TraceContractProfile::class)
class AmlTraceContractIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("aml-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-compliance"),
            Claim(key = "preferred_username", value = "u-compliance"),
        ],
    )
    fun `creating an aml case keeps the request, its case INSERT and its outbox INSERT in one trace`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """{"partyId":"${UUID.randomUUID()}","accountId":null,"transactionId":null,""" +
                    """"customerReference":"trace-contract","screeningType":"TRANSACTION_MONITORING",""" +
                    """"riskLevel":"MEDIUM","alertCode":"TRACE_CONTRACT","alertDetail":null,""" +
                    """"matchedEntity":null}""",
            )
        } When {
            post("/api/v1/aml/cases")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/aml/cases")
            .requiresAttribute("POST /api/v1/aml/cases", "http.response.status_code")
            .requiresAttribute("INSERT aml_cases", "db.sql.table")
            .requiresAttribute("INSERT aml_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/aml/cases",
                "INSERT aml_cases",
                "INSERT aml_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("aml-create-case")
    }
}
