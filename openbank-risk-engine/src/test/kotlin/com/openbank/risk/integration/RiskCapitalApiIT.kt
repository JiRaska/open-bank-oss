// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * ADR-0313 phase 2 credit-risk capital end to end: ledger and lending doubles at the ports, a
 * snapshot that TIES OUT on real Postgres, then GET .../capital under the shipped parameter set.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskCapitalApiIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Inject
    lateinit var lending: FakeLendingPort

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @Inject
    lateinit var treasury: com.openbank.risk.application.port.out.TreasuryDealBook

    /** The ČNB deposit behind the 4000 on 1510: with the treasury read on, 1510 is contract-level. */
    private val cnbDeal = java.util.UUID.randomUUID()

    @AfterEach
    fun reset() {
        lending.loans = emptyList()
        ledger.inputs = Fixtures.tiedOut()
        TestDb.execute("DELETE FROM treasury_deal WHERE deal_id = '$cnbDeal'")
    }

    private fun seedCnbDeposit(principal: String) = kotlinx.coroutines.runBlocking {
        treasury.apply(
            com.openbank.risk.application.port.out.TreasuryDealEvent(
                state = "SETTLED",
                dealId = cnbDeal,
                product = "CNB_DEPOSIT_FACILITY",
                counterpartyId = "CNB",
                currency = "CZK",
                principal = BigDecimal(principal),
                rate = BigDecimal("2.50"),
                valueDate = java.time.LocalDate.parse("2026-05-28"),
                maturityDate = java.time.LocalDate.parse("2026-06-01"),
            ),
        )
    }

    private fun snapshot(asOf: String, status: String): String = given().contentType("application/json")
        .body("""{"asOf":"$asOf"}""").`when`().post("/api/v1/risk/snapshots")
        .then().statusCode(201).body("status", equalTo(status)).extract().path("id")

    private fun capital(runId: String): JsonNode = json.readTree(
        given().`when`().get("/api/v1/risk/snapshots/$runId/capital").then().statusCode(200).extract().asString(),
    )

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a tied book gets RWA per class, the 8 percent requirement, ratios and unclassified balances`() {
        val loan = lendingLoan(principal = "12000.00", currency = "CZK", term = 24, paid = 6)
        val l = loan.outstandingPrincipal
        ledger.inputs = LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1001", "ASSET", "CZK", "1500.00", "0"),
                tb("1510", "ASSET", "CZK", "4000.00", "0"),
                tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
                tb("1200", "ASSET", "CZK", l.toPlainString(), "0"),
                tb("6000", "EQUITY", "CZK", "0", l.add(BigDecimal("4000.00")).toPlainString()),
                tb("1000", "ASSET", "CZK", "300.00", "0"),
                tb("4100", "INCOME", "CZK", "0", "300.00"),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00"), sl(Fixtures.BOB, "CZK", "0", "500.00")),
        )
        lending.loans = listOf(loan)
        seedCnbDeposit("4000.00")
        val body = capital(snapshot("2026-05-29", "TIED_OUT"))

        // The default set is the EU CRR one (application.yaml parameter-set-id).
        assertThat(body["parameterSetId"].asText()).isEqualTo("eu-crr3-sa")
        assertThat(body["parameterSetVersion"].asText()).isEqualTo("1")
        assertThat(body["provenance"].asText()).isEqualTo("synthetic")
        val total = body["total"]
        // nostro 1500 × 150% (SCRA Grade C, CRR Art. 121) + ČNB 4000 × 0% (Art. 114(4))
        // + loan × 100% (not verifiably Art. 123 retail, so corporate, Art. 122)
        val rwa = BigDecimal("2250.00").add(l)
        assertThat(total["totalRwa"].decimalValue()).isEqualByComparingTo(rwa)
        assertThat(body["ownFundsRequirement"].decimalValue())
            .isEqualByComparingTo(rwa.multiply(BigDecimal("0.08")).setScale(2, java.math.RoundingMode.HALF_EVEN))
        val classes = total["classes"].associate { it["exposureClass"].asText() to it["rwa"].decimalValue() }
        assertThat(classes.keys).containsExactlyInAnyOrder("sovereign-and-central-bank", "bank", "corporate")
        assertThat(classes["sovereign-and-central-bank"]).isEqualByComparingTo("0")
        assertThat(total["lines"].all { it["citation"].asText().startsWith("CRR Art. ") }).isTrue()
        assertThat(body["ratios"]["total"]["citation"].asText()).startsWith("CRR Art. 92(1)(c)")

        val cet1 = l.add(BigDecimal("4000.00"))
        assertThat(total["ownFunds"]["cet1"].decimalValue()).isEqualByComparingTo(cet1)
        assertThat(body["ratios"]["total"]["ratio"].decimalValue())
            .isEqualByComparingTo(cet1.divide(rwa, 6, java.math.RoundingMode.HALF_EVEN))
        assertThat(body["ratiosNotComputable"].isNull).isTrue()

        // 1000 "Cash and Cash Equivalents" is NOT mapped: listed, never counted.
        assertThat(body["unclassified"].map { it["glAccountCode"].asText() }).containsExactly("1000")

        val a = body["assumptions"]
        assertThat(a["scope"].asText()).startsWith("EU CRR SA weights")
        assertThat(a["classification"]["retailTreatment"].asText()).isEqualTo("corporate-unrated")
        assertThat(a["factors"].size()).isEqualTo(15)
        assertThat(a["classification"]["bankScraGrade"].asText()).isEqualTo("C")
        assertThat(a["creditRiskMitigation"].asText()).startsWith("None applied")
        assertThat(a["offBalanceSheet"].asText()).contains("no credit conversion factor")
    }

    @Inject
    @jakarta.enterprise.inject.Any
    lateinit var connector: io.smallrye.reactive.messaging.memory.InMemoryConnector

    /** A ČNB fixing through the real consumer: validity Fri 00:00 - Mon 00:00 Prague, as fx-service stamps it. */
    private fun publishFixing(date: String, currency: String, ratePerUnit: String) {
        val d = java.time.LocalDate.parse(date)
        val prague = java.time.ZoneId.of("Europe/Prague")
        connector.source<String>("fx-fixing-in").send(
            """
            {"source":"CNB","fixingDate":"$date","sequence":1,"quoteCurrency":"CZK",
             "validFrom":"${d.atStartOfDay(
                prague,
            ).toInstant()}","validTo":"${d.plusDays(3).atStartOfDay(prague).toInstant()}",
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
    fun `a EUR and CZK book on a Sunday is totalled in CZK at the Friday ČNB fixing`() {
        publishFixing("2026-09-18", "GBP", "28.5") // an unrelated currency, never used
        publishFixing("2026-09-25", "EUR", "24.335")
        ledger.inputs = eurBook()
        val body = capital(snapshot("2026-09-27", "TIED_OUT"))

        val total = body["total"]
        assertThat(total["currency"].asText()).isEqualTo("CZK")
        // 1500 × 150% + 100 × 24.335 × 150% = 2250 + 3650.25 (both nostros are Grade C banks)
        assertThat(total["totalRwa"].decimalValue()).isEqualByComparingTo("5900.25")
        assertThat(total["classes"].single()["exposureClass"].asText()).isEqualTo("bank")
        assertThat(body["ownFundsRequirement"].decimalValue()).isEqualByComparingTo("472.02")
        assertThat(body["totalNotStated"].isNull).isTrue()
        val fx = body["fxRates"].single()
        assertThat(fx["currency"].asText()).isEqualTo("EUR")
        assertThat(fx["rate"].decimalValue()).isEqualByComparingTo("24.335")
        assertThat(fx["fixingDate"].asText()).isEqualTo("2026-09-25")
        assertThat(fx["source"].asText()).isEqualTo("CNB")
        assertThat(body["currencies"].map { it["currency"].asText() }).containsExactly("CZK", "EUR")
        assertThat(body["assumptions"]["currencyAggregation"].asText()).contains("ČNB fixing")
    }

    /**
     * #10896 / #11107: the ASSET account set the sandbox carried on 2026-09-29, where 1100, 1400,
     * 1990, 1991 and 1995 were unclassified and gapped every COREP C 02.00 credit-risk row. Amounts
     * are small stand-ins with the sandbox's signs; balanced per currency.
     */
    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `the sandbox asset set is fully classified and totalled in CZK`() {
        publishFixing("2026-09-04", "EUR", "24.40")
        val loan = lendingLoan(principal = "12000.00", currency = "CZK", term = 24, paid = 6)
        val l = loan.outstandingPrincipal
        ledger.inputs = LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1001", "ASSET", "CZK", "1500.00", "0"),
                tb("1100", "ASSET", "CZK", "16000.00", "0"),
                tb("1200", "ASSET", "CZK", l.toPlainString(), "0"),
                tb("1300", "ASSET", "CZK", "50.00", "0"),
                tb("1400", "ASSET", "CZK", "0", "80.00"),
                tb("1520", "ASSET", "CZK", "10.00", "0"),
                tb("1990", "ASSET", "CZK", "0", "132.00"),
                tb("1995", "ASSET", "CZK", "0", "126.00"),
                tb("1991", "ASSET", "EUR", "5.19", "0"),
                tb("4100", "INCOME", "EUR", "0", "5.19"),
                tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
                tb("6000", "EQUITY", "CZK", "0", l.add(BigDecimal("15722.00")).toPlainString()),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00"), sl(Fixtures.BOB, "CZK", "0", "500.00")),
        )
        lending.loans = listOf(loan)
        val body = capital(snapshot("2026-09-06", "TIED_OUT"))

        assertThat(body["unclassified"].map { it["glAccountCode"].asText() }).isEmpty()
        assertThat(body["totalNotStated"].isNull).isTrue()
        val total = body["total"]
        assertThat(total["currency"].asText()).isEqualTo("CZK")
        // EU CRR weights (the default set). The loan's outstanding principal after 6 of 24 annuity
        // instalments is fixed by the fixture; pinned so the total below is an exact literal.
        assertThat(l).isEqualByComparingTo("9133.28")
        // 1001 nostro       1 500.00 × 150% (institution, SCRA Grade C, Art. 121)   =  2 250.00
        // 1520 accrued int.    10.00 × 150% (follows its principal 1500, Grade C)    =     15.00
        // 1100 clearing    16 000.00 × 100% (POLICY CHOICE #11481, Art. 134(1))     = 16 000.00
        // 1300 int. recv.      50.00 × 100% (other item, Art. 134(1))                =     50.00
        // loan (stage 1)   9 133.28 × 100% (corporate, Art. 122)          =  9 133.28
        // 1400 allowance and 1990 / 1991 (EUR, at the 24.40 fixing) / 1995 FX positions: not exposures.
        assertThat(total["totalRwa"].decimalValue()).isEqualByComparingTo("27448.28")
        val classes = total["classes"].associate { it["exposureClass"].asText() to it["rwa"].decimalValue() }
        assertThat(classes.keys).containsExactlyInAnyOrder("bank", "other-asset", "corporate")
        assertThat(classes["bank"]).isEqualByComparingTo("2265.00")
        assertThat(classes["other-asset"]).isEqualByComparingTo("16050.00")
        assertThat(classes["corporate"]).isEqualByComparingTo(l)
        assertThat(body["ownFundsRequirement"].decimalValue()).isEqualByComparingTo("2195.86")
        // EU-specific: under bcbs-d424-sa the loan is "retail" (d424 ¶57) and citations are d424 ¶.
        assertThat(total["lines"].all { it["citation"].asText().startsWith("CRR Art. ") }).isTrue()
        assertThat(total["lines"].map { it["glAccountCode"].asText() })
            .doesNotContain("1400", "1990", "1991", "1995")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a EUR book with no fixing in effect states no total and says why`() {
        ledger.inputs = eurBook()
        val body = capital(snapshot("2026-08-05", "TIED_OUT"))
        assertThat(body["total"].isNull).isTrue()
        assertThat(body["ownFundsRequirement"].isNull).isTrue()
        assertThat(body["totalNotStated"].asText()).contains("EUR").contains("2026-08-05")
        assertThat(body["fxRates"].isEmpty).isTrue()
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an untied run answers 409`() {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))
        val untied = snapshot("2026-01-30", "UNTIED")
        given().`when`().get("/api/v1/risk/snapshots/$untied/capital")
            .then().statusCode(409).body("error", equalTo("UNTIED"))
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an unknown run answers 404`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-0000000000ff/capital")
            .then().statusCode(404)
    }

    @Test
    fun `an unauthenticated caller cannot read capital`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-000000000001/capital")
            .then().statusCode(401)
    }
}
