// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank authors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop

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
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of a Verification-of-Payee check (money path, ADR-0171).
 *
 * Every verify must leave a tamper-evident evidence row (ADR-0171 §7), and that row is written
 * best-effort inside the request: a recording failure is logged and swallowed so a logging outage
 * cannot fail the payment flow (fail-open, ADR-0171 §3). That decision makes the trace contract
 * the one place the evidence write is actually verified end to end — nothing else in the module
 * can distinguish "the row was written in the request's trace" from "the row was written late, in
 * a trace of its own, or not at all": the unit tests mock the recorder, and the fail-open handler
 * hides a lost write from the response.
 *
 * `requiresSameTrace` is the load-bearing assertion: a `vop_verification` INSERT that escaped the
 * request context (an un-propagated reactive hop between `VopVerificationService.verify` and
 * `VopVerificationRecordAdapter.record`) still answers 200 and still stores the row, so no
 * response-level test can see it — but the regulator-reading evidence would land in a trace of
 * its own. The spans are the service's own: Quarkus' OpenTelemetry instrumentation produces them
 * for the real HTTP request and the real Hibernate-reactive write; the recorder only reads what an
 * OTLP collector would receive.
 *
 * A non-domestic IBAN keeps the request inside vop-service (NO_DATA / NO_SCHEME_CONNECTIVITY, no
 * outbound call), so the trace under assertion is entirely the service's own.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(VopVerifyTraceContractIT.VopTraceProfile::class)
class VopVerifyTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "vop-trace-contract", roles = ["ROLE_OPERATOR"])
    fun `a verify keeps the request and its vop_verification evidence INSERT in one trace`() {
        Given {
            contentType("application/json")
            body("""{"creditorIban":"DE89370400440532013000","creditorName":"Max Mustermann"}""")
        } When {
            post("/api/v1/vop/verify")
        } Then {
            statusCode(200)
        }

        awaitServerSpan()
        exporter.contract()
            .requiresSpan("POST /api/v1/vop/verify")
            .requiresAttribute("POST /api/v1/vop/verify", "http.response.status_code")
            .requiresAttribute("INSERT vop_verification", "db.sql.table")
            .requiresSameTrace("POST /api/v1/vop/verify", "INSERT vop_verification")
            .hasNoErrorSpan()
            .verifiedAs("vop-verify-evidence")
    }

    private fun awaitServerSpan() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            try {
                exporter.contract().requiresSpan("POST /api/v1/vop/verify")
                return
            } catch (failure: AssertionError) {
                if (System.nanoTime() >= deadline) throw failure
            }
            Thread.sleep(25)
        }
    }

    /**
     * VOP-specific keys on top of the fleet baseline: no OPA sidecar answers in a @QuarkusTest,
     * so authorization runs advisory (same shape as [VopRateLimitHttpIT.SmallLimit]).
     */
    class VopTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }
}
