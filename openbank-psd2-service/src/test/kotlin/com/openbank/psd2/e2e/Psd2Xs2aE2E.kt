// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.e2e

import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import com.github.tomakehurst.wiremock.client.WireMock.equalTo as wmEqual

/**
 * E2E proof of the Berlin Group NextGenPSD2 XS2A chain over REAL adapters with a downstream
 * WireMock standing in for tpp-registry, consent-service and the OIDC token endpoint
 * ([Psd2E2EWireMockResource], [Psd2E2EProfile] — the module's @Mock registry fake is excluded so
 * the real EidasMtlsFilter → TppAuthorizationGuard → REST client round trip is what gets tested).
 *
 * Every `/v1` request crosses three real hops before the domain runs: the QWAC transport
 * authorization against tpp-registry (role selected by path — AISP outside `/payments`, PISP
 * inside), the fail-closed consent validation against consent-service (scope selected by
 * endpoint, M2M OIDC bearer attached by the reactive filter), and — on the account list — a
 * consent read to resolve the party. The WireMock request journal is the evidence: fan-out
 * counts, verbatim query params and bodies, the bearer on the wire, and zero-fan-out proofs for
 * the reject paths.
 */
@QuarkusTest
@TestProfile(Psd2E2EProfile::class)
class Psd2Xs2aE2E {

    @BeforeEach
    fun resetJournal() {
        Psd2E2EWireMockResource.resetRequests()
    }

    @Test
    fun `ais account list validates the consent over the wire and resolves the party`() {
        val xReq = UUID.randomUUID().toString()
        Given {
            header("X-TPP-ID", TPP_GOOD)
            header("Consent-ID", CONSENT_ACTIVE)
            header("X-Request-ID", xReq)
        } When { get("/v1/accounts") } Then {
            statusCode(200)
            header("X-Request-ID", equalTo(xReq))
            body("accounts[0].resourceId", equalTo("acc-001"))
            body("accounts[0].iban", equalTo("CZ6508000000192000145399"))
        }

        // Transport authorization: exactly one registry check for this request, AISP role.
        assertThat(registryChecks(null)).isEqualTo(1)
        assertThat(registryChecks("AISP")).isEqualTo(1)
        // Consent validation crossed the wire: ACCOUNTS_READ for the grantee TPP, no account bound.
        val validations = validateRequests(null)
        assertThat(validations).hasSize(1)
        assertThat(validations[0].bodyAsString)
            .contains("\"granteeId\":\"$TPP_GOOD\"")
            .contains("\"requiredScope\":\"ACCOUNTS_READ\"")
        // Party resolution reads the consent back — with the M2M bearer the OIDC filter attached.
        val reads = consentReads()
        assertThat(reads).hasSize(1)
        assertThat(reads[0].url).endsWith("/$CONSENT_ACTIVE")
        assertThat(reads[0].getHeader("Authorization")).isEqualTo("Bearer psd2-e2e-token")
    }

    @Test
    fun `ais balances bind the validated scope to the account id`() {
        Given {
            header("X-TPP-ID", TPP_GOOD)
            header("Consent-ID", CONSENT_ACTIVE)
            header("X-Request-ID", UUID.randomUUID().toString())
        } When { get("/v1/accounts/acc-001/balances") } Then {
            statusCode(200)
            body("account.iban", equalTo("acc-001"))
            body("balances[0].balanceType", equalTo("closingBooked"))
        }
        val validations = validateRequests(null)
        assertThat(validations).hasSize(1)
        assertThat(validations[0].bodyAsString)
            .contains("\"requiredScope\":\"BALANCES_READ\"")
            .contains("\"accountIban\":\"acc-001\"")
        // Balances resolve no party — the consent read stays at zero.
        assertThat(consentReads()).isEmpty()
    }

    @Test
    fun `ais transactions with a consent that fails validation are rejected before any data path`() {
        Given {
            header("X-TPP-ID", TPP_GOOD)
            header("Consent-ID", "consent-denied-e2e")
            header("X-Request-ID", UUID.randomUUID().toString())
        } When { get("/v1/accounts/acc-001/transactions") } Then {
            statusCode(401)
            body("tppMessages[0].code", equalTo("CONSENT_INVALID"))
        }
        assertThat(validateRequests("TRANSACTIONS_READ")).hasSize(1)
        assertThat(consentReads()).isEmpty()
    }

    @Test
    fun `pis sepa initiation selects the PISP role and validates the payment scope with the debtor iban`() {
        val xReq = UUID.randomUUID().toString()
        Given {
            contentType("application/json")
            header("X-TPP-ID", TPP_GOOD)
            header("Consent-ID", CONSENT_ACTIVE)
            header("X-Request-ID", xReq)
            body(sepaPayment("10.50", "Acme s.r.o."))
        } When { post("/v1/payments/sepa-credit-transfers") } Then {
            statusCode(201)
            header("X-Request-ID", equalTo(xReq))
            body("transactionStatus", equalTo("RCVD"))
            body("paymentId", notNullValue())
        }
        // The `/payments` path segment is what selects the PISP role at the transport gate.
        assertThat(registryChecks(null)).isEqualTo(1)
        assertThat(registryChecks("PISP")).isEqualTo(1)
        val validations = validateRequests(null)
        assertThat(validations).hasSize(1)
        assertThat(validations[0].bodyAsString)
            .contains("\"requiredScope\":\"PAYMENTS_INITIATE\"")
            .contains("\"accountIban\":\"CZ6508000000192000145399\"")
    }

    @Test
    fun `pis replay with the same request id serves the stored outcome and never revalidates`() {
        val xReq = UUID.randomUUID().toString()
        fun initiate(amount: String): String = (
            Given {
                contentType("application/json")
                header("X-TPP-ID", TPP_GOOD)
                header("Consent-ID", CONSENT_ACTIVE)
                header("X-Request-ID", xReq)
                body(sepaPayment(amount, "Acme s.r.o."))
            } When { post("/v1/payments/sepa-credit-transfers") } Then { statusCode(201) }
            ).extract().path<String>("paymentId")

        val first = initiate("10.50")
        val replayed = initiate("10.50")
        assertThat(replayed).isEqualTo(first)
        // The consent was validated exactly once: the second response came out of the real Redis
        // idempotency store, not out of a re-executed (and re-validated) payment.
        assertThat(validateRequests("PAYMENTS_INITIATE")).hasSize(1)

        // The same request id with a DIFFERENT payment is a conflict, not a replay.
        Given {
            contentType("application/json")
            header("X-TPP-ID", TPP_GOOD)
            header("Consent-ID", CONSENT_ACTIVE)
            header("X-Request-ID", xReq)
            body(sepaPayment("99.90", "Acme s.r.o."))
        } When { post("/v1/payments/sepa-credit-transfers") } Then {
            statusCode(409)
            body("tppMessages[0].code", equalTo("IDEMPOTENCY_KEY_REUSED"))
        }
        assertThat(validateRequests("PAYMENTS_INITIATE")).hasSize(1)
    }

    @Test
    fun `pis initiation without a payment-authorising consent is rejected fail-closed`() {
        Given {
            contentType("application/json")
            header("X-TPP-ID", TPP_GOOD)
            header("Consent-ID", "consent-denied-e2e")
            header("X-Request-ID", UUID.randomUUID().toString())
            body(sepaPayment("10.50", "Acme s.r.o."))
        } When { post("/v1/payments/sepa-credit-transfers") } Then {
            statusCode(401)
            body("tppMessages[0].code", equalTo("CONSENT_INVALID"))
        }
        assertThat(validateRequests("PAYMENTS_INITIATE")).hasSize(1)
    }

    @Test
    fun `a tpp the registry rejects never reaches consent-service`() {
        Given {
            header("X-TPP-ID", "TPP-BANNED")
            header("Consent-ID", CONSENT_ACTIVE)
            header("X-Request-ID", UUID.randomUUID().toString())
        } When { get("/v1/accounts") } Then {
            statusCode(401)
            body("tppMessages[0].code", equalTo("CERTIFICATE_INVALID"))
            body("tppMessages[0].text", equalTo("certificate revoked by supervisor"))
        }
        // Zero fan-out: the registry's no is the last downstream call.
        assertThat(validateRequests(null)).isEmpty()
        assertThat(consentReads()).isEmpty()
    }

    @Test
    fun `a registry outage fans out over the guard retry and fails closed as unavailable`() {
        Given {
            header("X-TPP-ID", "TPP-OUTAGE")
            header("Consent-ID", CONSENT_ACTIVE)
            header("X-Request-ID", UUID.randomUUID().toString())
        } When { get("/v1/accounts") } Then { statusCode(503) }
        // TppAuthorizationGuard: initial attempt + 2 retries = 3 registry calls for one request.
        assertThat(registryChecks(null)).isEqualTo(3)
        assertThat(validateRequests(null)).isEmpty()
    }

    @Test
    fun `a consent-service outage retries then denies through the fail-closed fallback`() {
        Given {
            header("X-TPP-ID", TPP_GOOD)
            header("Consent-ID", "consent-outage-e2e")
            header("X-Request-ID", UUID.randomUUID().toString())
        } When { get("/v1/accounts") } Then {
            statusCode(401)
            body("tppMessages[0].code", equalTo("CONSENT_INVALID"))
        }
        // ResilientConsentServiceClient: initial attempt + 3 retries = 4 validation calls,
        // then the fallback denies instead of ever answering from a broken downstream.
        assertThat(validateRequests(null)).hasSize(4)
    }

    @Test
    fun `berlin consent lifecycle create get status and delete over the real chain`() {
        // X-Request-ID is the Berlin request identifier and this surface's idempotency key.
        Given {
            contentType("application/json")
            header("X-TPP-ID", TPP_GOOD)
            body(CONSENT_CREATE_BODY)
        } When { post("/v1/consents") } Then {
            statusCode(400)
            body("tppMessages[0].code", equalTo("FORMAT_ERROR"))
        }

        val xReq = UUID.randomUUID().toString()
        val created = (
            Given {
                contentType("application/json")
                header("X-TPP-ID", TPP_GOOD)
                header("X-Request-ID", xReq)
                body(CONSENT_CREATE_BODY)
            } When { post("/v1/consents") } Then {
                statusCode(201)
                body("consentStatus", equalTo("received"))
                body("consentId", notNullValue())
            }
            ).extract()
        assertThat(created.header("Location")).endsWith("/v1/consents/${created.path<String>("consentId")}")
        assertThat(created.header("X-Request-ID")).isEqualTo(xReq)

        // Consent information: the consent-service status maps onto the Berlin enum (ACTIVE → valid).
        Given {
            header("X-TPP-ID", TPP_GOOD)
            header("X-Request-ID", UUID.randomUUID().toString())
        } When { get("/v1/consents/$CONSENT_ACTIVE") } Then {
            statusCode(200)
            body("consentStatus", equalTo("valid"))
        }
        Given {
            header("X-TPP-ID", TPP_GOOD)
            header("X-Request-ID", UUID.randomUUID().toString())
        } When { get("/v1/consents/consent-pending-e2e/status") } Then {
            statusCode(200)
            body("consentStatus", equalTo("received"))
        }
        // Revocation of the platform consent is a clean no-content.
        Given {
            header("X-TPP-ID", TPP_GOOD)
            header("X-Request-ID", UUID.randomUUID().toString())
        } When { delete("/v1/consents/$CONSENT_ACTIVE") } Then { statusCode(204) }
        assertThat(consentReads()).hasSize(2)
    }

    private fun sepaPayment(amount: String, creditor: String) = """
        {"endToEndIdentification":"e2e-pis",
         "debtorAccount":{"iban":"CZ6508000000192000145399","currency":"CZK"},
         "instructedAmount":{"currency":"CZK","amount":$amount},
         "creditorAccount":{"iban":"CZ1234567890123456789012","currency":"CZK"},
         "creditorName":"$creditor"}
    """.trimIndent()

    private fun registryChecks(role: String?): Int = Psd2E2EWireMockResource.requestsTo(
        getRequestedFor(urlPathEqualTo(CHECK_PATH)).apply { role?.let { withQueryParam("role", wmEqual(it)) } },
    )

    private fun validateRequests(scope: String?): List<LoggedRequest> {
        val pattern = postRequestedFor(urlPathMatching("/api/v1/consents/[^/]+/validate")).apply {
            scope?.let { withRequestBody(containing("\"requiredScope\":\"$it\"")) }
        }
        return Psd2E2EWireMockResource.wm().findAll(pattern)
    }

    private fun consentReads() = Psd2E2EWireMockResource.wm()
        .findAll(getRequestedFor(urlPathMatching("/api/v1/consents/[^/]+")))

    companion object {
        private const val TPP_GOOD = "TPP-GOOD"
        private const val CONSENT_ACTIVE = "consent-active-e2e"
        private const val CHECK_PATH = "/api/v1/tpp-registry/check"

        private val CONSENT_CREATE_BODY = """
            {"access":{"accounts":[{"iban":"CZ6508000000192000145399"}]},
            "recurringIndicator":true,"validUntil":"2027-12-31","frequencyPerDay":4}
        """.trimIndent()
    }
}
