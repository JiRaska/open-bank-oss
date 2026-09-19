// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.openbank.sca.it.PostgresRedisTestResource
import com.openbank.sca.it.StubPartyTypeLookup
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * #10281 through real HTTP: the APPROVAL purpose's dynamic linking (approval request + frozen
 * payload hash, plus amount/currency/creditor for a payment), the deciding party written onto the
 * challenge, and the refusal to enrol a device to a non-natural person.
 *
 * The device decision is signed with a real P-256 key over the literal payload format, so the
 * test also pins the byte layout the app must sign: `id|APPROVED|amount|currency|creditor|ref|
 * approvalRequestId|payloadSha256`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaApprovalLinkingIT {

    @Inject
    lateinit var dataSource: DataSource

    private val sha = "a".repeat(64)
    private val otherSha = "b".repeat(64)
    private val iban = "CZ6508000000192000145399"

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `device enrolment to a company is refused 422 and nothing is stored`() {
        val company = UUID.randomUUID()
        StubPartyTypeLookup.TYPES[company] = "COMPANY"
        enrol(company, "cred-co-${UUID.randomUUID()}", publicKey = es256().spki(), expect = 422)
        assertThat(deviceCount(company)).isZero()
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `device enrolment fails closed 503 when the register cannot answer`() {
        val party = UUID.randomUUID()
        StubPartyTypeLookup.UNAVAILABLE += party
        enrol(party, "cred-na-${UUID.randomUUID()}", publicKey = es256().spki(), expect = 503)
        assertThat(deviceCount(party)).isZero()
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a sole trader is a natural person and may enrol`() {
        val trader = UUID.randomUUID()
        StubPartyTypeLookup.TYPES[trader] = "SOLE_TRADER"
        enrol(trader, "cred-st-${UUID.randomUUID()}", publicKey = es256().spki(), expect = 201)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an APPROVAL challenge without approval linking is refused at initiate`() {
        Given {
            contentType("application/json")
            body("""{"partyId":"${UUID.randomUUID()}","purpose":"APPROVAL","preferredMethod":"PUSH_NOTIFICATION"}""")
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an approval co-signature is spent only on its approval and payload and records who decided`() {
        val human = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val approvalId = UUID.randomUUID().toString()
        val keys = es256()
        val credentialId = "cred-appr-${UUID.randomUUID()}"
        enrol(human, credentialId, keys.spki(), expect = 201)

        val challengeId = initiateApproval(human, entity, approvalId, sha)
        val payload = "$challengeId|APPROVED|1000.00|CZK|$iban|INV-1|$approvalId|$sha"
        Given {
            contentType("application/json")
            body("""{"credentialId":"$credentialId","decision":"APPROVED","signature":"${keys.sign(payload)}"}""")
        } When {
            post("/api/v1/sca/challenges/$challengeId/decision")
        } Then {
            statusCode(200)
        }

        // Dynamic-link hash mismatch: the same approval, an edited payload.
        consume(challengeId, human, approvalId, otherSha, expect = 409)
        // Another approval request, the right hash.
        consume(challengeId, human, UUID.randomUUID().toString(), sha, expect = 409)
        // Approval fields omitted entirely: a linked challenge never authorises an unlinked consume.
        consume(challengeId, human, null, null, expect = 409)

        Given {
            contentType("application/json")
            body(consumeBody(human, approvalId, sha))
        } When {
            post("/api/v1/sca/challenges/$challengeId/consume")
        } Then {
            statusCode(200)
            body("decidedByPartyId", equalTo(human.toString()))
            body("onBehalfOfPartyId", equalTo(entity.toString()))
        }
        // Single use.
        consume(challengeId, human, approvalId, sha, expect = 409)

        assertThat(decidedBy(challengeId)).isEqualTo(human to credentialId)
    }

    private fun initiateApproval(human: UUID, entity: UUID, approvalId: String, payloadSha: String): String = Given {
        contentType("application/json")
        body(
            """
                {"partyId":"$human","purpose":"APPROVAL","preferredMethod":"PUSH_NOTIFICATION",
                 "onBehalfOfPartyId":"$entity",
                 "dynamicLinkingData":{"amount":"1000.00","currency":"CZK","creditorIban":"$iban",
                   "creditorName":"Dodavatel","reference":"INV-1",
                   "approvalRequestId":"$approvalId","payloadSha256":"$payloadSha"}}
            """.trimIndent(),
        )
    } When {
        post("/api/v1/sca/challenges")
    } Then {
        statusCode(201)
    } Extract {
        path("id")
    }

    private fun consumeBody(party: UUID, approvalId: String?, payloadSha: String?): String {
        val approval = listOfNotNull(
            approvalId?.let { "\"approvalRequestId\":\"$it\"" },
            payloadSha?.let { "\"payloadSha256\":\"$it\"" },
        ).joinToString(",", prefix = if (approvalId != null || payloadSha != null) "," else "")
        val creditor = "CZ65 0800 0000 1920 0014 5399"
        return """{"partyId":"$party","amount":"1000","currency":"czk","creditor":"$creditor"$approval}"""
    }

    private fun consume(challengeId: String, party: UUID, approvalId: String?, payloadSha: String?, expect: Int) {
        Given {
            contentType("application/json")
            body(consumeBody(party, approvalId, payloadSha))
        } When {
            post("/api/v1/sca/challenges/$challengeId/consume")
        } Then {
            statusCode(expect)
        }
    }

    private fun enrol(party: UUID, credentialId: String, publicKey: String, expect: Int) {
        Given {
            contentType("application/json")
            body("""{"credentialId":"$credentialId","publicKey":"$publicKey","algorithm":"ES256"}""")
        } When {
            post("/api/v1/sca/parties/$party/devices")
        } Then {
            statusCode(expect)
        }
    }

    private fun deviceCount(party: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from sca_enrolled_devices where party_id = ?").use { ps ->
            ps.setObject(1, party)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun decidedBy(challengeId: String): Pair<UUID?, String?> = dataSource.connection.use { c ->
        c.prepareStatement("select decided_by_party_id, decided_by_credential_id from sca_challenges where id = ?")
            .use { ps ->
                ps.setObject(1, UUID.fromString(challengeId))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java) to rs.getString(2)
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
