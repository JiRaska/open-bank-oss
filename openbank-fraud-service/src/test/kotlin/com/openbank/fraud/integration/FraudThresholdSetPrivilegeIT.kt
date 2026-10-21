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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * V10 (least privilege on `fraud_threshold_set`, follow-up to #13457), against a real Postgres where
 * the app connects as the NON-superuser database owner — the CNPG shape. Proves both halves:
 *
 * - the ADR-0347 flow still works end to end under the reduced privileges (propose = INSERT,
 *   four-eyes decide = column-scoped UPDATE, scoring = SELECT + a `fraud_scores` INSERT);
 * - the application role can no longer DELETE, TRUNCATE or rewrite the payload of a version.
 *
 * Without V10 the owner holds every privilege and the refusal tests fail (measured by removing V10).
 */
@QuarkusTest
@TestProfile(FraudThresholdSetPrivilegeIT.OwnProfile::class)
@QuarkusTestResource(PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FraudThresholdSetPrivilegeIT {

    @Test
    @Order(1)
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
    @Order(2)
    @TestSecurity(user = "risk-maker", roles = ["ROLE_RISK"])
    fun `a proposal is still inserted`() {
        proposalId = Given {
            contentType("application/json")
            body(
                """
                {"version": 2, "thresholds": {
                  "large-single-transaction": {"CZK": "1000", "EUR": "20000"},
                  "velocity-h1-amount": {"CZK": "1000000", "EUR": "40000"},
                  "new-payee-high-amount": {"CZK": "250000", "EUR": "10000"}}}
                """.trimIndent(),
            )
        } When { post("/api/v1/fraud/thresholds/proposals") } Then {
            statusCode(201)
            body("state", equalTo("PROPOSED"))
        } Extract { path("id") }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "risk-checker", roles = ["ROLE_COMPLIANCE"])
    fun `the four-eyes decision is still recorded`() {
        Given {
            contentType("application/json")
            body("""{"approve": true, "reason": "privilege IT"}""")
        } When { post("/api/v1/fraud/thresholds/proposals/$proposalId/decide") } Then {
            statusCode(200)
            body("state", equalTo("EXECUTED"))
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `scoring still reads the active set and writes its audit row`() {
        Given {
            contentType("application/json")
            body("""{"amount": "1250.00", "currency": "CZK", "rail": "SEPA_INSTANT"}""")
        } When { post("/api/v1/fraud/score") } Then {
            statusCode(200)
            body("verdict", equalTo("REVIEW"))
        }
        jdbc { conn ->
            conn.createStatement().executeQuery(
                "SELECT threshold_set_version FROM fraud_scores ORDER BY created_at DESC, id DESC LIMIT 1",
            ).use { rs ->
                check(rs.next()) { "no fraud_scores row was written" }
                assertThat(rs.getInt(1)).isEqualTo(2)
            }
        }
    }

    @Test
    @Order(5)
    fun `the app role cannot DELETE a threshold set`() {
        assertRefused("DELETE FROM fraud_threshold_set")
    }

    @Test
    @Order(6)
    fun `the app role cannot TRUNCATE the threshold sets`() {
        assertRefused("TRUNCATE fraud_threshold_set")
    }

    @Test
    @Order(7)
    fun `the app role cannot rewrite an approved payload`() {
        assertRefused("UPDATE fraud_threshold_set SET thresholds_json = '{}' WHERE version = 2")
        assertRefused("UPDATE fraud_threshold_set SET proposed_by = 'someone-else'")
    }

    @Test
    @Order(8)
    fun `nothing was lost`() {
        jdbc { conn ->
            conn.createStatement().executeQuery("SELECT count(*) FROM fraud_threshold_set").use { rs ->
                check(rs.next())
                assertThat(rs.getInt(1)).isEqualTo(2)
            }
        }
    }

    private fun assertRefused(sql: String) {
        jdbc { conn ->
            assertThatThrownBy { conn.createStatement().executeUpdate(sql) }
                .describedAs(sql)
                .isInstanceOf(SQLException::class.java)
                .extracting { (it as SQLException).sqlState }
                .isEqualTo(INSUFFICIENT_PRIVILEGE)
        }
    }

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

        // Shared across the ordered methods: JUnit makes a new instance per method.
        lateinit var proposalId: String
    }
}
