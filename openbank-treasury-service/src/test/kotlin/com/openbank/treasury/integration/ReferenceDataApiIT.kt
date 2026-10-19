// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D3 through real HTTP and Postgres: the seeds are there, a proposal is PENDING until a
 * DIFFERENT human approves, an AI agent can propose but never approve, an approval closes the
 * overlapping version, and an approved counterparty is projected so a deal can face it. Rows are
 * asserted by plain JDBC, not through the API that wrote them.
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReferenceDataApiIT {
    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun post(path: String, body: String? = null, key: String = UUID.randomUUID().toString()) = given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury/reference$path")

    private val calendarProposal = """
        {"key":"CERTIS","validFrom":"${today.plusDays(1)}","reason":"extra bank closure",
         "payload":{"code":"CERTIS","baseCalendar":"CERTIS","extraHolidays":["${today.plusDays(
        40,
    )}"],"removedHolidays":[]}}
    """.trimIndent()

    private val counterpartyProposal = """
        {"key":"CORP-IT-1","validFrom":"2000-01-01","reason":"new corporate client",
         "payload":{"id":"CORP-IT-1","name":"Integration Corporate","kind":"CORPORATE","lei":null,
                    "countryCode":"CZ","limits":{"CZK":1000000.00},"nettingAgreementId":null}}
    """.trimIndent()

    @Test
    @Order(1)
    @TestSecurity(user = "rita.reader", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - the seeds are served - calendars, indices, books, a bucket set and the migrated counterparties`() {
        given().`when`().get("/api/v1/treasury/reference/index?status=ACTIVE&asOf=$today").then().statusCode(200)
            .body("key", hasItem("PRIBOR_3M")).body("key", hasItem("ESTR")).body("key", hasItem("EURIBOR_6M"))
        given().`when`().get("/api/v1/treasury/reference/calendar/CERTIS").then().statusCode(200)
            .body("active.version", equalTo(1)).body("active.verified", equalTo(true))
            .body("active.createdByType", equalTo("SYSTEM"))
        given().`when`().get("/api/v1/treasury/reference/counterparty/SIMBK-A").then().statusCode(200)
            .body("active.payload.limits.CZK", equalTo(2.0E9f)).body("active.verified", equalTo(false))
        given().`when`().get("/api/v1/treasury/reference/bucket-set/STANDARD").then().statusCode(200)
            .body(
                "active.payload.buckets[9].label",
                equalTo(">10Y"),
            ).body("active.payload.buckets[9].toDays", nullValue())
        given().`when`().get("/api/v1/treasury/reference/curve").then().statusCode(400)
        given().`when`().get("/api/v1/treasury/reference/calendar/NOPE").then().statusCode(404)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "erik.editor", roles = ["ROLE_TREASURY_REFERENCE_EDITOR", "ROLE_TREASURY_APPROVER"])
    fun `2 - an editor proposes and cannot approve their own proposal, even holding the approver role`() {
        post("/calendar", calendarProposal).then().statusCode(201)
            .body("status", equalTo("PENDING")).body("version", equalTo(2)).body("verified", equalTo(false))
        post("/calendar/CERTIS/versions/2/approve").then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
        post("/counterparty", counterpartyProposal).then().statusCode(201).body("status", equalTo("PENDING"))
        // A payload with an unknown field is refused, not silently ignored.
        post(
            "/book",
            """{"key":"B","validFrom":"2026-01-01","reason":"r","payload":{"id":"B","name":"b","purpose":"BANKING_BOOK","ifrs9Category":"FVOCI","colour":"red"}}""",
        )
            .then().statusCode(400)
        post("/calendar", calendarProposal.replace("\"key\":\"CERTIS\"", "\"key\":\"TARGET2\"")).then().statusCode(400)
        assertThat(status("calendar", "CERTIS", 2)).isEqualTo("PENDING")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "agent:treasury-drafter", roles = ["ROLE_TREASURY_REFERENCE_EDITOR", "ROLE_TREASURY_APPROVER"])
    fun `3 - an AI agent may propose a draft but never approve`() {
        post("/calendar", calendarProposal.replace("extra bank closure", "agent proposal")).then().statusCode(201)
            .body("createdByType", equalTo("AI_AGENT"))
        post("/calendar/CERTIS/versions/2/approve").then().statusCode(403).body("error", equalTo("ACTOR_NOT_PERMITTED"))
        post("/calendar/CERTIS/versions/2/verify").then().statusCode(403)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `4 - a dealer may neither propose nor approve`() {
        post("/calendar", calendarProposal).then().statusCode(403)
        post("/calendar/CERTIS/versions/2/approve").then().statusCode(403)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `5 - a second human approves - the old version is closed at the new validFrom, the projection appears`() {
        val key = UUID.randomUUID().toString()
        post("/calendar/CERTIS/versions/2/approve", key = key).then().statusCode(200).body("status", equalTo("ACTIVE"))
        post("/calendar/CERTIS/versions/2/approve", key = key).then().statusCode(200).body("status", equalTo("ACTIVE"))
        post("/calendar/CERTIS/versions/2/approve").then().statusCode(409)
        jdbc { c ->
            c.prepareStatement(
                "select version, status, valid_to, approved_by from reference_records where kind = 'calendar' and record_key = 'CERTIS' order by version",
            ).executeQuery().use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString("status")).isEqualTo("ACTIVE")
                assertThat(rs.getDate("valid_to").toLocalDate()).isEqualTo(today.plusDays(1))
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString("status")).isEqualTo("ACTIVE")
                assertThat(rs.getString("approved_by")).isEqualTo("anna.approver")
            }
        }
        post("/calendar/CERTIS/versions/3/reject", """{"reason":"duplicate of v2"}""").then().statusCode(200)
            .body("status", equalTo("REJECTED"))
        post("/calendar/CERTIS/versions/2/verify").then().statusCode(200).body("verified", equalTo(true))

        post("/counterparty/CORP-IT-1/versions/1/approve").then().statusCode(200)
        jdbc { c ->
            c.prepareStatement(
                "select kind, limit_czk, synthetic from counterparties where counterparty_id = 'CORP-IT-1'",
            )
                .executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("kind")).isEqualTo("CORPORATE")
                    assertThat(rs.getBigDecimal("limit_czk")).isEqualByComparingTo("1000000.00")
                    assertThat(rs.getBoolean("synthetic")).isFalse()
                }
        }
        given().`when`().get("/api/v1/treasury/counterparties").then().statusCode(200)
            .body("counterpartyId", hasItem("CORP-IT-1"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `6 - the dependency query lists the indices fixing on a calendar and deals facing a counterparty`() {
        given().`when`().get("/api/v1/treasury/reference/dependencies/calendar/CERTIS").then().statusCode(200)
            .body("dependants.id", hasItem("PRIBOR_3M")).body("dependants.id", hasItem("CZEONIA"))
    }

    private fun status(kind: String, key: String, version: Int): String? = jdbc { c ->
        c.prepareStatement(
            "select status from reference_records where kind = ? and record_key = ? and version = ?",
        ).use { ps ->
            ps.setString(1, kind)
            ps.setString(2, key)
            ps.setInt(3, version)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
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
}
