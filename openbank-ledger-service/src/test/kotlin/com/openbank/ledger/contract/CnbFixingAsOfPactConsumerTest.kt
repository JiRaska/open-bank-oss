// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.contract

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
 * Consumer-driven contract for the ČNB fixing lookup the daily FX revaluation makes
 * ([com.openbank.ledger.infrastructure.client.FxServiceClient], ADR-0046) — specifically for the
 * `?asOf=` form added by #3921, which asks for the fixing that was in effect on the business day
 * being marked rather than the newest one.
 *
 * ## Why this pact has to exist, and why it is not enough on its own
 *
 * `asOf` is a request-shape change, and a request-shape change is the one defect class a consumer
 * test structurally cannot catch: the Pact mock server answers whatever it is asked for, so an
 * `asOf` fx-service never implements — or rejects, since fx-service answers 400 for `asOf` without
 * `source=CNB` — verifies green here and fails only in production. The thing that catches it is the
 * PROVIDER replay, `FxPactFolderProviderVerificationTest` in fx-service, which runs on a PR because
 * it is `@PactFolder`-sourced rather than broker-gated (#2327/#2338). This file's job is to commit
 * the interaction that replay verifies.
 *
 * That is not hypothetical here. fx-service's own history is the worked example: its ČNB feed URL
 * was a 404 for the entire life of the service and every test layer stayed green, because a unit
 * test stubs the client, an IT serves a fixture, and a pact answers whatever path it is asked for.
 *
 * ## Path and query are LITERALS on the expectation side
 *
 * Deliberately not reflected off `FxServiceClient`'s `@Path`/`@QueryParam` annotations. Deriving
 * both the expectation and the request from the annotation is DRY and vacuous — the two move
 * together, so the test stays green when the client points at a route that does not exist
 * (measured on #2290). The asymmetry IS the test.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-fx-service", pactVersion = PactSpecVersion.V3)
class CnbFixingAsOfPactConsumerTest {

    @Pact(consumer = "openbank-ledger-service", provider = "openbank-fx-service")
    fun cnbFixingAsOfPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an EUR/CZK CNB fixing is in effect on 2026-05-27")
        .uponReceiving("GET the EUR/CZK CNB fixing in effect on a given business day")
        .path("/api/v1/fx/rates/EUR/CZK")
        .query("source=CNB&asOf=2026-05-27")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // stringValue, not stringType: the pair IS the identity of the fixing, and a
                // provider echoing the wrong pair would verify green under a type matcher while
                // the consumer went on to revalue a position at it (the #2425 finding).
                o.stringValue("baseCurrency", "EUR")
                o.stringValue("quoteCurrency", "CZK")
                // Rates genuinely move — decimal matchers, not values.
                o.decimalType("bidRate", 24.90)
                o.decimalType("askRate", 24.90)
                // The field the whole of #3921 turns on. Without it on the wire there is no fixing
                // age to publish and no fixing identity to key the correcting entry on, and its
                // absence is INVISIBLE downstream: `@JsonIgnoreProperties(ignoreUnknown = true)`
                // on the consumer DTO drops an undeclared field silently, which is how ledger went
                // its whole life with no time value to reason about at all.
                //
                // A regex, NOT `datetimeExpression`: that helper emitted a `timestamp` matcher
                // with format `yyyy-MM-dd'T'HH:mm:ss` — the trailing `'Z'` is consumed as the
                // generator's expression and dropped from the matcher — so it would have rejected
                // the `2026-05-26T22:00:00Z` fx-service actually serialises an `Instant` as. The
                // consumer test could not have told me that, and did not: it was green with the
                // broken matcher, because the mock server answers the generated example, which
                // matches its own matcher by construction. It was caught by reading the emitted
                // `pacts/*.json` — the provider replay is what would have failed on it later.
                o.stringMatcher(
                    "validFrom",
                    """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z""",
                    "2026-05-26T22:00:00Z",
                )
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cnbFixingAsOfPact")
    fun `the CNB fixing for a business day carries the pair, the rate and its validFrom`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            // LITERAL, per the class KDoc — this string is the expectation, not a restatement of it.
            .get("/api/v1/fx/rates/EUR/CZK?source=CNB&asOf=2026-05-27")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("baseCurrency")).isEqualTo("EUR")
        assertThat(body.getString("quoteCurrency")).isEqualTo("CZK")
        assertThat(body.getDouble("bidRate")).isPositive()
        // Not merely non-null: `validFrom` is what the freshness gauge and the idempotency key are
        // computed from, and a missing one must degrade to "age unknown", never to "age zero".
        assertThat(body.getString("validFrom")).isNotBlank()
    }
}
