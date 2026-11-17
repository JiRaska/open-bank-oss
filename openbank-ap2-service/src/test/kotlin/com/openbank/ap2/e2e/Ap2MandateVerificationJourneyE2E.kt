// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.HexFormat

/**
 * The AP2 mandate-verification journey over the real HTTP surface (ADR-0193): an agent presents a
 * signed PAYMENT mandate with the payment it authorizes, the service resolves the issuer key from
 * the configured trust list, verifies the ED25519 signature, and checks the payment against the
 * mandate's declared constraints — payee, amount cap, currency, expiry — returning a data-minimised
 * verdict whose `mandateHash` is the SHA-256 of the signing input, never the credential itself.
 *
 * The module's tests prove one property each (the pure constraint table, the PDP gate in-process,
 * the wire schema against `openapi.yaml`); this proves the route sequence serves the whole
 * "present, verify, and record evidence" story, so a wiring regression that deregisters the route
 * (#3371), resolves no issuer key, or stops running the constraint stage fails here even when
 * every property-level assertion stays green.
 *
 * The mandate is signed for real with a fixed ED25519 key pair whose public half is registered in
 * the trust list by the test profile, so the happy path exercises BOTH verification stages over
 * HTTP — a verdict of `valid: true` is impossible if either the signature stage or the constraint
 * stage is broken. The PDP alternative (`TestPolicyDecisionPoint`, keying its decision on the
 * mandate kind the endpoint really passes) allows PAYMENT and denies every other kind, so the
 * 403 path is driven through the same running container.
 */
@QuarkusTest
@TestProfile(Ap2MandateVerificationJourneyE2E.Profile::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Ap2MandateVerificationJourneyE2E {

    private val signingInput = "e2e.PAYMENT.$PAYEE.$CAP.$CURRENCY.$EXPIRES_AT.v1"

    @Test
    @Order(1)
    fun `1 - a compliant signed mandate verifies valid end to end`() {
        Given {
            contentType("application/json")
            header(AGENT_HEADER, AGENT_ID)
            body(verifyBody(mandate("PAYMENT", signingInput), payment(PAYEE, CAP, CURRENCY, PAYMENT_AT)))
        } When {
            post("/ap2/verify")
        } Then {
            statusCode(200)
            body("valid", equalTo(true))
            body("evidence.signatureValid", equalTo(true))
            body("evidence.constraintsSatisfied", equalTo(true))
            body("evidence.mandateKind", equalTo("PAYMENT"))
            body("evidence.issuer", equalTo(ISSUER))
            body("evidence.subject", equalTo(SUBJECT))
            body("evidence.mandateHash", equalTo(sha256Hex(signingInput)))
            body("evidence.constraints.payee", equalTo(PAYEE))
            body("evidence.constraints.amountCapMinor", equalTo(CAP.toInt()))
            body("evidence.constraints.currency", equalTo(CURRENCY))
            body("failures", equalTo(emptyList<String>()))
        }
    }

    @Test
    @Order(2)
    fun `2 - an amount over the cap is outside the mandate`() {
        Given {
            contentType("application/json")
            header(AGENT_HEADER, AGENT_ID)
            body(
                verifyBody(
                    mandate("PAYMENT", "$signingInput.over-cap"),
                    payment(PAYEE, CAP + 1, CURRENCY, PAYMENT_AT),
                ),
            )
        } When {
            post("/ap2/verify")
        } Then {
            statusCode(200)
            body("valid", equalTo(false))
            body("evidence.constraintsSatisfied", equalTo(false))
            body("failures", hasItem(containsString("exceeds cap")))
        }
    }

    @Test
    @Order(3)
    fun `3 - a currency mismatch is outside the mandate`() {
        Given {
            contentType("application/json")
            header(AGENT_HEADER, AGENT_ID)
            body(
                verifyBody(
                    mandate("PAYMENT", "$signingInput.wrong-currency"),
                    payment(PAYEE, CAP, "EUR", PAYMENT_AT),
                ),
            )
        } When {
            post("/ap2/verify")
        } Then {
            statusCode(200)
            body("valid", equalTo(false))
            body("failures", hasItem(containsString("currency")))
        }
    }

    @Test
    @Order(4)
    fun `4 - a different payee is outside the mandate`() {
        Given {
            contentType("application/json")
            header(AGENT_HEADER, AGENT_ID)
            body(
                verifyBody(
                    mandate("PAYMENT", "$signingInput.wrong-payee"),
                    payment("CZ99OTHER", CAP, CURRENCY, PAYMENT_AT),
                ),
            )
        } When {
            post("/ap2/verify")
        } Then {
            statusCode(200)
            body("valid", equalTo(false))
            body("failures", hasItem(containsString("payee")))
        }
    }

    @Test
    @Order(5)
    fun `5 - a payment after the mandate expiry is outside the mandate`() {
        Given {
            contentType("application/json")
            header(AGENT_HEADER, AGENT_ID)
            body(
                verifyBody(
                    mandate("PAYMENT", "$signingInput.expired"),
                    payment(PAYEE, CAP, CURRENCY, "2027-01-01T00:00:00Z"),
                ),
            )
        } When {
            post("/ap2/verify")
        } Then {
            statusCode(200)
            body("valid", equalTo(false))
            body("failures", hasItem(containsString("expired")))
        }
    }

    @Test
    @Order(6)
    fun `6 - a policy-denied mandate kind is refused before any verification`() {
        Given {
            contentType("application/json")
            header(AGENT_HEADER, AGENT_ID)
            body(
                verifyBody(
                    mandate("INTENT", "$signingInput.intent"),
                    payment(PAYEE, CAP, CURRENCY, PAYMENT_AT),
                ),
            )
        } When {
            post("/ap2/verify")
        } Then {
            statusCode(403)
            body("error", equalTo("denied by policy"))
            body("reason", equalTo("no matching allow rule"))
        }
    }

    @Test
    @Order(7)
    fun `7 - a request without the required mandate is rejected not silently defaulted`() {
        Given {
            contentType("application/json")
            header(AGENT_HEADER, AGENT_ID)
            body(mapOf("payment" to payment(PAYEE, CAP, CURRENCY, PAYMENT_AT)))
        } When {
            post("/ap2/verify")
        } Then {
            statusCode(not(200))
        }
    }

    private fun mandate(kind: String, input: String) = mapOf(
        "kind" to kind,
        "issuer" to ISSUER,
        "subject" to SUBJECT,
        "algorithm" to "ED25519",
        "signingInput" to input,
        "signatureB64" to sign(input),
        "constraints" to mapOf(
            "payee" to PAYEE,
            "amountCapMinor" to CAP,
            "currency" to CURRENCY,
            "expiresAt" to EXPIRES_AT,
            "singleUse" to false,
        ),
    )

    private fun payment(payee: String, amountMinor: Long, currency: String, at: String) = mapOf(
        "payee" to payee,
        "amountMinor" to amountMinor,
        "currency" to currency,
        "at" to at,
    )

    private fun verifyBody(mandate: Map<String, Any>, payment: Map<String, Any>) =
        mapOf("mandate" to mandate, "payment" to payment)

    private fun sign(input: String): String {
        val privateKey = KeyFactory.getInstance("Ed25519")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_KEY_B64)))
        return Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(input.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    /** Registers the fixed journey issuer in the phase-1 trust list, exactly as an operator would. */
    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("ap2.trust-list" to "$ISSUER=$PUBLIC_KEY_B64")
    }

    private companion object {
        const val AGENT_HEADER = "X-Agent-Id"
        const val AGENT_ID = "agent:e2e-journey"
        const val ISSUER = "e2e-issuer"
        const val SUBJECT = "cust-e2e-1"
        const val PAYEE = "CZ6508000000192000145399"
        const val CAP = 100_00L
        const val CURRENCY = "CZK"
        const val EXPIRES_AT = "2026-12-31T00:00:00Z"
        const val PAYMENT_AT = "2026-06-01T00:00:00Z"
        const val PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAhr20p/0zporMEFcj0cckE2WteS6X31j5EEMLNVU/oi8="
        const val PRIVATE_KEY_B64 = "MC4CAQAwBQYDK2VwBCIEIE8fCvVVpCpgkutCglYfN85ZiAvMLABmbi6HUZ3JoKky"
    }
}
