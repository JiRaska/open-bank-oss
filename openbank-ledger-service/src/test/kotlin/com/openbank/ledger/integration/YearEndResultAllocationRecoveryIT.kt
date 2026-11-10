// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
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
 * Real-Postgres proof of the allocation's crash recovery and of the allocation/attestation
 * serialisation (ADR-0096 D3). A crash is simulated by writing exactly what the service would have
 * left behind at each step: a PENDING attribution with no journal (crash before the post), and a
 * PENDING attribution whose key DID book a journal (crash before completion).
 */
@QuarkusTest
@QuarkusTestResource(value = PostgresTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(YearEndResultAllocationRecoveryIT.IsolatedPersistenceProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class YearEndResultAllocationRecoveryIT {

    class IsolatedPersistenceProfile : QuarkusTestProfile

    @Inject lateinit var dataSource: AgroalDataSource

    @Test
    @Order(1)
    @TestSecurity(user = "rec-maker", roles = ["ROLE_OPERATOR"])
    fun `crash before the post - PENDING without a journal blocks attestation and is recovered as ABANDONED`() {
        post(CRASH_YEAR, INCOME, "500", UUID.randomUUID().toString())
        draft(CRASH_YEAR)
        pending(CRASH_YEAR, "year-end-result:$CRASH_YEAR:crashed:0", "rec-maker")
        assertThat(status("year-end-result:$CRASH_YEAR:crashed:0")).isEqualTo("PENDING")

        val result = allocate(CRASH_YEAR)
        assertThat(status("year-end-result:$CRASH_YEAR:crashed:0")).isEqualTo("ABANDONED")
        assertThat(result.getBoolean("posted")).isTrue()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "rec-refresher", roles = ["ROLE_OPERATOR"])
    fun `a second operator refreshes the draft, then a crash leaves a PENDING row`() {
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$CRASH_YEAR")
            .then().statusCode(200)
        pending(CRASH_YEAR, "year-end-result:$CRASH_YEAR:crashed2:0", "rec-maker")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "rec-checker", roles = ["ROLE_OPERATOR"])
    fun `a PENDING row blocks attestation`() {
        val body = given().contentType("application/json").`when`().post("/api/v1/ledger/close/$CRASH_YEAR/attest")
            .then().statusCode(409).extract().asString()
        assertThat(body).contains("PENDING")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "rec-maker", roles = ["ROLE_OPERATOR"])
    fun `crash after the post - PENDING whose key booked a journal is recovered as POSTED with that journal`() {
        val key = "year-end-result:$CRASH_YEAR:crashed2:0"
        val journalId = post(CRASH_YEAR, INCOME, "7", key)
        allocate(CRASH_YEAR)
        assertThat(status(key)).isEqualTo("POSTED")
        assertThat(journalOf(key)).isEqualTo(journalId)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "rec-maker", roles = ["ROLE_OPERATOR"])
    fun `an unattributed closing journal blocks allocation, even with nothing left to allocate`() {
        post(ORPHAN_YEAR, INCOME, "3", "year-end-result:$ORPHAN_YEAR:orphan:0")
        draft(ORPHAN_YEAR)
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$ORPHAN_YEAR/result-allocation")
            .then().statusCode(409)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "rec-maker", roles = ["ROLE_OPERATOR"])
    fun `concurrent allocations book exactly one closing journal`() {
        post(RACE_YEAR, INCOME, "900", UUID.randomUUID().toString())
        draft(RACE_YEAR)
        val codes = race {
            given().contentType("application/json").`when`()
                .post("/api/v1/ledger/close/$RACE_YEAR/result-allocation").then().extract().statusCode()
        }
        assertThat(codes).contains(200)
        assertThat(
            count("SELECT count(*) FROM ledger_idempotency WHERE idempotency_key LIKE 'year-end-result:$RACE_YEAR:%'"),
        )
            .isEqualTo(1)
        // Whatever lost the race left no PENDING behind for attestation to trip on.
        assertThat(
            count(
                "SELECT count(*) FROM ledger_year_close_allocation " +
                    "WHERE fiscal_year = $RACE_YEAR AND status = 'PENDING'",
            ),
        )
            .isZero()
    }

    @Test
    @Order(7)
    @TestSecurity(user = "rec-checker", roles = ["ROLE_OPERATOR"])
    fun `a second operator refreshes the raced year`() {
        // Neither the drafter (rec-checker) nor the allocator (rec-maker) is the racing attestor.
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$RACE_YEAR").then().statusCode(200)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "rec-third", roles = ["ROLE_OPERATOR"])
    fun `two attests race - one wins, one conflicts, one attested event`() {
        val codes = race {
            given().contentType("application/json").`when`()
                .post("/api/v1/ledger/close/$RACE_YEAR/attest").then().extract().statusCode()
        }
        assertThat(codes.sorted()).containsExactly(200, 409)
        assertThat(
            count(
                "SELECT count(*) FROM ledger_outbox WHERE event_type = 'YearCloseAttested' " +
                    "AND payload LIKE '%\"fiscalYear\":$RACE_YEAR%'",
            ),
        )
            .isEqualTo(1)
    }

    private fun race(call: () -> Int): List<Int> {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val futures = (1..2).map {
            pool.submit<Int> {
                start.await()
                call()
            }
        }
        start.countDown()
        val codes = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        return codes
    }

    private fun pending(year: Int, key: String, by: String) = dataSource.connection.use { c ->
        c.prepareStatement(
            "INSERT INTO ledger_year_close_allocation " +
                "(idempotency_key, fiscal_year, status, allocated_by, requested_at) " +
                "VALUES (?, ?, 'PENDING', ?, now())",
        ).use {
            it.setString(1, key)
            it.setInt(2, year)
            it.setString(3, by)
            it.executeUpdate()
        }
    }

    private fun status(key: String): String? = column("status", key)

    private fun journalOf(key: String): String? = column("journal_id", key)

    private fun column(name: String, key: String): String? = dataSource.connection.use { c ->
        val ps = c.prepareStatement("SELECT $name FROM ledger_year_close_allocation WHERE idempotency_key = ?")
        ps.setString(1, key)
        val rs = ps.executeQuery()
        (if (rs.next()) rs.getString(1) else null).also { ps.close() }
    }

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

    private fun allocate(year: Int) = given().contentType("application/json").`when`()
        .post("/api/v1/ledger/close/$year/result-allocation").then().statusCode(200).extract().jsonPath()

    private fun post(year: Int, account: String, amount: String, key: String): String {
        val date = "$year-06-15"
        val journal = """
            {"idempotencyKey":"$key","transactionId":"${UUID.randomUUID()}",
             "entryDate":"$date","valueDate":"$date","description":"Allocation recovery IT",
             "createdBy":"00000000-0000-0000-0000-000000000705","lines":[
             {"glAccountId":"$CASH","side":"DEBIT","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"},
             {"glAccountId":"$account","side":"CREDIT","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"}]}
        """.trimIndent()
        return given().contentType("application/json").body(journal).`when`().post("/api/v1/journals")
            .then().statusCode(201).extract().jsonPath().getString("id")
    }

    private companion object {
        const val CRASH_YEAR = 2018
        const val ORPHAN_YEAR = 2017
        const val RACE_YEAR = 2016
        const val CASH = "a0000000-0000-0000-0000-000000000001"
        const val INCOME = "a0000000-0000-0000-0000-000000004100"
    }
}
