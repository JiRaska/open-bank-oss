// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import com.openbank.treasury.nostro.NostroFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of a camt.053 nostro statement upload (money path, ADR-0315).
 *
 * The statement row is the reconciliation's input of record: every break the sweep later opens is
 * computed against `nostro_statements` / `nostro_statement_entries`, so a write that escaped the
 * request context — an un-propagated reactive hop between the resource and the repository — would
 * still answer 201 and still store the row, and no response-level assertion in this module can
 * distinguish it from a write that happened inside the request. Only the spans can: an OTLP
 * collector reading the service's own OpenTelemetry output must see the INSERT in the same trace
 * as the POST that carried it.
 *
 * `requiresSameTrace` is the load-bearing assertion. The spans are the service's own: Quarkus'
 * OpenTelemetry instrumentation produces them for the real HTTP request and the real
 * Hibernate-reactive write; the recorder only reads what an OTLP collector would receive.
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class NostroStatementUploadTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "nostro-trace-contract", roles = ["ROLE_TREASURY_APPROVER"])
    fun `a statement upload keeps the request and its nostro_statements INSERT in one trace`() {
        given()
            .contentType("application/xml")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(uniqueStatement())
            .`when`()
            .post("/api/v1/treasury/nostro/statements")
            .then()
            .statusCode(201)

        awaitServerSpan()
        exporter.contract()
            .requiresSpan("POST /api/v1/treasury/nostro/statements")
            .requiresAttribute("POST /api/v1/treasury/nostro/statements", "http.response.status_code")
            .requiresSpan("INSERT nostro_statements")
            .requiresAttribute("INSERT nostro_statements", "db.sql.table")
            .requiresSameTrace("POST /api/v1/treasury/nostro/statements", "INSERT nostro_statements")
            .hasNoErrorSpan()
            .verifiedAs("nostro-statement-upload-evidence")
    }

    /** The fixture's statement id is unique per upload; a replayed id would be a 409, not an INSERT. */
    private fun uniqueStatement(): ByteArray {
        val xml = String(NostroFixtures.xml())
        return xml.replace("SYNTH-STMT-20260925-CZK", "SYNTH-TRACE-${UUID.randomUUID()}".take(40)).toByteArray()
    }

    private fun awaitServerSpan() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            try {
                exporter.contract().requiresSpan("POST /api/v1/treasury/nostro/statements")
                return
            } catch (failure: AssertionError) {
                if (System.nanoTime() >= deadline) throw failure
            }
            Thread.sleep(25)
        }
    }
}
