// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.curve.ReferenceCurveSet
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The sandbox reference curves, end to end through the REAL scheduler (#2148/#2187: a direct call
 * would supply the Vert.x context the scheduler does not): a snapshot run is recorded, the tick
 * gives its date a reference set, repeated ticks do not duplicate it, and the run's IRRBB and
 * liquidity-forecast reads then work on that set — the exact path that was empty in the sandbox.
 *
 * Negative check: with the `saveIfAbsent` call in `ReferenceCurveSetSeeder` removed, the first
 * assertion times out — no set ever appears.
 */
@QuarkusTest
@QuarkusTestResource(EodSnapshotSchedulerVertxContextIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(ReferenceCurveSetSchedulerVertxContextIT.FastReferenceCurvesProfile::class)
class ReferenceCurveSetSchedulerVertxContextIT {

    /** Literals only — a profile loads in its own classloader (CLAUDE.md, StandingOrderExecutionSweepIT). */
    class FastReferenceCurvesProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.risk.reference-curves.enabled" to "true",
            "openbank.risk.reference-curves.every" to "2s",
            "openbank.risk.reference-curves.delay" to "1s",
        )
    }

    @Inject
    lateinit var ledger: FakeLedgerPort

    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return ready()
    }

    private fun setsFor(asOf: LocalDate): Int =
        TestDb.count("SELECT count(*) FROM curve_set WHERE as_of = DATE '$asOf'")

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a recorded run gets exactly one reference set, and IRRBB and the forecast work on it`() {
        ledger.inputs = Fixtures.tiedOut()
        val asOf = LocalDate.parse("2026-03-31")
        val runId: String = given().contentType("application/json").body("""{"asOf":"$asOf"}""")
            .`when`().post("/api/v1/risk/snapshots")
            .then().statusCode(201).extract().path("id")

        assertThat(await { setsFor(asOf) > 0 })
            .describedAs("the scheduler must give the run's date a reference curve set")
            .isTrue()
        Thread.sleep(EXTRA_TICKS_MILLIS)
        assertThat(setsFor(asOf)).describedAs("repeated ticks must not duplicate the set").isEqualTo(1)

        val setId = ReferenceCurveSet.idFor(asOf).toString()
        given().`when`().get("/api/v1/risk/curve-sets?asOf=$asOf")
            .then().statusCode(200)
            .body("curveSets", hasSize<Any>(1))
            .body("curveSets[0].id", equalTo(setId))
            .body("curveSets[0].provenance", equalTo("synthetic"))
            .body("curveSets[0].indices", hasSize<Any>(6))
        given().`when`().get("/api/v1/risk/curve-sets?asOf=${asOf.minusDays(1)}")
            .then().statusCode(200).body("curveSets", hasSize<Any>(0))

        given().`when`().get("/api/v1/risk/snapshots/$runId/irrbb?curveSetId=$setId")
            .then().statusCode(200)
            .body("scenarios", hasSize<Any>(6))
            .body("curveSetProvenance", equalTo("synthetic"))
        given().`when`().get("/api/v1/risk/snapshots/$runId/liquidity-forecast?curveSetId=$setId")
            .then().statusCode(200)
            .body("curveSetId", equalTo(setId))
    }

    private companion object {
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val EXTRA_TICKS_MILLIS = 5_000L
    }
}
