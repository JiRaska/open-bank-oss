// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.sl
import com.openbank.risk.domain.Fixtures.tb
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Maintenance-period averaging (ADR-0315 D8) end to end with holdings STATED: a profile maps GL
 * 1001 as the ČNB current account, the ledger double changes per day, and every day is a real
 * TIED_OUT snapshot on Postgres read back through GET /min-reserves/periods/{id}.
 */
@QuarkusTest
@TestProfile(RiskMinReservesPeriodApiIT.CnbCurrentAccountProfile::class)
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskMinReservesPeriodApiIT {

    /** Literal values only: a profile loads in its own classloader. */
    class CnbCurrentAccountProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("openbank.risk.min-reserves.classification.gl-accounts.1001" to "reserve-holding")
    }

    @Inject
    lateinit var ledger: FakeLedgerPort

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @BeforeEach
    fun facts() = TestDb.seedReserveFacts()

    @AfterEach
    fun reset() {
        ledger.inputs = Fixtures.tiedOut()
    }

    /** Deposits 10 000 (requirement 400 at the 4 % ratio in effect), [holding] on the ČNB current account 1001. */
    private fun day(asOf: String, holding: String) {
        val equity = BigDecimal("10000").subtract(BigDecimal(holding)).toPlainString()
        ledger.inputs = LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1001", "ASSET", "CZK", holding, "0"),
                tb("2100", "LIABILITY", "CZK", "0", "10000.00"),
                tb("6000", "EQUITY", "CZK", equity, "0"),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "10000.00")),
        )
        given().contentType("application/json").body("""{"asOf":"$asOf"}""")
            .`when`().post("/api/v1/risk/snapshots").then().statusCode(201).body("status", equalTo("TIED_OUT"))
    }

    private fun period(asOf: String): JsonNode = json.readTree(
        given().queryParam("asOf", asOf).`when`().get("/api/v1/risk/min-reserves/periods/2026-12")
            .then().statusCode(200).extract().asString(),
    )

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `running average, coverage and the daily holding proposal over several daily snapshots`() {
        day("2026-11-30", "9000.00") // base reference date of period 2026-12: requirement 400 (4 %)
        day("2026-12-01", "100.00")
        day("2026-12-02", "50.00")
        day("2026-12-03", "150.00")

        val full = period("2026-12-03")
        assertThat(full["requirement"].decimalValue()).isEqualByComparingTo("400.00")
        assertThat(full["daysInPeriod"].asInt()).isEqualTo(31)
        assertThat(full["daysElapsed"].asInt()).isEqualTo(3)
        assertThat(full["daysWithData"].asInt()).isEqualTo(3)
        assertThat(full["coverage"].decimalValue()).isEqualByComparingTo("1")
        assertThat(full["averageHoldings"].decimalValue()).isEqualByComparingTo("100.00")
        assertThat(full["days"].map { it["holdings"].decimalValue() })
            .usingElementComparator(Comparator.naturalOrder()).containsExactly(
                BigDecimal("100"),
                BigDecimal("50"),
                BigDecimal("150"),
            )
        // (400 × 31 − 300) / 28 = 432.142… → 432.14 stated, 432.15 proposed (rounded up)
        assertThat(full["remainingRequiredAverage"].decimalValue()).isEqualByComparingTo("432.14")
        assertThat(full["dailyHoldingProposal"].decimalValue()).isEqualByComparingTo("432.15")
        assertThat(full["proposal"].size()).isEqualTo(28)
        assertThat(full["proposal"][0]["date"].asText()).isEqualTo("2026-12-04")
        assertThat(full["proposalNotStated"].isNull).isTrue()
        assertThat(full["calendarStatus"].asText()).isEqualTo("sample-unverified")

        // a day with no snapshot: coverage drops, the average stays over the 3 days, no proposal
        val gappy = period("2026-12-04")
        assertThat(gappy["coverage"].decimalValue()).isEqualByComparingTo("0.75")
        assertThat(gappy["missingDays"].map { it.asText() }).containsExactly("2026-12-04")
        assertThat(gappy["averageHoldings"].decimalValue()).isEqualByComparingTo("100.00")
        assertThat(gappy["proposal"].isNull).isTrue()
        assertThat(gappy["proposalNotStated"].asText()).contains("partial sum")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `with no reserve ratio in effect on the base date the requirement is not stated, with the reason`() {
        TestDb.execute("DELETE FROM cnb_policy_rate_fact WHERE instrument = 'MIN_RESERVE_RATIO'")
        day("2026-09-30", "9000.00") // base reference date of period 2026-10

        val body = json.readTree(
            given().queryParam("asOf", "2026-10-01").`when`().get("/api/v1/risk/min-reserves/periods/2026-10")
                .then().extract().asString(),
        )

        assertThat(body["requirement"].isNull).isTrue()
        assertThat(body["requirementNotStated"].asText()).contains("NOT_EVALUABLE").contains("MIN_RESERVE_RATIO")
    }
}
