// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.integration

import com.openbank.document.contract.PdfRendererStubResource
import com.openbank.document.it.PostgresRedisTestResource
import com.openbank.document.it.TraceContractProfile
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
 * template happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still answers
 * 201, is invisible to every assertion this module already had, and lands in a trace of its own.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@QuarkusTestResource(PdfRendererStubResource::class, restrictToAnnotatedClass = true)
@TestProfile(TraceContractProfile::class)
class DocumentTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `ensuring an onboarding agreement keeps the request and its documents INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """
                {
                  "partyRef": "tc-${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}",
                  "lang": "cs"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/documents/onboarding-agreement")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/documents/onboarding-agreement")
            .requiresAttribute("POST /api/v1/documents/onboarding-agreement", "http.response.status_code")
            .requiresAttribute("INSERT documents", "db.sql.table")
            .requiresSameTrace("POST /api/v1/documents/onboarding-agreement", "INSERT documents")
            .hasNoErrorSpan()
            .verifiedAs("document-onboarding-agreement")
    }
}
