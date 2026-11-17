// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.e2e

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * The decoupled SCA journey over the real HTTP surface: enroll a device → raise a
 * dynamic-linked payment challenge → the device signs an APPROVED decision out of band →
 * verify completes it. Ordered because each step is only reachable from the previous one, and
 * `@TestSecurity` fixes one identity per method.
 *
 * This is the strongest shape the e2e layer can take here: the decision endpoint's security
 * boundary is the signature check against the challenge's dynamic-linking payload (RTS Art. 5 —
 * the device must have signed THIS amount and payee), not caller identity, so the test signs the
 * real payload with the enrolled key pair and everything — Postgres, Redis decision/OTP stores,
 * the HTTP routes — is production code.
 *
 * The integration tests in this module prove one property each (decision durability, idempotency
 * fingerprint, outbox atomicity); this proves the customer-visible route sequence serves the whole
 * journey, so a wiring regression that deregisters a route (#3371) or answers the wrong status
 * for a step fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "e2e-sca-operator", roles = ["ROLE_OPERATOR"])
class ScaDecoupledApprovalE2E {

    private val party = UUID.randomUUID()
    private val credentialId = "e2e-${UUID.randomUUID()}"
    private val amount = "1250.00"
    private val currency = "CZK"
    private val creditorIban = "CZ6508000000192000145399"
    private val reference = "e2e-payment-${UUID.randomUUID()}"
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    @Order(1)
    fun `1 - the customer enrolls a device and it lists back`() {
        given()
            .contentType("application/json")
            .body(
                mapOf(
                    "credentialId" to credentialId,
                    "publicKey" to Base64.getEncoder().encodeToString(keys.public.encoded),
                    "algorithm" to "ES256",
                ),
            )
            .post("/api/v1/sca/parties/{partyId}/devices", party)
            .then()
            .statusCode(201)
            .body("credentialId", equalTo(credentialId))

        given().header("Content-Type", "application/json")
            .get("/api/v1/sca/parties/{partyId}/devices", party)
            .then()
            .statusCode(200)
            .body("credentialId", hasItem(credentialId))
    }

    @Test
    @Order(2)
    fun `2 - a dynamic-linked payment challenge is raised and awaits the device decision`() {
        challengeId = given()
            .contentType("application/json")
            .header("Idempotency-Key", "e2e-${UUID.randomUUID()}")
            .body(
                """
                {
                  "partyId": "$party",
                  "purpose": "PAYMENT_INITIATION",
                  "preferredMethod": "BIOMETRIC",
                  "dynamicLinkingData": {
                    "amount": "$amount",
                    "currency": "$currency",
                    "creditorIban": "$creditorIban",
                    "creditorName": "E2E Creditor",
                    "reference": "$reference"
                  },
                  "redirectUrl": null
                }
                """.trimIndent(),
            )
            .post("/api/v1/sca/challenges")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("status", equalTo("PENDING"))
            .body("method", equalTo("BIOMETRIC"))
            .extract()
            .path<String>("id")

        given().header("Content-Type", "application/json")
            .get("/api/v1/sca/parties/{partyId}/challenges/pending", party)
            .then()
            .statusCode(200)
            .body("id", hasItem(challengeId))

        // No decision yet: verify stays PENDING and consumes no attempt (safer than a bypass).
        given()
            .contentType("application/json")
            .body(mapOf("partyId" to party))
            .post("/api/v1/sca/challenges/{id}/verify", challengeId)
            .then()
            .statusCode(200)
            .body("status", equalTo("PENDING"))
            .body("attemptCount", equalTo(0))
    }

    @Test
    @Order(3)
    fun `3 - the device signs APPROVED over this challenge's amount and payee`() {
        given()
            .contentType("application/json")
            .body(
                mapOf(
                    "credentialId" to credentialId,
                    "decision" to "APPROVED",
                    "signature" to sign(decisionPayload()),
                ),
            )
            .post("/api/v1/sca/challenges/{id}/decision", challengeId)
            .then()
            .statusCode(200)
            .body("status", equalTo("PENDING"))
    }

    @Test
    @Order(4)
    fun `4 - verify now completes the challenge and it leaves the pending list`() {
        given()
            .contentType("application/json")
            .body(mapOf("partyId" to party))
            .post("/api/v1/sca/challenges/{id}/verify", challengeId)
            .then()
            .statusCode(200)
            .body("status", equalTo("COMPLETED"))

        given().header("Content-Type", "application/json")
            .get("/api/v1/sca/challenges/{id}", challengeId)
            .then()
            .statusCode(200)
            .body("status", equalTo("COMPLETED"))

        given().header("Content-Type", "application/json")
            .get("/api/v1/sca/parties/{partyId}/challenges/pending", party)
            .then()
            .statusCode(200)
            .body("id", not(hasItem(challengeId)))
    }

    /** The dynamic-linking bytes the service verifies against: id|decision|amount|currency|iban|reference. */
    private fun decisionPayload(): ByteArray =
        listOf(challengeId, "APPROVED", amount, currency, creditorIban, reference)
            .joinToString("|")
            .toByteArray(Charsets.UTF_8)

    private fun sign(payload: ByteArray): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(payload)
        Base64.getEncoder().encodeToString(sign())
    }

    private companion object {
        lateinit var challengeId: String
    }
}
