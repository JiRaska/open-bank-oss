// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.openbank.sca.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0335 D1 through real HTTP: the consumer scope is resolved from the AUTHENTICATED principal
 * (not anything in the body) and decided against the STORED, device-signed approval id.
 *
 * OPA is advisory in this profile, so every step here runs under one principal; what is under test
 * is the domain layer, which holds even if the policy layer admitted the call.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaPensionScopedConsumeIT {
    @Inject
    lateinit var dataSource: DataSource

    private val sha = "d".repeat(64)

    @Test
    @TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_API"])
    fun `pension spends its own approval once and cannot spend an ordinary one`() {
        val human = UUID.randomUUID()
        val keys = es256()
        val credentialId = "cred-pension-${UUID.randomUUID()}"
        enrol(human, credentialId, keys.spki())

        val pensionOp = "pension-exit:$sha"
        val pensionChallenge = approvedChallenge(human, pensionOp, keys, credentialId)
        val ordinaryOp = UUID.randomUUID().toString()
        val ordinaryChallenge = approvedChallenge(human, ordinaryOp, keys, credentialId)

        // Outside its namespace: refused 403 and NOT burned.
        consume(ordinaryChallenge, human, ordinaryOp, expect = 403)
        assertThat(consumedAt(ordinaryChallenge)).isNull()

        // Inside it, but for another party: still refused (party check unchanged).
        consume(pensionChallenge, UUID.randomUUID(), pensionOp, expect = 403)
        consume(pensionChallenge, human, pensionOp, expect = 200)
        // Single use.
        consume(pensionChallenge, human, pensionOp, expect = 409)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `no other consumer can spend a customer's pension approval`() {
        val human = UUID.randomUUID()
        val keys = es256()
        val credentialId = "cred-pension-${UUID.randomUUID()}"
        enrol(human, credentialId, keys.spki())
        val pensionOp = "pension-onboarding:${UUID.randomUUID()}:${UUID.randomUUID()}"
        val challenge = approvedChallenge(human, pensionOp, keys, credentialId)

        consume(challenge, human, pensionOp, expect = 403)
        assertThat(consumedAt(challenge)).isNull()
    }

    private fun approvedChallenge(human: UUID, approvalId: String, keys: KeyPair, credentialId: String): String {
        val challengeId: String = Given {
            contentType("application/json")
            body(
                """
                    {"partyId":"$human","purpose":"APPROVAL","preferredMethod":"PUSH_NOTIFICATION",
                     "redirectUrl":null,
                     "dynamicLinkingData":{"amount":null,"currency":null,"creditorIban":null,
                       "creditorName":null,"reference":null,
                       "approvalRequestId":"$approvalId","payloadSha256":"$sha"}}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(201)
        } Extract {
            path("id")
        }
        val payload = "$challengeId|APPROVED|APPROVAL|$approvalId|$sha"
        Given {
            contentType("application/json")
            body("""{"credentialId":"$credentialId","decision":"APPROVED","signature":"${keys.sign(payload)}"}""")
        } When {
            post("/api/v1/sca/challenges/$challengeId/decision")
        } Then {
            statusCode(200)
        }
        return challengeId
    }

    private fun consume(challengeId: String, party: UUID, approvalId: String, expect: Int) {
        Given {
            contentType("application/json")
            body("""{"partyId":"$party","approvalRequestId":"$approvalId","payloadSha256":"$sha"}""")
        } When {
            post("/api/v1/sca/challenges/$challengeId/consume")
        } Then {
            statusCode(expect)
        }
    }

    private fun enrol(party: UUID, credentialId: String, publicKey: String) {
        Given {
            contentType("application/json")
            body("""{"credentialId":"$credentialId","publicKey":"$publicKey","algorithm":"ES256"}""")
        } When {
            post("/api/v1/sca/parties/$party/devices")
        } Then {
            statusCode(201)
        }
    }

    private fun consumedAt(challengeId: String): Any? = dataSource.connection.use { c ->
        c.prepareStatement("select consumed_at from sca_challenges where id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(challengeId))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1)
            }
        }
    }

    private fun es256(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun KeyPair.spki(): String = Base64.getEncoder().encodeToString(public.encoded)

    private fun KeyPair.sign(payload: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(private)
        update(payload.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(sign())
    }
}
