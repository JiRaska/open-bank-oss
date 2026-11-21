// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.referral.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
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
 * The observable distributed shape of creating a referral program.
 *
 * `POST /api/v1/referrals/programs` persists the draft program every later invite, attribution
 * and reward references (`referral_program`). The spans asserted here are the service's OWN —
 * Quarkus' OpenTelemetry auto-instrumentation produces them for the real HTTP request and the
 * real Postgres write; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a
 * contract can read what an OTLP collector would receive. Nothing asserted here is created by
 * the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * program happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace of
 * its own.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); program creation is the write every later referral lifecycle step hangs off, so it is
 * the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(ReferralTraceContractIT.ReferralTraceProfile::class)
class ReferralTraceContractIT {

    /** The libs baseline gives this class its own boot (own recorder, dispatcher off). */
    class ReferralTraceProfile : TraceContractProfile()

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it", roles = ["ROLE_OPERATOR"])
    fun `creating a program keeps the request and its program INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """{"name":"trace-${UUID.randomUUID()}","version":1,"rewardAmount":10,"currency":"EUR",""" +
                    """"qualifyingEvent":"account.opened"}""",
            )
        } When {
            post("/api/v1/referrals/programs")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/referrals/programs")
            .requiresAttribute("POST /api/v1/referrals/programs", "http.response.status_code")
            .requiresAttribute("INSERT referral_program", "db.sql.table")
            .requiresSameTrace("POST /api/v1/referrals/programs", "INSERT referral_program")
            .hasNoErrorSpan()
            .verifiedAs("referral-program-create")
    }
}
