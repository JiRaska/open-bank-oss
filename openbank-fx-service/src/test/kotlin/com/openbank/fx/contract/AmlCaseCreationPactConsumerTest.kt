// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.fx.infrastructure.client.AmlServiceClient
import com.openbank.fx.infrastructure.client.CreateAmlCaseRequest
import io.restassured.RestAssured.given
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the aml-service case store that the FX screening gate calls
 * ([com.openbank.fx.infrastructure.client.AmlCaseAdapter] -> [AmlServiceClient.createCase],
 * `POST /api/v1/aml/cases`). Issue #2255 (C3): aml-service shipped an `openapi.yaml` but no contract
 * test at all, while fx-service calls it on every BLOCK/REVIEW conversion.
 *
 * The generated pact is committed to `pacts/openbank-fx-service-openbank-aml-service.json`
 * (git-pact, ADR-0063) and replayed by `AmlPactProviderVerificationTest` in openbank-aml-service —
 * that test always runs, no Pact Broker involved.
 *
 * IMPORTANT — regenerate on change: if the `@Pact` method below changes (new interaction, renamed
 * field, different matcher), re-run this test
 * (`./gradlew :openbank-fx-service:test --tests "*AmlCaseCreationPactConsumerTest*"`) and commit the
 * updated pact JSON in the same PR. There is no CI drift check yet (ADR-0063 Phase 2) — a
 * un-regenerated pact file silently verifies the OLD contract on the provider side.
 *
 * ## What this pins, and why the response side is deliberately thin
 *
 * [AmlServiceClient.createCase] returns the raw JAX-RS `Response` and `AmlCaseAdapter` never reads
 * it — opening a case is best-effort follow-up signalling that must never flip the conversion
 * verdict. So the consumer genuinely depends on the REQUEST side plus the status code, and nothing
 * else. Pinning response fields fx does not parse would be over-specification, which is exactly the
 * coupling Pact exists to avoid. What the single interaction therefore proves:
 *  - the path `/api/v1/aml/cases` exists on aml-service and accepts `POST` — this is the #2269
 *   failure class (a shipped client calling a provider path that does not exist);
 *  - the provider honours the `Idempotency-Key` **header** (not a body field or a query param);
 *  - the exact JSON body fx serialises is *valid* to the provider: a `201` — rather than a `400`
 *   from an unknown `screeningType`/`riskLevel` enum or a missing required field — is the assertion.
 *
 * ## Traps this test is written around
 *  - The request body is Jackson-serialised from the REAL consumer DTO [CreateAmlCaseRequest] with
 *   the same Kotlin module the `quarkus-rest-client-reactive-jackson` runtime uses, not a
 *   hand-written literal. A renamed or added field therefore changes the recorded pact instead of
 *   leaving it silently agreeing with a stale literal (the issue #1347 lesson from
 *   `LedgerPostJournalPactConsumerTest`).
 *  - The *executed* request takes its path and its idempotency header NAME from [AmlServiceClient]'s
 *   own annotations by reflection, while the `@Pact` definition below states them as literals. So a
 *   rename of `@Path`/`@HeaderParam` on the client fails THIS test immediately, rather than only
 *   surfacing on the provider side after someone remembers to regenerate the pact.
 *  - No `minArrayLike`/`eachLike` question arises here (the trap documented in
 *   `LedgerTrialBalancePactConsumerTest`) because the pinned response carries no body: the provider
 *   replays against a fresh Testcontainer DB with no seeded rows, and a create does not read any.
 *
 * Uses the Pact DSL rather than booting Quarkus, so the test runs in well under a second.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-aml-service", pactVersion = PactSpecVersion.V3)
class AmlCaseCreationPactConsumerTest {

    private val conversionId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val partyId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val accountId = UUID.fromString("44444444-4444-4444-4444-444444444444")

    /**
     * Mirrors the literal formulas at the FX conversion boundary — `"aml-${conv.id}-$alertCode"`
     * and `"${conv.partyId} ${conv.fromCurrency}->${conv.toCurrency} ${conv.fromAmountMinorUnits}"`
     * in `FxService.openCaseQuietly` / `FxActivitiesImpl.openCaseQuietly`. Those live inline at the
     * call sites, not in a factory, so they cannot be invoked from here the way
     * `PaymentJournalFactory.buildLines` can be in the ledger pact.
     */
    private val idempotencyKey = "aml-$conversionId-$ALERT_SANCTIONS_HIT"
    private val customerReference = "$partyId CZK->EUR 100000"

    /**
     * Same Jackson stack `quarkus-rest-client-reactive-jackson` serialises the request with, so the
     * recorded body is a proof about [CreateAmlCaseRequest], not a literal that happens to agree.
     * Every nullable field is populated: the sanctions-hit path is the one that carries an
     * `alertDetail` and a `matchedEntity`, and a fully-populated body is the strictest request the
     * provider must accept.
     */
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val requestBody: String = objectMapper.writeValueAsString(
        CreateAmlCaseRequest(
            partyId = partyId,
            accountId = accountId,
            transactionId = conversionId,
            customerReference = customerReference,
            // `AmlCaseAdapter.SCREENING_TYPE`, a private companion const — mirrored, not referenced.
            screeningType = "TRANSACTION_MONITORING",
            riskLevel = "CRITICAL",
            alertCode = ALERT_SANCTIONS_HIT,
            alertDetail = "DEBTOR '$partyId' POTENTIAL_MATCH score=92",
            matchedEntity = "OFAC SDN entry 'ACME EXPORT LLC'",
        ),
    )

    @Pact(consumer = "openbank-fx-service", provider = "openbank-aml-service")
    fun createSanctionsHitCasePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no AML case exists for the FX conversion idempotency key")
        .uponReceiving("POST a CRITICAL TRANSACTION_MONITORING case for a sanctions-hit FX conversion")
        // Stated as a literal on purpose — the executed request below reads it off the client
        // annotation, so the two only agree while the client still declares this path.
        .path("/api/v1/aml/cases")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Idempotency-Key" to idempotencyKey))
        .body(requestBody)
        .willRespondWith()
        // 201 is the whole response-side assertion: it proves the body above is accepted, not
        // rejected as a 400. fx returns the raw Response and reads no field of it.
        .status(201)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "createSanctionsHitCasePact")
    fun `createCase opens an AML case for a sanctions-hit conversion via the Idempotency-Key header`(
        mockServer: MockServer,
    ) {
        val response = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .header(idempotencyHeaderNameOnClient(), idempotencyKey)
            .body(requestBody)
            .post(clientPathOnClient())
            .then()
            .extract()

        assertThat(response.statusCode()).isEqualTo(201)
    }

    /** The `@Path` the client actually declares — a rename here breaks the pact's literal path. */
    private fun clientPathOnClient(): String = AmlServiceClient::class.java.getAnnotation(Path::class.java).value

    /** The `@HeaderParam` name the client actually declares for the idempotency key. */
    private fun idempotencyHeaderNameOnClient(): String {
        val createCase = AmlServiceClient::class.java.declaredMethods.single { it.name == "createCase" }
        return createCase.parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<HeaderParam>()
            .single()
            .value
    }

    private companion object {
        /** `FxService.ALERT_SANCTIONS_HIT` / `FxActivitiesImpl.ALERT_SANCTIONS_HIT`, both private. */
        const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
    }
}
