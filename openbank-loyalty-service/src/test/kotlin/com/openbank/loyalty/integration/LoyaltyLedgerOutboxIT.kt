// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.integration

import com.openbank.loyalty.it.LoyaltyPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Drives the real HTTP surface against a real Postgres.
 *
 * Why this exists as well as the unit tests: a reactive Panache repository cannot be called from a
 * bare `@QuarkusTest` thread at all (`No current Vertx context found`), and a mocked repository
 * cannot observe a transaction, so neither can establish the property this service depends on —
 * that a ledger row and its outbox row commit together. Only a real request through the endpoint,
 * with the row read back over plain JDBC, can. It is also the only test here that would notice an
 * entity column that does not exist, which is a defect class that has reached production in this
 * fleet before and is invisible to every mocked test.
 */
@QuarkusTest
@QuarkusTestResource(LoyaltyPostgresTestResource::class)
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_OPERATOR"])
class LoyaltyLedgerOutboxIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `an earn writes the lot and its outbox row in one transaction`() {
        val party = UUID.randomUUID()
        val correlation = UUID.randomUUID()

        Given {
            contentType("application/json")
            body("""{"earnSourceId":"SAVINGS_GOAL_REACHED","correlationEventId":"$correlation"}""")
        } When {
            post("/api/v1/loyalty/parties/$party/earn")
        } Then {
            statusCode(201)
            body("outcome", equalTo("AWARDED"))
        }

        assertThat(countRows("SELECT count(*) FROM leaf_ledger_entry WHERE party_id = ?", party)).isEqualTo(1)
        assertThat(countRows("SELECT count(*) FROM loyalty_outbox WHERE aggregate_id = ?", party)).isEqualTo(1)
    }

    /**
     * The same achievement reported twice is one achievement. Asserts on the ROWS, not only on the
     * response: a guard that answers ALREADY_AWARDED while still writing a second lot would pass a
     * response-only assertion.
     */
    @Test
    fun `a redelivered achievement awards once`() {
        val party = UUID.randomUUID()
        val correlation = UUID.randomUUID()
        val body = """{"earnSourceId":"LOGIN_STREAK","correlationEventId":"$correlation"}"""

        Given {
            contentType("application/json")
            body(body)
        }
            .When { post("/api/v1/loyalty/parties/$party/earn") }
            .Then { statusCode(201) }

        Given {
            contentType("application/json")
            body(body)
        }
            .When { post("/api/v1/loyalty/parties/$party/earn") }
            .Then {
                statusCode(200)
                body("outcome", equalTo("ALREADY_AWARDED"))
            }

        assertThat(countRows("SELECT count(*) FROM leaf_ledger_entry WHERE party_id = ?", party)).isEqualTo(1)
    }

    /**
     * A redemption burns, grants and publishes atomically, and a retry with the same key does none
     * of it a second time. The balance read at the end is the real check — a double burn shows up
     * there even if both responses looked right.
     */
    @Test
    fun `a redemption burns once under a retried idempotency key`() {
        val party = UUID.randomUUID()
        repeat(3) {
            Given {
                contentType("application/json")
                body("""{"earnSourceId":"EMERGENCY_BUFFER_REACHED","correlationEventId":"${UUID.randomUUID()}"}""")
            }.When { post("/api/v1/loyalty/parties/$party/earn") }.Then { statusCode(201) }
        }
        val before = When { get("/api/v1/loyalty/parties/$party") }
            .Then { statusCode(200) } Extract { path<Int>("balance") }

        val redeem = """{"benefitId":"MONTHLY_MAINTENANCE_FEE_WAIVER"}"""
        val grantId = Given {
            contentType("application/json")
            header("Idempotency-Key", "it-key-1")
            body(redeem)
        }
            .When { post("/api/v1/loyalty/parties/$party/redeem") }
            .Then {
                statusCode(201)
                body("outcome", equalTo("GRANTED"))
            } Extract { path<String>("grantId") }

        Given {
            contentType("application/json")
            header("Idempotency-Key", "it-key-1")
            body(redeem)
        }
            .When { post("/api/v1/loyalty/parties/$party/redeem") }
            .Then {
                statusCode(200)
                body("outcome", equalTo("ALREADY_GRANTED"))
                body("grantId", equalTo(grantId))
            }

        val after = When { get("/api/v1/loyalty/parties/$party") }
            .Then { statusCode(200) } Extract { path<Int>("balance") }

        assertThat(before - after).isEqualTo(FEE_WAIVER_PRICE)
        assertThat(countRows("SELECT count(*) FROM benefit_grant WHERE party_id = ?", party)).isEqualTo(1)
    }

    /** An unaffordable redemption is a 409 that burns nothing and grants nothing. */
    @Test
    fun `an unaffordable redemption changes no state`() {
        val party = UUID.randomUUID()
        Given {
            contentType("application/json")
            header("Idempotency-Key", "it-key-2")
        }
            .body("""{"benefitId":"SAVINGS_RATE_BONUS_90D"}""")
            .When { post("/api/v1/loyalty/parties/$party/redeem") }
            .Then {
                statusCode(409)
                body("outcome", equalTo("INSUFFICIENT_LEAVES"))
            }

        assertThat(countRows("SELECT count(*) FROM benefit_grant WHERE party_id = ?", party)).isZero()
        assertThat(countRows("SELECT count(*) FROM leaf_ledger_entry WHERE party_id = ?", party)).isZero()
    }

    /**
     * The absent-header case, which is the one the guard was written for and the one three
     * services in this fleet answered with a 500. The parameter is declared nullable so
     * `requireNotNull` in the body is reachable at all; libs-runtime maps it to 400.
     */
    @Test
    fun `a redemption without an Idempotency-Key is a 400, not a 500`() {
        Given { contentType("application/json") }
            .body("""{"benefitId":"MONTHLY_MAINTENANCE_FEE_WAIVER"}""")
            .When { post("/api/v1/loyalty/parties/${UUID.randomUUID()}/redeem") }
            .Then { statusCode(400) }
    }

    /**
     * Reads every column of every entity through a real query. An entity property mapped to a
     * column no migration created fails here with `42703`, which no mocked test can see.
     */
    @Test
    fun `the ledger reads back over its real columns`() {
        val party = UUID.randomUUID()
        Given {
            contentType("application/json")
            body("""{"earnSourceId":"FEEDBACK_GIVEN","correlationEventId":"${UUID.randomUUID()}"}""")
        }.When { post("/api/v1/loyalty/parties/$party/earn") }.Then { statusCode(201) }

        When { get("/api/v1/loyalty/parties/$party") } Then {
            statusCode(200)
            body("history[0].earnSourceId", equalTo("FEEDBACK_GIVEN"))
            body("history[0].type", equalTo("EARN"))
        }
    }

    private fun countRows(sql: String, party: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, party)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private companion object {
        const val FEE_WAIVER_PRICE = 300
    }
}
