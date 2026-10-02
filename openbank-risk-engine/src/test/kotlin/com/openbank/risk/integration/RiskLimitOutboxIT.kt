// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.risk.application.port.`in`.LimitUseCase
import com.openbank.risk.application.port.out.LimitEventOutbox
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.limits.LimitDefinition
import com.openbank.risk.domain.limits.LimitEvaluation
import com.openbank.risk.domain.limits.LimitMetric
import com.openbank.risk.domain.limits.LimitStatus
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The limit-event outbox on real Postgres (ADR-0313 D9): a BREACH is written as a PENDING
 * `risk_outbox` row with the payload the AsyncAPI contract describes; re-recording the same run
 * writes nothing; and one evaluation's rows commit together or not at all.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskLimitOutboxIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Inject
    lateinit var limits: LimitUseCase

    @Inject
    lateinit var outbox: LimitEventOutbox

    private val json = ObjectMapper()

    @AfterEach
    fun reset() {
        ledger.inputs = Fixtures.tiedOut()
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    /** Fixtures.tiedOut(): nostro-only assets, so HQLA is 0 and lcr-min BREACHES. */
    private fun breachingRun(asOf: String): UUID = UUID.fromString(
        given().contentType("application/json").body("""{"asOf":"$asOf"}""")
            .`when`().post("/api/v1/risk/snapshots").then().statusCode(201).body("status", equalTo("TIED_OUT"))
            .extract().path("id"),
    )

    private fun rows(runId: UUID) = TestDb.count("SELECT count(*) FROM risk_outbox WHERE aggregate_id = '$runId'")

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a breach is written as one pending outbox row, and recording the run again writes nothing`() {
        val runId = breachingRun("2024-04-01")
        val analysis = onEventLoop { limits.evaluate(runId) }
        assertThat(analysis.evaluations.single { it.definition.id == "lcr-min" }.status).isEqualTo(LimitStatus.BREACH)
        val nonOk = analysis.evaluations.count {
            it.status == LimitStatus.BREACH ||
                it.status == LimitStatus.EARLY_WARNING
        }

        val written = onEventLoop { outbox.recordNonOk(analysis, Instant.parse("2024-04-01T20:30:00Z")) }

        assertThat(written).isEqualTo(nonOk).isEqualTo(1)
        assertThat(
            TestDb.count(
                "SELECT count(*) FROM risk_outbox WHERE aggregate_id = '$runId' AND status = 'PENDING' " +
                    "AND event_type = 'risk.limit.breach.v1' AND attempt_count = 0 AND synthetic = false " +
                    "AND dedup_key = '$runId:lcr-min:openbank-risk-appetite:1'",
            ),
        ).isEqualTo(1)
        val payload = json.readTree(payloadOf(runId))
        assertThat(payload["limitId"].asText()).isEqualTo("lcr-min")
        assertThat(payload["runId"].asText()).isEqualTo(runId.toString())
        assertThat(payload["bound"].asText()).isEqualTo("MIN")
        assertThat(payload["value"].decimalValue()).isEqualByComparingTo("0")
        assertThat(payload["asOf"].asText()).isEqualTo("2024-04-01")
        assertThat(payload["occurredAt"].asText()).isEqualTo("2024-04-01T20:30:00Z") // ISO, never epoch millis
        assertThat(payload["sourceService"].asText()).isEqualTo("risk-engine")

        // The EOD scheduler re-evaluates a replayed run: the natural key makes that a no-op.
        assertThat(onEventLoop { outbox.recordNonOk(analysis, Instant.parse("2024-04-02T20:30:00Z")) }).isEqualTo(0)
        assertThat(rows(runId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `one evaluation's events commit together - a failing row rolls back the ones before it`() {
        val analysis = onEventLoop { limits.evaluate(breachingRun("2024-04-03")) }
        val otherRun = analysis.run.copy(id = UUID.randomUUID())
        val breach = analysis.evaluations.single { it.definition.id == "lcr-min" }
        // A second BREACH whose dedup key cannot fit dedup_key VARCHAR(512): its INSERT fails.
        val oversized = LimitEvaluation(
            LimitDefinition("x".repeat(600), LimitMetric.NSFR, BigDecimal.ONE, BigDecimal.ONE, "c"),
            LimitStatus.BREACH,
            BigDecimal("0.5"),
            "b",
        )
        val doomed = analysis.copy(run = otherRun, evaluations = listOf(breach, oversized))

        assertThatThrownBy { onEventLoop { outbox.recordNonOk(doomed, Instant.parse("2024-04-03T20:30:00Z")) } }
            .hasMessageContaining("value too long")
        assertThat(rows(otherRun.id))
            .describedAs("the lcr-min row inserted first in the same batch must not survive the failed one")
            .isEqualTo(0)

        // Positive control: the same breach alone does commit for that run.
        val alone = analysis.copy(run = otherRun, evaluations = listOf(breach))
        assertThat(onEventLoop { outbox.recordNonOk(alone, Instant.parse("2024-04-03T20:30:00Z")) }).isEqualTo(1)
        assertThat(rows(otherRun.id)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `OK and NOT_EVALUABLE limits write nothing`() {
        ledger.inputs = Fixtures.tiedOut()
        val analysis = onEventLoop { limits.evaluate(breachingRun("2024-04-04")) }
        val quiet = analysis.copy(
            evaluations = analysis.evaluations.filter {
                it.status == LimitStatus.OK ||
                    it.status == LimitStatus.NOT_EVALUABLE
            },
        )
        assertThat(quiet.evaluations).isNotEmpty()
        assertThat(onEventLoop { outbox.recordNonOk(quiet, Instant.parse("2024-04-04T20:30:00Z")) }).isEqualTo(0)
        assertThat(rows(analysis.run.id)).isEqualTo(0)
    }

    private fun payloadOf(runId: UUID): String {
        var out = ""
        TestDb.query("SELECT payload FROM risk_outbox WHERE aggregate_id = '$runId'") { out = it }
        return out
    }
}
