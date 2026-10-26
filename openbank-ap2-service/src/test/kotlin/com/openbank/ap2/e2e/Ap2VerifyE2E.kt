// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat

/**
 * E2E suite for the AP2 mandate verification surface (ADR-0193), proving the two ports no other
 * test reaches over a wire: the REAL OPA HTTP decision client (the contract
 * TestPolicyDecisionPoint alternative is excluded by [Ap2E2EProfile]) and the REAL JCA signature
 * path — mandates are signed in-test with private keys whose public halves crossed the real
 * `ap2.trust-list` config property into the real TrustListKeyResolver. The OPA request journal is
 * the authorization wire evidence: which agent principal was decided on, with which mandateKind
 * attribute.
 */
@QuarkusTest
@TestProfile(Ap2E2EProfile::class)
class Ap2VerifyE2E {

    private val signingInput = "eyJhbGciOiJFUzI1NiJ9.eyJraW5kIjoiUEFZTUVOVCJ9"

    @BeforeEach
    fun resetPdp() {
        Ap2E2EWireMockResource.resetToAllow()
    }

    private fun mandateJson(
        issuer: String = Ap2E2EWireMockResource.TRUSTED_ISSUER,
        signatureB64: String = Ap2E2EWireMockResource.signEs256(signingInput),
        algorithm: String = "ES256",
        signingInputOverride: String = signingInput,
        cap: Long = 50000,
        payee: String = "Coffee Republic s.r.o.",
        currency: String = "CZK",
        expiresAt: String = "2026-12-31T23:59:59Z",
        paymentPayee: String = payee,
        paymentAmountMinor: Long = 4200,
    ) = """{
        "mandate": {
          "kind": "PAYMENT",
          "issuer": "$issuer",
          "subject": "customer-42",
          "constraints": {
            "payee": "$payee",
            "amountCapMinor": $cap,
            "currency": "$currency",
            "expiresAt": "$expiresAt",
            "singleUse": false
          },
          "signingInput": "$signingInputOverride",
          "signatureB64": "$signatureB64",
          "algorithm": "$algorithm"
        },
        "payment": {
          "payee": "$paymentPayee",
          "amountMinor": $paymentAmountMinor,
          "currency": "CZK",
          "at": "2026-10-26T12:00:00Z"
        }
    }"""

    @Test
    fun `a genuinely signed in-bounds mandate verifies with the real JCA path and a real OPA decision`() {
        Given {
            contentType("application/json")
            header("X-Agent-Id", "agent:e2e-trader")
            body(mandateJson())
        } When { post("/ap2/verify") } Then {
            statusCode(200)
            body("valid", equalTo(true))
            body("evidence.signatureValid", equalTo(true))
            body("evidence.constraintsSatisfied", equalTo(true))
            body("evidence.issuer", equalTo(Ap2E2EWireMockResource.TRUSTED_ISSUER))
            // Data minimisation (ADR-0193 GDPR row): the evidence carries the mandate HASH, not
            // the credential — the hash recomputed independently here.
            body(
                "evidence.mandateHash",
                equalTo(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signingInput.toByteArray())),
                ),
            )
            body("failures", equalTo(emptyList<Any>()))
        }

        // The authorization wire evidence: the real OPA client posted the agent principal and the
        // mandateKind attribute the endpoint derives.
        assertThat(Ap2E2EWireMockResource.opaPosts()).isEqualTo(1)
        val pdpBody = Ap2E2EWireMockResource.wm()
            .findAll(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/v1/data/openbank/rest/allow"),
                ),
            )[0].bodyAsString
        assertThat(pdpBody)
            .contains("\"id\":\"agent:e2e-trader\"")
            .contains("\"type\":\"AI_AGENT\"")
            .contains("\"action\":\"verify.mandate\"")
            .contains("\"mandateKind\":\"PAYMENT\"")
    }

    @Test
    fun `a payment above the cap fails the constraint stage with a valid signature`() {
        Given {
            contentType("application/json")
            header("X-Agent-Id", "agent:e2e-trader")
            body(mandateJson(cap = 4000))
        } When { post("/ap2/verify") } Then {
            statusCode(200)
            body("valid", equalTo(false))
            body("evidence.signatureValid", equalTo(true))
            body("evidence.constraintsSatisfied", equalTo(false))
            body("failures[0]", containsString("amount 4200 exceeds cap 4000"))
        }
    }

    @Test
    fun `an expired mandate with the wrong payee and currency names every violation`() {
        Given {
            contentType("application/json")
            header("X-Agent-Id", "agent:e2e-trader")
            body(
                mandateJson(
                    currency = "EUR",
                    expiresAt = "2026-01-01T00:00:00Z",
                    paymentPayee = "Other Payee",
                ),
            )
        } When { post("/ap2/verify") } Then {
            statusCode(200)
            body("valid", equalTo(false))
            body("evidence.signatureValid", equalTo(true))
            body(
                "failures",
                org.hamcrest.Matchers.hasItems(
                    containsString("mandate expired at"),
                    containsString("currency CZK does not match mandate EUR"),
                    containsString("payee Other Payee does not match mandate Coffee Republic s.r.o."),
                ),
            )
        }
    }

    @Test
    fun `an issuer absent from the trust list fails closed as not trusted`() {
        Given {
            contentType("application/json")
            header("X-Agent-Id", "agent:e2e-trader")
            body(mandateJson(issuer = "mallory"))
        } When { post("/ap2/verify") } Then {
            statusCode(200)
            body("valid", equalTo(false))
            body("evidence.signatureValid", equalTo(false))
            body("failures[0]", containsString("issuer not trusted: mallory"))
        }
    }

    @Test
    fun `a tampered signing input fails signature verification against the trusted key`() {
        val signatureOverOtherInput = Ap2E2EWireMockResource.signEs256("eyJhbGciOiJFUzI1NiJ9.dGFtcGVyZWQ")
        Given {
            contentType("application/json")
            header("X-Agent-Id", "agent:e2e-trader")
            body(mandateJson(signatureB64 = signatureOverOtherInput))
        } When { post("/ap2/verify") } Then {
            statusCode(200)
            body("valid", equalTo(false))
            body("evidence.signatureValid", equalTo(false))
            body("failures[0]", containsString("signature invalid"))
        }
    }

    @Test
    fun `an Ed25519-signed mandate verifies over the second real JCA path`() {
        val edInput = "eyJhbGciOiJFZERTQSJ9.eyJraW5kIjoiUEFZTUVOVCJ9"
        Given {
            contentType("application/json")
            header("X-Agent-Id", "agent:e2e-trader")
            body(
                mandateJson(
                    issuer = Ap2E2EWireMockResource.TRUSTED_ISSUER_ED,
                    signatureB64 = Ap2E2EWireMockResource.signEd25519(edInput),
                    algorithm = "ED25519",
                    signingInputOverride = edInput,
                ),
            )
        } When { post("/ap2/verify") } Then {
            statusCode(200)
            body("valid", equalTo(true))
            body("evidence.signatureValid", equalTo(true))
        }
    }

    @Test
    fun `a denied decision answers 403 with the policy reason and verifies nothing`() {
        Ap2E2EWireMockResource.stubDeny()

        Given {
            contentType("application/json")
            header("X-Agent-Id", "agent:e2e-trader")
            body(mandateJson())
        } When { post("/ap2/verify") } Then {
            statusCode(403)
            body("error", equalTo("denied by policy"))
            body("reason", equalTo("no matching allow rule"))
        }
        // The decision crossed the wire exactly once and the denied call never reached the
        // verifier — there is no verdict in the response.
        assertThat(Ap2E2EWireMockResource.opaPosts()).isEqualTo(1)
    }

    @Test
    fun `a caller without an agent header is decided as the anonymous agent`() {
        Ap2E2EWireMockResource.stubDeny()

        Given {
            contentType("application/json")
            body(mandateJson())
        } When { post("/ap2/verify") } Then {
            statusCode(403)
        }
        val pdpBody = Ap2E2EWireMockResource.wm()
            .findAll(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/v1/data/openbank/rest/allow"),
                ),
            )[0].bodyAsString
        assertThat(pdpBody).contains("\"id\":\"agent:ap2-anonymous\"")
    }

    @Test
    fun `a pdp outage fails closed with 503, never a verdict`() {
        Ap2E2EWireMockResource.stubOutage()

        Given {
            contentType("application/json")
            header("X-Agent-Id", "agent:e2e-trader")
            body(mandateJson())
        } When { post("/ap2/verify") } Then {
            statusCode(503)
            body("error", equalTo("authorization unavailable"))
        }
    }

    @Test
    fun `a malformed body is a 400 before any policy call`() {
        Given {
            contentType("application/json")
            header("X-Agent-Id", "agent:e2e-trader")
            body("{}")
        } When { post("/ap2/verify") } Then {
            statusCode(400)
        }
        assertThat(Ap2E2EWireMockResource.opaPosts()).isZero()
    }
}
