// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.it.PostgresTestResource
import com.openbank.kyb.it.StubPartyGateway
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Drives the whole two-signer flow through real HTTP against a real Postgres, then reads the
 * outbox with plain JDBC — the only way to prove the case row and its event commit together
 * (a mocked repository cannot tell which publisher a use case called).
 */
@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class KybCaseApiIT {

    @Inject
    lateinit var parties: StubPartyGateway

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    private val initiator = UUID.randomUUID()
    private val cosigner = UUID.randomUUID()

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @Suppress("LongMethod") // one continuous journey; splitting it would need shared state between tests
    fun `two-signer s r o onboarding end to end with outbox evidence`() {
        // 1. lookup shows the register preview without creating anything
        Given {
            contentType("application/json")
            body("""{"scheme":"CZ_ICO","identifier":"452 746 49"}""")
        } When {
            post("/api/v1/kyb/lookup")
        } Then {
            statusCode(200)
            body("legalName", equalTo("Příklad s.r.o."))
            body("representatives", hasSize<Any>(2))
            body("representationRule.requiredSigners", equalTo(2))
        }

        // 2. a body naming a different initiator than the authenticated customer is refused
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body("""{"scheme":"CZ_ICO","identifier":"45274649","initiatorPartyId":"${UUID.randomUUID()}"}""")
        } When { post("/api/v1/kyb/cases") } Then { statusCode(403) }

        // 3. start
        val caseId = (
            Given {
                contentType("application/json")
                header("X-Customer-Party-Id", initiator.toString())
                body("""{"scheme":"CZ_ICO","identifier":"45274649","initiatorPartyId":"$initiator"}""")
            } When { post("/api/v1/kyb/cases") } Then {
                statusCode(201)
                body("status", equalTo("REGISTRY_VERIFIED"))
                body("entityPartyId", notNullValue())
                body("requiredSignatures", equalTo(2))
            }
            ).extract().path<String>("id")
        assertThat(parties.created).anyMatch { it.registrationNumber == "45274649" && it.partyType == "COMPANY" }

        // 4. initiator = Jana (index 0)
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body("""{"representativeIndex":0,"claimedName":"Jana Nováková"}""")
        } When { post("/api/v1/kyb/cases/$caseId/initiator") } Then {
            statusCode(200)
            body("status", equalTo("INITIATOR_MATCHED"))
        }

        // 5. invite Eva; the initiator sees the token
        val token = (
            Given {
                contentType("application/json")
                header("X-Customer-Party-Id", initiator.toString())
                body("""{"representativeIndexes":[1]}""")
            } When { post("/api/v1/kyb/cases/$caseId/cosigners") } Then {
                statusCode(200)
                body("status", equalTo("AWAITING_COSIGNERS"))
                body("signers", hasSize<Any>(2))
            }
            ).extract().path<String>("signers[1].invitationToken")
        assertThat(token).isNotBlank()

        // ...but a stranger reading the case gets 404, never the token
        Given {
            header("X-Customer-Party-Id", UUID.randomUUID().toString())
        } When { get("/api/v1/kyb/cases/$caseId") } Then
            { statusCode(404) }

        // 6. Eva onboards as herself and claims; she sees the case but not the token
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", cosigner.toString())
            body("""{"partyId":"$cosigner"}""")
        } When { post("/api/v1/kyb/invitations/$token/claim") } Then {
            statusCode(200)
            body("status", equalTo("READY_TO_SIGN"))
            body("signers[1].invitationToken", nullValue())
        }
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", cosigner.toString())
            body("""{"partyId":"$cosigner"}""")
        } When {
            post("/api/v1/kyb/invitations/$token/claim")
        } Then { statusCode(409) }

        // 7. both sign
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body("""{"signatureRef":"cer-1"}""")
        } When {
            post("/api/v1/kyb/cases/$caseId/sign")
        } Then {
            statusCode(200)
            body("signedCount", equalTo(1))
        }
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", cosigner.toString())
            body("""{"signatureRef":"cer-2"}""")
        } When {
            post("/api/v1/kyb/cases/$caseId/sign")
        } Then {
            statusCode(200)
            body("status", equalTo("SIGNED"))
        }
        assertThat(parties.mandates.filter { it.evidenceRef.contains(caseId) }).hasSize(2)

        // 8. the outbox holds one row per lifecycle event, written in the same transactions
        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
            c.createStatement().executeQuery(
                "select event_type from kyb_outbox where aggregate_id = '$caseId' order by id",
            ).use { rs ->
                val types = generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                assertThat(types).containsExactly(
                    "BUSINESS_REGISTRY_VERIFIED",
                    "BUSINESS_SIGNER_INVITED",
                    "BUSINESS_SIGNER_IDENTIFIED",
                    "BUSINESS_AGREEMENT_SIGNED",
                )
            }
        }

        // 9. "cases I am involved in" for the co-signer
        Given {
            header("X-Customer-Party-Id", cosigner.toString())
        } When { get("/api/v1/kyb/cases?partyId=$cosigner") } Then
            {
                statusCode(200)
                body("[0].id", equalTo(caseId))
            }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `a wrong check digit is a 400 before any register is asked, an unknown IČO is a 404`() {
        Given {
            contentType("application/json")
            body("""{"scheme":"CZ_ICO","identifier":"45274648"}""")
        } When
            { post("/api/v1/kyb/lookup") } Then
            { statusCode(400) }
        Given {
            contentType("application/json")
            body("""{"scheme":"CZ_ICO","identifier":"00000019"}""")
        } When
            { post("/api/v1/kyb/lookup") } Then
            { statusCode(404) }
    }
}
