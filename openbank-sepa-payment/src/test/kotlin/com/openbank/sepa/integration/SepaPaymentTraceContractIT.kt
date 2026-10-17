// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.sepa.it.PostgresRedisTestResource
import com.openbank.sepa.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of the money-path write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres write; [com.openbank.sepa.it.TraceContractRecorder]
 * only adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would
 * receive. Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * payment happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still answers
 * 201, is invisible to every assertion this module already had (including the xmin-based outbox
 * atomicity proof, which says nothing about which trace the write landed in), and lands in a
 * trace of its own.
 *
 * Kafka is switched to the in-memory connector and the outbox dispatcher off (same reasoning as
 * [SepaPaymentOutboxAtomicityIT]): no broker container exists in this IT, and the dispatcher's
 * background sends are spans this contract never asked about.
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentTraceContractIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(TraceContractProfile::class)
class SepaPaymentTraceContractIT {

    class NoDispatchInMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("events-out").toMutableMap().also {
                it["quarkus.kafka.devservices.enabled"] = "false"
            }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `creating a payment keeps the HTTP request and its sepa_payments INSERT in one trace`() {
        val created = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", "trace-contract-${UUID.randomUUID()}")
            .body(
                """
                {
                  "type": "SCT",
                  "debtorAccountId": "${UUID.randomUUID()}",
                  "debtorIban": "CZ6508000000192000145399",
                  "debtorName": "Alice Example",
                  "creditorIban": "DE89370400440532013000",
                  "creditorName": "Berlin Utility",
                  "creditorBic": "COBADEFFXXX",
                  "amount": 1234.56,
                  "currency": "EUR",
                  "remittanceInfo": "Utility bill",
                  "endToEndId": null
                }
                """.trimIndent(),
            )
            .post("/api/v1/sepa-payments")
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/sepa-payments: %s", created.body.asString())
            .isEqualTo(201)

        exporter.contract()
            .requiresSpan("POST /api/v1/sepa-payments")
            .requiresAttribute("POST /api/v1/sepa-payments", "http.response.status_code")
            .requiresAttribute("INSERT sepa_payments", "db.sql.table")
            .requiresSameTrace("POST /api/v1/sepa-payments", "INSERT sepa_payments")
            .hasNoErrorSpan()
            .verifiedAs("sepa-payment-create")
    }
}
