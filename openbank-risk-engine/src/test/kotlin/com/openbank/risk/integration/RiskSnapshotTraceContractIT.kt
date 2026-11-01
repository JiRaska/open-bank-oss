// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import com.openbank.risk.domain.Fixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The observable distributed shape of a risk snapshot run.
 *
 * The spans are the service's own: Quarkus' OpenTelemetry instrumentation produces them for the
 * real HTTP request and the real reactive Postgres write. The recorder only reads what an OTLP
 * collector would receive, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one. A snapshot whose `snapshot_run` INSERT escaped the
 * request context (an un-propagated reactive hop) still answers 201 and still stores the row, so no
 * other test here can see it, but the regulatory run would land in a trace of its own.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class RiskSnapshotTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `a tied-out snapshot keeps the request and its snapshot_run INSERT in one trace`() {
        ledger.inputs = Fixtures.tiedOut()

        val created = given()
            .contentType("application/json")
            .body("""{"asOf":"2028-06-30"}""")
            .`when`().post("/api/v1/risk/snapshots")
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/risk/snapshots: %s", created.body.asString())
            .isEqualTo(201)

        exporter.contract()
            .requiresSpan("POST /api/v1/risk/snapshots")
            .requiresAttribute("POST /api/v1/risk/snapshots", "http.response.status_code")
            .requiresSpan("INSERT snapshot_run")
            .requiresSameTrace("POST /api/v1/risk/snapshots", "INSERT snapshot_run")
            .hasNoErrorSpan()
            .verifiedAs("risk-snapshot-run")
    }
}
