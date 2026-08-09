// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * One origination step must be applied AT MOST ONCE, however many operators press "advance" at the
 * same moment (issue #3850).
 *
 * WHY NO EXISTING TEST SEES THIS
 *
 * Every other test of `advance` drives the flow sequentially, so the in-memory decision —
 * `OriginationAdvance.nextState(existing.status, …)` computed from the row `findById` returned — is
 * always taken against the committed result of the previous step and is always right. It is right
 * for a reason that does not survive concurrency: `findById` carries `@WithSession` and `update`
 * carries its own `@WithTransaction`, two separate transactions with no lock, no `@Version` column
 * anywhere in this service, and no predicate on the expected state inside the UPDATE. Two `advance`
 * calls arriving together therefore both read `SUBMITTED`, both compute `KYC_PENDING`, both pass the
 * state machine, and both write — a lost update on the money path's approval gate.
 *
 * WHAT THE HARM IS
 *
 * The surviving `status` column looks correct, which is what makes this hard to see: both writers
 * store the same value. What is duplicated is the *transition* — two `credit.application.transition`
 * evidence records for one step, each naming its own actor, and two workflow signals. On a control
 * whose whole purpose is that a named human moved the application from one state to the next, an
 * audit trail asserting the step happened twice is not cosmetic. The same window on `decide()`
 * (`LendingService.kt:346`) lets two checkers each believe they cast the deciding four-eyes vote,
 * and whichever write lands last owns `decidedBy` / `decisionReason` / `decidedAt`.
 *
 * WHAT THIS TEST DOES, AND WHAT IT CANNOT PROMISE
 *
 * It races two real `POST /applications/{id}/advance` calls over real HTTP against the same
 * application, [ROUNDS] times with a fresh application each round, synchronised on a
 * [CyclicBarrier], and records two violation classes per round:
 *
 *  1. the raced `SUBMITTED -> KYC_PENDING` step recorded more than once;
 *  2. any origination transition recorded twice for that application;
 *  3. the raced step not recorded at all.
 *
 * It deliberately does NOT assert "exactly one request returned 200". Two 200s are legitimate when
 * the second racer's read lands after the first commit: it then reads `KYC_PENDING` and takes the
 * NEXT edge, which is correct serialised behaviour and not a lost update. That was measured — it
 * happens — so a status-code assertion would have been a false red waiting to fire. The invariant
 * that separates the defect from honest interleaving is that no single transition is claimed twice.
 *
 * It is a timing test and is honest about being probabilistic. It does NOT widen the window
 * artificially — no sleep, no test hook in production code — so a round in which the two requests
 * happen to serialise passes vacuously. That is why it runs [ROUNDS] rounds rather than one, and why
 * the observed failure rate against unpatched code is recorded in the pull request. **It cannot
 * produce a false RED** — one origination step recorded twice is a defect however the threads landed
 * — **but it can produce a false GREEN**, on a machine slow or loaded enough that no round
 * interleaves.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(OriginationConcurrentAdvanceIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class OriginationConcurrentAdvanceIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    private companion object {
        /** Rounds of the race, each on its own freshly created application. */
        const val ROUNDS = 12
        const val BARRIER_TIMEOUT_SECONDS = 30L
        const val PROPOSER = "advance-race-proposer"

        /**
         * The step raced. `SUBMITTED -> KYC_PENDING` is the first forward edge and needs no
         * compliance pack, no credit-bureau call and no four-eyes identity, so nothing but the
         * read-check-write window can decide the outcome. `ASSESSMENT` is deliberately avoided:
         * it routes through the decision engine, whose latency would mask the window.
         */
        const val FROM_STATE = "SUBMITTED"
        const val TO_STATE = "KYC_PENDING"
    }

    @Test
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_CREDIT_RISK"])
    fun `two concurrent advances cannot both apply the same origination step`() {
        val pool = Executors.newFixedThreadPool(2)
        // One list, so a run reports every violation class it saw rather than only the first.
        val violations = mutableListOf<String>()
        try {
            repeat(ROUNDS) { round ->
                val id = submitApplication()
                assertThat(statusOf(id)).isEqualTo(FROM_STATE)

                val barrier = CyclicBarrier(2)
                val first = pool.submit(advanceTask(barrier, id))
                val second = pool.submit(advanceTask(barrier, id))
                val statuses = listOf(first.get(), second.get())

                val recorded = recordedTransitions(id)
                val racedStep = recorded.count { it == "$FROM_STATE->$TO_STATE" }
                if (racedStep > 1) {
                    violations += "round $round: $racedStep records of $FROM_STATE -> $TO_STATE (statuses=$statuses)"
                }
                val duplicated = recorded.groupingBy { it }.eachCount().filterValues { it > 1 }
                if (duplicated.isNotEmpty()) {
                    violations += "round $round: transitions claimed more than once: $duplicated"
                }
                if (racedStep == 0) {
                    violations += "round $round: the raced step was never recorded (statuses=$statuses)"
                }
            }
        } finally {
            pool.shutdownNow()
        }

        assertThat(violations)
            .describedAs("origination steps applied more than once by concurrent advance calls")
            .isEmpty()
    }

    private fun advanceTask(barrier: CyclicBarrier, id: String) = Callable {
        barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        RestAssured.given()
            .contentType("application/json")
            .post("/api/v1/lending/applications/$id/advance")
            .statusCode
    }

    /**
     * Created through the real maker endpoint rather than by a hand-written INSERT, so the row the
     * race runs against is exactly the shape the service produces. The id is read back over JDBC
     * because the response body wraps it in the `LoanApplicationId` value type.
     */
    private fun submitApplication(): String {
        val body = """
            {"partyId":"${UUID.randomUUID()}","requestedAmount":{"amount":"10000.00","currency":{"code":"EUR"}},
            "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
        """.trimIndent()
        RestAssured.given()
            .contentType("application/json")
            .body(body)
            .post("/api/v1/lending/applications")
            .then()
            .statusCode(201)
        return queryOne(
            "SELECT id::text FROM loan_application WHERE proposed_by = ? ORDER BY created_at DESC LIMIT 1",
            PROPOSER,
        )
    }

    private fun statusOf(id: String): String =
        queryOne("SELECT status::text FROM loan_application WHERE id = ?::uuid", id)

    /**
     * Every origination transition the application recorded, as `FROM->TO`, read out of the
     * transactional outbox written by `LendingService.transitionEvidence`. A repeated entry means
     * one step was claimed twice — the audit consequence of the lost update, and the thing a
     * duplicated HTTP 200 only hints at.
     */
    private fun recordedTransitions(id: String): List<String> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT payload FROM lending_outbox
             WHERE aggregate_id = ?::uuid AND event_type = 'credit.application.transition'
            """.trimIndent(),
        ).use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> rs.readTransitions() }
        }
    }

    private fun java.sql.ResultSet.readTransitions(): List<String> {
        val out = mutableListOf<String>()
        while (next()) {
            val payload = getString(1)
            out += "${payload.field("fromState")}->${payload.field("toState")}"
        }
        return out
    }

    private fun String.field(name: String): String =
        checkNotNull(Regex("\"$name\":\"([^\"]*)\"").find(this)) { "no $name in $this" }.groupValues[1]

    private fun queryOne(sql: String, param: String): String = dataSource.connection.use { conn ->
        conn.prepareStatement(sql).use { st ->
            st.setString(1, param)
            st.executeQuery().use { rs ->
                check(rs.next()) { "no row for [$sql] with [$param]" }
                rs.getString(1)
            }
        }
    }
}
