// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.contract

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
import com.openbank.psd2.infrastructure.client.TppAuthorizationResponse
import com.openbank.psd2.infrastructure.client.TppRegistryRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the eIDAS licence gate every XS2A request passes through:
 * tpp-registry `GET /api/v1/tpp-registry/check?tppId=&role=`, called by
 * [com.openbank.psd2.infrastructure.client.TppAuthorizationGuard]. The generated pact is committed
 * to `pacts/openbank-psd2-service-openbank-tpp-registry-service.json` (git-pact, ADR-0063) and
 * replayed by `TppRegistryPactProviderVerificationTest` (`@PactFolder("../pacts")`) — that test
 * always runs, no broker involved.
 *
 * psd2-service had an `openapi.yaml` and no contract test at all (issue #2255 dimension C3), while
 * this call decides whether a TPP may act as an AISP/PISP.
 *
 * Pinned: the **refusal** — an unregistered TPP answers HTTP **403** carrying the result body.
 * `TppRegistryRestClient` declares a plain return type, so a non-2xx becomes a
 * `WebApplicationException`, and that is what makes [TppAuthorizationGuard] fail closed. A
 * 200-carrying-`authorized:false` would silently defeat it, so pinning the 403 pins the security
 * property, not merely a status code. It also *proves the route exists*, which a 404 interaction
 * could not: Quarkus answers 404 for an absent route too, so a pact pinned to a 404 is satisfied by
 * a client pointed at nothing. This is the sharpest available form of the #2269 check — finrep
 * shipped a call to a ledger path that had never existed while its unit tests passed against a
 * mocked port. No seeded data is needed; nothing registers the id this interaction uses.
 *
 * WITHHELD, deliberately: the **allow** branch (HTTP 200, `authorized: true`, `roles: ["AISP"]`).
 * It is written and it FAILS against a real tpp-registry — `TppRepositoryImpl.toDomain()` maps the
 * entity's `BIGSERIAL` id through `UUID.fromString(id.toString())`, so reading any registered row
 * throws and the endpoint answers 400 `"Invalid UUID string: 2"` (issue #2340). The provider replay
 * is what found it; this consumer test was green throughout, which is the asymmetry CLAUDE.md
 * records. Committing that interaction would leave a permanently red provider gate, so it lands with
 * the fix. Note what this means for the contract as it stands: the refusal branch is verified, the
 * allow branch is currently unreachable in production.
 *
 * **The expected path is a LITERAL; only the outgoing requests are reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290). Deriving both sides is vacuous —
 * expectation and request move together, so the test stays green against a route that does not
 * exist. [assertClientPathMatchesContract] pins the annotations to the literal instead.
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-psd2-service:test
 * --tests "*TppRegistryCheckPactConsumerTest*"`) and commit the updated pact JSON in the same PR;
 * `.github/workflows/pact-drift-check.yml` fails the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-tpp-registry-service", pactVersion = PactSpecVersion.V3)
class TppRegistryCheckPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unregisteredTppRefusedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no registry entry exists for the pact unknown-TPP id")
        .uponReceiving("GET check an unregistered TPP — the fail-closed refusal")
        .path(EXPECTED_CHECK_PATH)
        .query("tppId=$UNKNOWN_TPP_ID&role=$AISP_ROLE")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        // 403, not 200-with-authorized:false. The client's plain return type turns this into a
        // WebApplicationException, which is what makes TppAuthorizationGuard fail closed.
        .status(403)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("tppId", UNKNOWN_TPP_ID)
                o.booleanValue("authorized", false)
                o.array("roles")
                o.stringType("reason", "TPP not found in registry")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "unregisteredTppRefusedPact")
    fun `an unregistered TPP is refused with 403 and a reason`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = check(mockServer, UNKNOWN_TPP_ID, 403)

        val response = mapper.readValue<TppAuthorizationResponse>(raw)
        assertThat(response.authorized).isFalse()
        assertThat(response.roles).isEmpty()
        assertThat(response.reason).isNotBlank()
    }

    private fun check(mockServer: MockServer, tppId: String, expectedStatus: Int): String = given()
        .baseUri(mockServer.getUrl())
        .accept("application/json")
        .queryParam("tppId", tppId)
        .queryParam("role", AISP_ROLE)
        // Reflected off the client, NOT retyped: this is the request the real client issues.
        .get(clientDerivedCheckPath())
        .then()
        .statusCode(expectedStatus)
        .extract().asString()

    /**
     * The asymmetry that makes this contract falsifiable at the consumer layer: the path the client
     * would really call, recomputed from [TppRegistryRestClient]'s own annotations, must equal the
     * literal this pact promises tpp-registry. A `@Path` edit on the client reddens here.
     */
    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedCheckPath())
            .describedAs(
                "TppRegistryRestClient's @Path no longer produces the path this pact pins — either " +
                    "fix the client or update EXPECTED_CHECK_PATH *and* re-verify against tpp-registry",
            )
            .isEqualTo(EXPECTED_CHECK_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-psd2-service"
        const val PROVIDER = "openbank-tpp-registry-service"

        /** Never registered — a fresh Testcontainer DB satisfies the refusal state by construction. */
        const val UNKNOWN_TPP_ID = "CZ-CNB-PACT-UNREGISTERED"

        /** `TppRole.AISP`, sent as the exact enum name the provider's `valueOf` accepts. */
        const val AISP_ROLE = "AISP"

        /**
         * LITERAL, deliberately retyped from tpp-registry's `TppRegistryResource`
         * (`@Path("/api/v1/tpp-registry")` + `@GET @Path("/check")`). Never derive from the client.
         */
        const val EXPECTED_CHECK_PATH = "/api/v1/tpp-registry/check"

        fun clientDerivedCheckPath(): String {
            val base = TppRegistryRestClient::class.java.getAnnotation(Path::class.java).value
            val method = TppRegistryRestClient::class.java.methods
                .single { it.name == "checkAuthorization" }
                .getAnnotation(Path::class.java)
                .value
            return base + method
        }
    }
}
