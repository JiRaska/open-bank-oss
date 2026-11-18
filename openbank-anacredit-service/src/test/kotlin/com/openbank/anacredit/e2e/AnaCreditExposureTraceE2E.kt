// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.e2e

import com.openbank.anacredit.it.PostgresRedpandaTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Trace evidence for the exposure registration: the INSERT into `credit_exposures` must happen
 * inside the registering request's own trace. A row that escaped the request context — written by
 * a follow-up job or a detached session — still answers 201 and passes every HTTP-level assertion,
 * but lands in the regulatory feed without an observable cause. Kept in its own boot so the
 * recorder's contract reads only this one request's spans.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestProfile(AnaCreditE2eTraceProfile::class)
@TestSecurity(user = "anacredit-operator@openbank.test", roles = ["ROLE_OPERATOR"])
class AnaCreditExposureTraceE2E {

    @jakarta.inject.Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `the exposure insert stays inside the registering request trace`() {
        val fresh = "OD-E2E-TRACE-${UUID.randomUUID()}"
        Given {
            contentType("application/json")
            body(
                """
                   |{"instrumentId":"$fresh","debtorId":"LE-E2E-TRACE","debtorType":"LEGAL_ENTITY",
                   |"currency":"EUR","committedAmount":50000,"drawnAmount":12000,
                   |"committedAmountEur":50000,"originationDate":"2025-06-01"}
                """.trimMargin(),
            )
        } When {
            post("/api/v1/anacredit/exposures")
        } Then { statusCode(201) }

        exporter.contract()
            .requiresSpan("POST /api/v1/anacredit/exposures")
            .requiresAttribute("POST /api/v1/anacredit/exposures", "http.response.status_code")
            .requiresAttribute("INSERT credit_exposures", "db.sql.table")
            .requiresSameTrace("POST /api/v1/anacredit/exposures", "INSERT credit_exposures")
            .hasNoErrorSpan()
            .verifiedAs("anacredit-e2e-register-exposure")
    }
}
