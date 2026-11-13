// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import com.openbank.swift.integration.SwiftOutboxAtomicityIT.AcceptingSchemeGateway
import com.openbank.swift.integration.SwiftOutboxAtomicityIT.AccountIdDrivenSettlement
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of a SWIFT message submission.
 *
 * With scheme submission enabled, `POST /api/v1/swift` persists the `swift_messages` row and its
 * `swift.message.status-changed` `swift_outbox` row in ONE transaction at the scheme verdict
 * (proved row-wise by [SwiftOutboxAtomicityIT]). The outbox row is the status fact the settlement
 * and reconciliation projections build on, so the two writes ARE the SWIFT money path. The spans
 * asserted here are the service's OWN: Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says the message row and its evidence row
 * happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the rows and still answers
 * 201, is invisible to every presence assertion this module already had, and lands in a trace of
 * its own: an interbank payment instruction no operator can reconstruct from telemetry.
 *
 * The scheme gateway and settlement adapters are the same CDI alternatives the sibling IT enables
 * through its profile ([AcceptingSchemeGateway], [AccountIdDrivenSettlement]); with no
 * `orderingCustomerAccountId` the settlement leg is a no-op and the message ends at SENT, so one
 * request exercises the scheme-verdict write in isolation.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); message submission is this service's most important
 * write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(SwiftTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
@TestProfile(SwiftTraceContractIT.SwiftTraceProfile::class)
class SwiftTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off); the one
     * addition is `openbank.swift.scheme-submission.enabled=true` — ADR-0104 D4's pilot flag
     * defaults OFF, and with it off `submitToScheme` returns before either write path is reached
     * (same switch as [SwiftOutboxAtomicityIT.SchemeSubmissionNoDispatchProfile]). This module's
     * `%test` already pins `test-port: 0`, so no port override is needed. The two collaborators
     * are enabled as alternatives through the profile, exactly like the sibling IT: an
     * `@Alternative` with `@Priority` would leak into every other test in the module.
     */
    class SwiftTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "openbank.swift.scheme-submission.enabled" to "true",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> =
            setOf(AcceptingSchemeGateway::class.java, AccountIdDrivenSettlement::class.java)
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("swift-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `submitting a swift message keeps the request and both money rows in one trace`() {
        val reference = "TRX-${UUID.randomUUID().toString().take(REFERENCE_SUFFIX_LENGTH)}"

        val response = Given {
            contentType("application/json")
            body(
                """
                {
                  "idempotencyKey": "${UUID.randomUUID()}",
                  "messageType": "MT103",
                  "senderBic": "OPBKCZPP",
                  "receiverBic": "DEUTDEFF",
                  "transactionReference": "$reference",
                  "relatedReference": null,
                  "valueDate": "20260120",
                  "currency": "EUR",
                  "amountMinorUnits": 125000,
                  "orderingCustomerAccount": "DE89370400440532013000",
                  "orderingCustomerAccountId": null,
                  "orderingCustomerName": "Alice",
                  "beneficiaryAccount": "GB33BUKB20201555555555",
                  "beneficiaryName": "Bob",
                  "remittanceInfo": "Invoice 1",
                  "chargeCode": "SHA",
                  "priority": "NORMAL"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(201)
        }
        // Arrangement assertion: submitToScheme swallows every failure and holds the message in
        // VALIDATED, so without this a broken stub (or a flag that never took effect) would leave
        // the trace without the money writes while the HTTP assertions still pass.
        assertThat(response.extract().path<String>("status"))
            .describedAs("the flow really reached the scheme-verdict write path")
            .isEqualTo("SENT")

        exporter.contract()
            .requiresSpan("POST /api/v1/swift")
            .requiresAttribute("POST /api/v1/swift", "http.response.status_code")
            .requiresAttribute("INSERT swift_messages", "db.sql.table")
            .requiresAttribute("INSERT swift_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/swift",
                "INSERT swift_messages",
                "INSERT swift_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("swift-message-submit")
    }

    private companion object {
        /** `swift_messages.transaction_reference` is `varchar(16)`; `TRX-` + 12 hex fills it exactly. */
        const val REFERENCE_SUFFIX_LENGTH = 12
    }
}
