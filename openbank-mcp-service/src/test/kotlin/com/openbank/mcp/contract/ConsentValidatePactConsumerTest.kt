// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.contract

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
import com.openbank.mcp.infrastructure.read.ConsentScopes
import com.openbank.mcp.infrastructure.read.ConsentValidateClient
import com.openbank.mcp.infrastructure.read.ConsentValidationResponse
import com.openbank.mcp.infrastructure.read.ValidateConsentRequest
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the single call every MCP banking tool fails closed on:
 * consent-service `POST /api/v1/consents/{id}/validate` (ADR-0195 / ADR-0126 §D2). The generated
 * pact is committed to `pacts/openbank-mcp-service-openbank-consent-service.json` (git-pact,
 * ADR-0063) and replayed by `ConsentPactProviderVerificationTest` (`@PactFolder("../pacts")`) in
 * openbank-consent-service — that test always runs, no broker involved.
 *
 * Why this call above all of mcp's downstream reads: [com.openbank.mcp.infrastructure.read.RealAccountReadPort]
 * reads `valid` AND `grantedAccounts` off this response, and `grantedAccounts` is the authority for
 * which IBANs the caller may see — deliberately taken from the live validate response rather than
 * from the caller's token, so revoke/expire are honoured per call. A drift in either field is a
 * confidentiality bug, and `RealAccountReadPortTest` cannot see it: the client is mocked there.
 * The port is still `@Vetoed` (not wired as the default), so this pact exists precisely to make the
 * ADR-0195 cutover a wiring step rather than a discovery of a route that never existed (#2269).
 *
 * **The expected paths are LITERALS; only the outgoing requests are reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290). Deriving both sides is vacuous — the
 * Pact mock server answers whatever the client asks for, so expectation and request move together
 * and the test stays green against a nonexistent route. [assertClientPathMatchesContract] pins the
 * annotations to the literal instead, and the provider replay adjudicates the rest.
 *
 * Two interactions, both branches of a fail-closed gate:
 * - an ACTIVE, in-scope, account-covering consent → `valid: true` plus the projection fields;
 * - an unknown consent id → HTTP **200** with `valid: false` and `CONSENT_NOT_FOUND`, not a 404.
 *   `ConsentService.validateConsent` returns an `Invalid` result rather than throwing, so the body
 *   is the only place the denial appears; a client that trusted the status code would allow it.
 *
 * `valid` and `code` are pinned by VALUE, not by a type matcher: a `booleanType` matcher would
 * accept `false` where the contract says allow, which is exactly the assertion worth making.
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-mcp-service:test --tests
 * "*ConsentValidatePactConsumerTest*"`) and commit the updated pact JSON in the same PR;
 * `.github/workflows/pact-drift-check.yml` fails the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-consent-service", pactVersion = PactSpecVersion.V3)
class ConsentValidatePactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeConsentValidatesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an ACTIVE AISP consent covers the pact account for the pact grantee")
        .uponReceiving("POST validate an ACTIVE ACCOUNTS_READ consent covering the requested IBAN")
        .path(EXPECTED_VALIDATE_PATH)
        .method("POST")
        .headers(mapOf("Accept" to "application/json", "Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(COVERED_REQUEST))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Value, not type: "the provider allows this" is the contract.
                o.booleanValue("valid", true)
                // The projection RealAccountReadPort actually reads. grantedAccounts is the
                // confidentiality boundary — it decides which IBANs an MCP tool may return.
                o.array("scopes") { a -> a.stringValue(ConsentScopes.ACCOUNTS_READ) }
                o.array("grantedAccounts") { a -> a.stringType(PACT_ACCOUNT_IBAN) }
                // PSD2 RTS Art. 10(2)(b): 4 for any AISP scope, deterministic on the provider.
                o.numberValue("frequencyPerDay", 4)
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownConsentIsDeniedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no consent exists with the pact unknown-consent id")
        .uponReceiving("POST validate an unknown consent id — the fail-closed deny")
        .path(EXPECTED_UNKNOWN_VALIDATE_PATH)
        .method("POST")
        .headers(mapOf("Accept" to "application/json", "Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(UNKNOWN_REQUEST))
        .willRespondWith()
        // 200, not 404 — see the class KDoc. The denial lives in the body.
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.booleanValue("valid", false)
                o.stringType("reason", "Consent not found")
                // Value: RealAccountReadPort surfaces this code, and a caller that special-cases
                // "unknown consent" must be able to rely on it.
                o.stringValue("code", "CONSENT_NOT_FOUND")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeConsentValidatesPact")
    fun `an active in-scope consent validates as allowed and names the granted accounts`(mockServer: MockServer) {
        assertClientPathMatchesContract(PACT_CONSENT_ID, EXPECTED_VALIDATE_PATH)

        val raw = postValidate(mockServer, clientDerivedValidatePath(PACT_CONSENT_ID), COVERED_REQUEST)

        // Bound into the REAL client DTO, not asserted off a JsonPath: renaming a field on
        // ConsentValidationResponse reddens HERE, which is the half the provider replay cannot see.
        val response = mapper.readValue<ConsentValidationResponse>(raw)
        assertThat(response.valid).isTrue()
        assertThat(response.grantedAccounts).containsExactly(PACT_ACCOUNT_IBAN)
        assertThat(response.scopes).containsExactly(ConsentScopes.ACCOUNTS_READ)
        assertThat(response.frequencyPerDay).isEqualTo(4)
    }

    @Test
    @PactTestFor(pactMethod = "unknownConsentIsDeniedPact")
    fun `an unknown consent id is denied in the body with a machine-readable code`(mockServer: MockServer) {
        assertClientPathMatchesContract(UNKNOWN_CONSENT_ID, EXPECTED_UNKNOWN_VALIDATE_PATH)

        val raw = postValidate(mockServer, clientDerivedValidatePath(UNKNOWN_CONSENT_ID), UNKNOWN_REQUEST)

        val response = mapper.readValue<ConsentValidationResponse>(raw)
        assertThat(response.valid).isFalse()
        assertThat(response.code).isEqualTo("CONSENT_NOT_FOUND")
        assertThat(response.reason).isNotBlank()
    }

    private fun postValidate(mockServer: MockServer, path: String, request: ValidateConsentRequest): String = given()
        .baseUri(mockServer.getUrl())
        .accept("application/json")
        .contentType("application/json")
        .body(mapper.writeValueAsString(request))
        // Reflected off the client, NOT retyped: this is the request the real client issues.
        .post(path)
        .then()
        .statusCode(200)
        .extract().asString()

    /**
     * The asymmetry that makes this contract falsifiable at the consumer layer: the path the client
     * would really call, recomputed from [ConsentValidateClient]'s own annotations, must equal the
     * literal this pact promises consent-service. A `@Path` edit on the client reddens here.
     */
    private fun assertClientPathMatchesContract(id: String, expected: String) {
        assertThat(clientDerivedValidatePath(id))
            .describedAs(
                "ConsentValidateClient's @Path no longer produces the path this pact pins — either " +
                    "fix the client or update the literal *and* re-verify against consent-service",
            )
            .isEqualTo(expected)
    }

    private companion object {
        const val CONSUMER = "openbank-mcp-service"
        const val PROVIDER = "openbank-consent-service"

        /** Seeded by consent-service's `an ACTIVE AISP consent covers the pact account…` state. */
        const val PACT_CONSENT_ID = "c1c1c1c1-d2d2-4e4e-8f8f-a9a9a9a9a9a9"

        /** Never seeded — a fresh Testcontainer DB satisfies the unknown-id state by construction. */
        const val UNKNOWN_CONSENT_ID = "00000000-0000-4000-8000-0000000c0de0"

        const val PACT_GRANTEE_ID = "agent:pact-verify-mcp"
        const val PACT_ACCOUNT_IBAN = "CZ6508000000192000145399"

        /**
         * LITERALS, deliberately retyped from consent-service's `ConsentResource`
         * (`@Path("/api/v1/consents")` + `@POST @Path("/{id}/validate")`). Never derive these from
         * the client — see the class KDoc.
         */
        const val EXPECTED_VALIDATE_PATH = "/api/v1/consents/$PACT_CONSENT_ID/validate"
        const val EXPECTED_UNKNOWN_VALIDATE_PATH = "/api/v1/consents/$UNKNOWN_CONSENT_ID/validate"

        val COVERED_REQUEST = ValidateConsentRequest(
            granteeId = PACT_GRANTEE_ID,
            // The exact ConsentScope enum NAME consent-service deserializes into — a value the
            // enum lacks would be a 400, which only the provider replay can reveal.
            requiredScope = ConsentScopes.ACCOUNTS_READ,
            accountIban = PACT_ACCOUNT_IBAN,
        )

        val UNKNOWN_REQUEST = ValidateConsentRequest(
            granteeId = PACT_GRANTEE_ID,
            requiredScope = ConsentScopes.ACCOUNTS_READ,
            accountIban = PACT_ACCOUNT_IBAN,
        )

        fun clientDerivedValidatePath(id: String): String {
            val base = ConsentValidateClient::class.java.getAnnotation(Path::class.java).value
            val method = ConsentValidateClient::class.java.methods
                .single { it.name == "validate" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{id}", id)
        }
    }
}
