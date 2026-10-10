// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Real HTTP, real Postgres (ADR-0337 amendment). What only this test shows: the routes are
 * registered (#3371), V15 creates the tables and columns the entities name, a correction retires
 * the current version and inserts the next in ONE transaction against the partial unique index,
 * and the 409-not-empty-list rule holds on the wire.
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class PortfolioApiIT {

    private fun fixture(name: String, date: LocalDate): ByteArray =
        requireNotNull(javaClass.getResource("/semt002/$name")).readText()
            .replace("2026-12-31", date.toString()).toByteArray()

    /** Each test owns its statement date, so tests do not depend on order or on each other. */
    private fun freshDate(): LocalDate = BASE.plusDays(DAYS.incrementAndGet())

    private fun upload(xml: ByteArray, key: String? = UUID.randomUUID().toString()) = given()
        .contentType("application/xml")
        .apply { if (key != null) header("Idempotency-Key", key) }
        .body(xml)
        .`when`().post("/api/v1/treasury/portfolio/statements")

    private fun periodEnd(date: Any?) = given()
        .apply { if (date != null) queryParam("date", date.toString()) }
        .`when`().get("/api/v1/treasury/portfolio/period-end")

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `upload then read the period-end portfolio - classified, decimals as strings`() {
        val date = freshDate()
        val id: String = upload(fixture("pension-co-2026-12-31.xml", date)).then().statusCode(201)
            .body("entity", equalTo("pension-co"))
            .body("version", equalTo(1))
            .body("positionCount", equalTo(4))
            .body("uploadedBy", equalTo("anna.approver"))
            .extract().path("id")

        periodEnd(date).then().statusCode(200)
            .body("asOf", equalTo(date.toString()))
            .body("currency", equalTo("CZK"))
            .body("statementUuid", equalTo(id))
            .body("positions", hasSize<Any>(4))
            .body("positions.isin", contains("CZ0001005037", "CZ0001006266", "CZ0008008018", "IE00B4L5Y983"))
            .body("positions[0].instrumentClass", equalTo("DEBT_SECURITY"))
            .body("positions[0].quantity", equalTo("10000000"))
            .body("positions[0].valuation", equalTo("9875000.0000"))
            .body("positions[0].valuationCurrency", equalTo("CZK"))
            .body("positions[2].instrumentClass", equalTo("EQUITY"))
            .body("positions[3].instrumentClass", equalTo("FUND_UNIT"))
        assertThat(rows("select count(*) from portfolio_holdings where statement_uuid = '$id'")).isEqualTo(4)
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `NEGATIVE - no statement for the date is 409, never an empty list`() {
        periodEnd(freshDate()).then().statusCode(409)
            .body("error", equalTo("PORTFOLIO_SNAPSHOT_MISSING"))
            .body("positions", nullValue())
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `an empty statement is 200 with no positions - distinct from a missing one`() {
        val date = freshDate()
        val empty = String(fixture("pension-co-2026-12-31.xml", date))
            .replace(Regex("(?s)<BalForAcct>.*</BalForAcct>"), "").toByteArray()
        upload(empty).then().statusCode(201).body("positionCount", equalTo(0))
        periodEnd(date).then().statusCode(200).body("positions", hasSize<Any>(0))
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `NEGATIVE - an unmapped CFI is 400 and stores nothing, so the date stays 409`() {
        val date = freshDate()
        upload(fixture("pension-co-2026-12-31-unmapped-cfi.xml", date)).then().statusCode(400)
            .body("message", containsString("IE00B4L5Y983 (CFI FFICSX)"))
        periodEnd(date).then().statusCode(409)
        assertThat(rows("select count(*) from portfolio_statements where statement_date = '$date'")).isZero()
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `re-ingestion is idempotent and a correction supersedes with the trail kept`() {
        val date = freshDate()
        val xml = fixture("pension-co-2026-12-31.xml", date)
        upload(xml, key = null).then().statusCode(400)
        val key = UUID.randomUUID().toString()
        val first: String = upload(xml, key).then().statusCode(201).extract().path("id")
        assertThat(upload(xml, key).then().statusCode(201).extract().path<String>("id")).isEqualTo(first)
        val alias = UUID.randomUUID().toString()
        assertThat(upload(xml, alias).then().statusCode(201).extract().path<String>("id")).isEqualTo(first)
        upload(fixture("pension-co-2026-12-31-corrected.xml", date), alias).then().statusCode(409)
        assertThat(rows("select count(*) from portfolio_statement_keys where statement_uuid = '$first'"))
            .isEqualTo(2)
        upload(fixture("pension-co-2026-12-31-corrected.xml", date), key).then().statusCode(409)

        val corrected: String = upload(fixture("pension-co-2026-12-31-corrected.xml", date)).then().statusCode(201)
            .body("version", equalTo(2))
            .body("supersedes", equalTo(first))
            .extract().path("id")

        upload(xml, alias).then().statusCode(201).body("id", equalTo(first)).body("version", equalTo(1))
        periodEnd(date).then().statusCode(200)
            .body("statementUuid", equalTo(corrected))
            .body("version", equalTo(2))
            .body("positions[0].valuation", equalTo("9880000.0000"))
        given().queryParam("date", date.toString()).`when`().get("/api/v1/treasury/portfolio/statements")
            .then().statusCode(200)
            .body("version", contains(1, 2))
            .body("[0].supersededBy", equalTo(corrected))
            .body("[1].supersededBy", nullValue())
        assertThat(rows("select count(*) from portfolio_holdings where statement_date = '$date'")).isEqualTo(8)
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `a missing or malformed date is 400`() {
        periodEnd(null).then().statusCode(400)
        periodEnd("31.12.2026").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "tax", roles = ["ROLE_API"])
    fun `the API role reads but cannot upload`() {
        periodEnd(freshDate()).then().statusCode(409)
        upload(fixture("pension-co-2026-12-31.xml", freshDate())).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "teller", roles = ["ROLE_CUSTOMER"])
    fun `an unrelated role is refused at the RBAC layer`() {
        periodEnd(freshDate()).then().statusCode(403)
    }

    private fun rows(sql: String): Int {
        val config = ConfigProvider.getConfig()
        val url = config.getValue("quarkus.datasource.jdbc.url", String::class.java)
        val user = config.getValue("quarkus.datasource.username", String::class.java)
        val password = config.getValue("quarkus.datasource.password", String::class.java)
        return DriverManager.getConnection(url, user, password).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
    }

    private companion object {
        val BASE: LocalDate = LocalDate.of(2090, 1, 1)
        val DAYS = AtomicLong((System.nanoTime() % 20_000).let { if (it < 0) -it else it })
    }
}
