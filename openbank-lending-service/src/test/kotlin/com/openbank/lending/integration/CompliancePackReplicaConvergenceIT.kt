// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * A pack activated by a SIBLING replica must become enforceable here, without a restart (#3467).
 *
 * WHAT THIS TEST IS SIMULATING
 *
 * `CompliancePackRegistry` is per-pod in-memory state. Two writers put packs into it —
 * `CompliancePackActivationService.decide()` on the pod that served the approval, and
 * `CompliancePackBootLoader` at boot — and before this test there was nothing in between. So a pack
 * approved on pod A was enforced by pod A immediately and by pod B only after pod B restarted, with
 * no bound on that window and nothing reporting it. Two requests to the same service would then be
 * judged against different compliance rules depending on which pod answered, which is the four-eyes
 * control diverging rather than a caching nit. It is unreachable today only because
 * `lending-service.yaml` pins `replicas: 1` — a constraint that lives in a gitops value and is
 * invisible to the code that depends on it.
 *
 * The running application here IS pod B: it booted against an empty table and has approved nothing.
 * Pod A's approval is represented by the only artifact a sibling pod can ever see — the committed
 * `compliance_pack_activation` row — written over plain JDBC. Nothing in this test touches pod B's
 * registry directly; the only assertion is what pod B SERVES, through the same endpoint the console
 * and the origination guard read.
 *
 * WHY THE ROW IS INSERTED RATHER THAN APPROVED OVER REST
 *
 * Approving through pod B's own REST endpoint would activate the pack in pod B's registry as a side
 * effect of the request, which is precisely the path that already works and the one that cannot
 * diverge. The defect only exists for a pod that did NOT serve the approval, so the write has to
 * arrive the way it arrives in production: as a row this process never wrote.
 *
 * RED BEFORE GREEN
 *
 * Run against unpatched `main` this test fails by timeout — the pack never appears, because boot is
 * the only thing that ever reads the table. It is the absence of any convergence mechanism that it
 * measures, so it is written entirely against endpoints and a table that already exist.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(CompliancePackReplicaConvergenceIT.FastRefresh::class)
class CompliancePackReplicaConvergenceIT {

    /**
     * Shrinks the refresh cadence so the test spends seconds, not the production interval. The
     * literals are deliberate: a `QuarkusTestProfile` loads in a different classloader from the test
     * class, so a value computed in a companion object would be initialised twice and the scheduler
     * and the assertion would not be looking at the same number.
     */
    class FastRefresh : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "lending.compliance.refresh-interval" to "1s",
        )
    }

    @Inject
    lateinit var dataSource: DataSource

    /**
     * The REAL shipped pack with only its version changed — an invented payload would be rejected by
     * `CompliancePackParser`/`CompliancePackCompiler` and the test would go red for a reason that has
     * nothing to do with replica convergence. Version 998 keeps it clear of the genuinely activated
     * `cz-consumer-credit-v1` and of `CompliancePackActivationIT`'s 999.
     */
    private val packJson: String by lazy {
        val raw = checkNotNull(javaClass.getResourceAsStream("/compliance-packs/cz-consumer-credit-v1.json")) {
            "the shipped CZ pack must be on the test classpath"
        }.bufferedReader().readText()
        raw.replace(Regex("\"version\"\\s*:\\s*1"), "\"version\": $PACK_VERSION")
    }

    private companion object {
        const val PACK_VERSION = 998
        const val CONVERGENCE_BUDGET_MS = 30_000L
        const val POLL_INTERVAL_MS = 250L
    }

    @Test
    @TestSecurity(user = "convergence-it-reader", roles = ["ROLE_COMPLIANCE"])
    fun `a pack approved on a sibling replica becomes active here without a restart`() {
        // Pod B has approved nothing, so it must not be serving this pack yet. Asserting the
        // precondition matters: without it a test that polls for a pack could pass on a registry that
        // already held it for an unrelated reason, and would then prove nothing about convergence.
        assertThat(activeBody())
            .describedAs("this replica has approved nothing, so version $PACK_VERSION cannot be active yet")
            .doesNotContain("\"packVersion\":$PACK_VERSION")

        insertSiblingApproval()

        val deadline = System.currentTimeMillis() + CONVERGENCE_BUDGET_MS
        var body = activeBody()
        while (!body.contains("\"packVersion\":$PACK_VERSION") && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            body = activeBody()
        }

        assertThat(body)
            .describedAs(
                "a pack activated by a sibling replica must converge here within the refresh interval — " +
                    "on unpatched main it never does, because boot is the only reader of the table (#3467)",
            )
            .contains("\"packVersion\":$PACK_VERSION")
    }

    private fun activeBody(): String = Given {
        contentType("application/json")
    } When {
        get("/api/v1/lending/compliance-packs/active")
    } Then {
        statusCode(200)
    } Extract {
        asString()
    }

    /** The committed row a sibling pod's four-eyes approval leaves behind — maker and checker differ. */
    private fun insertSiblingApproval() {
        val now = OffsetDateTime.now()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO compliance_pack_activation
                  (id, state, jurisdiction, product_type, pack_version, effective_from, payload,
                   content_hash, proposed_by, proposed_at, decided_by, decided_at, decision_reason,
                   created_at, updated_at)
                VALUES (?::uuid, 'EXECUTED', 'CZ', 'CONSUMER_CREDIT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { st ->
                var i = 1
                st.setString(i++, UUID.randomUUID().toString())
                st.setInt(i++, PACK_VERSION)
                st.setObject(i++, LocalDate.parse("2026-08-01"))
                st.setString(i++, packJson)
                st.setString(i++, "sha256:replica-convergence-it")
                st.setString(i++, "sibling-pod-maker")
                st.setObject(i++, now)
                st.setString(i++, "sibling-pod-checker")
                st.setObject(i++, now)
                st.setString(i++, "approved on another replica")
                st.setObject(i++, now)
                st.setObject(i, now)
                st.executeUpdate()
            }
        }
    }
}
