// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.contract

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
import com.openbank.vop.infrastructure.client.AccountServiceClient
import com.openbank.vop.infrastructure.client.AccountSummary
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for **hop 1** of the ADR-0171 §4 VoP name resolution: `GET
 * /api/v1/accounts/iban/{iban}`, the call that turns the payee IBAN into the owning party id.
 * Issue #8345 — the coverage axis: `check-pact-provider-replay.py` already guarantees every
 * committed pact is replayed before merge, but a cross-service call with *no pact at all* is
 * invisible to it, and this was one of 27 such money-path calls.
 *
 * ## Why this hop was previously left unpinned, and why that reason is gone
 *
 * [PartyNameLookupPactConsumerTest] (hop 2) records that hop 1 was deliberately skipped because
 * account-service's only `@Provider` class was message-only — a `MessageTestTarget` with no Quarkus
 * HTTP boot — so an HTTP interaction had nowhere to be replayed. That is no longer true:
 * `AccountPactFolderProviderVerificationTest` is `@QuarkusTest`-booted and dispatches per
 * interaction (`MessageTestTarget` for asynchronous messages, `HttpTestTarget` otherwise), which is
 * exactly the conversion that KDoc said was missing. This contract therefore needs **no**
 * provider-side change: it reuses the already-seeded
 * `an account owned by a known party exists` state, whose account carries [PACT_IBAN].
 *
 * ## What this contract is actually load-bearing for
 *
 * `AccountHolderNameLookupAdapter.lookupWithResilience` reads exactly one field off this response —
 * `partyId` — and a null-or-blank one short-circuits the whole verification to NO_DATA without an
 * error anywhere. So a renamed or dropped `partyId` silently turns every Verification of Payee into
 * "we hold no name for this IBAN", which is a *valid* VoP answer and therefore indistinguishable
 * from the real thing in logs, metrics and every unit test (the port is mocked in those). This
 * pact pins the field name, and the provider replay is what adjudicates whether account-service
 * still serves the route and still emits it.
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290). Deriving *both* sides is vacuous —
 * the Pact mock server answers whatever the client asks for, so expectation and request move
 * together and the test stays green against a route that does not exist. That is exactly how
 * finrep-service shipped a call to a ledger path that never existed (#2269). Here
 * [assertClientPathMatchesContract] pins the client's annotations to the literal, so a `@Path` edit
 * on [AccountServiceClient] reddens this test, and the provider replay independently answers
 * whether account-service serves it.
 *
 * The response is bound into the REAL client DTO [AccountSummary] rather than read off a JsonPath,
 * so renaming a field on the mirror reddens THIS test — the half of the contract the provider
 * replay cannot see.
 *
 * No `X-Customer-Party-Id` header is sent, matching the client: VoP is a service-to-service check
 * made on behalf of a payer who is *not* the account owner, and account-service applies its
 * owner-scoping only when that header is present (`isCustomerOwnershipViolation`).
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-vop-service:test --tests
 * "*AccountIbanLookupPactConsumerTest*"`) and commit the updated pact JSON in the same PR;
 * `.github/workflows/pact-drift-check.yml` fails the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class AccountIbanLookupPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountByIbanPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the account behind the payee IBAN, for its owning party id")
        .path(EXPECTED_ACCOUNT_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // uuid(), not stringType(): hop 2 feeds this straight into
                // `GET /api/v1/parties/{id}`, so a value that is not a UUID is not a name lookup
                // that returns nothing — it is a 404 on the next hop.
                o.uuid("id", UUID.fromString(PACT_ACCOUNT_ID))
                o.uuid("partyId", UUID.fromString(PACT_OWNER_PARTY_ID))
                // stringValue, NOT stringType: the seeded account is ACTIVE, and a type matcher
                // would accept "CLOSED" just as happily while proving nothing about the route.
                o.stringValue("status", "ACTIVE")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountByIbanPact")
    fun `the account response binds into AccountSummary and carries the owning party id`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            // Reflected off the client, NOT retyped: this is the request the real client issues.
            .get(clientDerivedAccountPath())
            .then()
            .statusCode(200)
            .extract().asString()

        val summary = mapper.readValue<AccountSummary>(raw)
        // The one field the adapter actually consumes. `isNotBlank` rather than a fixed value:
        // what the adapter cannot survive is the field being renamed, absent or empty.
        assertThat(summary.partyId).isNotBlank()
        assertThat(summary.id).isNotBlank()
        assertThat(summary.status).isEqualTo("ACTIVE")
    }

    /**
     * The negative case, and why it is a **404 rather than a 401**.
     *
     * A 401 here could never pass the provider replay. `AccountPactFolderProviderVerificationTest`
     * is a `@QuarkusTest` whose requests are authenticated by construction, so the provider answers
     * 200 to the very request this pact says must be refused — measured on `main` as
     * `expected status of 401 but was 200`, red since the pact was committed and invisible because
     * path-scoped CI does not rebuild account-service when only `pacts/` changes (#8552).
     *
     * "Not found" is the negative this hop can actually assert, and it is the one that matters to
     * the caller: `AccountHolderNameLookupAdapter` turns an unknown IBAN into NO_DATA, a *valid*
     * Verification of Payee answer, so a route that started answering 200-with-nulls instead of 404
     * would be indistinguishable from a real "we hold no name for this IBAN" in every log and
     * metric. Pinning the 404 is what stops that.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun rejectsUnknownIban(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no account exists for the unknown IBAN")
        .uponReceiving("GET the account behind an IBAN account-service does not know")
        .path(UNKNOWN_ACCOUNT_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "rejectsUnknownIban")
    fun `answers 404 for an IBAN account-service does not know`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .get(clientDerivedPathFor(UNKNOWN_IBAN))
            .then()
            .statusCode(404)
    }

    /**
     * The asymmetry that makes this contract falsifiable at the consumer layer: the path the client
     * would really call, recomputed from [AccountServiceClient]'s own annotations, must equal the
     * literal this pact promises account-service. A `@Path` edit on the client reddens here.
     */
    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedAccountPath())
            .describedAs(
                "AccountServiceClient's @Path no longer produces the path this pact pins — either " +
                    "fix the client or update EXPECTED_ACCOUNT_PATH *and* re-verify against " +
                    "account-service",
            )
            .isEqualTo(EXPECTED_ACCOUNT_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-vop-service"
        const val PROVIDER = "openbank-account-service"

        /**
         * All three seeded by account-service's `an account owned by a known party exists` state
         * (`AccountPactFolderProviderVerificationTest` and its broker twin). Retyped here, not
         * imported: the two modules share no test source set, and the provider states document the
         * same requirement in the opposite direction.
         */
        const val PACT_IBAN = "CZ6508000000192000145399"
        const val PACT_ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val PACT_OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"

        /**
         * LITERAL, deliberately retyped from account-service's `AccountResource`
         * (`@Path("/api/v1/accounts")` + `@GET @Path("/iban/{iban}")`). Never derive this from the
         * client — see the class KDoc.
         */
        const val EXPECTED_ACCOUNT_PATH = "/api/v1/accounts/iban/$PACT_IBAN"

        /**
         * A valid IBAN — correct mod-97 check digits — that no provider state seeds, so the route
         * genuinely answers 404.
         *
         * Deliberately NOT a malformed string, and the difference is measurable rather than
         * theoretical: the first draft of this constant had wrong check digits and the provider
         * replay answered **400, not 404**, because validation rejects it long before the lookup.
         * A malformed IBAN tests the validator; this one tests the lookup, and only the second is
         * the case VoP's caller depends on.
         */
        const val UNKNOWN_IBAN = "CZ7155000000001234567890"
        const val UNKNOWN_ACCOUNT_PATH = "/api/v1/accounts/iban/$UNKNOWN_IBAN"

        fun clientDerivedAccountPath(): String = clientDerivedPathFor(PACT_IBAN)

        fun clientDerivedPathFor(iban: String): String {
            val base = AccountServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = AccountServiceClient::class.java.methods
                .single { it.name == "getAccountByIban" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{iban}", iban)
        }
    }
}
