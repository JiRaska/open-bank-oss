// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.port.out.FundHolding
import com.openbank.pension.application.port.out.FundHoldings
import com.openbank.pension.application.port.out.FundUnitTransaction
import com.openbank.pension.application.port.out.PendingFundOrder
import com.openbank.pension.infrastructure.fund.InMemoryFundAdministrationAdapter
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The participant valuation, unit-transaction and operator mandates routes over real HTTP against
 * a real Postgres: the routes are SERVED next to the other `/contracts/{id}` resources (#3371),
 * ownership answers 404 for someone else's contract, the party header is honoured only from the
 * edge relay, and the mandates list is staff-only. The unit register is the `%test` in-memory one.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ContractValuationApiIT {

    @Inject
    lateinit var register: InMemoryFundAdministrationAdapter

    private val base = "/api/v2/pension/contracts"
    private val fundPriced = UUID.randomUUID()
    private val fundNew = UUID.randomUUID()

    private fun spec(party: UUID?): RequestSpecification = given()
        .apply { if (party != null) header("X-Customer-Party-Id", party.toString()) }

    private fun contractWithUnits(party: UUID, unpricedFund: Boolean): UUID {
        val id = seededContract(party)
        val holdings = buildList {
            add(
                FundHolding(
                    fundPriced,
                    BigDecimal("100"),
                    BigDecimal("1.25"),
                    LocalDate.parse("2026-10-01"),
                    BigDecimal("125.00"),
                    "CZK",
                ),
            )
            if (unpricedFund) add(FundHolding(fundNew, BigDecimal("4"), null, null, null, "CZK"))
        }
        val pending =
            PendingFundOrder(UUID.randomUUID(), fundPriced, "SUBSCRIBE", BigDecimal("500"), null, Instant.now())
        val txs = (1..3).map { i ->
            FundUnitTransaction(
                UUID.randomUUID(),
                fundPriced,
                "SUBSCRIBE",
                BigDecimal("10"),
                BigDecimal("12.50"),
                BigDecimal("1.25"),
                UUID.randomUUID(),
                Instant.parse("2026-09-0${i}T16:00:00Z"),
            )
        }
        register.setHoldings(id, FundHoldings(holdings, listOf(pending)), txs)
        return id
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the participant reads their own valuation and paginated transactions`() {
        val party = UUID.randomUUID()
        val id = contractWithUnits(party, unpricedFund = false)
        spec(party).get("$base/$id/valuation").then().statusCode(200)
            .body("status", equalTo("VALUED"))
            .body("totalValue", equalTo(125.0f))
            .body("currency", equalTo("CZK"))
            .body("asOf", equalTo("2026-10-01"))
            .body("holdings[0].navStatus", equalTo("PUBLISHED"))
            .body("pendingOrders", hasSize<Any>(1))
        spec(party).get("$base/$id/transactions?page=0&size=2").then().statusCode(200)
            .body("total", equalTo(3)).body("items", hasSize<Any>(2))
            .body("items[0].pricedAt", equalTo("2026-09-03T16:00:00Z"))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a fund without a published NAV is said so, and no total is stated`() {
        val party = UUID.randomUUID()
        val id = contractWithUnits(party, unpricedFund = true)
        spec(party).get("$base/$id/valuation").then().statusCode(200)
            .body("status", equalTo("NAV_NOT_PUBLISHED"))
            .body("totalValue", nullValue())
            .body("holdings.find { it.fundId == '$fundNew' }.navStatus", equalTo("NOT_PUBLISHED"))
            .body("holdings.find { it.fundId == '$fundNew' }.value", nullValue())
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `someone else's contract is 404 on both routes`() {
        val id = contractWithUnits(UUID.randomUUID(), unpricedFund = false)
        val stranger = UUID.randomUUID()
        spec(stranger).get("$base/$id/valuation").then().statusCode(404)
        spec(stranger).get("$base/$id/transactions").then().statusCode(404)
        spec(stranger).get("$base/${UUID.randomUUID()}/valuation").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "mallory", roles = ["ROLE_API"])
    fun `a party header from anyone but the edge relay is refused`() {
        val party = UUID.randomUUID()
        val id = contractWithUnits(party, unpricedFund = false)
        spec(party).get("$base/$id/valuation").then().statusCode(403)
        // And without the header a non-staff API caller is no reader at all.
        spec(null).get("$base/$id/valuation").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `staff read any contract's valuation, and the sibling contract routes are still served`() {
        val id = contractWithUnits(UUID.randomUUID(), unpricedFund = false)
        spec(null).get("$base/$id/valuation").then().statusCode(200).body("status", equalTo("VALUED"))
        spec(null).get("$base/$id/transactions").then().statusCode(200).body("total", equalTo(3))
        spec(null).get("$base/$id").then().statusCode(200)
        spec(null).get("$base/$id/transactions?size=0").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `staff list mandates by contract and status`() {
        val id = seededContract(UUID.randomUUID())
        val active = seedMandate(id, "ACTIVE")
        seedMandate(id, "CANCELLED")
        spec(null).get("/api/v2/pension/operator/mandates?contractId=$id").then().statusCode(200)
            .body("", hasSize<Any>(2))
        spec(null).get("/api/v2/pension/operator/mandates?contractId=$id&status=ACTIVE").then().statusCode(200)
            .body("", hasSize<Any>(1)).body("[0].id", equalTo(active.toString()))
            .body("[0].kind", equalTo("STANDING_ORDER"))
        spec(null).get("/api/v2/pension/operator/mandates?limit=0").then().statusCode(400)
        // Staff never act "as" a participant on the operator list.
        spec(UUID.randomUUID()).get("/api/v2/pension/operator/mandates").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the edge cannot reach the operator mandates list`() {
        spec(null).get("/api/v2/pension/operator/mandates").then().statusCode(403)
    }

    @Test
    fun `no identity is 401`() {
        given().get("$base/${UUID.randomUUID()}/valuation").then().statusCode(401)
        given().get("/api/v2/pension/operator/mandates").then().statusCode(401)
    }

    // --- seeding (same idiom as FundingApiIT) ----------------------------------------------

    private fun seededContract(party: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc { c ->
            c.prepareStatement(
                "INSERT INTO pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction, " +
                    "pack_version, provider_entity_id, provider_type, participant_birth_date, status, " +
                    "contribution_amount, contribution_currency, contribution_frequency, start_date, created_at, " +
                    "updated_at) VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, ?, 'DPS', 'CZ', 2, ?, " +
                    "'PENSION_COMPANY', DATE '1985-05-05', 'ACTIVE', 1700, 'CZK', 'MONTHLY', DATE '2025-01-01', " +
                    "now(), now())",
            ).use { st ->
                st.setObject(1, id)
                st.setObject(2, party)
                st.setObject(3, UUID.randomUUID())
                st.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO pension_strategy_elections (id, contract_id, strategy_code, effective_from, elected_at) " +
                    "VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, 'BALANCED', DATE '2025-01-01', now())",
            ).use { st ->
                st.setObject(1, id)
                st.executeUpdate()
            }
        }
        return id
    }

    private fun seedMandate(contractId: UUID, status: String): UUID {
        val id = UUID.randomUUID()
        jdbc { c ->
            c.prepareStatement(
                "INSERT INTO pension_payment_mandates (id, contract_id, kind, external_id, status, created_at, " +
                    "updated_at) VALUES (?, ?, 'STANDING_ORDER', ?, ?, now(), now())",
            ).use { st ->
                st.setObject(1, id)
                st.setObject(2, contractId)
                st.setString(3, "so-$id")
                st.setString(4, status)
                st.executeUpdate()
            }
        }
        return id
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }
}
