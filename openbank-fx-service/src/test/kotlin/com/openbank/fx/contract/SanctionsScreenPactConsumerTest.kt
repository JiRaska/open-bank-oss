// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.fx.infrastructure.client.SanctionsServiceClient
import com.openbank.fx.infrastructure.client.ScreenRequest
import com.openbank.fx.infrastructure.client.ScreenResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the sanctions-service synchronous screen that the FX conversion gate
 * calls ([com.openbank.fx.infrastructure.client.SanctionsScreeningAdapter] ->
 * [SanctionsServiceClient.screen], `POST /api/v1/sanctions/screen`).
 *
 * Issue #2255 (C3): sanctions-service shipped an `openapi.yaml` and is called by **seven** services,
 * yet nothing replayed a contract against it. It only ever *looked* covered — the pre-#2291 scorer
 * matched the bare word "contract" anywhere under `src/test`, and this service happened to have a
 * comment containing it. This pact, and the `SanctionsPactProviderVerificationTest` that replays it,
 * are its first real contract coverage.
 *
 * ## Why fx-service is the consumer that writes it
 *
 * Of the callers that hold a `@RegisterRestClient(configKey = "sanctions-service")`, fx-service is
 * the one whose dependency on the *response* is strongest and most dangerous to get wrong:
 * [com.openbank.fx.infrastructure.client.SanctionsScreeningAdapter.mapStatus] branches on the exact
 * `status` **string** and treats anything it does not recognise as `ESCALATED`, so a renamed field or
 * a re-spelled enum value does not fail loudly — it silently escalates or, on the `overallScore`
 * side, feeds a wrong number into a BLOCK/REVIEW decision on a conversion that is about to move
 * money. account-service's adapter reads the same three fields but defaults `status` to `"CLEAR"` on
 * absence (a missing field there fails *open* in shape terms, and its own guard is the
 * fail-closed exception path), and kyc-service only asks for a narrower `PEP_GLOBAL`-scoped screen.
 * fx is also on `rules.yaml: money_path_services`, and its pact wiring already exists (#2284), so
 * this needs no `build.gradle.kts` change on either side beyond the provider's test deps.
 *
 * The generated pact is committed to `pacts/openbank-fx-service-openbank-sanctions-service.json`
 * (git-pact, ADR-0063) and replayed by `SanctionsPactProviderVerificationTest` in
 * openbank-sanctions-service — that test always runs, no Pact Broker involved.
 *
 * IMPORTANT — regenerate on change: if a `@Pact` method below changes (new interaction, renamed
 * field, different matcher), re-run this test
 * (`./gradlew :openbank-fx-service:test --tests "*SanctionsScreenPactConsumerTest*"`) and commit the
 * updated pact JSON in the same PR — an un-regenerated pact file silently verifies the OLD contract
 * on the provider side. `pact-drift-check.yml` enforces this only for modules on its hand-maintained
 * list; `:openbank-fx-service` is already on it (added by #2284), so this pact is covered.
 *
 * ## What the two interactions pin
 *
 * Both are the *same* endpoint in the two states the FX gate actually branches on, and both are
 * satisfied by sanctions-service's **boot-time** seeding — `V6__seed_sanctions_entries.sql` is a
 * Flyway migration, so a fresh Testcontainer Postgres already carries the OFAC/EU/UN/HM-Treasury/PEP
 * entries. No state handler has to write a row, which is what keeps the provider side clear of the
 * HR000068 reactive-Panache-from-a-plain-JUnit-thread trap.
 *  - **CLEAR** (the allow path): a name no seeded list carries. `status` is pinned as an exact value
 *   (`stringValue`), not a type — "this input produces CLEAR" is the assertion, and a type matcher
 *   would let the provider answer `HIT` and still pass. `matches` is pinned as an *empty* array,
 *   which in Pact requires the actual to be empty too: CLEAR must carry no match.
 *  - **HIT** (the block path): `Vladimir Putin`, present on four seeded lists. `status` exact again;
 *   `matches` uses `minArrayLike(1, 1)` — min=1 because this state genuinely must return at least
 *   one match (contrast `LedgerTrialBalancePactConsumerTest`, where the provider's DB is empty and
 *   `eachLike`'s implicit min=1 would fail: the choice is about what the state guarantees, not a
 *   habit). Element fields are type matchers, because the trigram score is a `word_similarity()`
 *   value we must not freeze.
 *
 * `overallScore` is a `decimalType` in both: the consumer coerces it with `?: 0.0` and compares it
 * numerically, so its *type* is the contract, not any particular score.
 *
 * ## Traps this test is written around
 *  - The request body is Jackson-serialised from the REAL consumer DTO [ScreenRequest] with the same
 *   Kotlin module `quarkus-rest-client-reactive-jackson` uses, so a renamed or added field changes
 *   the recorded pact instead of silently agreeing with a hand-written literal (#1347).
 *  - The *executed* request takes its path from [SanctionsServiceClient]'s own `@Path` annotations by
 *   reflection, while the `@Pact` definitions state the path as a literal. Deriving BOTH sides would
 *   be vacuous — measured on #2290 — and a literal on both sides misses a client rename. This way a
 *   rename of either `@Path` fails THIS test immediately.
 *  - `201`, not `200`: `SanctionsResource.screen` wraps the result in `Response.status(201)`. A pact
 *   written against the plausible-looking 200 would go red only on the provider.
 *  - Response deserialisation is asserted through [ScreenResponse] itself, so the pinned JSON is
 *   proven to be readable by the DTO the adapter actually parses — not merely present.
 *
 * Uses the Pact DSL rather than booting Quarkus, so the test runs in well under a second.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sanctions-service", pactVersion = PactSpecVersion.V3)
class SanctionsScreenPactConsumerTest {

    /**
     * Mirrors `FxService`/`FxActivitiesImpl`: `screeningPort.screen(..., "$conversionId:party")`.
     * The formula lives inline at both call sites, not in a factory, so it is mirrored not invoked.
     * Two distinct conversions because `SanctionsService.screen` short-circuits on a replayed
     * `idempotencyKey` — one key could not produce both a CLEAR and a HIT against one provider DB.
     */
    private val clearConversionId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val hitConversionId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private val objectMapper = ObjectMapper().registerKotlinModule()

    /**
     * `SanctionsScreeningAdapter.ENTITY_TYPE`, a private companion const — mirrored, not referenced.
     * fx sends no `listTypes`, so the provider screens every list: the widest possible screen, which
     * is the strictest thing to pin.
     */
    private val clearRequestBody: String = objectMapper.writeValueAsString(
        ScreenRequest(
            idempotencyKey = "$clearConversionId:party",
            entityType = ENTITY_TYPE,
            name = UNLISTED_NAME,
        ),
    )

    private val hitRequestBody: String = objectMapper.writeValueAsString(
        ScreenRequest(
            idempotencyKey = "$hitConversionId:party",
            entityType = ENTITY_TYPE,
            name = SEEDED_SANCTIONED_NAME,
        ),
    )

    @Pact(consumer = "openbank-fx-service", provider = "openbank-sanctions-service")
    fun screenClearPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the sanctions lists are seeded and carry no entry for the screened name")
        .uponReceiving("POST a full-list screen for a name that is on no sanctions or PEP list")
        // Literal on purpose — the executed request below reads it off the client annotations, so
        // the two only agree while the client still declares this path.
        .path("/api/v1/sanctions/screen")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(clearRequestBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Exact value: this state must clear. A type matcher here would accept "HIT".
                o.stringValue("status", "CLEAR")
                o.decimalType("overallScore", 0.0)
                // Empty array, no matching rule => Pact requires the actual array to be empty too.
                o.array("matches")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-fx-service", provider = "openbank-sanctions-service")
    fun screenHitPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the sanctions lists are seeded with the boot-time OFAC/EU/UN/PEP entries")
        .uponReceiving("POST a full-list screen for a name carried by the seeded sanctions lists")
        .path("/api/v1/sanctions/screen")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(hitRequestBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("status", "HIT")
                o.decimalType("overallScore", 1.0)
                // min=1: this state guarantees at least one match. Field types, not values —
                // word_similarity() scores must not be frozen into a contract.
                o.minArrayLike("matches", 1, 1) { match ->
                    match.stringType("matchedName", SEEDED_SANCTIONED_NAME)
                    match.decimalType("matchScore", 1.0)
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "screenClearPact")
    fun `screen returns CLEAR with no matches for a name on no list`(mockServer: MockServer) {
        val response = postScreen(mockServer, clearRequestBody)

        assertThat(response.status).isEqualTo("CLEAR")
        assertThat(response.overallScore).isEqualTo(0.0)
        assertThat(response.matches).isEmpty()
    }

    @Test
    @PactTestFor(pactMethod = "screenHitPact")
    fun `screen returns HIT with a matched name and score for a sanctioned name`(mockServer: MockServer) {
        val response = postScreen(mockServer, hitRequestBody)

        assertThat(response.status).isEqualTo("HIT")
        assertThat(response.matches).isNotEmpty()
        assertThat(response.matches.first().matchedName).isNotBlank()
        assertThat(response.matches.first().matchScore).isNotNull()
    }

    /**
     * Deserialises through [ScreenResponse] — the DTO the adapter parses — so the pinned JSON is
     * proven readable by the consumer, not merely present on the wire.
     */
    private fun postScreen(mockServer: MockServer, body: String): ScreenResponse {
        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(body)
            .post(screenPathOnClient())
            .then()
            .statusCode(201)
            .extract()
            .asString()
        return objectMapper.readValue(raw, ScreenResponse::class.java)
    }

    /**
     * The path [SanctionsServiceClient] actually declares — class-level `@Path` plus the `screen`
     * method's own `@Path`. A rename of either breaks the pact's literal path above.
     */
    private fun screenPathOnClient(): String {
        val basePath = SanctionsServiceClient::class.java.getAnnotation(Path::class.java).value
        val screen = SanctionsServiceClient::class.java.declaredMethods.single { it.name == "screen" }
        return basePath + screen.getAnnotation(Path::class.java).value
    }

    private companion object {
        /** `SanctionsScreeningAdapter.ENTITY_TYPE`, private. */
        const val ENTITY_TYPE = "INDIVIDUAL"

        /**
         * Deliberately not close to any seeded `search_text`: the provider's screen is a
         * `word_similarity()` query with a 0.85 threshold, so a name sharing a token with a seeded
         * entry would flip this interaction to HIT and make the CLEAR contract flaky.
         */
        const val UNLISTED_NAME = "Zdenka Bezprijmeni"

        /** Seeded by `V6__seed_sanctions_entries.sql` on OFAC_SDN, EU, HM_TREASURY and PEP_GLOBAL. */
        const val SEEDED_SANCTIONED_NAME = "Vladimir Putin"
    }
}
