// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.openbank.delegation.infrastructure.client.PidServiceRestClient
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the party-eligibility lookup
 * ([com.openbank.delegation.infrastructure.client.ResilientPartyEligibilityClient], ADR-0232 D5):
 * both ends of a grant must be an ACTIVE party, and the grantee's KYC level must clear the bar
 * for the capabilities being handed over (FULL for anything that moves money, BASIC otherwise).
 *
 * ## The nested read is the fragile part, and it is what this pins
 *
 * `PartyEligibility` is built from three fields of pid-service's `PartyResponse`: `id`, `status`,
 * and `kycAttributes.kycLevel` — a NESTED field, read with an elvis to `"NONE"`:
 *
 * ```
 * kycLevel = party.kycAttributes?.kycLevel ?: "NONE"
 * ```
 *
 * That default is why this contract matters more than its size suggests. If pid-service ever
 * renames the object or moves `kycLevel` up a level, the client does not fail — it silently reads
 * `"NONE"`, `KYC_RANK` ranks it 0, and EVERY offer is refused. A fail-closed regression is still a
 * regression, and nothing else in the estate would have gone red.
 *
 * `status` and `kycLevel` are `stringValue`, not `stringType`: `active` is `status == "ACTIVE"`
 * exactly, and `KYC_RANK`'s keys are exactly pid's `KycLevel` names, so a type matcher would let
 * the replay answer with a vocabulary the consumer cannot rank.
 *
 * Expected path is a LITERAL; only the request is reflected off [PidServiceRestClient] (see
 * [ClientRoute]).
 *
 * Provider replay: `PidPactFolderProviderVerificationTest` (`@PactFolder`, runs on every PR).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-pid-service", pactVersion = PactSpecVersion.V3)
class DelegationPartyEligibilityPactConsumerTest {

    private companion object {
        // Fixed UUID — must match the @State seed in pid-service's provider verification.
        const val PARTY_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    }

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-pid-service")
    fun getPartyEligibilityPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an ACTIVE party with FULL KYC exists")
        .uponReceiving("GET the party whose delegation eligibility is being checked")
        .path("/api/v1/parties/$PARTY_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", PARTY_ID)
                o.stringValue("status", "ACTIVE")
                o.`object`("kycAttributes") { kyc -> kyc.stringValue("kycLevel", "FULL") }
                // issue #3604 — the counterparty display name snapshotted onto a grant comes from
                // here. `stringType`, not `stringValue`: unlike status and kycLevel these are not
                // a closed vocabulary the consumer ranks, so the contract is the PATH and the type,
                // not the literal. The values match the provider's @State seed (synthetic).
                o.`object`("coreAttributes") { core ->
                    core.stringType("givenName", "Pact")
                    core.stringType("familyName", "Verifier")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getPartyEligibilityPact")
    fun `eligibility is read from id, status and the nested kycAttributes kycLevel`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get(ClientRoute.of(PidServiceRestClient::class.java, "getParty", "id" to PARTY_ID))
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("id")).isEqualTo(PARTY_ID)
        assertThat(body.getString("status")).isEqualTo("ACTIVE")
        // Asserted at the nested path the client actually reads — not `kycLevel` at the root,
        // which is exactly the shape change the elvis default would swallow.
        assertThat(body.getString("kycAttributes.kycLevel")).isEqualTo("FULL")
        // Asserted at the nested paths ResilientPartyEligibilityClient reads to build the grant's
        // counterparty label. If pid-service moved these, the client would snapshot null and the
        // accept screen would silently go back to showing a UUID (issue #3604) — a fail-quiet
        // regression the provider replay is what actually catches.
        assertThat(body.getString("coreAttributes.givenName")).isEqualTo("Pact")
        assertThat(body.getString("coreAttributes.familyName")).isEqualTo("Verifier")
    }
}
