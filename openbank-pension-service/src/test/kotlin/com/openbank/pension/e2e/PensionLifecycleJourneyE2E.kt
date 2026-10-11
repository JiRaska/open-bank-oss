// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.e2e

import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Participant-side end-to-end journeys for the pension lifecycle (ADR-0334, issue #12350 slice S7).
 *
 * Same mechanism as every other `*JourneyE2E` in the fleet (`DomesticPaymentJourneyE2E`,
 * `AccountLifecycleE2E`): `@QuarkusTest` + RestAssured over the real HTTP surface, a real
 * Postgres with the full Flyway chain, nothing mocked out of the request path. The caller is
 * authenticated the way customer-edge calls this service — `ROLE_API` plus the
 * `X-Customer-Party-Id` header the edge stamps from the token it validated.
 *
 * ### Service boundaries — stated, not pretended
 *
 * The unit register, NAV and strategy administration live in pension-fund-service and are
 * journeyed by `PensionFundUnitJourneyE2E` there. This class covers what pension-service owns
 * today; each scenario's KDoc names the steps of #12350 that are NOT yet exercisable because the
 * slice that owns them has not landed, so a green run is never read as covering them.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PensionLifecycleJourneyE2E {

    /**
     * Scenario (a) — a new DPS contract: onboard, activate, evaluate the state incentive the first
     * contribution earns. Not yet exercisable: catalog → simulate (the retirement pack is covered
     * in product-catalog, S6a), suitability questionnaire / KID / SCA signature (S2), contribution
     * booking and the state-agency claim/receipt (S3).
     */
    @Test
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `a new DPS contract is onboarded, activated and earns the capped state contribution`() {
        val party = UUID.randomUUID()
        val created = create(party, dps(monthly = 1700))
        assertThat(created.statusCode).describedAs(created.body.asString()).isEqualTo(201)
        val id = created.jsonPath().getString("contractId")
        val draft = created.jsonPath()
        assertThat(draft.getString("status")).isEqualTo("DRAFT")
        assertThat(draft.getString("productLine")).isEqualTo("DPS")
        assertThat(draft.getString("jurisdiction")).isEqualTo("CZ")
        assertThat(draft.getInt("packVersion")).isEqualTo(1)
        assertThat(draft.getString("startDate")).isNull()

        assertThat(post(party, "$BASE/$id/submit").jsonPath().getString("status")).isEqualTo("PENDING_ACTIVATION")
        val active = post(party, "$BASE/$id/activate")
        assertThat(active.statusCode).isEqualTo(200)
        assertThat(active.jsonPath().getString("status")).isEqualTo("ACTIVE")
        assertThat(active.jsonPath().getString("startDate"))
            .describedAs("activation pins the contract start date, which the payout rules count from")
            .isNotNull()

        // 1 700 CZK a month: 20 % matching is exactly the 340 cap; the tax deduction is zero
        // because 12 x 1 700 = 20 400 is the threshold, not above it.
        val first = incentives(party, id, contribution = 1700, period = "MONTH")
        assertThat(money(first, "state-contribution")).isEqualByComparingTo("340.00")
        assertThat(first.getString("find { it.incentiveId == 'state-contribution' }.claimChannel"))
            .isEqualTo("STATE_AGENCY_BATCH")
        assertThat(money(first, "income-tax-deduction")).isEqualByComparingTo("0.00")

        // Above the cap the state contribution stays at 340, and the excess becomes deductible.
        val high = incentives(party, id, contribution = 5700, period = "MONTH")
        assertThat(money(high, "state-contribution")).isEqualByComparingTo("340.00")
        assertThat(money(high, "income-tax-deduction")).isEqualByComparingTo("48000.00")
        assertThat(BigDecimal(high.getString("find { it.incentiveId == 'income-tax-deduction' }.indicativeSaving")))
            .isEqualByComparingTo("7200.00")

        // Below the 500 minimum there is no matching at all.
        assertThat(money(incentives(party, id, contribution = 400, period = "MONTH"), "state-contribution"))
            .isEqualByComparingTo("0.00")
    }

    /**
     * Scenario (b) — strategy change on an active contract. Not yet exercisable: transfer-in from
     * another provider and activation on arrival of funds (S2); the unit switch at the next NAV is
     * journeyed in pension-fund-service (`PensionFundUnitJourneyE2E`), as the FundAdministrationPort
     * wiring that would trigger it from here is S3.
     */
    @Test
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `a strategy change is appended to the history and the earlier election is kept`() {
        val party = UUID.randomUUID()
        val id = activeContract(party, dps(monthly = 1000))

        val changed = given().contentType(JSON).header(PARTY, party.toString())
            .body("""{"strategyCode":"DYNAMIC"}""")
            .`when`().put("$BASE/$id/strategy")
        assertThat(changed.statusCode).describedAs(changed.body.asString()).isEqualTo(200)

        val stored = read(party, id).jsonPath()
        assertThat(stored.getString("currentStrategy.strategyCode")).isEqualTo("DYNAMIC")
        assertThat(stored.getList<String>("strategyHistory.strategyCode")).containsExactly("BALANCED", "DYNAMIC")
        assertThat(stored.getString("status")).isEqualTo("ACTIVE")
    }

    /**
     * Scenario (d) — early termination: the preview is a pure read, and confirming with the same
     * inputs executes exactly the previewed figures. The state contribution is returned in full,
     * the tax deduction is recaptured for the pack's 10-year window only. Not yet exercisable:
     * the actual redemption of units, the payout transfer and the state-agency return (S5).
     */
    @Test
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `early termination executes exactly the previewed surrender, returning incentives and recapturing tax`() {
        val party = UUID.randomUUID()
        val id = activeContract(party, dps(monthly = 1700))
        val thisYear = LocalDate.now().year
        val inputs = """
            "currentValue": 50000,
            "incentivesReceived": {
              "state-contribution": { "${thisYear - 1}": 4080, "$thisYear": 2720 },
              "income-tax-deduction": { "${thisYear - 11}": 9999, "${thisYear - 1}": 3600 }
            }
        """.trimIndent()

        val preview = post(party, "$BASE/$id/early-termination", "{ $inputs, \"confirm\": false }")
        assertThat(preview.statusCode).describedAs(preview.body.asString()).isEqualTo(200)
        val p = preview.jsonPath()
        assertThat(p.getBoolean("payoutConditionsMet")).isFalse()
        assertThat(p.getBoolean("earlyWithdrawalAllowed")).isTrue()
        assertThat(clawback(p, "state-contribution", "RETURN_ALL")).isEqualByComparingTo("6800.00")
        assertThat(clawback(p, "income-tax-deduction", "RECAPTURE_YEARS"))
            .describedAs("only deductions inside the 10-year window are recaptured")
            .isEqualByComparingTo("3600.00")
        assertThat(BigDecimal(p.getString("estimatedNetPayout")))
            .isEqualByComparingTo(
                BigDecimal("50000").subtract(BigDecimal(p.getString("fee"))).subtract(BigDecimal("10400")),
            )
        assertThat(p.getString("contract.status")).isEqualTo("ACTIVE")
        assertThat(read(party, id).jsonPath().getString("status"))
            .describedAs("a preview must not move the contract")
            .isEqualTo("ACTIVE")

        val executed = post(party, "$BASE/$id/early-termination", "{ $inputs, \"confirm\": true }")
        assertThat(executed.statusCode).describedAs(executed.body.asString()).isEqualTo(200)
        val e = executed.jsonPath()
        assertThat(e.getString("contract.status")).isEqualTo("TERMINATING")
        for (field in listOf("currentValue", "fee", "estimatedNetPayout")) {
            assertThat(BigDecimal(e.getString(field))).describedAs(field).isEqualByComparingTo(p.getString(field))
        }
        assertThat(e.getList<Map<String, Any>>("clawbacks")).isEqualTo(p.getList<Map<String, Any>>("clawbacks"))
        assertThat(read(party, id).jsonPath().getString("status")).isEqualTo("TERMINATING")
    }

    /**
     * Scenario (e) — regular payout (lump sum, phased) and death → beneficiary payouts. Neither is
     * exercisable before S5; what pension-service already guarantees is that the beneficiary
     * designation those payouts will read is stored as submitted.
     */
    @Test
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `beneficiary designations are stored as submitted for the later death payout`() {
        val party = UUID.randomUUID()
        val beneficiary = UUID.randomUUID()
        val body = dps(
            monthly = 1000,
            beneficiaries = """[{"name":"Jana Nova","partyId":"$beneficiary","sharePercent":60},
                               {"name":"Petr Novy","sharePercent":40}]""",
        )
        val id = activeContract(party, body)
        val stored = read(party, id).jsonPath()
        assertThat(stored.getList<String>("beneficiaries.name")).containsExactly("Jana Nova", "Petr Novy")
        assertThat(stored.getString("beneficiaries[0].partyId")).isEqualTo(beneficiary.toString())
        assertThat(stored.getList<Any>("beneficiaries.sharePercent").map { BigDecimal(it.toString()) })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal("60"), BigDecimal("40"))
    }

    /** Scenario (f) — DIP happy path: a bank may provide it, and it earns tax relief, never a state contribution. */
    @Test
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `a DIP contract provided by the bank activates and earns tax relief only`() {
        val party = UUID.randomUUID()
        val id = activeContract(party, dip(providerType = "BANK"))
        val contract = read(party, id).jsonPath()
        assertThat(contract.getString("productLine")).isEqualTo("DIP")
        assertThat(contract.getString("providerType")).isEqualTo("BANK")

        val relief = incentives(party, id, contribution = 4000, period = "MONTH")
        assertThat(relief.getList<String>("incentiveId")).doesNotContain("state-contribution")
        assertThat(money(relief, "income-tax-deduction")).isEqualByComparingTo("48000.00")
        // The deduction cap is shared with DPS: a participant who used 30 000 there has 18 000 left.
        val shared = post(
            party,
            "$BASE/$id/incentive-evaluation",
            """{"contribution":4000,"period":"MONTH","sharedCapUsed":{"retirement-products-deduction":30000}}""",
        ).jsonPath()
        assertThat(money(shared, "income-tax-deduction")).isEqualByComparingTo("18000.00")

        // The DPS pack does not permit a bank as provider; the DIP pack does.
        assertThat(create(party, dps(monthly = 1000, providerType = "BANK")).statusCode).isEqualTo(400)
    }

    /** Scenario (f) — authorisation negatives: another customer sees a 404 on every route, never a 403 or the data. */
    @Test
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `another customer gets 404 on every route of a contract that is not theirs`() {
        val owner = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        val id = activeContract(owner, dps(monthly = 1700))

        assertThat(read(stranger, id).statusCode).isEqualTo(404)
        assertThat(post(stranger, "$BASE/$id/suspend").statusCode).isEqualTo(404)
        assertThat(post(stranger, "$BASE/$id/resume").statusCode).isEqualTo(404)
        assertThat(
            post(stranger, "$BASE/$id/incentive-evaluation", """{"contribution":1,"period":"MONTH"}""").statusCode,
        )
            .isEqualTo(404)
        assertThat(post(stranger, "$BASE/$id/early-termination", """{"currentValue":1,"confirm":true}""").statusCode)
            .isEqualTo(404)
        assertThat(
            given().contentType(JSON).header(PARTY, stranger.toString()).body("""{"strategyCode":"DYNAMIC"}""")
                .`when`().put("$BASE/$id/strategy").statusCode,
        ).isEqualTo(404)

        // The stranger's attempts changed nothing for the owner.
        val after = read(owner, id).jsonPath()
        assertThat(after.getString("status")).isEqualTo("ACTIVE")
        assertThat(after.getString("currentStrategy.strategyCode")).isEqualTo("BALANCED")
    }

    // ---------------------------------------------------------------------------------------------

    private fun dps(
        monthly: Int,
        providerType: String = "PENSION_COMPANY",
        beneficiaries: String = """[{"name":"Jane Doe","sharePercent":100}]""",
    ) = """
        {"productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}",
         "providerType":"$providerType","birthDate":"1985-05-05","residencyCountry":"CZ",
         "schedule":{"amount":$monthly,"currency":"CZK","frequency":"MONTHLY"},
         "strategyCode":"BALANCED","beneficiaries":$beneficiaries}
    """.trimIndent()

    private fun dip(providerType: String) = """
        {"productLine":"DIP","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}",
         "providerType":"$providerType","birthDate":"1979-03-14",
         "schedule":{"amount":4000,"currency":"CZK","frequency":"MONTHLY"},
         "strategyCode":"DYNAMIC","beneficiaries":[]}
    """.trimIndent()

    private fun create(party: UUID, body: String): Response = given().contentType(JSON)
        .header(PARTY, party.toString())
        .header(IDEMPOTENCY, UUID.randomUUID().toString())
        .body(body)
        .`when`().post(BASE)

    private fun activeContract(party: UUID, body: String): String {
        val created = create(party, body)
        assertThat(created.statusCode).describedAs(created.body.asString()).isEqualTo(201)
        val id = created.jsonPath().getString("contractId")
        assertThat(post(party, "$BASE/$id/submit").statusCode).isEqualTo(200)
        assertThat(post(party, "$BASE/$id/activate").statusCode).isEqualTo(200)
        return id
    }

    private fun post(party: UUID, path: String, body: String = "{}"): Response = given().contentType(JSON)
        .header(PARTY, party.toString())
        .header(IDEMPOTENCY, UUID.randomUUID().toString())
        .body(body)
        .`when`().post(path)

    private fun read(party: UUID, id: String): Response =
        given().header(PARTY, party.toString()).`when`().get("$BASE/$id")

    private fun incentives(party: UUID, id: String, contribution: Int, period: String): JsonPath {
        val response =
            post(party, "$BASE/$id/incentive-evaluation", """{"contribution":$contribution,"period":"$period"}""")
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(200)
        return response.jsonPath()
    }

    private fun money(results: JsonPath, incentiveId: String): BigDecimal =
        BigDecimal(results.getString("find { it.incentiveId == '$incentiveId' }.amount"))

    private fun clawback(preview: JsonPath, incentiveId: String, mode: String): BigDecimal {
        assertThat(preview.getString("clawbacks.find { it.incentiveId == '$incentiveId' }.mode")).isEqualTo(mode)
        return BigDecimal(preview.getString("clawbacks.find { it.incentiveId == '$incentiveId' }.amount"))
    }

    private companion object {
        const val BASE = "/api/v1/pension/contracts"
        const val JSON = "application/json"
        const val PARTY = "X-Customer-Party-Id"
        const val IDEMPOTENCY = "Idempotency-Key"
    }
}
