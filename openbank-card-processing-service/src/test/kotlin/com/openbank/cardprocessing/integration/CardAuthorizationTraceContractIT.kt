// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.integration

import com.openbank.cardprocessing.integration.CardAuthorizationOutboxIT.StubIssuer
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

/**
 * The observable distributed shape of a card authorisation.
 *
 * `POST /api/v1/card-authorizations` commits the `card_authorizations` row and its
 * `card.authorised.v1` `card_outbox` row in ONE transaction (ADR-0050, proven row-wise by
 * [CardAuthorizationOutboxIT]). The outbox row is the authorisation fact the clearing and ledger
 * projections reconcile against, so the two INSERTs ARE the card money write. The spans asserted
 * here are the service's OWN: Quarkus' OpenTelemetry auto-instrumentation produces them for the
 * real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an in-memory
 * `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing asserted
 * here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says the authorisation row and its event
 * happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the rows and still answers
 * 201, is invisible to every presence assertion this module already had, and lands in a trace of
 * its own. For a card authorisation that is a hold on customer funds no operator can reconstruct
 * from telemetry.
 *
 * The issuer is the same CDI alternative [StubIssuer] the sibling IT enables through its profile —
 * what is under contract is this service's own persistence path, and card-issuance's decision is
 * pinned separately by the committed pact plus its provider replay.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); the authorisation is this service's highest-frequency
 * money write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(
    com.openbank.libs.testing.containers.PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_card_processing_it")],
)
@TestProfile(CardAuthorizationTraceContractIT.CardTraceProfile::class)
@TestSecurity(user = "card-processing-it", roles = ["ROLE_OPERATOR"])
class CardAuthorizationTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off). The
     * additions are card-processing-specific:
     *
     *  - [StubIssuer] as an enabled alternative, so the issuer decision resolves without a network
     *    call (the scoped-switch pattern from [CardAuthorizationOutboxIT.StubbedIssuerProfile] —
     *    an `@Alternative` with `@Priority` would leak into every other test in the module);
     *  - port 0: a profile's own boot re-binds the fixed default test port 8081 before the
     *    outgoing instance has released it (`QuarkusBindException` — measured in billing's
     *    `BillingOutboxAtomicityIT`); port 0 lets Quarkus pick a free one and rewrites the
     *    property so RestAssured still finds it;
     *  - `quarkus.oidc-client.enabled=false`: the module's `%test` boots and
     *    `TransactionLedgerPostingHttpIT`'s profile run with the key pinned false; a custom
     *    profile's boot must carry the same value across the `ConfigRecorder` build-time check.
     */
    class CardTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.http.test-port" to "0",
            "quarkus.oidc-client.enabled" to "false",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(StubIssuer::class.java)
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `authorising a card keeps the request and both money rows in one trace`() {
        Given {
            contentType(ContentType.JSON)
            header("Idempotency-Key", "trace-it-${System.nanoTime()}")
            body(
                """
                {"cardId":"${StubIssuer.KNOWN_CARD}","amountMinorUnits":25000,"currencyCode":"CZK",
                 "channel":"CONTACTLESS","mcc":"5411","merchantName":"Potraviny","merchantCountry":"CZ",
                 "networkReference":"trace-it"}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/card-authorizations")
        } Then {
            statusCode(201)
            body("id", notNullValue())
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/card-authorizations")
            .requiresAttribute("POST /api/v1/card-authorizations", "http.response.status_code")
            .requiresAttribute("INSERT card_authorizations", "db.sql.table")
            .requiresAttribute("INSERT card_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/card-authorizations",
                "INSERT card_authorizations",
                "INSERT card_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("card-authorization-create")
    }
}
