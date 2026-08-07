// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The four-eyes decision on a compliance pack must be applied AT MOST ONCE, and the pack must be
 * enforceable only if the decision that won the row was an approval.
 *
 * WHY THIS IS NOT COVERED BY [CompliancePackActivationIT]
 *
 * That test drives the workflow sequentially, so the in-memory guard in
 * `CompliancePackActivationService.decide()` — `require(entity.state == PROPOSED)` — always sees the
 * committed result of the previous leg and is always right. It is right for a reason that does not
 * survive concurrency: the guard reads the row through `findById` (`@WithSession`) and writes it
 * through `save` (`@WithTransaction`), two separate transactions with no lock, no version column and
 * no conditional predicate between them. Two decisions arriving together therefore both read
 * `PROPOSED`, both pass the guard, and both write — a textbook lost update on a money-path
 * segregation-of-duties control.
 *
 * The harm is not "the audit trail names the wrong checker", although it does. `decide()` calls
 * `registry.activate(decided)` on the approve leg regardless of whether that leg's write survived, so
 * a REJECTED row can coexist with a pod that is enforcing the pack the rejection refused. Nothing
 * reconciles the two: `CompliancePackRegistry` has no de-activation path (ADR-0212 D3), so the pod
 * enforces the rejected pack until it restarts.
 *
 * WHAT THIS TEST DOES, AND WHAT IT CANNOT PROMISE
 *
 * It races a real approve against a real reject on the same proposal, over real HTTP, [ROUNDS] times
 * with a fresh proposal each round, and asserts two things per round:
 *
 *  1. exactly one of the two requests is accepted — the other must be refused, as it would be if the
 *     two had arrived a second apart;
 *  2. `GET /compliance-packs/active` lists the pack **iff** the surviving row is APPROVED/EXECUTED.
 *
 * Assertion 2 is the money-path one and does not depend on which decision wins.
 *
 * This is a timing test and is therefore honest about being probabilistic: it can only fail when the
 * race actually interleaves. It does not artificially widen the window — no sleep, no test hook in
 * production code — so a round in which the two requests happen to serialise passes vacuously. That
 * is why it runs [ROUNDS] rounds rather than one: the observed failure rate on unpatched code is
 * recorded in the pull request, and a single green round proves nothing. It cannot ever produce a
 * false RED — an accepted second decision on a PROPOSED row is a defect however the threads landed.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(PostgresRedisTestResource::class)
class CompliancePackConcurrentDecideIT {

    @Inject
    lateinit var dataSource: DataSource

    private val packTemplate: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/compliance-packs/cz-consumer-credit-v1.json")) {
            "the shipped CZ pack must be on the test classpath"
        }.bufferedReader().readText()
    }

    private companion object {
        /** Rounds of the race. Each uses its own pack version so the rounds cannot interfere. */
        const val ROUNDS = 12

        /** Pack versions 900..911 — clear of the real v1 and of [CompliancePackActivationIT]'s 999. */
        const val FIRST_VERSION = 900
        const val BARRIER_TIMEOUT_SECONDS = 30L
    }

    /**
     * Both requests are issued as the same checker. That is not a weakening: the four-eyes rule the
     * domain enforces is checker != maker, and both requests satisfy it, so both reach the
     * read-check-write window this test is about. Using two distinct checkers would exercise the same
     * window, and `@TestSecurity` binds one identity for the whole test method.
     */
    @Test
    @TestSecurity(user = "pack-race-checker", roles = ["ROLE_COMPLIANCE"])
    fun `a concurrent approve and reject cannot both be applied to one proposal`() {
        val pool = Executors.newFixedThreadPool(2)
        // One list, so a run reports EVERY violation it saw rather than only the first class of
        // them. Two separate assertions would hide the enforced-despite-rejection cases behind the
        // double-apply ones, and the second class is the one with money-path consequences.
        val violations = mutableListOf<String>()
        try {
            repeat(ROUNDS) { round ->
                val version = FIRST_VERSION + round
                val proposalId = propose(version)
                val barrier = CyclicBarrier(2)

                val approve = pool.submit(decideTask(barrier, proposalId, approve = true))
                val reject = pool.submit(decideTask(barrier, proposalId, approve = false))
                val statuses = listOf(approve.get(), reject.get())

                if (statuses.count { it == 200 } > 1) {
                    violations += "v$version: both decisions accepted (statuses=$statuses)"
                }

                val state = stateOf(proposalId)
                val isActive = activeVersions().contains(version)
                // The invariant that matters even when the race does not interleave: what the
                // origination guard enforces must agree with the row that records the decision.
                if (state == "REJECTED" && isActive) {
                    violations += "v$version: row is REJECTED yet the pack is enforced"
                }
                if (state in setOf("APPROVED", "EXECUTED") && !isActive) {
                    violations += "v$version: row is $state yet the pack is not enforced"
                }
            }
        } finally {
            pool.shutdownNow()
        }

        assertThat(violations)
            .describedAs("four-eyes decisions that were applied more than once, or a guard that disagrees with the row")
            .isEmpty()
    }

    private fun decideTask(barrier: CyclicBarrier, proposalId: String, approve: Boolean) = Callable {
        barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        RestAssured.given()
            .contentType("application/json")
            .body("""{"approve":$approve,"reason":"race probe"}""")
            .post("/api/v1/lending/compliance-packs/proposals/$proposalId/decide")
            .statusCode
    }

    /**
     * Proposed by a principal that is NOT the checker, so the four-eyes rule cannot be what refuses
     * either racer. Written over plain JDBC because `@TestSecurity` binds one identity per method and
     * the maker leg needs a different one; the columns are exactly what the maker endpoint writes.
     */
    private fun propose(version: Int): String {
        val payload = packTemplate.replace(Regex("\"version\"\\s*:\\s*1"), "\"version\": $version")
        val id = java.util.UUID.randomUUID().toString()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO compliance_pack_activation
                    (id, state, jurisdiction, product_type, pack_version, effective_from, payload,
                     content_hash, proposed_by, proposed_at, created_at, updated_at)
                VALUES (?::uuid, 'PROPOSED', 'CZ', 'CONSUMER_CREDIT', ?, DATE '2026-08-01', ?,
                        ?, 'pack-race-maker', now(), now(), now())
                """.trimIndent(),
            ).use { st ->
                st.setString(1, id)
                st.setInt(2, version)
                st.setString(3, payload)
                st.setString(4, "race-probe-$version")
                st.executeUpdate()
            }
        }
        return id
    }

    private fun stateOf(proposalId: String): String = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT state FROM compliance_pack_activation WHERE id = ?::uuid").use { st ->
            st.setString(1, proposalId)
            st.executeQuery().use { rs ->
                check(rs.next()) { "no compliance_pack_activation row for $proposalId" }
                rs.getString("state")
            }
        }
    }

    private fun activeVersions(): List<Int> = Given {
        contentType("application/json")
    } When {
        get("/api/v1/lending/compliance-packs/active")
    } Then {
        statusCode(200)
    } Extract {
        jsonPath().getList("packVersion", Int::class.java)
    }
}
