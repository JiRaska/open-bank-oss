// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.contract

import au.com.dius.pact.consumer.MockServer
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
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.infrastructure.client.LedgerRestClient
import com.openbank.finrep.infrastructure.client.TrialBalanceResponse
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
 * Only the three line fields finrep actually consumes (`code`, `type`, `net`) are declared — a
 * consumer contract must not pin provider fields it does not read (`glAccountId`, `name`,
 * `currency`, `totalDebit`, `totalCredit` are ledger's business).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class LedgerTrialBalancePactConsumerTest {

    private val json = jacksonObjectMapper()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun trialBalanceWithEntriesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has journal entries for the reporting date")
        .uponReceiving("GET the GL trial balance for a FINREP reporting date")
        .path(LEDGER_TRIAL_BALANCE_PATH)
        .query("$AS_OF_PARAM=$REPORTING_DATE")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringType("asOf", REPORTING_DATE)
                o.booleanType("balanced", true)
                o.minArrayLike("lines", 0, 1) { line ->
                    line.stringType("code", "1100-CASH-CLEARING-CZK")
                    line.stringType("type", "ASSET")
                    line.decimalType("net", 150_000.00)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-finrep-service", provider = "openbank-ledger-service")
    fun trialBalanceEmptyLedgerPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has no journal entries")
        .uponReceiving("GET the GL trial balance for a date with no ledger activity")
        .path(LEDGER_TRIAL_BALANCE_PATH)
        // A pre-platform date, so this interaction stays valid even when the provider run shares a
        // Testcontainer DB with interactions that post journals (they are all dated "today").
        .query("$AS_OF_PARAM=$PRE_HISTORY_DATE")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringType("asOf", PRE_HISTORY_DATE)
                o.booleanType("balanced", true)
                o.array("lines") // empty — a date before any ledger activity
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "trialBalanceWithEntriesPact")
    fun `the ledger client contract yields the fields the FINREP mappers consume`(mockServer: MockServer) {
        // Guards the reflection helpers themselves: were they ever to return an empty or partial
        // path, the interaction and the request would still agree with each other and both pact
        // tests would pass against a contract that pins nothing.
        assertThat(ledgerTrialBalancePath).isEqualTo(LEDGER_TRIAL_BALANCE_PATH)
        assertThat(asOfQueryParam).isEqualTo(AS_OF_PARAM)

        val response = fetchTrialBalance(mockServer, REPORTING_DATE)

        assertThat(response.asOf).isNotBlank()
        assertThat(response.balanced).isTrue()
        // The three fields LedgerAdapter maps into TrialBalanceLineDto must all deserialize.
        assertThat(response.lines).isNotEmpty()
        val line = response.lines.first()
        assertThat(line.code).isNotBlank()
        assertThat(line.type).isNotBlank()
        assertThat(line.net).isNotNull()

        // ... and the mapper must accept them: proves the contract feeds a real F01.01 render,
        // not merely that some JSON parsed.
        val template = F0101Mapper.map(
            response.lines.map { TrialBalanceLineDto(code = it.code, accountType = it.type, net = it.net) },
            LocalDate.parse(REPORTING_DATE),
        )
        assertThat(template.cells.map { it.rowRef }).containsExactly("r010", "r380", "r490")
        assertThat(template.cells.first().value).isEqualByComparingTo(BigDecimal("150000.00"))
    }

    @Test
    @PactTestFor(pactMethod = "trialBalanceEmptyLedgerPact")
    fun `a zero-line ledger still renders a fully populated zero template`(mockServer: MockServer) {
        val response = fetchTrialBalance(mockServer, PRE_HISTORY_DATE)

        assertThat(response.balanced).isTrue()
        assertThat(response.lines).isEmpty()

        // A regulatory report must never be silently truncated (ADR-0097): every row is present,
        // honestly zero.
        val template = F0101Mapper.map(emptyList(), LocalDate.parse(PRE_HISTORY_DATE))
        assertThat(template.cells).hasSize(3)
        assertThat(template.cells.map { it.value }).allSatisfy { assertThat(it).isEqualByComparingTo(BigDecimal.ZERO) }
    }

    /** Issues the request against the path the production client is annotated with. */
    private fun fetchTrialBalance(mockServer: MockServer, asOf: String): TrialBalanceResponse {
        val body = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam(asOfQueryParam, asOf)
            .get(ledgerTrialBalancePath)
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
        const val LEDGER_TRIAL_BALANCE_PATH = "/api/v1/journals/trial-balance"

        /** Ledger's query-parameter name for the as-of date — likewise literal. */
        const val AS_OF_PARAM = "asOf"

        /** A FINREP quarter-end reference date. */
        const val REPORTING_DATE = "2026-06-30"

        /** Before any ledger activity can exist, so the empty-lines assertion is stable. */
        const val PRE_HISTORY_DATE = "2000-01-01"

        private val getTrialBalanceMethod =
            LedgerRestClient::class.java.getDeclaredMethod("getTrialBalance", String::class.java)

        /**
         * `@Path` on the interface + `@Path` on the method, joined the way JAX-RS resolves them.
         * Read from the production client so the contract cannot drift from the code that calls it.
         */
        val ledgerTrialBalancePath: String = listOf(
            LedgerRestClient::class.java.getAnnotation(Path::class.java).value,
            getTrialBalanceMethod.getAnnotation(Path::class.java).value,
        ).joinToString("/") { it.trim('/') }.let { "/$it" }

        /** The query-parameter name the production client sends the as-of date under. */
        val asOfQueryParam: String = getTrialBalanceMethod.parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<QueryParam>()
            .single()
            .value
    }
}
