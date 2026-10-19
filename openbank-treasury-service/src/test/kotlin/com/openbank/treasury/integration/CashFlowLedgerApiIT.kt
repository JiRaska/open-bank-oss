// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D2/D4 over real HTTP and Postgres. Submission stores the cash-flow ledger and its
 * `treasury.cashflow.generated` event in the SAME transaction as the deal (one `xmin`); the rows
 * are read back by plain JDBC; an override is four-eyes (the proposer and an AI agent are refused)
 * and keeps the original beside it; a leg-described IRS stores PROJECTED floating rows.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CashFlowLedgerApiIT {

    class NoDispatch : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("treasury-events-out") +
                mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun post(path: String, body: String? = null, key: String = UUID.randomUUID().toString()) = given()
        .contentType("application/json").header("Idempotency-Key", key)
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    @Test
    @Order(1)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    @Suppress("LongMethod")
    fun `1 - submitting a placement stores its ledger and the generated event with the deal, in one transaction`() {
        dealId = post(
            "/deals",
            """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK","principal":100000.00,"rate":4.25,
               "valueDate":"$today","maturityDate":"${today.plusDays(30)}","bookId":"BANKING-BOOK",
               "note":"ledger IT","counterpartyReference":"CP-REF-77","channel":"VOICE"}""",
        ).then().statusCode(201).body("kind", equalTo("ACTUAL")).body("bookId", equalTo("BANKING-BOOK"))
            .body(
                "legs",
                hasSize<Any>(1),
            ).body("legs[0].dayCount", equalTo("ACT/360")).body("scheduleVersion", equalTo(0))
            .body("channel", equalTo("VOICE"))
            .extract().path("dealId")
        // A deal naming a book that does not exist is refused with the missing reference listed.
        post(
            "/deals",
            """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK","principal":1.00,"rate":1,
               "valueDate":"$today","maturityDate":"${today.plusDays(3)}","bookId":"NO-SUCH-BOOK"}""",
        ).then().statusCode(422).body("error", equalTo("REFERENCE_NOT_EFFECTIVE"))
            .body("missingReferences", hasItem("book:NO-SUCH-BOOK"))
        // Preview generates without storing.
        post("/deals/$dealId/schedule-preview").then().statusCode(200).body("scheduleVersion", equalTo(1))
            .body("flows", hasSize<Any>(3))
        assertThat(count("select count(*) from deal_cash_flows where deal_id = ?", dealId)).isZero()

        post("/deals/$dealId/submit").then().statusCode(200).body("scheduleVersion", equalTo(1))
            .body("scheduleHash", org.hamcrest.Matchers.notNullValue())

        jdbc { c ->
            c.prepareStatement(
                "select kind, direction, payment_date, amount, state, rate_source, manual from deal_cash_flows " +
                    "where deal_id = ? and schedule_version = 1 order by payment_date, kind",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(dealId))
                val rows = ps.executeQuery().use { rs ->
                    generateSequence {
                        if (rs.next()) {
                            listOf(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getDate(3).toString(),
                                rs.getBigDecimal(4),
                                rs.getString(5),
                            )
                        } else {
                            null
                        }
                    }.toList()
                }
                assertThat(rows).hasSize(3)
                assertThat(rows.map { it[0] }).containsExactlyInAnyOrder("NOTIONAL_EXCHANGE", "INTEREST", "PRINCIPAL")
                // 100 000 x 4.25 % x 30 / 360 = 354.166... -> 354.17, the deal's own ACT/360 figure.
                assertThat(rows.single { it[0] == "INTEREST" }[3] as BigDecimal).isEqualByComparingTo("354.17")
                assertThat(rows).allMatch { it[4] == "FIXED" }
            }
        }
        // xmin: the deal row (as last updated by the submission), the PENDING_APPROVAL timeline row,
        // the cash-flow rows and the generated event are one transaction.
        val xmins = jdbc { c ->
            c.prepareStatement(
                """
                select d.xmin::text, t.xmin::text, (select min(f.xmin::text) from deal_cash_flows f where f.deal_id = d.deal_id),
                       (select max(f.xmin::text) from deal_cash_flows f where f.deal_id = d.deal_id), o.xmin::text
                from deals d
                join deal_transitions t on t.deal_id = d.deal_id and t.to_state = 'PENDING_APPROVAL'
                join treasury_outbox o on o.aggregate_id = d.deal_id and o.event_type = 'treasury.cashflow.generated.v1'
                where d.deal_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.fromString(dealId))
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) (1..5).map { rs.getString(it) } else null }.toList()
                }
            }
        }
        assertThat(xmins).hasSize(1)
        assertThat(xmins.single().toSet()).describedAs("one writing transaction").hasSize(1)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - the ledger is served, and the booked event carries kind, legs and the schedule hash`() {
        val flows: List<Map<String, Any?>> = given().`when`().get(
            "/api/v1/treasury/deals/$dealId/cash-flows",
        ).then().statusCode(200)
            .body("scheduleVersion", equalTo(1)).body("flows", hasSize<Any>(3)).extract().path("flows")
        interestFlow = flows.single { it["kind"] == "INTEREST" }["flowId"] as String
        post("/deals/$dealId/approve").then().statusCode(200).body("state", equalTo("BOOKED"))
        val payload = jdbc { c ->
            c.prepareStatement(
                "select payload from treasury_outbox where aggregate_id = ? and event_type = 'treasury.deal.booked.v1'",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(dealId))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }
        assertThat(
            payload,
        ).contains("\"kind\":\"ACTUAL\"").contains("\"bookId\":\"BANKING-BOOK\"").contains("\"scheduleHash\":\"")
            .contains("\"legs\":[")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "bob.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE", "ROLE_TREASURY_APPROVER"])
    fun `3 - back office proposes an override and cannot approve it, even holding the approver role`() {
        overrideId = post(
            "/deals/$dealId/cash-flows/$interestFlow/overrides",
            """{"paymentDate":"${today.plusDays(31)}","reason":"counterparty pays a day later"}""",
        ).then().statusCode(201).body("status", equalTo("PENDING")).extract().path("overrideId")
        post("/deals/$dealId/cash-flows/$interestFlow/overrides/$overrideId/approve").then().statusCode(422)
            .body("error", equalTo("FOUR_EYES_VIOLATION"))
        // Not yet applied.
        assertThat(count("select count(*) from deal_cash_flows where flow_id = ? and manual", interestFlow)).isZero()
        post("/deals/$dealId/send-confirmation", """{"documentId":"doc-77","channel":"SWIFT"}""").then().statusCode(200)
            .body("history[-1].note", containsString("confirmation sent"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "agent:treasury-assistant", roles = ["ROLE_TREASURY_APPROVER", "ROLE_TREASURY_BACK_OFFICE"])
    fun `4 - an AI agent cannot approve an override`() {
        post("/deals/$dealId/cash-flows/$interestFlow/overrides/$overrideId/approve").then().statusCode(403)
            .body("error", equalTo("ACTOR_NOT_PERMITTED"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `5 - a second human approves - the flow is manual and keeps its original date, the event is atomic`() {
        val key = UUID.randomUUID().toString()
        post("/deals/$dealId/cash-flows/$interestFlow/overrides/$overrideId/approve", key = key).then().statusCode(200)
            .body("status", equalTo("APPROVED")).body("decidedBy", equalTo("anna.approver"))
        post("/deals/$dealId/cash-flows/$interestFlow/overrides/$overrideId/approve", key = key).then().statusCode(200)
        post("/deals/$dealId/cash-flows/$interestFlow/overrides/$overrideId/approve").then().statusCode(409)
        jdbc { c ->
            c.prepareStatement(
                "select f.payment_date, f.original_payment_date, f.manual, f.xmin::text, o.xmin::text, v.xmin::text " +
                    "from deal_cash_flows f join cash_flow_overrides o on o.override_id = f.override_id " +
                    "join treasury_outbox v on v.aggregate_id = f.deal_id " +
                    "and v.event_type = 'treasury.cashflow.overridden.v1' " +
                    "where f.flow_id = ?",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(interestFlow))
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getDate(1).toLocalDate()).isEqualTo(today.plusDays(31))
                    assertThat(rs.getDate(2).toLocalDate()).isEqualTo(today.plusDays(30))
                    assertThat(rs.getBoolean(3)).isTrue()
                    assertThat(
                        setOf(rs.getString(4), rs.getString(5), rs.getString(6)),
                    ).describedAs("one transaction").hasSize(1)
                }
            }
        }
        given().`when`().get("/api/v1/treasury/deals/$dealId/cash-flows").then().statusCode(200)
            .body("flows.find { it.flowId == '$interestFlow' }.manual", equalTo(true))
            .body(
                "flows.find { it.flowId == '$interestFlow' }.original.paymentDate",
                equalTo(today.plusDays(30).toString()),
            )
        given().`when`().get(
            "/api/v1/treasury/deals/$dealId/cash-flows/conflicts",
        ).then().statusCode(200).body("", hasSize<Any>(0))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "rita.reader", roles = ["ROLE_TREASURY_DEALER"])
    fun `6 - blotter filters, count, export, history and the event calendar read the new fields`() {
        given().`when`().get("/api/v1/treasury/deals?bookId=BANKING-BOOK&text=cp-ref-77").then().statusCode(200)
            .header(
                "X-Total-Count",
                "1",
            ).body("[0].dealId", equalTo(dealId)) // bob sent the confirmation last
            .body("[0].lastChangedBy", equalTo("bob.backoffice"))
        given().`when`().get(
            "/api/v1/treasury/deals?bookId=TRADING-BOOK",
        ).then().statusCode(200).header("X-Total-Count", "0")
        val csv = given().`when`().get(
            "/api/v1/treasury/deals/export?format=csv&scope=selection&ids=$dealId&layout=REGULATORY",
        )
            .then().statusCode(200).extract().asString()
        assertThat(csv.lines().first()).contains("\"uti\"").contains("\"lcrCategory\"")
        assertThat(csv.lines().filter { it.isNotBlank() }).hasSize(2)
        given().`when`().get("/api/v1/treasury/deals/$dealId/history").then().statusCode(200)
            .body("versions[0].version", equalTo(1)).body("versions[0].scheduleVersion", equalTo(1))
        given().`when`().get(
            "/api/v1/treasury/calendar?from=$today&to=${today.plusDays(40)}&bookId=BANKING-BOOK",
        ).then().statusCode(200)
            .body("findAll { it.dealId == '$dealId' }.type", hasItem("INTEREST_PAYMENT"))
            .body("findAll { it.dealId == '$dealId' }.type", hasItem("MATURITY"))
        given().`when`().get("/api/v1/treasury/calendar?from=$today&to=${today.minusDays(1)}").then().statusCode(400)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "erik.editor", roles = ["ROLE_TREASURY_REFERENCE_EDITOR"])
    fun `7 - the IRS mandate is proposed as a product-type record`() {
        post(
            "/reference/product-type",
            """{"key":"IRS","validFrom":"$today","reason":"open IRS dealing",
               "payload":{"code":"IRS","permitted":true,"maxPrincipal":{"CZK":500000000.00},"maxTenorDays":3660}}""",
        ).then().statusCode(201)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `8 - widening a product mandate needs the senior approver`() {
        post(
            "/reference/product-type/IRS/versions/1/approve",
        ).then().statusCode(403).body("error", equalTo("SENIOR_APPROVAL_REQUIRED"))
    }

    @Test
    @Order(9)
    @TestSecurity(user = "sam.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `9 - the senior activates it`() {
        post("/reference/product-type/IRS/versions/1/approve").then().statusCode(200).body("status", equalTo("ACTIVE"))
    }

    @Test
    @Order(10)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `10 - an IRS submits and stores FIXED coupons and PROJECTED floating rows with fixing dates`() {
        val start = today.plusDays(10)
        val end = start.plusYears(1)
        val legs = """
            [{"payReceive":"PAY","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360","rate":{"type":"FIXED","rate":4.0}},
             {"payReceive":"RECEIVE","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360",
              "rate":{"type":"FLOATING","index":"PRIBOR_3M","spread":0.10,"resetFrequency":"QUARTERLY"}}]
        """.trimIndent()
        val irs: String = post(
            "/deals",
            """{"product":"IRS","counterpartyId":"SIMBK-A","bookId":"TRADING-BOOK","calendars":["CERTIS"],
               "businessDayConvention":"MODIFIED_FOLLOWING","legs":$legs}""",
        ).then().statusCode(201).body("legs", hasSize<Any>(2)).body("legs[1].rate.index", equalTo("PRIBOR_3M"))
            .extract().path("dealId")
        post("/deals/$irs/submit").then().statusCode(200).body("scheduleVersion", equalTo(1))
        // A flat principal that disagrees with the legs would let the limit checks measure a
        // different deal from the one the schedule books: refused.
        post(
            "/deals",
            """{"product":"IRS","counterpartyId":"SIMBK-A","bookId":"TRADING-BOOK","principal":1.00,"legs":$legs}""",
        ).then().statusCode(400).body("message", containsString("principal"))
        assertThat(
            count(
                "select count(*) from deal_cash_flows where deal_id = ? and state = 'FIXED' and kind = 'INTEREST'",
                irs,
            ),
        ).isEqualTo(4)
        assertThat(
            count(
                "select count(*) from deal_cash_flows where deal_id = ? and state = 'PROJECTED' and amount is null and fixing_date is not null",
                irs,
            ),
        ).isEqualTo(4)
        given().`when`().get("/api/v1/treasury/reference/dependencies/index/PRIBOR_3M").then().statusCode(200)
            .body("dependants.id", hasItem(irs))
        // A HYPOTHETICAL deal is priced only.
        val hypo: String = post(
            "/deals",
            """{"product":"IRS","counterpartyId":"SIMBK-A","bookId":"TRADING-BOOK","kind":"HYPOTHETICAL","legs":$legs}""",
        ).then().statusCode(201).extract().path("dealId")
        post(
            "/deals/$hypo/schedule-preview",
        ).then().statusCode(200).body("flows.findAll { it.amount == null }", hasSize<Any>(4))
            .body("flows[0].amount", org.hamcrest.Matchers.anything())
        post("/deals/$hypo/submit").then().statusCode(409)
        given().`when`().get(
            "/api/v1/treasury/deals/$hypo/cash-flows",
        ).then().statusCode(200).body("scheduleVersion", equalTo(0))
            .body("scheduleHash", nullValue())
    }

    private fun count(sql: String, id: String): Int = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
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

    private companion object {
        lateinit var dealId: String
        lateinit var interestFlow: String
        lateinit var overrideId: String
    }
}
