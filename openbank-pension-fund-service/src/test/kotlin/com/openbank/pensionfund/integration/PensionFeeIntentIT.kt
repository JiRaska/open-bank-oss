// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.pensionfund.FixedFundTerms
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Publication and accounting intent are one database commit; remote delivery stays disabled. */
@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "pension_fee_intent_it")],
)
@TestProfile(PensionFeeIntentIT.DisabledDelivery::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PensionFeeIntentIT {
    class DisabledDelivery : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "false",
            "openbank.pension-fund.accounting.delivery-enabled" to "false",
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = "fee-maker", roles = ["ROLE_OPERATOR"])
    fun `maker creates the fund NAVs and queued orders without an accounting intent`() {
        val first = createSource("CZ0008474053")
        fund = first.first
        nav = first.second
        val second = createSource("CZ0008474061")
        rollbackFund = second.first
        rollbackNav = second.second
        assertThat(scalar("SELECT count(*) FROM pension_fund_accounting_outbox")).isEqualTo("0")
        assertThat(scalar("SELECT count(*) FROM unit_transactions")).isEqualTo("0")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "fee-checker", roles = ["ROLE_OPERATOR"])
    fun `checker publication persists the fee source while disabled delivery remains explicitly incomplete`() {
        approve(nav).statusCode(200)
            .body("nav.status", equalTo("PUBLISHED")).body("settledOrders", equalTo(1))
        val mapper = jacksonObjectMapper()
        val payload = mapper.readTree(
            scalar(
                "SELECT payload FROM pension_fund_accounting_outbox WHERE event_id = '$nav'",
            ),
        )
        assertThat(payload.path("navId").asText()).isEqualTo(nav)
        assertThat(payload.path("fundId").asText()).isEqualTo(fund)
        assertThat(BigDecimal(payload.path("amount").asText())).isEqualByComparingTo("1.00")
        assertThat(payload.path("currency").asText()).isEqualTo("CZK")
        assertThat(scalar("SELECT status FROM pension_fund_accounting_outbox WHERE event_id = '$nav'"))
            .isEqualTo("PENDING")
        assertThat(scalar("SELECT count(*) FROM pension_fund_fee_receipts")).isEqualTo("0")
        assertThat(scalar("SELECT event_type FROM pension_fund_accounting_outbox WHERE event_id = '$nav'"))
            .isEqualTo("PensionManagementFeeAccrued.v1")
        given().get("/api/v2/funds/$fund/accounting-days/$day").then().statusCode(200)
            .body("complete", equalTo(false)).body("journalsPosted", equalTo(0))
            .body("blockers", hasItem("ACCOUNTING_DELIVERY_INCOMPLETE"))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "fee-checker", roles = ["ROLE_OPERATOR"])
    fun `an outbox insertion failure rolls back NAV publication and order settlement together`() {
        sql(
            "CREATE FUNCTION reject_test_fee_intent() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN RAISE EXCEPTION ''injected fee intent failure''; END'; " +
                "CREATE TRIGGER reject_test_fee_intent BEFORE INSERT ON pension_fund_accounting_outbox " +
                "FOR EACH ROW EXECUTE FUNCTION reject_test_fee_intent()",
        )
        try {
            approve(rollbackNav).statusCode(500)
            assertThat(scalar("SELECT status FROM fund_navs WHERE id = '$rollbackNav'"))
                .isEqualTo("CALCULATED")
            assertThat(scalar("SELECT status FROM unit_orders WHERE fund_id = '$rollbackFund'"))
                .isEqualTo("PENDING")
            assertThat(scalar("SELECT count(*) FROM unit_transactions WHERE fund_id = '$rollbackFund'"))
                .isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM pension_fund_accounting_outbox WHERE event_id = '$rollbackNav'"))
                .isEqualTo("0")
        } finally {
            sql(
                "DROP TRIGGER reject_test_fee_intent ON pension_fund_accounting_outbox; " +
                    "DROP FUNCTION reject_test_fee_intent()",
            )
        }
        approve(rollbackNav).statusCode(200)
            .body("nav.status", equalTo("PUBLISHED")).body("settledOrders", equalTo(1))
        assertThat(scalar("SELECT count(*) FROM pension_fund_accounting_outbox WHERE event_id = '$rollbackNav'"))
            .isEqualTo("1")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "fee-checker", roles = ["ROLE_OPERATOR"])
    fun `only receipts for the immutable own book complete its source evidence`() {
        sql("INSERT INTO pension_fund_fee_destinations (fund_id, company_book_id) VALUES ('$fund', '$company')")
        receipt(nav, "PENSION_FUND", UUID.randomUUID().toString())
        evidence().body("complete", equalTo(false)).body("blockers", hasItem("ACCOUNTING_DELIVERY_INCOMPLETE"))
        sql(
            "UPDATE pension_fund_fee_receipts SET book_id = '$fund' " +
                "WHERE nav_id = '$nav' AND book_kind = 'PENSION_FUND'",
        )
        val runId = evidence().body("complete", equalTo(true)).body("journalsPosted", equalTo(1))
            .extract().path<String>("sourceRunId")
        assertThat(runId).matches("[a-f0-9]{64}")
        assertThat(evidence().extract().path<String>("sourceRunId")).isEqualTo(runId)
        evidence("PENSION_COMPANY").body("complete", equalTo(false))
        receipt(nav, "PENSION_COMPANY", UUID.randomUUID().toString())
        evidence("PENSION_COMPANY").body("complete", equalTo(false))
        sql(
            "UPDATE pension_fund_fee_receipts SET book_id = '$company' " +
                "WHERE nav_id = '$nav' AND book_kind = 'PENSION_COMPANY'",
        )
        evidence("PENSION_COMPANY").body("complete", equalTo(true))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "fee-checker", roles = ["ROLE_OPERATOR"])
    fun `historical missing intent and partial delivery block an otherwise delivered current NAV`() {
        val historical = UUID.randomUUID().toString()
        val yesterday = day.minusDays(1)
        sql(
            "INSERT INTO fund_navs (id, fund_id, valuation_date, gross_assets, accrued_management_fee, " +
                "other_liabilities, net_assets, units_outstanding, nav_per_unit, status, calculated_by, " +
                "calculated_at, approved_by, published_at, positions_recorded) " +
                "SELECT '$historical', fund_id, '$yesterday', gross_assets, accrued_management_fee, " +
                "other_liabilities, net_assets, units_outstanding, nav_per_unit, 'PUBLISHED', calculated_by, " +
                "calculated_at, approved_by, published_at, positions_recorded FROM fund_navs WHERE id = '$nav'",
        )
        evidence().body("complete", equalTo(false)).body("blockers", hasItem("HISTORICAL_NAV_HAS_NO_ACCOUNTING_INTENT"))
        sql(
            "INSERT INTO pension_fund_accounting_outbox (id, event_id, aggregate_id, event_type, payload, " +
                "status, synthetic, created_at, updated_at) " +
                "SELECT nextval('pension_fund_accounting_outbox_seq'), '$historical', aggregate_id, event_type, " +
                "payload, 'PENDING', false, created_at, updated_at " +
                "FROM pension_fund_accounting_outbox WHERE event_id = '$nav'",
        )
        evidence().body("complete", equalTo(false)).body("blockers", hasItem("ACCOUNTING_DELIVERY_INCOMPLETE"))
        receipt(historical, "PENSION_FUND", fund, yesterday)
        evidence().body("complete", equalTo(true))
        evidence("PENSION_COMPANY").body("complete", equalTo(false))
            .body("blockers", hasItem("ACCOUNTING_DELIVERY_INCOMPLETE"))
        receipt(historical, "PENSION_COMPANY", company.toString(), yesterday)
        evidence("PENSION_COMPANY").body("complete", equalTo(true))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "fee-maker", roles = ["ROLE_OPERATOR"])
    fun `a calculated correction blocks the delivered original and changes the stable source identity`() {
        val before = evidence().body("complete", equalTo(true)).extract().path<String>("sourceRunId")
        given().header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
            .body(mapOf("valuationDate" to day.toString(), "cash" to "37000.00"))
            .post("/api/v2/funds/$fund/navs").then().statusCode(201)
            .body("status", equalTo("CALCULATED")).body("correctsNavId", equalTo(nav))
        val after = evidence().body("complete", equalTo(false)).body("blockers", hasItem("PENDING_NAV_APPROVAL"))
            .body("sourceNavs", equalTo(1)).extract().path<String>("sourceRunId")
        assertThat(after).isNotEqualTo(before)
        assertThat(evidence().extract().path<String>("sourceRunId")).isEqualTo(after)
        evidence("PENSION_COMPANY").body("complete", equalTo(false)).body("blockers", hasItem("PENDING_NAV_APPROVAL"))
    }

    private fun evidence(kind: String = "PENSION_FUND") = given().queryParam("bookKind", kind)
        .get("/api/v2/funds/$fund/accounting-days/$day").then().statusCode(200)

    private fun receipt(navId: String, kind: String, bookId: String, postingDate: LocalDate = day) {
        sql(
            "INSERT INTO pension_fund_fee_receipts " +
                "(nav_id, book_kind, book_id, journal_id, posting_date, outcome, synthetic) " +
                "VALUES ('$navId', '$kind', '$bookId', '${UUID.randomUUID()}', '$postingDate', 'POSTED', false)",
        )
    }

    private fun approve(navId: String) = given()
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .post("/api/v2/navs/$navId/approve").then()

    private fun createSource(isin: String): Pair<String, String> {
        val createdFund = given().header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json").body(
                mapOf(
                    "name" to "Fee accounting fund", "isin" to isin, "lei" to "315700ABCDEF12345678",
                    "depositaryReference" to "TEST-DEPOSITARY", "custodyAccountReference" to "TEST-$isin",
                    "currency" to "CZK", "riskClass" to 3, "mandatoryConservative" to false,
                    "productRevisionId" to FixedFundTerms.FEE_1_00_PERCENT, "launchNavPerUnit" to 1,
                ),
            ).post("/api/v2/funds").then().statusCode(201).extract().path<String>("id")
        given().header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
            .body(mapOf("fundId" to createdFund, "type" to "SUBSCRIBE", "amount" to "1000.00"))
            .post("/api/v2/contracts/${UUID.randomUUID()}/orders").then().statusCode(202)
        val createdNav = given().header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json").body(mapOf("valuationDate" to day.toString(), "cash" to "36500.00"))
            .post("/api/v2/funds/$createdFund/navs").then().statusCode(201)
            .body("status", equalTo("CALCULATED")).extract().path<String>("id")
        return createdFund to createdNav
    }

    private fun scalar(query: String): String = connection().use { c ->
        c.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                assertThat(rows.next()).isTrue()
                rows.getString(1)
            }
        }
    }

    private fun sql(query: String) {
        connection().use { c -> c.createStatement().use { it.execute(query) } }
    }

    private fun connection(): java.sql.Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        val day: LocalDate = LocalDate.now(ZoneOffset.UTC)
        val company: UUID = UUID.fromString("00000000-0000-0000-0000-000000000721")
        lateinit var fund: String
        lateinit var nav: String
        lateinit var rollbackFund: String
        lateinit var rollbackNav: String
    }
}
