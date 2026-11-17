// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-Postgres proof that the year-end result allocation is ONE transaction (ADR-0096 D3): the
 * closing journal and its attribution commit together or not at all, the reserved key prefix is
 * refused on the generic posting API, and allocation and attestation serialise on the year-close row.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class YearEndResultAllocationConcurrencyIT {

    @Inject lateinit var dataSource: AgroalDataSource

    @Test
    @Order(1)
    @TestSecurity(user = "con-maker", roles = ["ROLE_OPERATOR"])
    fun `the generic posting API refuses the reserved year-end-result prefix`() {
        val body = journal(ATOMIC_YEAR, "12", "year-end-result:$ATOMIC_YEAR:forged:0")
        given().contentType("application/json").body(body).`when`().post("/api/v1/journals").then().statusCode(400)
        assertThat(
            count(
                "SELECT count(*) FROM ledger_idempotency WHERE idempotency_key LIKE 'year-end-result:$ATOMIC_YEAR:%'",
            ),
        ).isZero()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "con-maker", roles = ["ROLE_OPERATOR"])
    fun `a failing attribution write rolls the closing journal back with it`() {
        post(ATOMIC_YEAR, "500")
        draft(ATOMIC_YEAR)
        // Sabotage: occupy the attribution row the allocation will write for its first key, without
        // any journal, so the INSERT inside the allocation's transaction fails AFTER the journal post.
        val key = "year-end-result:$ATOMIC_YEAR:${digestOf()}:0"
        exec(
            "INSERT INTO ledger_year_close_allocation " +
                "(idempotency_key, fiscal_year, allocated_by, journal_id, allocated_at) " +
                "VALUES ('$key', $ATOMIC_YEAR, 'saboteur', '${UUID.randomUUID()}', now())",
        )
        val code = given().contentType("application/json").`when`()
            .post("/api/v1/ledger/close/$ATOMIC_YEAR/result-allocation").then().extract().statusCode()
        assertThat(code).isGreaterThanOrEqualTo(400)
        // The journal did NOT survive its failed attribution: one transaction, both or neither.
        assertThat(
            count(
                "SELECT count(*) FROM ledger_idempotency WHERE idempotency_key LIKE 'year-end-result:$ATOMIC_YEAR:%'",
            ),
        )
            .isZero()
        exec("DELETE FROM ledger_year_close_allocation WHERE allocated_by = 'saboteur'")
        // Control: with the sabotage removed the same call succeeds and writes both.
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$ATOMIC_YEAR/result-allocation")
            .then().statusCode(200)
        assertThat(count("SELECT count(*) FROM ledger_idempotency WHERE idempotency_key = '$key'")).isEqualTo(1)
        assertThat(
            count("SELECT count(*) FROM ledger_year_close_allocation WHERE idempotency_key = '$key'"),
        ).isEqualTo(1)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "con-maker", roles = ["ROLE_OPERATOR"])
    fun `concurrent allocations book exactly one attributed closing journal`() {
        post(RACE_YEAR, "900")
        draft(RACE_YEAR)
        val codes = race(2) {
            given().contentType("application/json").`when`()
                .post("/api/v1/ledger/close/$RACE_YEAR/result-allocation").then().extract().statusCode()
        }
        assertThat(codes).contains(200)
        assertThat(
            count("SELECT count(*) FROM ledger_idempotency WHERE idempotency_key LIKE 'year-end-result:$RACE_YEAR:%'"),
        )
            .isEqualTo(1)
        assertThat(
            count("SELECT count(*) FROM ledger_year_close_allocation WHERE fiscal_year = $RACE_YEAR"),
        ).isEqualTo(1)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "con-drafter", roles = ["ROLE_OPERATOR"])
    fun `another operator refreshes the raced year and books a late posting`() {
        post(RACE_YEAR, "40")
        draft(RACE_YEAR)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "con-third", roles = ["ROLE_OPERATOR"])
    fun `allocate and attest interleaved serialise - never both, never an attest by the allocator`() {
        val codes = race(2) { i ->
            val path = if (i == 0) "result-allocation" else "attest"
            given().contentType("application/json").`when`()
                .post("/api/v1/ledger/close/$RACE_YEAR/$path").then().extract().statusCode()
        }
        assertThat(codes).describedAs("allocation then attest by the same principal can never both succeed")
            .isNotEqualTo(listOf(200, 200))
        val attested =
            count("SELECT count(*) FROM ledger_year_close WHERE fiscal_year = $RACE_YEAR AND status = 'ATTESTED'")
        val allocatedByAttestor = count(
            "SELECT count(*) FROM ledger_year_close_allocation " +
                "WHERE fiscal_year = $RACE_YEAR AND allocated_by = 'con-third'",
        )
        assertThat(attested * allocatedByAttestor).describedAs("attested by an allocator").isZero()
        assertThat(
            count(
                "SELECT count(*) FROM ledger_idempotency i " +
                    "WHERE i.idempotency_key LIKE 'year-end-result:$RACE_YEAR:%' " +
                    "AND NOT EXISTS (SELECT 1 FROM ledger_year_close_allocation a WHERE a.journal_id = i.journal_id)",
            ),
        ).describedAs("unattributed closing journals").isZero()
    }

    @Test
    @Order(6)
    @TestSecurity(user = "con-maker", roles = ["ROLE_OPERATOR"])
    fun `a maker drafts an unallocated year`() {
        post(ATTEST_YEAR, "10")
        draft(ATTEST_YEAR)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "con-fourth", roles = ["ROLE_OPERATOR"])
    fun `two attests race - exactly one flip and one attested event`() {
        val codes = race(2) {
            given().contentType("application/json").`when`()
                .post("/api/v1/ledger/close/$ATTEST_YEAR/attest").then().extract().statusCode()
        }
        assertThat(codes.sorted()).containsExactly(200, 409)
        assertThat(
            count(
                "SELECT count(*) FROM ledger_outbox WHERE event_type = 'YearCloseAttested' " +
                    "AND payload LIKE '%\"fiscalYear\":$ATTEST_YEAR%'",
            ),
        ).isEqualTo(1)
    }

    private fun digestOf(): String {
        val legs = given().accept(
            "application/json",
        ).`when`().get("/api/v1/ledger/close/$ATOMIC_YEAR/result-allocation")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("legs")
        val plan = com.openbank.ledger.domain.model.YearEndResultAllocationPlan(
            ATOMIC_YEAR,
            legs.map {
                com.openbank.ledger.domain.model.ResultAllocationLeg(
                    it.getValue("glAccountCode").toString(),
                    com.openbank.ledger.domain.model.JournalSide.valueOf(it.getValue("side").toString()),
                    java.math.BigDecimal(it.getValue("amount").toString()),
                )
            },
            java.math.BigDecimal.ZERO,
        )
        return plan.digest()
    }

    private fun race(n: Int, call: (Int) -> Int): List<Int> {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val futures = (0 until n).map { i ->
            pool.submit<Int> {
                start.await()
                call(i)
            }
        }
        start.countDown()
        val codes = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        return codes
    }

    private fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.executeUpdate(sql) } }

    private fun count(sql: String): Long = dataSource.connection.use { c ->
        c.createStatement().use {
            it.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun draft(year: Int) {
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$year").then().statusCode(200)
    }

    private fun post(year: Int, amount: String) {
        given().contentType("application/json").body(journal(year, amount, UUID.randomUUID().toString()))
            .`when`().post("/api/v1/journals").then().statusCode(201)
    }

    private fun journal(year: Int, amount: String, key: String): String {
        val date = "$year-06-15"
        return """
            {"idempotencyKey":"$key","transactionId":"${UUID.randomUUID()}",
             "entryDate":"$date","valueDate":"$date","description":"Allocation concurrency IT",
             "createdBy":"00000000-0000-0000-0000-000000000705","lines":[
             {"glAccountId":"$CASH","side":"DEBIT","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"},
             {"glAccountId":"$INCOME","side":"CREDIT","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"}]}
        """.trimIndent()
    }

    private companion object {
        const val ATOMIC_YEAR = 2008
        const val RACE_YEAR = 2007
        const val ATTEST_YEAR = 2006
        const val CASH = "a0000000-0000-0000-0000-000000000001"
        const val INCOME = "a0000000-0000-0000-0000-000000004100"
    }
}
