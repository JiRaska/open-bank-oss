// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MultiAccountPortfolioProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "openbank.treasury.portfolio.safekeeping-accounts" to "PSCO-OWN-0001,PSCO-OWN-0002",
    )
}

@QuarkusTest
@TestProfile(MultiAccountPortfolioProfile::class)
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
class PortfolioMultiAccountIT {
    private fun xml(date: LocalDate, account: String, corrected: Boolean = false): String = requireNotNull(
        javaClass.getResource("/semt002/pension-co-2026-12-31${if (corrected) "-corrected" else ""}.xml"),
    )
        .readText().replace("2026-12-31", date.toString()).replace("PSCO-OWN-0001", account)

    private fun upload(body: String, key: String = UUID.randomUUID().toString()): Response = given()
        .contentType("application/xml").header("Idempotency-Key", key).body(body)
        .post("/api/v1/treasury/portfolio/statements")

    private fun read(date: LocalDate): Response = given().queryParam("date", date.toString())
        .get("/api/v1/treasury/portfolio/period-end")

    @Test
    fun `simultaneous account uploads survive independently and correction preserves the other source`() {
        val date = LocalDate.of(2030, 1, 31)
        val a = xml(date, "PSCO-OWN-0001")
        val b = xml(date, "PSCO-OWN-0002")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val first = pool.submit<Response> {
                start.await()
                upload(a)
            }
            val second = pool.submit<Response> {
                start.await()
                upload(b)
            }
            start.countDown()
            val idA = first.get(30, TimeUnit.SECONDS).then().statusCode(201).extract().path<String>("id")
            val idB = second.get(30, TimeUnit.SECONDS).then().statusCode(201).extract().path<String>("id")
            read(date).then().statusCode(200)
                .body("sourceStatements", hasSize<Any>(2))
                .body("sourceStatements[0].statementUuid", equalTo(idA))
                .body("sourceStatements[1].statementUuid", equalTo(idB))
                .body("positions", hasSize<Any>(4))
                .body("positions[0].quantity", equalTo("20000000"))
                .body("positions[0].valuation", equalTo("19750000.0000"))
            val idCorrection = upload(xml(date, "PSCO-OWN-0001", corrected = true))
                .then().statusCode(201).body("version", equalTo(2)).extract().path<String>("id")
            read(date).then().statusCode(200)
                .body("sourceStatements[0].statementUuid", equalTo(idCorrection))
                .body("sourceStatements[0].version", equalTo(2))
                .body("sourceStatements[1].statementUuid", equalTo(idB))
                .body("sourceStatements[1].version", equalTo(1))
                .body("positions[0].valuation", equalTo("19755000.0000"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `missing account is 409 but a complete empty account and replay are valid sources`() {
        val date = LocalDate.of(2030, 2, 28)
        val a = xml(date, "PSCO-OWN-0001")
        val idA = upload(a).then().statusCode(201).extract().path<String>("id")
        read(date).then().statusCode(409)
        val emptyB = Regex("<BalForAcct>.*?</BalForAcct>", RegexOption.DOT_MATCHES_ALL)
            .replace(xml(date, "PSCO-OWN-0002"), "")
        upload(emptyB).then().statusCode(201)
        upload(a, "multi-account-alias").then().statusCode(201).body("id", equalTo(idA))
        upload(xml(date, "PSCO-OWN-0002"), "multi-account-alias").then().statusCode(409)
        read(date).then().statusCode(200).body("sourceStatements", hasSize<Any>(2)).body("positions", hasSize<Any>(4))
    }

    @Test
    fun `empty account does not contradict the nonempty account currency`() {
        val date = LocalDate.of(2030, 5, 31)
        upload(xml(date, "PSCO-OWN-0001").replace("Ccy=\"CZK\"", "Ccy=\"EUR\""))
            .then().statusCode(201)
        val emptyB = Regex("<BalForAcct>.*?</BalForAcct>", RegexOption.DOT_MATCHES_ALL)
            .replace(xml(date, "PSCO-OWN-0002"), "")
        upload(emptyB).then().statusCode(201)
        read(date).then().statusCode(200).body("currency", equalTo("EUR"))
            .body("positions", hasSize<Any>(4)).body("sourceStatements", hasSize<Any>(2))
        val allEmptyDate = LocalDate.of(2030, 8, 31)
        listOf("PSCO-OWN-0001", "PSCO-OWN-0002").forEach { account ->
            val empty = Regex("<BalForAcct>.*?</BalForAcct>", RegexOption.DOT_MATCHES_ALL)
                .replace(xml(allEmptyDate, account), "")
            upload(empty).then().statusCode(201)
        }
        read(allEmptyDate).then().statusCode(200).body("currency", equalTo("CZK"))
            .body("positions", hasSize<Any>(0)).body("sourceStatements", hasSize<Any>(2))
    }

    @Test
    fun `incompatible metadata for the same ISIN is refused rather than summed`() {
        val date = LocalDate.of(2030, 3, 31)
        upload(xml(date, "PSCO-OWN-0001")).then().statusCode(201)
        upload(xml(date, "PSCO-OWN-0002").replace("DBFTFB", "ESVUFR")).then().statusCode(201)
        read(date).then().statusCode(409)
    }

    @Test
    fun `two competing corrections cannot supersede both versions and other account stays current`() {
        val date = LocalDate.of(2030, 4, 30)
        val idA = upload(xml(date, "PSCO-OWN-0001")).then().statusCode(201).extract().path<String>("id")
        val idB = upload(xml(date, "PSCO-OWN-0002")).then().statusCode(201).extract().path<String>("id")
        val config = ConfigProvider.getConfig()
        val connection = DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
        val pool = Executors.newFixedThreadPool(2)
        connection.use { c ->
            c.autoCommit = false
            lockPrior(c, idA)
            try {
                val correction = xml(date, "PSCO-OWN-0001", corrected = true)
                val one = pool.submit<Response> { upload(correction) }
                val two = pool.submit<Response> { upload(correction.replace("9880000.00", "9881000.00")) }
                awaitCorrectionWaiters(c)
                c.commit()
                assertThat(listOf(one.get(30, TimeUnit.SECONDS).statusCode, two.get(30, TimeUnit.SECONDS).statusCode))
                    .containsExactlyInAnyOrder(201, 409)
                read(date).then().statusCode(200)
                    .body("sourceStatements[0].version", equalTo(2))
                    .body("sourceStatements[1].statementUuid", equalTo(idB))
                    .body("sourceStatements[1].version", equalTo(1))
            } finally {
                c.rollback()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `version trail is chronological across interleaved account sequences`() {
        val date = LocalDate.of(2030, 6, 30)
        val first = upload(xml(date, "PSCO-OWN-0001")).then().statusCode(201).extract().path<String>("id")
        val corrected = upload(xml(date, "PSCO-OWN-0001", corrected = true))
            .then().statusCode(201).extract().path<String>("id")
        val secondAccount = upload(xml(date, "PSCO-OWN-0002"))
            .then().statusCode(201).extract().path<String>("id")
        given().queryParam("date", date.toString()).get("/api/v1/treasury/portfolio/statements")
            .then().statusCode(200).body("id", contains(first, corrected, secondAccount))
            .body("version", contains(1, 2, 1))
    }

    @Test
    fun `rollback guard refuses multi-account history without changing its evidence`() {
        val date = LocalDate.of(2030, 7, 31)
        upload(xml(date, "PSCO-OWN-0001")).then().statusCode(201)
        upload(xml(date, "PSCO-OWN-0002")).then().statusCode(201)
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            assertThatThrownBy {
                connection.createStatement().use { statement ->
                    statement.execute(
                        "DO ${'$'}${'$'} BEGIN IF EXISTS (SELECT 1 FROM portfolio_statements " +
                            "GROUP BY entity, statement_date HAVING count(DISTINCT safekeeping_account) > 1) " +
                            "THEN RAISE EXCEPTION 'Rollback unavailable: retain multi-account history'; " +
                            "END IF; END ${'$'}${'$'};",
                    )
                }
            }.isInstanceOf(SQLException::class.java).hasMessageContaining("Rollback unavailable")
        }
        read(date).then().statusCode(200).body("sourceStatements", hasSize<Any>(2))
    }

    @Test
    fun `unlike quantity bases are refused without losing either account`() {
        val date = LocalDate.of(2030, 9, 30)
        upload(xml(date, "PSCO-OWN-0001")).then().statusCode(201)
        val units = xml(date, "PSCO-OWN-0002").replace("<FaceAmt>", "<Unit>").replace("</FaceAmt>", "</Unit>")
        upload(units).then().statusCode(201)
        read(date).then().statusCode(409)
        given().queryParam("date", date.toString()).get("/api/v1/treasury/portfolio/statements")
            .then().statusCode(200).body("safekeepingAccount", contains("PSCO-OWN-0001", "PSCO-OWN-0002"))
    }

    @Test
    fun `unknown legacy quantity basis requires a correction and is never guessed`() {
        val date = LocalDate.of(2030, 10, 31)
        val body = xml(date, "PSCO-OWN-0001")
        val id = upload(body).then().statusCode(201).extract().path<String>("id")
        upload(xml(date, "PSCO-OWN-0002")).then().statusCode(201)
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement("update portfolio_holdings set quantity_basis = null where statement_uuid = ?")
                .use { statement ->
                    statement.setObject(1, UUID.fromString(id))
                    assertThat(statement.executeUpdate()).isEqualTo(4)
                }
        }
        read(date).then().statusCode(409)
        upload(body).then().statusCode(201).body("id", equalTo(id))
        read(date).then().statusCode(409)
        upload(xml(date, "PSCO-OWN-0001", corrected = true)).then().statusCode(201).body("version", equalTo(2))
        read(date).then().statusCode(200)
            .body("positions[0].quantityBasis", equalTo("FACE_AMOUNT"))
            .body("positions[2].quantityBasis", equalTo("UNIT"))
            .body("sourceStatements", hasSize<Any>(2))
    }

    private fun lockPrior(connection: Connection, id: String) {
        connection.prepareStatement(
            "select statement_uuid from portfolio_statements where statement_uuid = ? for update",
        ).use { statement ->
            statement.setObject(1, UUID.fromString(id))
            statement.executeQuery().use { rows -> check(rows.next()) }
        }
    }

    private fun awaitCorrectionWaiters(connection: Connection) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var waiters = 0
        while (waiters < 2 && System.nanoTime() < deadline) {
            connection.createStatement().use { statement ->
                statement.execute("select pg_stat_clear_snapshot()")
                statement.executeQuery(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' " +
                        "and query ilike '%update%portfolio_statements%'",
                ).use { rows ->
                    rows.next()
                    waiters = rows.getInt(1)
                }
            }
            if (waiters < 2) Thread.sleep(25)
        }
        assertThat(waiters).isEqualTo(2)
    }
}
