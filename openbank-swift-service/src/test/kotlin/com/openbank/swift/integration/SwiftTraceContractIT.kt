// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.swift.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of the money-path write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres write; [com.openbank.swift.it.TraceContractRecorder]
 * only adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would
 * receive. Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * message happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still answers
 * 201, is invisible to every assertion this module already had (including the xmin-based outbox
 * atomicity proof, which says nothing about which trace the write landed in), and lands in a
 * trace of its own.
 *
 * The message is sent without `orderingCustomerAccountId`, so the settlement leg (profiled to a
 * no-op for absent account ids, mirroring `SettlementAdapter`'s own rule) leaves it at SENT with
 * exactly the create + scheme-verdict writes — the smallest flow that still persists the message.
 *
 * Kafka rides the in-memory connector (no broker container in this IT) and the outbox dispatcher
 * is off (its background sends are spans this contract never asked about).
 */
@QuarkusTest
@TestProfile(TraceContractProfile::class)
@QuarkusTestResource(SwiftTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
class SwiftTraceContractIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("swift-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `sending a message keeps the HTTP request and its swift_messages INSERT in one trace`() {
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
        }
        assertThat(response.statusCode)
            .describedAs("POST /api/v1/swift: %s", response.body.asString())
            .isEqualTo(201)
        assertThat(response.jsonPath().getString("status"))
            .describedAs("the accepting gateway stub must carry the message past VALIDATED")
            .isEqualTo("SENT")

        exporter.contract()
            .requiresSpan("POST /api/v1/swift")
            .requiresAttribute("POST /api/v1/swift", "http.response.status_code")
            .requiresAttribute("INSERT swift_messages", "db.sql.table")
            .requiresSameTrace("POST /api/v1/swift", "INSERT swift_messages")
            .hasNoErrorSpan()
            .verifiedAs("swift-message-send")
    }

    private companion object {
        /** `swift_messages.transaction_reference` is `varchar(16)`; `TRX-` + 12 hex fills it exactly. */
        const val REFERENCE_SUFFIX_LENGTH = 12
    }
}
