// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.interest.application.port.out.CapitalizationPosting
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.WithholdingTaxPolicy
import com.openbank.interest.domain.tax.WithholdingTreatment
import com.openbank.interest.infrastructure.client.CapitalizationJournalFactory
import com.openbank.interest.infrastructure.client.InterestLedgerConfig
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer-driven contract for the journal posting `openbank-interest-service` makes when it
 * capitalizes credit interest (ADR-0033 §D: `InterestService.capitalize` → [LedgerPostingPort]
 * [com.openbank.interest.application.port.out.LedgerPostingPort] →
 * [com.openbank.interest.infrastructure.client.RestLedgerPostingAdapter] →
 * `LedgerRestClient.postJournal`). Interest-service is the fifth ledger-posting consumer, after
 * transaction-, lending-, billing- and settlement-service, and it uses the same single ingestion
 * surface: `POST /api/v1/journals` → 201 `{id, transactionId, status}`.
 *
 * ### Why this test does not look like the other four
 *
 * The existing `*LedgerPostJournalPactConsumerTest`s hand-write their request body as a JSON string
 * literal and POST *that* with RestAssured; their journal factory is never invoked, so the contract
 * pins **what the test author typed**, not what the service emits. That is not a contract on the
 * consumer — a factory defect (wrong GL account, missing `subAccountId`, an over-scaled amount)
 * passes provider verification untouched and ships. Issue #1347 tracks retro-fitting them.
 *
 * This test derives the body from the REAL [CapitalizationJournalFactory] instead: it builds a
 * domain [CapitalizationPosting] the way `InterestService.capitalizeSet` builds one (raw accrual sum
 * → gross at the currency's scale → [WithholdingTaxPolicy] → net), calls the production factory, and
 * serializes THAT `PostJournalRequest` as the pact's request body. Nothing about the JSON is retyped
 * — if the factory changes what it emits, the pact changes with it and
 * `.github/workflows/pact-drift-check.yml` fails until the committed pact is regenerated and the
 * ledger re-verifies it.
 *
 * This is not theoretical for this service: the credit leg's first cut handed the port three bare
 * `BigDecimal`s at scale 4, and ledger-service re-wraps every incoming line as
 * `Money.of(amount, currencyCode)` — which rejects scale > the currency's minor units. Every
 * money-bearing capitalization would have 400'd at the boundary. A hand-written `"amount": 100.00`
 * body would have recorded a green contract for a service that could not post a single journal.
 *
 * ### Provider state
 *
 * "the standard chart of accounts is seeded" — the SAME state the transaction-/lending-/billing-/
 * settlement-service pacts already use, and it is already implemented (as a no-op, correctly: the
 * Flyway migrations seed the chart into the fresh Testcontainer DB) on **both** ledger-side
 * verification classes, `LedgerPactProviderVerificationTest` (git-pact/`@PactFolder`) and
 * `LedgerPactBrokerProviderVerificationTest` (broker). No new state is introduced, so there is
 * nothing that could end up on only one of them (#1198). The four GL accounts these interactions
 * post against are seeded by `V17__interest_capitalization_accounts.sql`, which is part of the same
 * ledger migration set and therefore part of the same "standard chart".
 *
 * ### Regenerating
 *
 * `./gradlew :openbank-interest-service:test --tests "*InterestLedgerPostJournalPactConsumerTest*"`
 * rewrites `pacts/openbank-interest-service-openbank-ledger-service.json`; commit it in the same PR.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class InterestLedgerPostJournalPactConsumerTest {

    /**
     * The three-leg case: a CZ-resident individual's CZK savings interest, withheld at 15 %.
     * Dr 4010 interest expense (gross) / Cr 2100 deposit control (net, `subAccountId` = the
     * customer's account) / Cr 2200 withholding-tax payable (tax).
     */
    private val withheldPosting = posting(accruedSum = "100.004321", accountId = WITHHELD_ACCOUNT_ID)

    /**
     * The two-leg case, and a real one rather than a contrivance: the statutory whole-CZK DOWN
     * rounding (daňový řád) means any gross below 7.00 CZK assesses `floor(gross) × 0.15 = 0` while
     * the treatment is still `WITHHELD`. The ledger enforces `CHECK (amount > 0)` per line, so
     * emitting the tax leg at zero would fail the whole entry — including the customer's legitimate
     * credit. The factory omits it; this contract records that it does.
     *
     * A DIFFERENT customer to the withheld case on purpose. The factory derives both the idempotency
     * key and the `transactionId` from the business identity (account, product, period end), so two
     * fixtures sharing an account would ship two interactions carrying the same key — and the
     * provider would replay the second onto the journal the first already booked, verifying the
     * three-leg entry twice and the two-leg entry never.
     */
    private val zeroTaxPosting = posting(accruedSum = "6.994321", accountId = ZERO_TAX_ACCOUNT_ID)

    private val withheldCzkBody = bodyOf(withheldPosting)
    private val zeroTaxCzkBody = bodyOf(zeroTaxPosting)

    @Pact(consumer = "openbank-interest-service", provider = "openbank-ledger-service")
    fun postWithheldCapitalizationJournalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("POST a balanced three-line CZK interest-capitalization journal with withholding tax")
        .path("/api/v1/journals")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(withheldCzkBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.uuid("transactionId")
                o.stringValue("status", "POSTED")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-interest-service", provider = "openbank-ledger-service")
    fun postZeroTaxCapitalizationJournalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("POST a balanced two-line CZK interest-capitalization journal with no withholding-tax leg")
        .path("/api/v1/journals")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(zeroTaxCzkBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.uuid("transactionId")
                o.stringValue("status", "POSTED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "postWithheldCapitalizationJournalPact")
    fun `postJournal accepts the withheld three-leg split and returns the created journal`(mockServer: MockServer) {
        // Pins the fixture as the case it claims to be: a fixture that silently stopped exercising
        // withholding would keep this contract green while contracting nothing.
        assertThat(CapitalizationJournalFactory.buildLines(withheldPosting, LedgerConfigFixture)).hasSize(3)

        val body = postJournal(mockServer, withheldCzkBody)

        assertThat(body.getString("id")).isNotBlank()
        assertThat(body.getString("transactionId")).isNotBlank()
        assertThat(body.getString("status")).isNotBlank()
    }

    @Test
    @PactTestFor(pactMethod = "postZeroTaxCapitalizationJournalPact")
    fun `postJournal accepts the zero-tax two-leg split and returns the created journal`(mockServer: MockServer) {
        // The tax is zero, but the treatment is still WITHHELD — floor(6.99) * 0.15 simply rounds
        // to 0 — and the factory must drop the leg rather than post it at zero.
        val assessed = WithholdingTaxPolicy.compute(BigDecimal("6.99"), "CZK", TAX_PROFILE, PERIOD_TO)
        assertThat(assessed.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
        assertThat(assessed.taxAmount).isEqualByComparingTo(BigDecimal.ZERO)

        val lines = CapitalizationJournalFactory.buildLines(zeroTaxPosting, LedgerConfigFixture)
        assertThat(lines).hasSize(2)
        assertThat(lines).`as`("a zero leg must never be emitted").allMatch { it.amount.signum() > 0 }

        val body = postJournal(mockServer, zeroTaxCzkBody)

        assertThat(body.getString("id")).isNotBlank()
        assertThat(body.getString("transactionId")).isNotBlank()
        assertThat(body.getString("status")).isNotBlank()
    }

    private fun postJournal(mockServer: MockServer, body: String) = given()
        .baseUri(mockServer.getUrl())
        .contentType("application/json")
        .body(body)
        .post("/api/v1/journals")
        .then()
        .statusCode(201)
        .extract().jsonPath()

    /**
     * Builds the posting the way `InterestService.capitalizeSet` does — from a raw scale-6 accrual
     * sum, rounded ONCE to the currency's scale, with tax and net derived by the real
     * [WithholdingTaxPolicy]. Retyping `gross`/`tax`/`net` here would reintroduce, one layer down,
     * exactly the "the author typed it" defect this test exists to avoid.
     */
    private fun posting(accruedSum: String, accountId: UUID, currency: String = "CZK"): CapitalizationPosting {
        val ccy = CurrencyCode.of(currency)
        val gross = BigDecimal(accruedSum).setScale(ccy.defaultFractionDigits, RoundingMode.HALF_UP)
        val assessed = WithholdingTaxPolicy.compute(gross, ccy.code, TAX_PROFILE, PERIOD_TO)
        return CapitalizationPosting(
            accountId = accountId,
            productId = PRODUCT_ID,
            periodTo = PERIOD_TO,
            gross = Money(gross, ccy),
            // Whole CZK by statute (scale 0), so the wire carries `15`, not `15.00`.
            tax = Money(assessed.taxAmount, ccy),
            net = Money(assessed.netAmount.setScale(ccy.defaultFractionDigits, RoundingMode.HALF_UP), ccy),
        )
    }

    /**
     * The exact bytes `LedgerRestClient.postJournal` puts on the wire: the production factory's
     * `PostJournalRequest`, serialized by Jackson with the Kotlin module — the same combination
     * Quarkus REST Client Reactive uses for this DTO (plain scalars only: String/UUID/BigDecimal/
     * List, no dates, so no Quarkus-specific ObjectMapper customization applies).
     * `WRITE_BIGDECIMAL_AS_PLAIN` keeps the amounts' scale visible on the wire as the ledger's
     * `Money.of` will read it.
     */
    private fun bodyOf(posting: CapitalizationPosting): String =
        MAPPER.writeValueAsString(CapitalizationJournalFactory.buildRequest(posting, LedgerConfigFixture))

    private companion object {
        private val WITHHELD_ACCOUNT_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val ZERO_TAX_ACCOUNT_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private const val PRODUCT_ID = "SAVINGS_CZK"
        private val PERIOD_TO: LocalDate = LocalDate.of(2026, 1, 20)

        /** ADR-0033 §C: the fiscally conservative default — a CZ-resident individual, withheld 15 %. */
        private val TAX_PROFILE = TaxProfile.FAIL_SAFE_DEFAULT

        private val MAPPER = jacksonObjectMapper().enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
    }

    /**
     * `InterestLedgerConfig`'s `@WithDefault` values, restated: a `@ConfigMapping` cannot be
     * instantiated outside Quarkus, and this test is deliberately HTTP-free apart from the pact mock
     * server. Drift between these and the real defaults is caught where it matters rather than here
     * — the pact body pins these ids, and ledger-service's provider verification replays it against
     * a Testcontainer DB with `V17__interest_capitalization_accounts.sql` applied, so a wrong id is
     * a failed verification, not a green test.
     */
    private object LedgerConfigFixture : InterestLedgerConfig {
        override fun systemActorId(): UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
        override fun gl(): InterestLedgerConfig.Gl = GlFixture

        object GlFixture : InterestLedgerConfig.Gl {
            override fun interestExpenseCzk(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004010")
            override fun interestExpenseEur(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004011")
            override fun interestExpenseUsd(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004012")
            override fun interestExpenseGbp(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004013")
            override fun withholdingTaxPayable(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000002200")
        }
    }
}
