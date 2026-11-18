// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
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
 * The observable distributed shape of registering a TPP.
 *
 * `POST /api/v1/tpp-registry` persists the registry row that every subsequent AIS/PIS consent
 * decision reads (`tpp_entries`), alongside its outbox row, in one transaction (see
 * [TppOutboxWriteIT] for why the two must commit together). The spans asserted here are the
 * service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for the real HTTP
 * request and the real Postgres write; `TraceContractRecorder` only adds an in-memory
 * `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing asserted
 * here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * registry entry happened *inside* the request's trace. A write that escaped the request context
 * — an un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace of
 * its own. For the registry that row is the licence the bank relies on before it lets a third
 * party near a customer's accounts.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap);
 * TPP registration is the registry's most important write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tpp_registry_it")],
)
@TestProfile(TppTraceContractIT.TppTraceProfile::class)
class TppTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off); the extra
     * key mirrors [TppOutboxWriteIT.DispatcherOffResource]: there is no OPA sidecar in a test JVM
     * and the interceptor correctly fails CLOSED (503) without one — the subject here is the trace
     * shape of the write, not the policy decision.
     */
    class TppTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it", roles = ["ROLE_ADMIN"])
    fun `registering a tpp keeps the request and its registry INSERT in one trace`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """
                {"tppId":"trace-${UUID.randomUUID().toString().take(8)}","name":"Trace Probe",
                 "countryCode":"CZ","nca":"CNB","roles":["AISP"],"qwacSubjectDn":"CN=QWAC",
                 "qsealSubjectDn":null}
                """.trimIndent(),
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
            .requiresSameTrace("POST /api/v1/tpp-registry", "INSERT tpp_entries")
            .hasNoErrorSpan()
            .verifiedAs("tpp-registry-register")
    }
}
