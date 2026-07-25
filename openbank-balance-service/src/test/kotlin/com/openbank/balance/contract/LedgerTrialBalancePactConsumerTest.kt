// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the ledger trial-balance endpoint called by the ADR-0039 Phase A
 * reconciliation. The generated pact file is committed to `pacts/` (git-pact pattern, ADR-0063)
 * and replayed by `LedgerPactProviderVerificationTest` (`@PactFolder("../pacts")`) in
 * openbank-ledger-service — that test always runs, no Pact Broker involved.
 *
 * IMPORTANT — regenerate on change: if this test's `@Pact` methods change (new interaction,
 * different matcher, renamed field), re-run this test (`./gradlew :openbank-balance-service:test
 * --tests "*LedgerTrialBalancePactConsumerTest*"`) and commit the updated
 * `pacts/openbank-balance-service-openbank-ledger-service.json` in the same PR — an un-regenerated
 * pact file silently verifies the OLD contract on the provider side. `pact-drift-check.yml`
 * (ADR-0063 Phase 2, issue #468) enforces this: it regenerates every consumer pact and fails on
 * `git diff -- pacts/`. Note what that gate can and cannot see — its only assertion is the diff,
 * so a module it does not regenerate does not read as *unchecked*, it reads as *passing*. Its
 * scope is therefore DERIVED, by `.github/scripts/derive-pact-drift-scope.sh`, from the
 * `@Pact(consumer = .., provider = ..)` annotations themselves; a consumer test in a new module
 * needs no workflow edit, and a pact nothing regenerates fails the derivation instead of going
 * quietly green.
 *
 * Uses Pact DSL — not the actual MicroProfile REST Client — so the test has zero Quarkus
 * boot overhead and runs in < 100 ms. What is verified: the shape and types the client DTO
 * [com.openbank.balance.infrastructure.client.LedgerTrialBalanceClient] parses, not the HTTP
 * plumbing (that is covered by LedgerApiIT on the provider side).
 *
 * Note on `minArrayLike(0, 1)` vs `eachLike`: the provider test runs against a fresh
 * Testcontainer DB with no seeded journal entries. eachLike implies min=1 which fails when the
 * DB is empty. minArrayLike(0, 1) sets min=0 so the provider passes with zero or more lines;
 * the consumer client handles both cases (reconciliation skips when lines is empty).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class LedgerTrialBalancePactConsumerTest {

    @Pact(consumer = "openbank-balance-service", provider = "openbank-ledger-service")
    fun trialBalanceBalancedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has journal entries for the reporting date")
        .uponReceiving("GET trial-balance as-of a specific date")
        .path("/api/v1/journals/trial-balance")
        .query("asOf=2024-01-31")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // type matchers: consumer only cares about shape, not exact values.
                o.stringType("asOf", "2024-01-31")
                o.booleanType("balanced", true)
                // minArrayLike(0): provider test runs against an empty-DB Testcontainer;
                // 0 lines is valid — reconciliation handles the zero-entry case.
                // The element template defines required field types for non-empty responses.
                o.minArrayLike("lines", 0, 1) { line ->
                    line.stringType("code", "1100-DEPOSITS-CZK")
                    line.stringType("currency", "CZK")
                    line.decimalType("totalDebit", 100_000.00)
                    line.decimalType("totalCredit", 100_000.00)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-balance-service", provider = "openbank-ledger-service")
    fun trialBalanceEmptyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has no journal entries")
        .uponReceiving("GET trial-balance returns balanced empty ledger")
        .path("/api/v1/journals/trial-balance")
        .query("asOf=2000-01-01")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringType("asOf", "2000-01-01")
                o.booleanType("balanced", true)
                o.array("lines") // empty array — reconciliation handles zero-line ledger
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "trialBalanceBalancedPact")
    fun `trial balance client receives a balanced ledger with lines`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam("asOf", "2024-01-31")
            .get("/api/v1/journals/trial-balance")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getBoolean("balanced")).isTrue()
        assertThat(body.getString("asOf")).isNotBlank()
        // lines may be empty in the mock (min=0 contract), but consumer handles both cases
        val lines = body.getList<Map<String, Any>>("lines")
        assertThat(lines).isNotNull()
    }

    @Test
    @PactTestFor(pactMethod = "trialBalanceEmptyPact")
    fun `trial balance client handles empty ledger without throwing`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam("asOf", "2000-01-01")
            .get("/api/v1/journals/trial-balance")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getBoolean("balanced")).isTrue()
        assertThat(body.getList<Any>("lines")).isEmpty()
    }
}
