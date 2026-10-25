// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import com.openbank.fraud.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * V10 (least privilege on `fraud_threshold_set`, follow-up to #13457), against a real Postgres where
 * the app connects as the NON-superuser database owner — the CNPG shape. Proves both halves:
 *
 * - the ADR-0347 flow still works under the reduced privileges (propose = INSERT, four-eyes decide
 *   = column-scoped UPDATE, scoring = SELECT + a `fraud_scores` INSERT);
 * - the application role can no longer DELETE, TRUNCATE or rewrite the payload of a version.
 *
 * Every test stands alone: none reads another's state, versions are taken relative to the live
 * maximum and row counts are compared before/after inside the same test, so a retried or reordered
 * test cannot turn an unrelated failure into a privilege verdict. The refusal tests seed their own
 * row through the superuser-free app path first, so they never pass merely because the table is
 * empty. Without V10 the four refusal tests fail (measured by removing V10).
 */
@QuarkusTest
@TestProfile(FraudThresholdSetPrivilegeIT.OwnProfile::class)
@QuarkusTestResource(PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
class FraudThresholdSetPrivilegeIT {

    @Test
    fun `the app role is a non-superuser owner, so privileges are actually checked`() {
        jdbc { conn ->
            conn.createStatement().executeQuery(
                "SELECT r.rolsuper, t.tableowner = current_user FROM pg_roles r, pg_tables t " +
                    "WHERE r.rolname = current_user AND t.tablename = 'fraud_threshold_set'",
            ).use { rs ->
                check(rs.next())
                assertThat(rs.getBoolean(1)).describedAs("rolsuper").isFalse()
                assertThat(rs.getBoolean(2)).describedAs("owns fraud_threshold_set").isTrue()
            }
        }
    }

    @Test
    @TestSecurity(user = "risk-maker", roles = ["ROLE_RISK"])
    fun `a proposal is still inserted`() {
        val version = nextVersion()
        Given {
            contentType("application/json")
            body(proposalBody(version))
        } When { post("/api/v1/fraud/thresholds/proposals") } Then {
            statusCode(201)
            body("state", equalTo("PROPOSED"))
            body("version", equalTo(version))
        }
    }

    @Test
    @TestSecurity(user = "risk-checker", roles = ["ROLE_COMPLIANCE"])
    fun `the four-eyes decision is still recorded`() {
        // The proposal is seeded by SQL as another principal (INSERT is granted), so this test does
        // not depend on the propose test having run first.
        val id = seedProposal(proposedBy = "risk-maker-seed")
        Given {
            contentType("application/json")
            body("""{"approve": true, "reason": "privilege IT"}""")
        } When { post("/api/v1/fraud/thresholds/proposals/$id/decide") } Then {
            statusCode(200)
            body("state", equalTo("EXECUTED"))
            body("decidedBy", equalTo("risk-checker"))
        }
    }

    @Test
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `scoring still reads the active set and writes its audit row`() {
        val before = count("fraud_scores")
        Given {
            contentType("application/json")
            body("""{"amount": "1250.00", "currency": "CZK", "rail": "SEPA_INSTANT"}""")
        } When { post("/api/v1/fraud/score") } Then { statusCode(200) } Extract { path<String>("verdict") }
        assertThat(count("fraud_scores")).isEqualTo(before + 1)
        jdbc { conn ->
            conn.createStatement().executeQuery(
                "SELECT threshold_set_version FROM fraud_scores ORDER BY created_at DESC, id DESC LIMIT 1",
            ).use { rs ->
                check(rs.next())
                assertThat(rs.getInt(1)).describedAs("pinned to the active set").isEqualTo(activeVersion())
            }
        }
    }

    @Test
    fun `the app role cannot DELETE a threshold set`() {
        seedProposal(proposedBy = "delete-probe")
        assertRefusedAndNothingLost("DELETE FROM fraud_threshold_set")
    }

    @Test
    fun `the app role cannot TRUNCATE the threshold sets`() {
        seedProposal(proposedBy = "truncate-probe")
        assertRefusedAndNothingLost("TRUNCATE fraud_threshold_set")
    }

    @Test
    fun `the app role cannot rewrite a payload or its author`() {
        val id = seedProposal(proposedBy = "rewrite-probe")
        assertRefusedAndNothingLost("UPDATE fraud_threshold_set SET thresholds_json = '{}' WHERE id = '$id'")
        assertRefusedAndNothingLost("UPDATE fraud_threshold_set SET proposed_by = 'someone-else' WHERE id = '$id'")
    }

    private fun assertRefusedAndNothingLost(sql: String) {
        val before = count("fraud_threshold_set")
        jdbc { conn ->
            // Always rolled back: if the privilege is missing the statement SUCCEEDS, and without
            // the rollback it would wipe the seed and cascade into every other test in the class.
            conn.autoCommit = false
            try {
                assertThatThrownBy { conn.createStatement().executeUpdate(sql) }
                    .describedAs(sql)
                    .isInstanceOf(SQLException::class.java)
                    .extracting { (it as SQLException).sqlState }
                    .isEqualTo(INSUFFICIENT_PRIVILEGE)
            } finally {
                conn.rollback()
            }
        }
        assertThat(count("fraud_threshold_set")).describedAs("rows after: $sql").isEqualTo(before)
    }

    /** A PROPOSED row at the next free version, written as the app role (INSERT is granted). */
    private fun seedProposal(proposedBy: String): String = jdbc { conn ->
        val id = java.util.UUID.randomUUID().toString()
        conn.prepareStatement(
            """
            INSERT INTO fraud_threshold_set (id, version, state, thresholds_json, content_hash,
                proposed_by, proposed_at)
            SELECT ?::uuid, (SELECT max(version) + 1 FROM fraud_threshold_set), 'PROPOSED', thresholds_json,
                content_hash, ?, now()
            FROM fraud_threshold_set WHERE version = 1
            """.trimIndent(),
        ).use {
            it.setString(1, id)
            it.setString(2, proposedBy)
            check(it.executeUpdate() == 1) { "seed proposal not inserted" }
        }
        id
    }

    private fun nextVersion(): Int = jdbc { conn ->
        conn.createStatement().executeQuery("SELECT max(version) + 1 FROM fraud_threshold_set").use { rs ->
            check(rs.next())
            rs.getInt(1)
        }
    }

    private fun activeVersion(): Int = jdbc { conn ->
        conn.createStatement().executeQuery(
            "SELECT version FROM fraud_threshold_set WHERE state = 'EXECUTED' AND active_from <= now() " +
                "ORDER BY version DESC LIMIT 1",
        ).use { rs ->
            check(rs.next()) { "no active threshold set" }
            rs.getInt(1)
        }
    }

    private fun count(table: String): Int = jdbc { conn ->
        conn.createStatement().executeQuery("SELECT count(*) FROM $table").use { rs ->
            check(rs.next())
            rs.getInt(1)
        }
    }

    private fun proposalBody(version: Int) =
        """
        {"version": $version, "thresholds": {
          "large-single-transaction": {"CZK": "1000", "EUR": "20000"},
          "velocity-h1-amount": {"CZK": "1000000", "EUR": "40000"},
          "new-payee-high-amount": {"CZK": "250000", "EUR": "10000"}}}
        """.trimIndent()

    private fun <T> jdbc(block: (Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    /**
     * Forces a full application restart for this class. Sharing the restart that
     * `restrictToAnnotatedClass` alone triggers left a request hitting an already-closed Hibernate
     * reactive pool (`UnknownServiceException: ReactiveConnectionPool`).
     */
    class OwnProfile : QuarkusTestProfile

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
