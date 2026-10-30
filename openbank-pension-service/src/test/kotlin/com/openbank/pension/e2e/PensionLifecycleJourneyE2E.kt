// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.e2e

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.infrastructure.fund.InMemoryFundAdministrationAdapter
import com.openbank.pension.integration.QuestionnaireApiIT
import com.openbank.pension.integration.StrategyCatalogWireMockResource
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ProviderFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.response.Response
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
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
 * Activation is the onboarding workflow's alone since S8 (KID, SCA signature, cooling-off); it is
 * journeyed in [PensionFullLifecycleJourneyE2E]. Contracts here are opened and submitted
 * through the use case (the S1 draft routes are retired, #12435) and activated by a repository FIXTURE, because this class's subject is what an active
 * contract does, not how it became active.
 *
 * The unit register, NAV and strategy administration live in pension-fund-service and are
 * journeyed by `PensionFundUnitJourneyE2E` there. This class covers what pension-service owns
 * today; each scenario's KDoc names the steps of #12350 that are NOT yet exercisable because the
 * slice that owns them has not landed, so a green run is never read as covering them.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(StrategyCatalogWireMockResource::class, restrictToAnnotatedClass = true)
@TestProfile(QuestionnaireApiIT.CatalogOidcEnabled::class)
class PensionLifecycleJourneyE2E {

    /**
     * Scenario (a) — a new DPS contract: onboard, activate, evaluate the state incentive the first
     * contribution earns. Not yet exercisable: catalog → simulate (the retirement pack is covered
     * in product-catalog, S6a), suitability questionnaire / KID / SCA signature (S2), contribution
     * booking and the state-agency claim/receipt (S3).
     */
    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `a new DPS contract is onboarded, activated and earns the capped state contribution`() {
        val party = UUID.randomUUID()
        // The S1 draft path is retired (#12435): a contract is born through onboarding, which
        // PensionFullLifecycleJourneyE2E journeys. Neither the create nor the submit route is served.
        assertThat(create(party, dps(monthly = 1700)).statusCode).isEqualTo(405)
        val id = draftByFixture(party, monthly = 1700)
        val draft = read(party, id).jsonPath()
        assertThat(draft.getString("status")).isEqualTo("DRAFT")
        assertThat(draft.getString("productLine")).isEqualTo("DPS")
        assertThat(draft.getString("jurisdiction")).isEqualTo("CZ")
        assertThat(draft.getInt("packVersion")).isEqualTo(2)
        assertThat(draft.getString("startDate")).isNull()

        assertThat(post(party, "$BASE/$id/submit").statusCode).isEqualTo(404)
        submitByFixture(party, id)
        assertThat(read(party, id).jsonPath().getString("status")).isEqualTo("PENDING_ACTIVATION")
        // The participant cannot activate it themselves (S8): activation is the onboarding
        // workflow's, journeyed in PensionFullLifecycleJourneyE2E (a1/a2). Fixture from here.
        assertThat(post(party, "$BASE/$id/activate").statusCode).isEqualTo(404)
        activateByFixture(id)
        val active = read(party, id)
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
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `a strategy change is appended to the history and the earlier election is kept`() {
        val party = UUID.randomUUID()
        val id = activeContract(party, monthly = 1000)

        // An S1 contract has no suitability assessment: a riskier strategy is refused even when
        // signed, and nothing is appended.
        val refused = given().contentType(JSON).header(PARTY, party.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"strategyCode":"DYNAMIC","scaChallengeId":"sca-${UUID.randomUUID()}"}""")
            .`when`().put("$BASE/$id/strategy")
        assertThat(refused.statusCode).describedAs(refused.body.asString()).isEqualTo(403)

        val stored = read(party, id).jsonPath()
        assertThat(stored.getString("currentStrategy.strategyCode")).isEqualTo("CONSERVATIVE")
        assertThat(stored.getList<String>("strategyHistory.strategyCode")).containsExactly("CONSERVATIVE")
        assertThat(stored.getString("status")).isEqualTo("ACTIVE")
    }

    /**
     * Scenario (d) — early termination: the S5 quote is binding and computed from the unit
     * register and the incentive ledger, never from caller-supplied values (S1's caller-valued
     * preview is retired). Quoting is a pure read: the contract stays ACTIVE until the participant
     * signs under SCA; signing and paying out are journeyed in PensionFullLifecycleJourneyE2E (d1).
     */
    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `early termination is quoted from the unit register and returns incentives, without moving the contract`() {
        val party = UUID.randomUUID()
        val id = activeContract(party, monthly = 1700)
        fund.setValue(UUID.fromString(id), BigDecimal("50000.00"))

        val retired = post(party, "$BASE/$id/early-termination", """{"currentValue":1,"confirm":true}""")
        assertThat(retired.statusCode).describedAs("the caller-valued S1 preview is gone").isEqualTo(404)

        val quoted = post(party, "$BASE/$id/exit/termination/quote")
        assertThat(quoted.statusCode).describedAs(quoted.body.asString()).isEqualTo(201)
        val q = quoted.jsonPath()
        assertThat(BigDecimal(q.getString("quote.redemptionValue"))).isEqualByComparingTo("50000")
        assertThat(BigDecimal(q.getString("quote.netPayout")))
            .isLessThanOrEqualTo(BigDecimal(q.getString("quote.redemptionValue")))
        assertThat(q.getString("status")).isEqualTo("QUOTED")
        assertThat(read(party, id).jsonPath().getString("status"))
            .describedAs("a quote must not move the contract")
            .isEqualTo("ACTIVE")
    }

    /**
     * Scenario (e) — regular payout (lump sum, phased) and death → beneficiary payouts. Neither is
     * exercisable before S5; what pension-service already guarantees is that the beneficiary
     * designation those payouts will read is stored as submitted.
     */
    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `beneficiary designations are stored as submitted for the later death payout`() {
        val party = UUID.randomUUID()
        val beneficiary = UUID.randomUUID()
        val id = activeContract(
            party,
            monthly = 1000,
            beneficiaries = listOf(
                Beneficiary("Jana Nova", beneficiary, BigDecimal("60")),
                Beneficiary("Petr Novy", null, BigDecimal("40")),
            ),
        )
        val stored = read(party, id).jsonPath()
        assertThat(stored.getList<String>("beneficiaries.name")).containsExactly("Jana Nova", "Petr Novy")
        assertThat(stored.getString("beneficiaries[0].partyId")).isEqualTo(beneficiary.toString())
        assertThat(stored.getList<Any>("beneficiaries.sharePercent").map { BigDecimal(it.toString()) })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal("60"), BigDecimal("40"))
    }

    /**
     * Scenario (f) — no direct draft at all: the S1 create route is retired (#12435), so neither a
     * DIP nor a bank-provided DPS contract can be opened around approved mapping and signed
     * onboarding. The method is refused (405) and nothing is stored.
     */
    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `a direct DIP draft is refused before onboarding and leaves no contract`() {
        val party = UUID.randomUUID()
        val created = create(party, dip(providerType = "BANK"))
        assertThat(created.statusCode).describedAs(created.body.asString()).isEqualTo(405)
        val contracts = given().header(PARTY, party.toString()).`when`().get(BASE)
        assertThat(contracts.statusCode).describedAs(contracts.body.asString()).isEqualTo(200)
        assertThat(contracts.jsonPath().getList<Any>("$")).isEmpty()

        assertThat(create(party, dps(monthly = 1000, providerType = "BANK")).statusCode).isEqualTo(405)
        assertThat(given().header(PARTY, party.toString()).`when`().get(BASE).jsonPath().getList<Any>("$")).isEmpty()
    }

    /** Scenario (f) — authorisation negatives: another customer sees a 404 on every route, never a 403 or the data. */
    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `another customer gets 404 on every route of a contract that is not theirs`() {
        val owner = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        val id = activeContract(owner, monthly = 1700)

        assertThat(read(stranger, id).statusCode).isEqualTo(404)
        assertThat(post(stranger, "$BASE/$id/suspend").statusCode).isEqualTo(404)
        assertThat(post(stranger, "$BASE/$id/resume").statusCode).isEqualTo(404)
        assertThat(
            post(stranger, "$BASE/$id/incentive-evaluation", """{"contribution":1,"period":"MONTH"}""").statusCode,
        )
            .isEqualTo(404)
        assertThat(post(stranger, "$BASE/$id/exit/termination/quote").statusCode).isEqualTo(404)
        assertThat(
            given().contentType(JSON).header(PARTY, stranger.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString()).body("""{"strategyCode":"DYNAMIC"}""")
                .`when`().put("$BASE/$id/strategy").statusCode,
        ).isEqualTo(404)

        // The stranger's attempts changed nothing for the owner.
        val after = read(owner, id).jsonPath()
        assertThat(after.getString("status")).isEqualTo("ACTIVE")
        assertThat(after.getString("currentStrategy.strategyCode")).isEqualTo("CONSERVATIVE")
    }

    // ---------------------------------------------------------------------------------------------

    private fun dps(
        monthly: Int,
        providerType: String = "PENSION_COMPANY",
        beneficiaries: String = """[{"name":"Jane Doe","sharePercent":100}]""",
    ) = """
        {"productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${ProviderFixtures.ID}",
         "providerType":"$providerType","birthDate":"1985-05-05","residencyCountry":"CZ",
         "schedule":{"amount":$monthly,"currency":"CZK","frequency":"MONTHLY"},
         "strategyCode":"CONSERVATIVE","beneficiaries":$beneficiaries}
    """.trimIndent()

    private fun dip(providerType: String) = """
        {"productLine":"DIP","jurisdiction":"CZ","providerEntityId":"${ProviderFixtures.ID}",
         "providerType":"$providerType","birthDate":"1979-03-14",
         "schedule":{"amount":4000,"currency":"CZK","frequency":"MONTHLY"},
         "strategyCode":"CONSERVATIVE","beneficiaries":[]}
    """.trimIndent()

    private fun create(party: UUID, body: String): Response = given().contentType(JSON)
        .header(PARTY, party.toString())
        .header(IDEMPOTENCY, UUID.randomUUID().toString())
        .body(body)
        .`when`().post(BASE)

    private fun <T> onVertx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    private fun activeContract(
        party: UUID,
        monthly: Int,
        beneficiaries: List<Beneficiary> = listOf(Beneficiary("Jane Doe", null, BigDecimal("100"))),
    ): String {
        val id = draftByFixture(party, monthly, beneficiaries)
        submitByFixture(party, id)
        activateByFixture(id)
        return id
    }

    /** Fixture: the S1 create route is retired (#12435); the use case still opens a draft. */
    private fun draftByFixture(
        party: UUID,
        monthly: Int,
        beneficiaries: List<Beneficiary> = listOf(Beneficiary("Jane Doe", null, BigDecimal("100"))),
    ): String = onVertx {
        useCase.createDraft(
            CreateDraftCommand(
                participantPartyId = party, productLine = ProductLine.DPS, jurisdiction = "CZ",
                providerEntityId = ProviderFixtures.ID, providerType = ProviderType.PENSION_COMPANY,
                birthDate = LocalDate.parse("1985-05-05"), residencyCountry = "CZ", residencyEvidence = emptySet(),
                hasGuardian = false,
                schedule = ContributionSchedule(BigDecimal(monthly), "CZK", ContributionFrequency.MONTHLY),
                initialStrategy = "CONSERVATIVE", beneficiaries = beneficiaries,
                idempotencyKey = UUID.randomUUID().toString(),
            ),
        ).id.toString()
    }

    private fun submitByFixture(party: UUID, id: String) {
        onVertx { useCase.submit(Caller.customer(party), UUID.fromString(id)) }
    }

    @Inject
    lateinit var useCase: PensionContractUseCase

    @Inject
    lateinit var contracts: PensionContractRepository

    @Inject
    lateinit var fund: InMemoryFundAdministrationAdapter

    /** Fixture: the product activates only through onboarding (S8); see the class KDoc. */
    private fun activateByFixture(id: String) {
        onVertx {
            val pending = requireNotNull(contracts.findById(UUID.fromString(id)))
            contracts.save(pending.activate(LocalDate.now(), Instant.now()))
        }
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

    private companion object {
        const val BASE = "/api/v2/pension/contracts"
        const val JSON = "application/json"
        const val PARTY = "X-Customer-Party-Id"
        const val IDEMPOTENCY = "Idempotency-Key"
    }
}
