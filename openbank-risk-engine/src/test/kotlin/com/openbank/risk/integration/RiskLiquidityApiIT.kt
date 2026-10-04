// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.application.port.out.TreasuryDealEvent
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.lendingLoan
import com.openbank.risk.domain.Fixtures.sl
import com.openbank.risk.domain.Fixtures.tb
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0313 phase 1 LCR / NSFR end to end: ledger and lending doubles at the ports, a snapshot that
 * TIES OUT on real Postgres, then GET .../liquidity under the shipped parameter set.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskLiquidityApiIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Inject
    lateinit var lending: FakeLendingPort

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @Inject
    lateinit var treasury: TreasuryDealBook

    /** With the treasury read on, 1510 and 2320 are contract-level: each balance needs its deal. */
    private val cnbLombard = UUID.randomUUID()

    private val placementIn20d = UUID.randomUUID()
    private val placementIn61d = UUID.randomUUID()
    private val cnbDeposit = UUID.randomUUID()

    @AfterEach
    fun reset() {
        lending.loans = emptyList()
        ledger.inputs = Fixtures.tiedOut()
        TestDb.execute(
            "DELETE FROM treasury_deal WHERE deal_id IN ('$placementIn20d', '$placementIn61d', '$cnbDeposit', '$cnbLombard')",
        )
    }

    private fun seedDeal(id: UUID, product: String, principal: String): Unit =
        seedDeal(id, product, principal, "2026-10-01")

    private fun seedDeal(id: UUID, product: String, principal: String, maturity: String): Unit = runBlocking {
        treasury.apply(
            TreasuryDealEvent(
                state = "SETTLED",
                dealId = id,
                product = product,
                counterpartyId = if (product.startsWith("CNB_")) "CNB" else "BANK-A",
                currency = "CZK",
                principal = BigDecimal(principal),
                rate = BigDecimal("3.00"),
                valueDate = LocalDate.parse("2026-09-15"),
                maturityDate = LocalDate.parse(maturity),
            ),
        )
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a placement due within 30 days is a 100 percent inflow, a later one and the CNB deposit are not`() {
        ledger.inputs = LedgerInputs(
            asOf = LocalDate.parse("2026-10-07"),
            trialBalance = listOf(
                tb("1500", "ASSET", "CZK", "3000.00", "0"),
                tb("1510", "ASSET", "CZK", "4000.00", "0"),
                tb("1001", "ASSET", "CZK", "3000.00", "0"),
                tb("2310", "LIABILITY", "CZK", "0", "10000.00"),
            ),
            subLedger = emptyList(),
        )
        seedDeal(placementIn20d, "MM_PLACEMENT", "2000.00", "2026-10-27")
        seedDeal(placementIn61d, "MM_PLACEMENT", "1000.00", "2026-12-07")
        seedDeal(cnbDeposit, "CNB_DEPOSIT_FACILITY", "4000.00", "2026-10-08")
        val lcr = liquidity(snapshot("2026-10-07", "TIED_OUT"))["total"]["lcr"]

        val placements = lcr["inflows"].filter { it["factorKey"].asText() == "lcr-inflow-fi-placement-30d" }
        assertThat(placements).hasSize(1)
        assertThat(placements.single()["amount"].decimalValue()).isEqualByComparingTo("2000.00")
        assertThat(placements.single()["glAccountCode"].asText()).isEqualTo("1500")
        assertThat(placements.single()["weighted"].decimalValue()).isEqualByComparingTo("2000.00")
        // The ČNB deposit is HQLA Level 1 and never also an inflow; the 1001 nostro is operational (0%).
        assertThat(lcr["inflows"].none { it["glAccountCode"].asText() == "1510" }).isTrue()
        assertThat(lcr["hqla"]["stock"].decimalValue()).isEqualByComparingTo("4000.00")
        assertThat(lcr["totalOutflows"].decimalValue()).isEqualByComparingTo("10000.00")
        assertThat(lcr["totalInflows"].decimalValue()).isEqualByComparingTo("2000.00")
        assertThat(lcr["cappedInflows"].decimalValue()).isEqualByComparingTo("2000.00")
        // 4000 / (10000 − 2000) = 0.5; without the placement inflow it would be 0.4.
        assertThat(lcr["ratio"].decimalValue()).isEqualByComparingTo("0.5")
    }

    private fun snapshot(asOf: String, status: String): String = given().contentType("application/json")
        .body("""{"asOf":"$asOf"}""").`when`().post("/api/v1/risk/snapshots")
        .then().statusCode(201).body("status", equalTo(status)).extract().path("id")

    private fun liquidity(runId: String): JsonNode = json.readTree(
        given().`when`().get("/api/v1/risk/snapshots/$runId/liquidity").then().statusCode(200).extract().asString(),
    )

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a tied book gets LCR and NSFR with components, unclassified balances and the parameter set`() {
        val loan = lendingLoan(principal = "12000.00", currency = "CZK", term = 24, paid = 6)
        val l = loan.outstandingPrincipal.toPlainString()
        ledger.inputs = LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1001", "ASSET", "CZK", "1500.00", "0"),
                tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
                tb("1200", "ASSET", "CZK", l, "0"),
                tb("6000", "EQUITY", "CZK", "0", l),
                tb("1000", "ASSET", "CZK", "300.00", "0"),
                tb("4100", "INCOME", "CZK", "0", "300.00"),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00"), sl(Fixtures.BOB, "CZK", "0", "500.00")),
        )
        lending.loans = listOf(loan)
        val body = liquidity(snapshot("2026-09-30", "TIED_OUT"))

        // EU rules are the default for this bank (#10860): Delegated Regulation (EU) 2015/61 + CRR2.
        assertThat(body["parameterSetId"].asText()).isEqualTo("eu-2015-61-crr2")
        assertThat(body["parameterSetVersion"].asText()).isEqualTo("4")
        assertThat(body["provenance"].asText()).isEqualTo("synthetic")
        val total = body["total"]
        assertThat(total["currency"].asText()).isEqualTo("CZK")

        val lcr = total["lcr"]
        assertThat(lcr["hqla"]["stock"].decimalValue()).isEqualByComparingTo("0")
        assertThat(lcr["totalOutflows"].decimalValue()).isEqualByComparingTo("150.00")
        assertThat(lcr["inflows"].map { it["factorKey"].asText() })
            .contains("lcr-operational-deposit-inflow", "lcr-retail-loan-inflow")
        assertThat(lcr["cappedInflows"].decimalValue()).isLessThanOrEqualTo(lcr["inflowCap"].decimalValue())
        assertThat(lcr["ratio"].decimalValue()).isEqualByComparingTo("0")

        val nsfr = total["nsfr"]
        assertThat(nsfr["asf"].map { it["factorKey"].asText() })
            .contains("nsfr-asf-capital", "nsfr-asf-retail-less-stable", "nsfr-asf-other")
        assertThat(nsfr["ratio"].isNull).isFalse()

        // 1000 "Cash and Cash Equivalents" is NOT mapped: listed, never counted.
        assertThat(body["unclassified"].map { it["glAccountCode"].asText() }).containsExactly("1000")
        assertThat(lcr["hqla"]["lines"].isEmpty).isTrue()
        // No ČNB lombard balance: no pledged-collateral note.
        assertThat(body["notes"].map { it.asText() }).noneMatch { it.contains("lombard") }

        val a = body["assumptions"]
        assertThat(a["parameterSetId"].asText()).isEqualTo("eu-2015-61-crr2")
        assertThat(a["scope"].asText()).contains("2015/61").contains("575/2013")
        assertThat(a["factors"].size()).isEqualTo(32)
        assertThat(
            a["factors"].all {
                it["citation"].asText().let { c -> c.startsWith("EU 2015/61") || c.startsWith("CRR ") }
            },
        ).isTrue()
        assertThat(a["factors"].single { it["key"].asText() == "nsfr-rsf-l1-securities" }["value"].decimalValue())
            .isEqualByComparingTo("0")
        assertThat(a["classification"]["retailStableShare"].decimalValue()).isEqualByComparingTo("0")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a CNB lombard balance on 2320 is classified, not unclassified, and flags the unmodelled collateral`() {
        seedDeal(cnbDeposit, "CNB_DEPOSIT_FACILITY", "2000.00")
        seedDeal(cnbLombard, "CNB_LOMBARD", "2000.00")
        val base = Fixtures.tiedOut()
        ledger.inputs = base.copy(
            trialBalance = base.trialBalance +
                tb("1510", "ASSET", "CZK", "2000.00", "0") +
                tb("2320", "LIABILITY", "CZK", "0", "2000.00"),
        )
        val body = liquidity(snapshot("2026-09-30", "TIED_OUT"))

        assertThat(body["unclassified"].map { it["glAccountCode"].asText() }).doesNotContain("2320")
        val lcr = body["total"]["lcr"]
        val out = lcr["outflows"].single { it["glAccountCode"].asText() == "2320" }
        assertThat(out["factorKey"].asText()).isEqualTo("lcr-central-bank-secured-outflow")
        assertThat(out["weighted"].decimalValue()).isEqualByComparingTo("0")
        val asf = body["total"]["nsfr"]["asf"].single { it["glAccountCode"].asText() == "2320" }
        assertThat(asf["factorKey"].asText()).isEqualTo("nsfr-asf-central-bank-under-6m")
        assertThat(body["notes"].map { it.asText() }).anyMatch { it.contains("lombard") && it.contains("HQLA") }
    }

    @Inject
    @jakarta.enterprise.inject.Any
    lateinit var connector: io.smallrye.reactive.messaging.memory.InMemoryConnector

    /** A ČNB fixing through the real consumer: validity Fri 00:00 - Mon 00:00 Prague, as fx-service stamps it. */
    private fun publishFixing(date: String, currency: String, ratePerUnit: String) {
        val d = java.time.LocalDate.parse(date)
        val prague = java.time.ZoneId.of("Europe/Prague")
        val validFrom = d.atStartOfDay(prague).toInstant()
        val validTo = d.plusDays(3).atStartOfDay(prague).toInstant()
        connector.source<String>("fx-fixing-in").send(
            """
            {"source":"CNB","fixingDate":"$date","sequence":1,"quoteCurrency":"CZK",
             "validFrom":"$validFrom","validTo":"$validTo",
             "rates":[{"rateId":"${java.util.UUID.randomUUID()}","currency":"$currency","ratePerUnit":$ratePerUnit}],
             "occurredAt":"${date}T12:30:00Z"}
            """.trimIndent(),
        )
        val deadline = System.nanoTime() + 10_000_000_000L
        while (TestDb.count(
                "SELECT count(*) FROM fx_fixing_rate WHERE fixing_date = DATE '$date' AND currency = '$currency'",
            ) == 0 &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(100)
        }
    }

    /** CZK: 1500 nostro against 1500 retail deposits. EUR: a 100 nostro against 100 of income. */
    private fun eurBook() = LedgerInputs(
        asOf = Fixtures.AS_OF,
        trialBalance = listOf(
            tb("1001", "ASSET", "CZK", "1500.00", "0"),
            tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
            tb("1002", "ASSET", "EUR", "100.00", "0"),
            tb("4100", "INCOME", "EUR", "0", "100.00"),
        ),
        subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00"), sl(Fixtures.BOB, "CZK", "0", "500.00")),
    )

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a EUR and CZK book on a Sunday is combined in CZK at the Friday ČNB fixing`() {
        publishFixing("2026-09-11", "EUR", "24.5")
        ledger.inputs = eurBook()
        val body = liquidity(snapshot("2026-09-13", "TIED_OUT"))

        val eur = body["currencies"].single { it["currency"].asText() == "EUR" }
        assertThat(eur["nsfr"]["totalRsf"].decimalValue()).isEqualByComparingTo("50.00") // still in EUR
        val total = body["total"]
        assertThat(total["currency"].asText()).isEqualTo("CZK")
        val nostro = total["lcr"]["inflows"].single { it["glAccountCode"].asText() == "1002" }
        assertThat(nostro["amount"].decimalValue()).isEqualByComparingTo("2450.00") // 100 × 24.5
        assertThat(total["lcr"]["totalOutflows"].decimalValue()).isEqualByComparingTo("150.00")
        // ASF 1500 × 90% = 1350; RSF 1500 × 50% + 2450 × 50% = 1975; NSFR on the converted sums.
        assertThat(total["nsfr"]["totalAsf"].decimalValue()).isEqualByComparingTo("1350.00")
        assertThat(total["nsfr"]["totalRsf"].decimalValue()).isEqualByComparingTo("1975.00")
        assertThat(total["nsfr"]["ratio"].decimalValue()).isEqualByComparingTo("0.683544")
        assertThat(body["totalNotStated"].isNull).isTrue()
        val fx = body["fxRates"].single()
        assertThat(fx["currency"].asText()).isEqualTo("EUR")
        assertThat(fx["rate"].decimalValue()).isEqualByComparingTo("24.5")
        assertThat(fx["fixingDate"].asText()).isEqualTo("2026-09-11")
        assertThat(fx["source"].asText()).isEqualTo("CNB")
        assertThat(body["assumptions"]["currencyAggregation"].asText()).contains("ČNB fixing")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a EUR book with no fixing in effect states no combined total and says why`() {
        ledger.inputs = eurBook()
        val body = liquidity(snapshot("2026-08-06", "TIED_OUT"))
        assertThat(body["total"].isNull).isTrue()
        assertThat(body["totalNotStated"].asText()).contains("EUR").contains("2026-08-06")
        assertThat(body["fxRates"].isEmpty).isTrue()
        assertThat(body["currencies"].map { it["currency"].asText() }).containsExactly("CZK", "EUR")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an untied run answers 409`() {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))
        val untied = snapshot("2026-04-30", "UNTIED")
        given().`when`().get("/api/v1/risk/snapshots/$untied/liquidity")
            .then().statusCode(409).body("error", equalTo("UNTIED"))
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an unknown run answers 404`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-0000000000ff/liquidity")
            .then().statusCode(404)
    }

    @Test
    fun `an unauthenticated caller cannot read liquidity`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-000000000001/liquidity")
            .then().statusCode(401)
    }
}
