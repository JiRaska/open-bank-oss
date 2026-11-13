// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The key of a held payment's case is what a rail later accepts a release or reject decision from,
 * so nobody but the hold intake may hold a case under it. Through real HTTP and a real Postgres:
 *
 *  - the create endpoint refuses the namespace (and the one a displaced case is moved into);
 *  - a case that sits under a hold key WITHOUT having been opened by the intake — stored the way the
 *    create endpoint stored one before the namespace was reserved — cannot be decided, so no
 *    status-changed event carrying the hold key can come from it.
 *
 * The control in each test is an ordinary key or an ordinary case: without it a profile that
 * answered 400 or 409 to everything would pass.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class AmlCaseReservedKeyIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "u-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-operator"),
            Claim(key = "preferred_username", value = "u-operator"),
        ],
    )
    fun `the create endpoint refuses the held-payment key namespace and opens no case`() {
        val payment = UUID.randomUUID()
        for (prefix in listOf("payment-hold:", "quarantined:")) {
            val key = "${prefix}domestic:$payment"
            Given {
                contentType("application/json")
                header("Idempotency-Key", key)
                body(caseJson(payment))
            } When {
                post("/api/v1/aml/cases")
            } Then {
                statusCode(400)
                body("status", equalTo(400))
                body("code", equalTo("VALIDATION_ERROR"))
                body("message", equalTo("Idempotency-Key prefix '$prefix' is reserved"))
            }
            assertThat(count("select count(*) from aml_cases where idempotency_key = ?", key)).isZero()
        }
        assertThat(count("select count(*) from aml_cases where transaction_id = ?", payment)).isZero()

        // Control: the same caller and body under an ordinary key is accepted.
        Given {
            contentType("application/json")
            header("Idempotency-Key", "ordinary-$payment")
            body(caseJson(payment))
        } When {
            post("/api/v1/aml/cases")
        } Then {
            statusCode(201)
        }
        assertThat(count("select count(*) from aml_cases where transaction_id = ?", payment)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "u-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-operator"),
            Claim(key = "preferred_username", value = "u-operator"),
        ],
    )
    fun `a case under a hold key that the hold intake did not open cannot be decided`() {
        val payment = UUID.randomUUID()
        val planted = plant("payment-hold:domestic:$payment", payment)
        val ordinary = plant("ordinary-$payment", payment)

        Given {
            contentType("application/json")
            body("""{"targetStatus":"CLEARED","decisionReason":"looks fine"}""")
        } When {
            put("/api/v1/aml/cases/$planted/decision")
        } Then {
            statusCode(409)
            body("status", equalTo(409))
            body("code", equalTo("CONFLICT"))
            body(
                "message",
                equalTo("This case holds a held-payment key it was not opened under and cannot be decided"),
            )
        }
        assertThat(statusOf(planted)).isEqualTo("OPEN")
        assertThat(count("select count(*) from aml_outbox where aggregate_id = ?", planted))
            .describedAs("no status-changed event carrying the hold key")
            .isZero()

        // Control: an identically stored case under an ordinary key is decided as before.
        Given {
            contentType("application/json")
            body("""{"targetStatus":"CLEARED","decisionReason":"looks fine"}""")
        } When {
            put("/api/v1/aml/cases/$ordinary/decision")
        } Then {
            statusCode(200)
            body("status", equalTo("CLEARED"))
        }
        assertThat(count("select count(*) from aml_outbox where aggregate_id = ?", ordinary)).isEqualTo(1)
    }

    private fun caseJson(payment: UUID) =
        """{"partyId":"${UUID.randomUUID()}","transactionId":"$payment","customerReference":"REF-1",""" +
            """"screeningType":"TRANSACTION_MONITORING","riskLevel":"LOW","alertCode":"PAYMENT_HELD"}"""

    /** A row as the create endpoint stored it before the namespace was reserved: no provenance. */
    private fun plant(key: String, payment: UUID): UUID {
        val caseId = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO aml_cases
                  (id, case_id, idempotency_key, party_id, transaction_id, customer_reference, screening_type,
                   risk_level, status, alert_code, screened_at, created_at, updated_at, request_hash)
                VALUES (?, ?, ?, ?, ?, 'REF-1', 'TRANSACTION_MONITORING',
                        'LOW', 'OPEN', 'PAYMENT_HELD', ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                val now = Timestamp.from(Instant.now())
                // Far above anything the entity sequence hands out in a test run.
                ps.setLong(1, PLANTED_ID_BASE + (caseId.leastSignificantBits and ID_SPREAD))
                ps.setObject(2, caseId)
                ps.setString(3, key)
                ps.setObject(4, UUID.randomUUID())
                ps.setObject(5, payment)
                ps.setTimestamp(6, now)
                ps.setTimestamp(7, now)
                ps.setTimestamp(8, now)
                ps.setString(9, "a".repeat(HASH_LENGTH))
                ps.executeUpdate()
            }
        }
        return caseId
    }

    private fun statusOf(caseId: UUID): String = dataSource.connection.use { c ->
        c.prepareStatement("select status from aml_cases where case_id = ?").use { ps ->
            ps.setObject(1, caseId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    private fun count(sql: String, arg: Any): Int = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, arg)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private companion object {
        const val HASH_LENGTH = 64
        const val PLANTED_ID_BASE = 9_000_000_000L
        const val ID_SPREAD = 0xFFFFFFL
    }
}
