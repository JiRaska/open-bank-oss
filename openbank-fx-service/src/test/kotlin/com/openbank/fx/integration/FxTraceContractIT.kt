// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.integration

import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.fx.infrastructure.client.SanctionsScreeningAdapter
import com.openbank.fx.it.PostgresRedisTestResource
import com.openbank.fx.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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
 * aggregate happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still answers
 * 201, is invisible to every assertion this module already had, and lands in a trace of its own.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(TraceContractProfile::class)
class FxTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    /**
     * Screening decides which write path runs: an unreachable sanctions service fails closed into
     * HOLD, which persists no conversion at all — the contract would then be asserting over a
     * request that never wrote anything.
     */
    @BeforeEach
    fun screeningIsClear() {
        val port = mockk<SanctionsScreeningAdapter>()
        coEvery { port.screen(any(), any(), any()) } answers {
            ScreeningResult(
                subject = firstArg(),
                role = ScreeningRole.DEBTOR,
                status = ScreeningMatchStatus.CLEAR,
                score = 0.0,
                matchedEntity = null,
            )
        }
        QuarkusMock.installMockForType(port, SanctionsScreeningAdapter::class.java)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `a settled conversion keeps the request and its fx_conversions INSERT in one trace`() {
        val created = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", "trace-contract-${UUID.randomUUID()}")
            .body(
                """
                {
                  "partyId": "${UUID.randomUUID()}",
                  "accountId": null,
                  "partyName": "Jan Novak",
                  "fromCurrency": "CZK",
                  "toCurrency": "EUR",
                  "fromAmountMinorUnits": 100000
                }
                """.trimIndent(),
            )
            .post("/api/v1/fx/convert")
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/fx/convert: %s", created.body.asString())
            .isEqualTo(201)
        assertThat(created.jsonPath().getString("status"))
            .describedAs("only a SETTLED conversion writes an fx_conversions row")
            .isEqualTo("SETTLED")

        exporter.contract()
            .requiresSpan("POST /api/v1/fx/convert")
            .requiresAttribute("POST /api/v1/fx/convert", "http.response.status_code")
            .requiresAttribute("INSERT fx_conversions", "db.sql.table")
            .requiresSameTrace("POST /api/v1/fx/convert", "INSERT fx_conversions")
            .hasNoErrorSpan()
            .verifiedAs("fx-conversion-settle")
    }
}
