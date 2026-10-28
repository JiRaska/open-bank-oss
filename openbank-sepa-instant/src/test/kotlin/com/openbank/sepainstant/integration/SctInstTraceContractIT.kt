// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of an instant SEPA credit transfer.
 *
 * With scheme submission enabled, `POST /api/v1/sepa-instant` persists the `sct_inst_payments`
 * row and BOTH of its `sct_inst_outbox` evidence rows (`SctInstPaymentSubmitted`,
 * `SctInstPaymentSettled`) — the submission fact and the settled fact that downstream
 * reconciliation and the recall journey build on (proved row-wise by
 * [SctInstSettlementAtomicityIT]). An instant payment is the fleet's most time-critical money
 * write: the whole point of SCT Inst is that the money moves in seconds, so the spans asserted
 * here are the service's OWN: Quarkus' OpenTelemetry auto-instrumentation produces them for the
 * real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an in-memory
 * `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing asserted
 * here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says the payment row and both of its
 * evidence rows happened *inside* the request's trace. A write that escaped the request context —
 * an un-propagated reactive hop, a fire-and-forget executor — still writes the rows and still
 * answers 201, is invisible to every presence assertion this module already had, and lands in a
 * trace of its own: for SCT Inst that is seconds-scale money movement no operator can reconstruct
 * from telemetry.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); instant payment submission is this service's only
 * money write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_it")],
)
@TestProfile(SctInstTraceContractIT.SctInstTraceProfile::class)
class SctInstTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off). The
     * additions are sepa-instant-specific:
     *
     *  - `openbank.sct-inst.scheme-submission.enabled=true`, the same switch as
     *    [SctInstSettlementAtomicityIT.SchemeEnabledProfile]: the request settles synchronously in
     *    the test JVM, so one POST exercises both money writes;
     *  - port 0: a profile's own boot re-binds the fixed default test port 8081 before the
     *    outgoing instance has released it (`QuarkusBindException` — measured in billing's
     *    `BillingOutboxAtomicityIT`); port 0 lets Quarkus pick a free one and rewrites the
     *    property so RestAssured still finds it;
     *  - `quarkus.oidc-client.enabled=false`: `%test` fixes this build-time key to false, and a
     *    custom profile's boot otherwise reads a runtime true — the same
     *    `ConfigRecorder: Build time property cannot be changed at runtime` failure measured in
     *    sepa-payment's `SepaPaymentOutboxClaimIT`. Either pin satisfies the recorder; this test
     *    makes no outbound M2M call, so it pins the disabled state.
     */
    class SctInstTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "openbank.sct-inst.scheme-submission.enabled" to "true",
            "quarkus.http.test-port" to "0",
            "quarkus.oidc-client.enabled" to "false",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it-operator", roles = ["ROLE_OPERATOR"])
    fun `submitting an instant payment keeps the request and all money rows in one trace`() {
        val key = UUID.randomUUID().toString()

        Given {
            contentType(ContentType.JSON)
            header("Idempotency-Key", key)
            body(
                """{"idempotencyKey":"$key","debtorAccountId":"${UUID.randomUUID()}","debtorIban":"CZ6508000000192000145399","debtorName":"Test Debtor","creditorIban":"DE89370400440532013000","creditorName":"Test Creditor","creditorBic":"COBADEFFXXX","amount":99.99,"currency":"EUR","endToEndId":"E2E-$key"}""",
            )
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(201)
            body("status", equalTo("SETTLED"))
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/sepa-instant")
            .requiresAttribute("POST /api/v1/sepa-instant", "http.response.status_code")
            .requiresAttribute("INSERT sct_inst_payments", "db.sql.table")
            .requiresAttribute("INSERT sct_inst_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/sepa-instant",
                "INSERT sct_inst_payments",
                "INSERT sct_inst_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("sct-inst-payment-submit")
    }
}
