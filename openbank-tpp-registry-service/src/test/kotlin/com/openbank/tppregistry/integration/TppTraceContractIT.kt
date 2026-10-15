// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.tppregistry.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
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
 * The observable distributed shape of the registration below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the registry row and the
 * INSERT of its TPP_REGISTERED event into `tpp_outbox` happened *inside* the request's trace.
 * [TppOutboxWriteIT] proves the pair commits together relationally (a plain JDBC read-back);
 * the trace half of that guarantee is what a security engineer searching Tempo for "when did
 * this third party enter our registry" actually sees. A row/event pair that escaped the
 * request context still answers 201 and is invisible to every assertion this module already
 * had, but lands in traces of its own — the TPP exists without an observable cause.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tpp_registry_it")],
)
@TestProfile(TraceContractProfile::class)
class TppTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-contract", roles = ["ROLE_ADMIN"])
    fun `registering a tpp keeps the request, its row INSERT and its outbox INSERT in one trace`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """{"tppId":"TPP-TRACE-${UUID.randomUUID().toString().take(8)}",""" +
                    """"name":"Trace Contract","countryCode":"CZ","nca":"CNB",""" +
                    """"roles":["AISP"],"qwacSubjectDn":"CN=QWAC","qsealSubjectDn":null}""",
            )
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/tpp-registry")
            .requiresAttribute("POST /api/v1/tpp-registry", "http.response.status_code")
            .requiresAttribute("INSERT tpp_entries", "db.sql.table")
            .requiresAttribute("INSERT tpp_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/tpp-registry",
                "INSERT tpp_entries",
                "INSERT tpp_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("tpp-register")
    }
}
