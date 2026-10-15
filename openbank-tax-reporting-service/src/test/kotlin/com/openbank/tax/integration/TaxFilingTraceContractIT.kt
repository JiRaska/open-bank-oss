// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.tax.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * The observable distributed shape of a period assembly.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the conditional UPDATE that freezes the
 * period's totals into `tax_filing` happened *inside* the assemble request's trace. A filing
 * whose state change escaped the request context (a detached transaction, a retry writing
 * elsewhere) still answers 200 and passes every assertion this module already had, but lands
 * in traces of its own — the statutory return changed state without an observable cause.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_trace_it")],
)
@QuarkusTestResource(TaxFilingTraceContractIT.InMemoryChannels::class)
@TestProfile(TraceContractProfile::class)
class TaxFilingTraceContractIT {

    class InMemoryChannels : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("withholding-remitted-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Inject
    lateinit var dataSource: DataSource

    /** Seeds an OPEN filing for a period that has already ended — assemble refuses open ones. */
    private fun seedOpenFiling() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into tax_filing
                    (id,period_year,period_month,status,currency,version)
                    values (?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setInt(2, 2026)
                statement.setInt(3, 1)
                statement.setString(4, "OPEN")
                statement.setString(5, "CZK")
                statement.setLong(6, 0L)
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `assembling a filing keeps the request and its filing UPDATE in one trace`() {
        seedOpenFiling()

        Given {
            contentType("application/json")
        } When {
            post("/api/v1/tax/filings/2026-01/assemble")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/tax/filings/{period}/assemble")
            .requiresAttribute("POST /api/v1/tax/filings/{period}/assemble", "http.response.status_code")
            .requiresAttribute("UPDATE tax_filing", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/tax/filings/{period}/assemble",
                "UPDATE tax_filing",
            )
            .hasNoErrorSpan()
            .verifiedAs("tax-filing-assemble")
    }
}
