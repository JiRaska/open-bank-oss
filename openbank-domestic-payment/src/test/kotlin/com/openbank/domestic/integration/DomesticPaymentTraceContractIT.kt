// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.it.TraceContractProfile
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * The observable distributed shape of the money-path write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres write; [com.openbank.domestic.it.TraceContractRecorder]
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
 * [DomesticPaymentOutboxAtomicityIT]): no broker container exists in this IT, and the dispatcher's
 * background sends are spans this contract never asked about.
 */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentTraceContractIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
@TestProfile(TraceContractProfile::class)
class DomesticPaymentTraceContractIT {

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
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = ACTOR_ID),
            Claim(key = "preferred_username", value = ACTOR_ID),
        ],
    )
    fun `creating a payment keeps the HTTP request and its domestic_payments INSERT in one trace`() {
        val created = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", "trace-contract-${UUID.randomUUID()}")
            .body(
                """
                {
                  "debtorAccountId": "${UUID.randomUUID()}",
                  "debtorAccountNumber": "1234567890",
                  "debtorBankCode": "0800",
                  "debtorName": "Alice Example",
                  "creditorAccountNumber": "9876543210",
                  "creditorBankCode": "0100",
                  "creditorName": "Brno Utility",
                  "amount": ${BigDecimal("1234.56")},
                  "currency": "CZK",
                  "variableSymbol": "2026001",
                  "specificSymbol": null,
                  "constantSymbol": "0308",
                  "messageForPayee": "Utility bill",
                  "priority": "STANDARD",
                  "transferScope": null,
                  "technicalAccountCode": null,
                  "statementLabel": "Monthly settlement",
                  "endToEndId": "E2E-TRACE-CONTRACT-IT"
                }
                """.trimIndent(),
            )
            .post("/api/v1/domestic-payments")
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/domestic-payments: %s", created.body.asString())
            .isEqualTo(201)

        exporter.contract()
            .requiresSpan("POST /api/v1/domestic-payments")
            .requiresAttribute("POST /api/v1/domestic-payments", "http.response.status_code")
            .requiresAttribute("INSERT domestic_payments", "db.sql.table")
            .requiresSameTrace("POST /api/v1/domestic-payments", "INSERT domestic_payments")
            .hasNoErrorSpan()
            .verifiedAs("domestic-payment-create")
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"
    }
}
