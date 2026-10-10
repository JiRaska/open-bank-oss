// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArray
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.domain.model.BalanceVerdict
import com.openbank.finrep.infrastructure.client.ClosedPeriodResponse
import com.openbank.finrep.infrastructure.client.ClosedPeriodTrialBalanceResponse
import com.openbank.finrep.infrastructure.client.LedgerRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Consumer-driven contract for the ledger GL trial balance that finrep's FINREP/COREP template
 * rendering reads (ADR-0097). The generated pact is committed to `pacts/` (git-pact pattern,
 * ADR-0063) and replayed by `LedgerPactProviderVerificationTest` (`@PactFolder("../pacts")`) in
 * openbank-ledger-service — that test always runs, no Pact Broker involved.
 *
 * **Why this test exists (#2269):** finrep's client pointed at `/api/v1/ledger/trial-balance`,
 * which openbank-ledger-service does not serve, so every F01.01 / F02.00 / C 01.00 render failed
 * against a real ledger. Nothing caught it: `FinrepResourceTest`/`CorepResourceTest` construct the
 * resource directly and the use-case tests mock `LedgerPort` — a mocked port cannot have a wrong
 * URL — and the response DTO happened to be correct, so the code read as working. The one-line
 * path fix on its own would regress just as silently; this pact is what keeps it fixed.
 *
 * **The two sides are deliberately sourced differently.** The interaction declares the contract as
 * a literal ([LEDGER_TRIAL_BALANCE_PATH]); the REQUEST is issued against [ledgerTrialBalancePath],
 * derived by reflection from [LedgerRestClient]'s own `@Path` annotations (and [asOfQueryParam] from
 * its `@QueryParam`). That asymmetry is the whole mechanism: the pact mock server fails the test
 * when the production client is annotated with anything other than the contract path, and the
 * provider-side replay of the committed pact fails if ledger ever moves the endpoint.
 *
 * Deriving BOTH sides from the annotation — the first cut of this test — is worthless and looks
 * identical: expectation and request move together, so it passed against the #2269 path. See the
 * note on [LEDGER_TRIAL_BALANCE_PATH].
 *
 * IMPORTANT — regenerate on change: if this test's `@Pact` methods change (new interaction,
 * different matcher, renamed field), re-run this test (`./gradlew :openbank-finrep-service:test
 * --tests "*LedgerTrialBalancePactConsumerTest*"`) and commit the updated
 * `pacts/openbank-finrep-service-openbank-ledger-service.json` in the same PR — an un-regenerated
 * pact file silently verifies the OLD contract on the provider side. `pact-drift-check.yml`
 * (ADR-0063 Phase 2, issue #468) enforces this: it regenerates every consumer pact and fails on
 * `git diff -- pacts/`. Note what that gate can and cannot see — its only assertion is the diff,
 * so a module it does not regenerate does not read as *unchecked*, it reads as *passing*. Its
 * scope is therefore DERIVED, by `.github/scripts/derive-pact-drift-scope.sh`, from the
 * `@Pact(consumer = .., provider = ..)` annotations themselves; a consumer test in a new module
 * needs no workflow edit, and a pact nothing regenerates fails the derivation instead of going
 * quietly green.
 *
 * Note on `minArrayLike(0, 1)` vs `eachLike`: the provider test runs against a fresh Testcontainer
 * DB with no seeded journal entries. `eachLike` implies min=1, which fails when the DB is empty.
 * `minArrayLike(0, 1)` sets min=0 so the provider passes with zero or more lines; the element
 * template still fixes the required field types for a non-empty response, and finrep's mappers fold
 * over an empty list to an all-zero template.
 *
 * Only the four line fields finrep actually consumes (`code`, `type`, `net`, `currency`) are
 * declared — a consumer contract must not pin provider fields it does not read (`glAccountId`,
 * `name`, `totalDebit`, `totalCredit` are ledger's business). `currency` moved from the second
 * list to the first with issue #5987, when the balance check began evaluating the double-entry
 * identity per currency.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class LedgerTrialBalancePactConsumerTest {

    private val json = jacksonObjectMapper().findAndRegisterModules()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun trialBalanceWithEntriesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has frozen monthly trial balance for the reporting date")
        .uponReceiving("GET the frozen GL closing balance for a FINREP reporting date")
        .path("$LEDGER_MONTH_TRIAL_BALANCE_PATH/$REPORTING_DATE/frozen-closing-balance")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // stringValue, NOT stringType (issue #2425): `asOf` is echoed from the
                // `asOf=` query parameter this interaction pins by literal. A trial balance
                // returned for a DIFFERENT date than the one asked for is a reporting defect of
                // the first order, and a type matcher accepted any date string at all.
                o.stringValue("period", "MONTH:2000-06")
                o.booleanType("balanced", true)
                // stringType on `code`/{currency,type}, DELIBERATELY (issue #2425): `lines`
                // is a heterogeneous list — a real trial balance carries many GL codes, several
                // currencies and every account type. Pinning an element value would assert
                // "every line is this one code", a claim the contract does not make and real
                // data would immediately falsify. The array is min=0 here anyway, so a pin
                // would also be vacuous against the empty-DB provider.
                o.minArrayLike("lines", 0, 1) { line ->
                    line.stringType("code", "1100-CASH-CLEARING-CZK")
                    line.stringType("type", "ASSET")
                    line.decimalType("net", 150_000.00)
                    // Added with issue #5987: finrep now READS `currency`, because the double-entry
                    // identity behind `isBalanced` holds per currency and a global sum would let a
                    // CZK line cancel a lost EUR one. A consumer pact must pin exactly the fields
                    // the consumer consumes — no more (that was the rule when this line was absent)
                    // and no fewer (the client's `currency` is non-nullable, so a provider that
                    // stopped sending it would fail deserialization at runtime, not here).
                    line.stringType("currency", "CZK")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun livePreviewTrialBalancePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has frozen monthly trial balance for the reporting date")
        .uponReceiving("GET the mutable GL closing balance for an internal working preview")
        .path("$LEDGER_MONTH_TRIAL_BALANCE_PATH/$REPORTING_DATE/closing-balance")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("period", "MONTH:2000-06")
                o.booleanType("balanced", true)
                o.minArrayLike("lines", 0, 1) { line ->
                    line.stringType("code", "1100-CASH-CLEARING-CZK")
                    line.stringType("type", "ASSET")
                    line.decimalType("net", 150_000.00)
                    line.stringType("currency", "CZK")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun frozenPeriodMovementsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has frozen monthly trial balance for the reporting date")
        .uponReceiving("GET the frozen monthly GL movements for F02 profit and loss")
        .path("$LEDGER_MONTH_TRIAL_BALANCE_PATH/$REPORTING_DATE/frozen-trial-balance")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(periodMovementsBody())
        .toPact()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun livePeriodMovementsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has frozen monthly trial balance for the reporting date")
        .uponReceiving("GET the mutable monthly GL movements for F02 working preview")
        .path("$LEDGER_MONTH_TRIAL_BALANCE_PATH/$REPORTING_DATE/trial-balance")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(periodMovementsBody())
        .toPact()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun frozenYearToDateMovementsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has frozen monthly trial balance for the reporting date")
        .uponReceiving("GET frozen January-to-date movements for FINREP F02")
        .path("$LEDGER_MONTH_TRIAL_BALANCE_PATH/$REPORTING_DATE/frozen-year-to-date-trial-balance")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(yearToDateMovementsBody(frozen = true))
        .toPact()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun liveYearToDateMovementsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has frozen monthly trial balance for the reporting date")
        .uponReceiving("GET mutable January-to-date movements for FINREP F02 preview")
        .path("$LEDGER_MONTH_TRIAL_BALANCE_PATH/$REPORTING_DATE/year-to-date-trial-balance")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(yearToDateMovementsBody(frozen = false))
        .toPact()

    private fun yearToDateMovementsBody(frozen: Boolean) = newJsonBody { o ->
        o.stringValue("period", "MONTH:2000-06")
        o.stringValue("from", "2000-01-01")
        o.stringValue("to", REPORTING_DATE)
        o.booleanType("balanced", true)
        if (frozen) {
            o.array("sourcePeriods") { periods ->
                (1..6).forEach { month -> periods.stringValue("MONTH:2000-%02d".format(month)) }
            }
            o.array("sourceContentHashes") { hashes ->
                (1..6).forEach { hashes.stringType("0".repeat(64)) }
            }
        }
        o.minArrayLike("lines", 0, 1) { line ->
            line.stringType("code", "1100")
            line.stringType("type", "ASSET")
            line.decimalType("net", 100.0)
            line.stringType("currency", "CZK")
        }
    }.build()

    private fun periodMovementsBody() = newJsonBody { o ->
        o.stringValue("period", "MONTH:2000-06")
        o.booleanType("balanced", true)
        o.minArrayLike("lines", 0, 1) { line ->
            line.stringType("code", "4100-INTEREST-INCOME-CZK")
            line.stringType("type", "INCOME")
            line.decimalType("net", -150_000.00)
            line.stringType("currency", "CZK")
        }
    }.build()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun closedPeriodsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has frozen monthly trial balance for the reporting date")
        .uponReceiving("GET closed periods available for regulatory reporting")
        .path(LEDGER_PERIODS_PATH)
        .query("from=$CLOSED_PERIODS_FROM&to=$CLOSED_PERIODS_TO")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonArray { a ->
                (1..6).forEach { month ->
                    a.`object` { period ->
                        period.stringValue("periodType", "MONTH")
                        period.stringValue("to", java.time.YearMonth.of(2000, month).atEndOfMonth().toString())
                        period.stringValue("status", "FROZEN")
                        period.stringValue("evidenceState", "LINES_V1")
                    }
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "trialBalanceWithEntriesPact")
    fun `the ledger client contract yields the fields the FINREP mappers consume`(mockServer: MockServer) {
        // Guards the reflection helpers themselves: were they ever to return an empty or partial
        // path, the interaction and the request would still agree with each other and both pact
        // tests would pass against a contract that pins nothing.
        assertThat(ledgerTrialBalancePath).isEqualTo("$LEDGER_MONTH_TRIAL_BALANCE_PATH/{asOf}/frozen-closing-balance")

        val response = fetchTrialBalance(mockServer, REPORTING_DATE)

        assertThat(response.period).isNotBlank()
        assertThat(response.balanced).isTrue()
        // The three fields LedgerAdapter maps into TrialBalanceLineDto must all deserialize.
        assertThat(response.lines).isNotEmpty()
        val line = response.lines.first()
        assertThat(line.code).isNotBlank()
        assertThat(line.type).isNotBlank()
        assertThat(line.net).isNotNull()
        assertThat(line.currency).isNotBlank()

        // ... and the mapper must accept them: proves the contract feeds a real F01.01 render,
        // not merely that some JSON parsed.
        val template = F0101Mapper.map(
            TrialBalanceSnapshot(
                lines = response.lines.map {
                    TrialBalanceLineDto(code = it.code, accountType = it.type, net = it.net, currency = it.currency)
                },
                ledgerReportsBalanced = response.balanced,
            ),
            LocalDate.parse(REPORTING_DATE),
        )
        assertThat(template.cells.map { it.rowRef }).contains("r0380")
        assertThat(template.cells.single { it.rowRef == "r0380" }.value).isEqualByComparingTo(BigDecimal("150000.00"))
        // The pact's single ASSET line does not tie out on its own, so the contract also proves the
        // balance check runs over real contract data and can answer `false` (issue #5987) — it is
        // not merely unit-testable against hand-built fixtures.
        assertThat(template.isBalanced).isFalse()
        // And the contract body is itself a disagreement: it declares `balanced: true` alongside a
        // lines array that does not tie out. That is exactly the wire shape a truncated response
        // has — a producer verdict computed over lines the consumer did not all receive — so the
        // cross-check of issue #6011 is exercised against contract data rather than a fixture.
        assertThat(response.balanced).isTrue()
        assertThat(template.balanceVerdict).isEqualTo(BalanceVerdict.SOURCES_DISAGREE)
    }

    @Test
    @PactTestFor(pactMethod = "closedPeriodsPact")
    fun `the ledger client exposes immutable reporting period eligibility`(mockServer: MockServer) {
        assertThat(ledgerPeriodsPath).isEqualTo(LEDGER_PERIODS_PATH)

        val body = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam(closedPeriodsFromParam, CLOSED_PERIODS_FROM)
            .queryParam(closedPeriodsToParam, CLOSED_PERIODS_TO)
            .get(ledgerPeriodsPath)
            .then()
            .statusCode(200)
            .extract().body().asString()
        val periods: List<ClosedPeriodResponse> = json.readValue(body)

        assertThat(periods).hasSize(6)
        val period = periods.last()
        assertThat(period.periodType).isEqualTo("MONTH")
        assertThat(period.to).isEqualTo(LocalDate.parse(REPORTING_DATE))
        assertThat(period.status).isEqualTo("FROZEN")
        assertThat(period.evidenceState).isEqualTo("LINES_V1")
    }

    @Test
    @PactTestFor(pactMethod = "livePreviewTrialBalancePact")
    fun `the ledger client uses a distinct explicit path for the mutable working preview`(mockServer: MockServer) {
        assertThat(ledgerLiveTrialBalancePath)
            .isEqualTo("$LEDGER_MONTH_TRIAL_BALANCE_PATH/{asOf}/closing-balance")

        given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .get(ledgerLiveTrialBalancePath.replace("{asOf}", REPORTING_DATE))
            .then()
            .statusCode(200)
    }

    @Test
    @PactTestFor(pactMethod = "frozenPeriodMovementsPact")
    fun `F02 frozen movement client path is pinned independently of closing stock`(mockServer: MockServer) {
        assertThat(ledgerFrozenMovementsPath)
            .isEqualTo("$LEDGER_MONTH_TRIAL_BALANCE_PATH/{asOf}/frozen-trial-balance")
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(ledgerFrozenMovementsPath.replace("{asOf}", REPORTING_DATE))
            .then().statusCode(200)
    }

    @Test
    @PactTestFor(pactMethod = "livePeriodMovementsPact")
    fun `F02 live movement client path is pinned independently of closing stock`(mockServer: MockServer) {
        assertThat(ledgerLiveMovementsPath)
            .isEqualTo("$LEDGER_MONTH_TRIAL_BALANCE_PATH/{asOf}/trial-balance")
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(ledgerLiveMovementsPath.replace("{asOf}", REPORTING_DATE))
            .then().statusCode(200)
    }

    @Test
    @PactTestFor(pactMethod = "frozenYearToDateMovementsPact")
    fun `F02 frozen YTD client path is pinned`(mockServer: MockServer) {
        assertThat(ledgerFrozenYearToDatePath)
            .isEqualTo("$LEDGER_MONTH_TRIAL_BALANCE_PATH/{asOf}/frozen-year-to-date-trial-balance")
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(ledgerFrozenYearToDatePath.replace("{asOf}", REPORTING_DATE)).then().statusCode(200)
    }

    @Test
    @PactTestFor(pactMethod = "liveYearToDateMovementsPact")
    fun `F02 live YTD client path is pinned`(mockServer: MockServer) {
        assertThat(ledgerLiveYearToDatePath)
            .isEqualTo("$LEDGER_MONTH_TRIAL_BALANCE_PATH/{asOf}/year-to-date-trial-balance")
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(ledgerLiveYearToDatePath.replace("{asOf}", REPORTING_DATE)).then().statusCode(200)
    }

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun anonymousClosingPact(builder: PactDslWithProvider): RequestResponsePact =
        anonymousReadPact(builder, "frozen-closing-balance")

    @Test
    @PactTestFor(pactMethod = "anonymousClosingPact")
    fun `anonymous frozen-closing-balance read is refused 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(ledgerTrialBalancePath.replace("{asOf}", REPORTING_DATE)).then().statusCode(401)
    }

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun anonymousLiveClosingPact(builder: PactDslWithProvider): RequestResponsePact =
        anonymousReadPact(builder, "closing-balance")

    @Test
    @PactTestFor(pactMethod = "anonymousLiveClosingPact")
    fun `anonymous closing-balance read is refused 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(ledgerLiveTrialBalancePath.replace("{asOf}", REPORTING_DATE)).then().statusCode(401)
    }

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun anonymousYtdPact(builder: PactDslWithProvider): RequestResponsePact =
        anonymousReadPact(builder, "frozen-year-to-date-trial-balance")

    @Test
    @PactTestFor(pactMethod = "anonymousYtdPact")
    fun `anonymous frozen-year-to-date-trial-balance read is refused 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(ledgerFrozenYearToDatePath.replace("{asOf}", REPORTING_DATE)).then().statusCode(401)
    }

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun anonymousLiveYtdPact(builder: PactDslWithProvider): RequestResponsePact =
        anonymousReadPact(builder, "year-to-date-trial-balance")

    @Test
    @PactTestFor(pactMethod = "anonymousLiveYtdPact")
    fun `anonymous year-to-date-trial-balance read is refused 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).accept("application/json")
            .get(ledgerLiveYearToDatePath.replace("{asOf}", REPORTING_DATE)).then().statusCode(401)
    }

    private fun anonymousReadPact(builder: PactDslWithProvider, suffix: String): RequestResponsePact = builder
        // Existing provider verifier replays this state without @TestSecurity.
        .given("no valid M2M identity is presented")
        .uponReceiving("GET $suffix for FINREP without an authenticated identity")
        .path("$LEDGER_MONTH_TRIAL_BALANCE_PATH/$REPORTING_DATE/$suffix")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(401)
        .toPact()

    /** Issues the request against the path the production client is annotated with. */
    private fun fetchTrialBalance(mockServer: MockServer, asOf: String): ClosedPeriodTrialBalanceResponse {
        val body = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .get(ledgerTrialBalancePath.replace("{asOf}", asOf))
            .then()
            .statusCode(200)
            .extract().body().asString()
        return json.readValue(body)
    }

    private companion object {
        /**
         * The contract, written out LITERALLY on purpose: openbank-ledger-service serves the GL
         * trial balance here (`operationId: getTrialBalance` in its `openapi.yaml`).
         *
         * It must NOT be derived from [LedgerRestClient] like [ledgerTrialBalancePath] is. Deriving
         * both sides from the same annotation makes the interaction and the request move together,
         * so the pact mock server always sees exactly what it expects and the test passes against a
         * client pointing anywhere at all — verified by temporarily deleting the guard assertion
         * below and re-running against the #2269 path: it went green. The literal is the fixed point
         * the annotation is measured against.
         */
        const val LEDGER_MONTH_TRIAL_BALANCE_PATH = "/api/v1/ledger/periods/MONTH"
        const val LEDGER_PERIODS_PATH = "/api/v1/ledger/periods"
        const val CLOSED_PERIODS_FROM = "1970-01-01"
        const val CLOSED_PERIODS_TO = "9999-12-31"

        /** Ledger's query-parameter name for the as-of date — likewise literal. */
        /** A FINREP quarter-end reference date. */
        const val REPORTING_DATE = "2000-06-30"

        private val getTrialBalanceMethod =
            LedgerRestClient::class.java.getDeclaredMethod("getTrialBalance", String::class.java)
        private val getLiveTrialBalanceMethod =
            LedgerRestClient::class.java.getDeclaredMethod("getLiveTrialBalance", String::class.java)
        private val getFrozenMovementsMethod =
            LedgerRestClient::class.java.getDeclaredMethod("getFrozenPeriodMovements", String::class.java)
        private val getLiveMovementsMethod =
            LedgerRestClient::class.java.getDeclaredMethod("getLivePeriodMovements", String::class.java)
        private val getFrozenYearToDateMethod =
            LedgerRestClient::class.java.getDeclaredMethod("getYearToDateMovements", String::class.java)
        private val getLiveYearToDateMethod =
            LedgerRestClient::class.java.getDeclaredMethod("getLiveYearToDateMovements", String::class.java)
        private val listClosedPeriodsMethod = LedgerRestClient::class.java.getDeclaredMethod(
            "listClosedPeriods",
            String::class.java,
            String::class.java,
        )

        /**
         * `@Path` on the interface + `@Path` on the method, joined the way JAX-RS resolves them.
         * Read from the production client so the contract cannot drift from the code that calls it.
         */
        val ledgerTrialBalancePath: String = listOf(
            LedgerRestClient::class.java.getAnnotation(Path::class.java).value,
            getTrialBalanceMethod.getAnnotation(Path::class.java).value,
        ).joinToString("/") { it.trim('/') }.let { "/$it" }

        val ledgerLiveTrialBalancePath: String = listOf(
            LedgerRestClient::class.java.getAnnotation(Path::class.java).value,
            getLiveTrialBalanceMethod.getAnnotation(Path::class.java).value,
        ).joinToString("/") { it.trim('/') }.let { "/$it" }

        val ledgerFrozenMovementsPath: String = listOf(
            LedgerRestClient::class.java.getAnnotation(Path::class.java).value,
            getFrozenMovementsMethod.getAnnotation(Path::class.java).value,
        ).joinToString("/") { it.trim('/') }.let { "/$it" }

        val ledgerLiveMovementsPath: String = listOf(
            LedgerRestClient::class.java.getAnnotation(Path::class.java).value,
            getLiveMovementsMethod.getAnnotation(Path::class.java).value,
        ).joinToString("/") { it.trim('/') }.let { "/$it" }

        val ledgerFrozenYearToDatePath: String = listOf(
            LedgerRestClient::class.java.getAnnotation(Path::class.java).value,
            getFrozenYearToDateMethod.getAnnotation(Path::class.java).value,
        ).joinToString("/") { it.trim('/') }.let { "/$it" }

        val ledgerLiveYearToDatePath: String = listOf(
            LedgerRestClient::class.java.getAnnotation(Path::class.java).value,
            getLiveYearToDateMethod.getAnnotation(Path::class.java).value,
        ).joinToString("/") { it.trim('/') }.let { "/$it" }

        val ledgerPeriodsPath: String = LedgerRestClient::class.java.getAnnotation(Path::class.java).value
        val closedPeriodsFromParam: String =
            listClosedPeriodsMethod.parameterAnnotations[0].filterIsInstance<QueryParam>().single().value
        val closedPeriodsToParam: String =
            listClosedPeriodsMethod.parameterAnnotations[1].filterIsInstance<QueryParam>().single().value

        /** The query-parameter name the production client sends the as-of date under. */
    }
}
