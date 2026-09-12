// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
import com.openbank.consent.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The observable distributed shape of the money-path write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * aggregate happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still answers
 * 201, is invisible to every assertion this module already had, and lands in a trace of its own.
 */
@QuarkusTest
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
@TestProfile(TraceContractProfile::class)
class ConsentTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `granting a consent keeps the request and its consents INSERT in one trace`() {
        val partyId = UUID.randomUUID()
        val payload = """
            {"partyId":"$partyId","granteeId":"tpp-trace","granteeType":"TPP","granteeName":"Trace TPP",
            "scopes":["ACCOUNTS_READ"],"accountIbans":["CZ6508000000192000145399"],
            "validTo":"${OffsetDateTime.now().plusDays(30)}","redirectUri":null,"tppTransactionId":null}
        """.trimIndent()

        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/consents")
            .requiresAttribute("POST /api/v1/consents", "http.response.status_code")
            .requiresAttribute("INSERT consents", "db.sql.table")
            .requiresSameTrace("POST /api/v1/consents", "INSERT consents")
            .hasNoErrorSpan()
            .verifiedAs("consent-grant")
    }
}
