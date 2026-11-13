// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import com.openbank.sepa.it.PostgresRedisTestResource
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
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of SEPA payment creation.
 *
 * `POST /api/v1/sepa-payments` commits the `sepa_payments` row and its `sepa.payment.created`
 * `sepa_payment_outbox` row in ONE transaction (`SepaPaymentRepositoryImpl.save` — proven row-wise
 * by [SepaPaymentOutboxAtomicityIT]). The outbox row is the evidence record the scheme gateway and
 * every downstream consumer reconcile against, so the two INSERTs ARE the money write. The spans
 * asserted here are the service's OWN: Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says the payment row and its evidence row
 * happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the rows and still answers
 * 201, is invisible to every presence assertion this module already had, and lands in a trace of
 * its own. For a SEPA credit transfer that is a payment instruction no operator can reconstruct
 * from telemetry.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); payment creation is this service's most important
 * write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(SepaTraceContractIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(SepaTraceContractIT.SepaTraceProfile::class)
class SepaTraceContractIT {

    /**
     * The outgoing channel goes in-memory (and Kafka devservices stay off) for the same reason as
     * in [SepaPaymentOutboxAtomicityIT]; the profile's own boot would otherwise ask SmallRye for a
     * live broker there is none of in the test JVM.
     */
    class NoDispatchInMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("events-out").toMutableMap().also {
                it["quarkus.kafka.devservices.enabled"] = "false"
            }

        override fun stop() = InMemoryConnector.clear()
    }

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off). The
     * additions are sepa-specific:
     *
     *  - port 0: a profile's own boot re-binds the fixed default test port 8081 before the
     *    outgoing instance has released it (`QuarkusBindException` — measured in billing's
     *    `BillingOutboxAtomicityIT`); port 0 lets Quarkus pick a free one and rewrites the
     *    property so RestAssured still finds it.
     *  - `quarkus.oidc-client.enabled=false`: `%test` fixes this build-time key to false, but a
     *    custom profile's boot reads a runtime value of true (same `ConfigRecorder:
     *    Build time property cannot be changed at runtime` failure measured in
     *    `SepaPaymentOutboxClaimIT`, whose profile pins the key to true because it needs the
     *    client). This test makes no scheme submission, so it pins the disabled state the rest
     *    of the module's `%test` boots run with — either pin satisfies the recorder; they must
     *    only match.
     */
    class SepaTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.http.test-port" to "0",
            "quarkus.oidc-client.enabled" to "false",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `creating a payment keeps the request and both money rows in one trace`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "trace-it-${UUID.randomUUID()}")
            body(
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
                  "remittanceInfo": "Trace contract",
                  "endToEndId": null
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sepa-payments")
        } Then {
            statusCode(201)
            body("id", notNullValue())
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/sepa-payments")
            .requiresAttribute("POST /api/v1/sepa-payments", "http.response.status_code")
            .requiresAttribute("INSERT sepa_payments", "db.sql.table")
            .requiresAttribute("INSERT sepa_payment_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/sepa-payments",
                "INSERT sepa_payments",
                "INSERT sepa_payment_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("sepa-payment-create")
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000008353"
    }
}
