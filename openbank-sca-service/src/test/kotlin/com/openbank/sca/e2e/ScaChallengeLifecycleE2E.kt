// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.e2e

import com.openbank.sca.integration.OutboxDispatchDisabledProfile
import com.openbank.sca.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The ADR-0021 challenge half of the SCA journey over real HTTP against real Postgres + Valkey:
 * initiate (idempotence, method gating), the device-signed decision (ownership, signature,
 * write-once, revocation), decoupled verify (never auto-approves, ADR-0021), and the settlement
 * gate consume (single-use, dynamic-linking binding, consumer scopes per ADR-0335). Durable
 * evidence (challenge row, decision outbox) is asserted over JDBC; the outbox dispatcher stays
 * disabled so the rows are owned by this suite.
 */
@QuarkusTest
@TestProfile(OutboxDispatchDisabledProfile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaChallengeLifecycleE2E : ScaE2EBase() {

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a payment challenge initiates pending and the challenge is readable`() {
        val party = UUID.randomUUID()
        val id = Given {
            spec(
                initiateSpec(paymentInitiateBody(party, "250.00", "EUR", IBAN, "Obchodnik A S", "REF-1")),
            )
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(201)
            body("status", equalTo("PENDING"))
            body("method", equalTo("PUSH_NOTIFICATION"))
            body("purpose", equalTo("PAYMENT_INITIATION"))
        } Extract {
            UUID.fromString(path("id"))
        }

        Given {
            pathParam("id", id)
        } When {
            get("/api/v1/sca/challenges/{id}")
        } Then {
            statusCode(200)
            body("status", equalTo("PENDING"))
            body("partyId", equalTo(party.toString()))
        }
        assertThat(challengeStatus(id)).isEqualTo("PENDING")
        assertThat(queryString("SELECT dynamic_amount FROM sca_challenges WHERE id = ?", id)).isEqualTo("250.00")
    }

    @Test
    @TestSecurity(user = OWN_PARTY, roles = ["ROLE_CUSTOMER"])
    fun `the pending list projects what the customer is approving`() {
        val challenge = UUID.randomUUID()
        insertChallenge(
            challenge,
            OWN_PARTY_UUID,
            "PAYMENT_INITIATION",
            "PUSH_NOTIFICATION",
            amount = "250.00",
            currency = "EUR",
            creditorIban = IBAN,
            creditorName = "Obchodnik A S",
            reference = "REF-1",
        )

        Given {
            pathParam("party", OWN_PARTY_UUID)
        } When {
            get("/api/v1/sca/parties/{party}/challenges/pending")
        } Then {
            statusCode(200)
            body("id", hasItem(challenge.toString()))
            body("amount", hasItem("250.00"))
            body("currency", hasItem("EUR"))
            body("creditorIban", hasItem(IBAN))
            body("creditorName", hasItem("Obchodnik A S"))
            body("reference", hasItem("REF-1"))
        }
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `initiate replays verbatim under the same key and refuses a mismatched body`() {
        val party = UUID.randomUUID()
        val key = "e2e-idem-${UUID.randomUUID()}"
        val body = paymentInitiateBody(party, "10.00", "EUR", IBAN, "Obchodnik B S", "REF-2")

        val first = Given {
            spec(initiateSpec(body, key))
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path("id"))
        }

        val replay = Given {
            spec(initiateSpec(body, key))
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", equalTo("true"))
        } Extract {
            UUID.fromString(path("id"))
        }
        assertThat(replay).isEqualTo(first)

        Given {
            spec(initiateSpec(loginInitiateBody(party), key))
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `TOTP is refused before a challenge exists`() {
        val party = UUID.randomUUID()
        Given {
            spec(
                jsonSpec("""{"partyId":"$party","purpose":"LOGIN","preferredMethod":"TOTP"}"""),
            )
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(422)
        }
        assertThat(countChallenges(party)).isZero()
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a device signed approval completes the challenge on verify and the evidence is durable`() {
        val party = UUID.randomUUID()
        val keys = newEs256KeyPair()
        val credential = "cred-life-${UUID.randomUUID()}"
        enroll(party, credential, keys)
        val challenge = initiate(party)

        decide(challenge, credential, "APPROVED", keys.signPayload(decisionPayload(challenge, "APPROVED")), 200)
        Given {
            spec(jsonSpec(verifyBody(party)))
        } When {
            post("/api/v1/sca/challenges/$challenge/verify")
        } Then {
            statusCode(200)
            body("status", equalTo("COMPLETED"))
            body("decidedByPartyId", equalTo(party.toString()))
        }

        assertThat(challengeStatus(challenge)).isEqualTo("COMPLETED")
        assertThat(
            queryString("SELECT decided_by_party_id FROM sca_challenges WHERE id = ?", challenge),
        ).isEqualTo(party.toString())
        // The decision evidence and its audit event commit together; the payload carries the credential.
        assertThat(outboxCount(challenge, "SCA_DEVICE_DECIDED")).isEqualTo(1)
        assertThat(outboxPayload(challenge, "SCA_DEVICE_DECIDED")["credentialId"].asText()).isEqualTo(credential)
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `decision guards enforce device ownership signature validity write-once and revocation`() {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val keysA = newEs256KeyPair()
        val keysB = newEs256KeyPair()
        val credA = "cred-guard-a-${UUID.randomUUID()}"
        val credB = "cred-guard-b-${UUID.randomUUID()}"
        enroll(partyA, credA, keysA)
        enroll(partyB, credB, keysB)

        // Another party's credential cannot decide this challenge.
        val ch1 = initiate(partyA)
        decide(ch1, credB, "APPROVED", keysB.signPayload(decisionPayload(ch1, "APPROVED")), 403)

        // A well-formed signature over the WRONG bytes is refused.
        val ch2 = initiate(partyA)
        decide(ch2, credA, "APPROVED", keysA.signPayload(decisionPayload(UUID.randomUUID(), "APPROVED")), 401)

        // Write-once: a second decision on the same challenge conflicts.
        val ch3 = initiate(partyA)
        val good = keysA.signPayload(decisionPayload(ch3, "APPROVED"))
        decide(ch3, credA, "APPROVED", good, 200)
        decide(ch3, credA, "APPROVED", good, 409)

        // A revoked credential cannot decide.
        val deviceId = queryString(
            "SELECT id FROM sca_enrolled_devices WHERE credential_id = ?",
            credA,
        )!!.let(UUID::fromString)
        Given {
            pathParam("party", partyA)
            pathParam("deviceId", deviceId)
        } When {
            delete("/api/v1/sca/parties/{party}/devices/{deviceId}")
        } Then {
            statusCode(204)
        }
        val ch4 = initiate(partyA)
        decide(ch4, credA, "APPROVED", keysA.signPayload(decisionPayload(ch4, "APPROVED")), 403)
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a denied decision never auto-approves and exhausts attempts like a failed OTP`() {
        val party = UUID.randomUUID()
        val keys = newEs256KeyPair()
        val credential = "cred-denied-${UUID.randomUUID()}"
        enroll(party, credential, keys)
        val challenge = initiate(party)
        decide(challenge, credential, "DENIED", keys.signPayload(decisionPayload(challenge, "DENIED")), 200)

        // Each verify consults the stored decision again; attempts accumulate until FAILED.
        verifyAttempt(party, challenge, "PENDING", 1)
        verifyAttempt(party, challenge, "PENDING", 2)
        Given {
            spec(jsonSpec(verifyBody(party)))
        } When {
            post("/api/v1/sca/challenges/$challenge/verify")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `an expired challenge refuses decision verify and consume`() {
        val party = UUID.randomUUID()
        val keys = newEs256KeyPair()
        val credential = "cred-expired-${UUID.randomUUID()}"
        enroll(party, credential, keys)
        val challenge = initiate(party)
        execute("UPDATE sca_challenges SET expires_at = now() - interval '1 second' WHERE id = '$challenge'")

        decide(challenge, credential, "APPROVED", keys.signPayload(decisionPayload(challenge, "APPROVED")), 422)
        Given {
            spec(jsonSpec(verifyBody(party)))
        } When {
            post("/api/v1/sca/challenges/$challenge/verify")
        } Then {
            statusCode(422)
        }
        consume(challenge, consumeBody(party), 422)
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `consume spends the approved challenge once and binds the amount and the payee`() {
        val party = UUID.randomUUID()
        val keys = newEs256KeyPair()
        val credential = "cred-consume-${UUID.randomUUID()}"
        enroll(party, credential, keys)
        val challenge = initiate(
            party,
            paymentInitiateBody(party, "100.00", "EUR", IBAN, "Obchodnik C S", "REF-9"),
        )
        decide(
            challenge,
            credential,
            "APPROVED",
            keys.signPayload(decisionPayload(challenge, "APPROVED", "100.00", "EUR", IBAN, "REF-9")),
            200,
        )

        // Numeric amount equality, case-insensitive currency, spacing-insensitive IBAN — the edge
        // forwards what the payment engine computed, the device signed what the customer saw.
        Given {
            spec(jsonSpec(consumeBody(party, amount = "100.0", currency = "eur", creditor = IBAN_SPACED)))
        } When {
            post("/api/v1/sca/challenges/$challenge/consume")
        } Then {
            statusCode(200)
            body("consumedAt", org.hamcrest.Matchers.notNullValue())
        }
        consume(challenge, consumeBody(party, amount = "100.00", currency = "EUR", creditor = IBAN), 409)

        // A refused consume never burns the challenge: wrong amount -> 409, still spendable.
        val other = initiate(party, paymentInitiateBody(party, "100.00", "EUR", IBAN, "Obchodnik C S", "REF-9"))
        decide(
            other,
            credential,
            "APPROVED",
            keys.signPayload(decisionPayload(other, "APPROVED", "100.00", "EUR", IBAN, "REF-9")),
            200,
        )
        consume(other, consumeBody(party, amount = "100.01", currency = "EUR", creditor = IBAN), 409)
        assertThat(challengeConsumedAt(other)).isNull()
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `consume refuses an unapproved pending challenge and a different party`() {
        val party = UUID.randomUUID()
        val keys = newEs256KeyPair()
        val credential = "cred-notapproved-${UUID.randomUUID()}"
        enroll(party, credential, keys)
        val pending = initiate(party)
        consume(pending, consumeBody(party), 400)

        val approved = initiate(party)
        decide(approved, credential, "APPROVED", keys.signPayload(decisionPayload(approved, "APPROVED")), 200)
        consume(approved, consumeBody(UUID.randomUUID()), 403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `an APPROVAL challenge requires binding and a general consumer cannot spend a reserved approval`() {
        val party = UUID.randomUUID()
        val keys = newEs256KeyPair()
        val credential = "cred-scope-${UUID.randomUUID()}"
        enroll(party, credential, keys)

        // Unbound APPROVAL is refused before a challenge exists.
        Given {
            spec(jsonSpec("""{"partyId":"$party","purpose":"APPROVAL","preferredMethod":"PUSH_NOTIFICATION"}"""))
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(400)
        }

        // A well-formed reserved-namespace approval: approved, but a GENERAL consumer may not spend it.
        val approvalId = "pension-exit:$SHA"
        val challenge = initiate(party, approvalInitiateBody(party, approvalId, SHA))
        decide(
            challenge,
            credential,
            "APPROVED",
            keys.signPayload(approvalPayload(challenge, "APPROVED", approvalId, SHA)),
            200,
        )
        consume(challenge, consumeBody(party, approvalRequestId = approvalId, payloadSha256 = SHA), 403)
        assertThat(challengeConsumedAt(challenge)).isNull()
    }

    @Test
    @TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_API"])
    fun `the reserved consumer spends its own well-formed approval once`() {
        val party = UUID.randomUUID()
        val keys = newEs256KeyPair()
        val credential = "cred-pension-${UUID.randomUUID()}"
        enroll(party, credential, keys)
        val approvalId = "pension-exit:$SHA"
        val challenge = initiate(party, approvalInitiateBody(party, approvalId, SHA))
        decide(
            challenge,
            credential,
            "APPROVED",
            keys.signPayload(approvalPayload(challenge, "APPROVED", approvalId, SHA)),
            200,
        )

        consume(challenge, consumeBody(party, approvalRequestId = approvalId, payloadSha256 = SHA), 200)
        consume(challenge, consumeBody(party, approvalRequestId = approvalId, payloadSha256 = SHA), 409)
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a TOTP challenge burns an attempt per wrong code and locks at max attempts`() {
        val party = UUID.randomUUID()
        val challenge = UUID.randomUUID()
        insertChallenge(challenge, party, "LOGIN", "TOTP")

        verifyAttempt(party, challenge, "PENDING", 1, otp = "000000")
        verifyAttempt(party, challenge, "PENDING", 2, otp = "000000")
        Given {
            spec(jsonSpec(verifyBody(party, otp = "000000")))
        } When {
            post("/api/v1/sca/challenges/$challenge/verify")
        } Then {
            statusCode(401)
        }
        Given {
            spec(jsonSpec(verifyBody(party, otp = "000000")))
        } When {
            post("/api/v1/sca/challenges/$challenge/verify")
        } Then {
            statusCode(429)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Journey steps shared by the tests above
    // ----------------------------------------------------------------------------------------------

    private fun enroll(party: UUID, credential: String, keys: java.security.KeyPair) {
        Given {
            spec(enrollSpec(party, credential, keys.spkiB64()))
        } When {
            post("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(201)
        }
    }

    private fun initiate(party: UUID, body: String? = null): UUID = Given {
        spec(initiateSpec(body ?: loginInitiateBody(party)))
    } When {
        post("/api/v1/sca/challenges")
    } Then {
        statusCode(201)
    } Extract {
        UUID.fromString(path("id"))
    }

    private fun decide(challenge: UUID, credential: String, decision: String, signature: String, expect: Int) {
        Given {
            spec(decideSpec(challenge, credential, decision, signature))
        } When {
            post("/api/v1/sca/challenges/$challenge/decision")
        } Then {
            statusCode(expect)
        }
    }

    private fun consume(challenge: UUID, body: String, expect: Int) {
        Given {
            spec(jsonSpec(body))
        } When {
            post("/api/v1/sca/challenges/$challenge/consume")
        } Then {
            statusCode(expect)
        }
    }

    private fun verifyAttempt(party: UUID, challenge: UUID, status: String, attempts: Int, otp: String? = null) {
        Given {
            spec(jsonSpec(verifyBody(party, otp)))
        } When {
            post("/api/v1/sca/challenges/$challenge/verify")
        } Then {
            statusCode(200)
            body("status", equalTo(status))
            body("attemptCount", equalTo(attempts))
        }
    }

    private companion object {
        const val IBAN = "FR7612345678900000123456789"
        const val IBAN_SPACED = "fr76 1234 5678 9000 0012 3456 789"
        const val OWN_PARTY = "1f2e3d4c-5b6a-7980-a1b2-c3d4e5f60718"
        const val SHA = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        val OWN_PARTY_UUID: UUID = UUID.fromString(OWN_PARTY)
    }
}
