// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.e2e

import com.openbank.lending.integration.LendingOutboxWriteIT
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end journey for a loan origination: **a maker submits an application, the submission
 * replays on a retried idempotency key and refuses a different body under the same key, the maker
 * walks the application along the origination graph to the four-eyes gate — and cannot decide it
 * themselves; a second person approves, the customer signs, and one more advance reaches
 * READY_TO_DISBURSE, the end of the graph. The tamper-evident evidence bundle reads the story
 * back over a remote hop, and the boundary journey withdraws a fresh application and watches the
 * terminal states refuse every further move.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against
 * real Postgres (full Flyway chain) and Valkey via `PostgresRedisTestResource`, outbox on the
 * in-memory connector. No use case, repository or domain object is touched directly. The ordered
 * methods exist because the four-eyes rule is about WHO (the principal's name), and
 * `@TestSecurity` fixes one identity per method — the maker lane, the checker lane and the
 * signature lane each need their own.
 *
 * ### Why the read-back is the observable
 *
 * Every command returns the aggregate the handler just wrote. The `GET` after each hop is a
 * different code path over the committed row, so the status comparison distinguishes "the command
 * was accepted" from "the command was accepted and the state moved". The evidence bundle is the
 * third witness: it arrives from audit-service's tamper-evident chain (ADR-0214 D3) over a remote
 * hop, which this module stubs module-wide (`LendingEvidenceFromAuditChainIT.StubAuditService`,
 * registered with `restrictToAnnotatedClass = false`, so it answers EVERY test class here) with a
 * chain of one MISMATCH entry — so the journey asserts the faithful mapping of whatever the chain
 * returns (attestation, tampered flag, entry fields, the caller named as requester), never the
 * hash verification itself; the MISMATCH/tamper semantics are the sibling IT's subject.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class LendingJourneyE2E {

    private lateinit var journeyApplicationId: String
    private lateinit var withdrawApplicationId: String
    private lateinit var partyId: String

    @Test
    @Order(1)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `1 - the maker submits, the retry replays, and the walk reaches the four-eyes gate`() {
        val key = "lending-${UUID.randomUUID()}"
        val body = applicationBody()
        val created = apply(body, key).then().statusCode(201).extractedBody()
        journeyApplicationId = created["id"] as String
        partyId = created["partyId"] as String
        assertThat(created["status"] as String).isEqualTo("SUBMITTED")

        // The committed row agrees with what the handler returned — over a different code path.
        val stored = getApplication(journeyApplicationId)
        for (field in listOf("partyId", "nominalAnnualRate", "termPeriods", "status")) {
            assertThat(stored[field])
                .`as`("stored application field %s must equal the submitted one", field)
                .isEqualTo(created[field])
        }
        // Money round-trips through two different serializers (the POST body is written by the
        // resource's own mapper, the GET by the JAX-RS runtime) — compare the amount as text.
        assertThat(amountOf(stored)).isEqualTo(amountOf(created))

        // A client that lost the response retries with the same key: the same application comes
        // back and the party's list carries it exactly once.
        val replay = apply(body, key).then().statusCode(201).extractedBody()
        assertThat(replay["id"] as String).isEqualTo(journeyApplicationId)
        assertThat(listApplicationIds(partyId)).containsExactly(journeyApplicationId)

        // The same key with a DIFFERENT fingerprint is a 409 (#10916) — the key is a fingerprint,
        // not a nonce. The key is scoped per party, so the colliding request must be the SAME
        // party asking for a different amount; and still only one row exists for the party.
        apply(applicationBody(partyId = partyId, amount = "12000.00"), key).then().statusCode(409)
        assertThat(listApplicationIds(partyId)).containsExactly(journeyApplicationId)

        // The maker walks the origination graph (ADR-0211): four advances, then the application
        // sits at the four-eyes gate awaiting a decision by someone else.
        repeat(ADVANCES_TO_FOUR_EYES) { advance(journeyApplicationId) }
        assertThat((getApplication(journeyApplicationId)["status"] as String)).isEqualTo("FOUR_EYES")

        // Four-eyes rule, from the maker's own lane: deciding your own application is refused
        // with a typed 409 — the gate is about WHO, not about roles.
        decide(journeyApplicationId, approve = true).then().statusCode(409)
        assertThat((getApplication(journeyApplicationId)["status"] as String)).isEqualTo("FOUR_EYES")
    }

    @Test
    @Order(2)
    @TestSecurity(user = CHECKER, roles = ["ROLE_CREDIT_RISK", "ROLE_ADMIN"])
    fun `2 - a second person approves, and one more advance reaches the signature gate`() {
        // The approval lands the application in OFFERED; the walk continues to AWAITING_SIGNATURE.
        val decided = decide(journeyApplicationId, approve = true).then().statusCode(200).extractedBody()
        assertThat(decided["status"] as String).isEqualTo("OFFERED")
        assertThat(decided["decidedBy"] as String).isEqualTo(CHECKER)
        advance(journeyApplicationId)

        // The decision is stored, over the read-back path — including WHO decided.
        val stored = getApplication(journeyApplicationId)
        assertThat(stored["status"] as String).isEqualTo("AWAITING_SIGNATURE")
        assertThat(stored["decidedBy"] as String).isEqualTo(CHECKER)
    }

    @Test
    @Order(3)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `3 - the customer signs, the evidence bundle witnesses the journey, and the walk ends at READY_TO_DISBURSE`() {
        val signed = Given {
            contentType("application/json")
            header("Idempotency-Key", "sign-${UUID.randomUUID()}")
            body("""{"artefactHash": "$ARTEFACT_HASH"}""")
        } When {
            post("$LENDING_PATH/applications/$journeyApplicationId/sign")
        } Then {
            statusCode(200)
        }
        assertThat(signed.extractedBody()["status"] as String).isEqualTo("SIGNED")

        val stored = getApplication(journeyApplicationId)
        assertThat(stored["status"] as String).isEqualTo("SIGNED")
        assertThat(stored["signatureArtefactHash"] as String).isEqualTo(ARTEFACT_HASH)

        // The evidence bundle (ADR-0214) is read from the tamper-evident audit chain — an
        // independent witness of the same history, not a projection of the application row.
        val evidence = Given {
            contentType("application/json")
        } When {
            get("$LENDING_PATH/applications/$journeyApplicationId/evidence")
        } Then {
            statusCode(200)
        }
        val bundle = evidence.extractedBody()
        // The module-wide stub answers one MISMATCH entry; the journey asserts the endpoint maps
        // the remote bundle field by field — including the tampered flag it must never dress up.
        assertThat(bundle["tampered"] as Boolean).isTrue()
        assertThat(bundle["truncated"] as Boolean).isFalse()
        assertThat(bundle["attestation"] as String).isEqualTo("audit-chain")
        assertThat(bundle["requestedBy"] as String).isEqualTo(PROPOSER)
        val events = bundle["events"] as List<Map<String, Any>>
        assertThat(events.single()["hashStatus"] as String).isEqualTo("MISMATCH")
        assertThat(events.single()["sourceService"] as String).isEqualTo("lending-service")

        // The walk is not over at SIGNED: one more advance skips the optional reflection period
        // and reaches READY_TO_DISBURSE, the last state the origination graph drives to — only
        // there does the machine refuse the next advance (422, row unchanged).
        advance(journeyApplicationId)
        assertThat((getApplication(journeyApplicationId)["status"] as String)).isEqualTo("READY_TO_DISBURSE")
        Given {
            contentType("application/json")
        } When {
            post("$LENDING_PATH/applications/$journeyApplicationId/advance")
        } Then {
            statusCode(422)
        }
        assertThat((getApplication(journeyApplicationId)["status"] as String)).isEqualTo("READY_TO_DISBURSE")
    }

    @Test
    @Order(4)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `4 - a fresh application can be withdrawn, and both terminal states refuse every further move`() {
        withdrawApplicationId = apply(applicationBody(), "lending-${UUID.randomUUID()}")
            .then().statusCode(201).extractedBody()["id"] as String

        // A too-short reason is refused by the request object itself, before anything moves.
        withdraw(withdrawApplicationId, "too short", "wd-${UUID.randomUUID()}").then().statusCode(400)
        assertThat((getApplication(withdrawApplicationId)["status"] as String)).isEqualTo("SUBMITTED")

        // A valid withdrawal moves the row; the keyed retry replays the stored outcome.
        withdraw(withdrawApplicationId, WITHDRAWAL_REASON, "wd-${UUID.randomUUID()}")
            .then().statusCode(200)
        assertThat((getApplication(withdrawApplicationId)["status"] as String)).isEqualTo("WITHDRAWN")

        // WITHDRAWN is terminal: a fresh-keyed second withdrawal is a 422 policy refusal, and so
        // is an advance — the row stays unchanged.
        withdraw(withdrawApplicationId, WITHDRAWAL_REASON, "wd-${UUID.randomUUID()}")
            .then().statusCode(422)
        Given {
            contentType("application/json")
        } When {
            post("$LENDING_PATH/applications/$withdrawApplicationId/advance")
        } Then {
            statusCode(422)
        }
        assertThat((getApplication(withdrawApplicationId)["status"] as String)).isEqualTo("WITHDRAWN")
    }

    private fun apply(body: String, key: String): io.restassured.response.Response = Given {
        contentType("application/json")
        header("Idempotency-Key", key)
        body(body)
    } When {
        post("$LENDING_PATH/applications")
    }

    private fun advance(id: String) {
        Given {
            contentType("application/json")
        } When {
            post("$LENDING_PATH/applications/$id/advance")
        } Then {
            statusCode(200)
        }
    }

    private fun decide(id: String, approve: Boolean): io.restassured.response.Response = Given {
        contentType("application/json")
        body("""{"approve": $approve, "reason": "credit policy clear"}""")
    } When {
        post("$LENDING_PATH/applications/$id/decision")
    }

    private fun withdraw(id: String, reason: String, key: String): io.restassured.response.Response = Given {
        contentType("application/json")
        header("Idempotency-Key", key)
        body("""{"reason": "$reason"}""")
    } When {
        post("$LENDING_PATH/applications/$id/withdraw")
    }

    private fun getApplication(id: String): Map<String, Any> {
        val response = Given {
            contentType("application/json")
        } When {
            get("$LENDING_PATH/applications/$id")
        } Then {
            statusCode(200)
        }
        return response.extractedBody()
    }

    private fun listApplicationIds(partyId: String): List<String> {
        val response = Given {
            contentType("application/json")
        } When {
            get("$LENDING_PATH/applications?partyId=$partyId")
        } Then {
            statusCode(200)
        }
        return response.extract().body().jsonPath().getList<String>("id")
    }

    private fun amountOf(body: Map<String, Any>): String {
        val requested = body["requestedAmount"]
        return requested.toString()
    }

    private fun applicationBody(): String = applicationBody(UUID.randomUUID().toString(), "10000.00")

    private fun applicationBody(partyId: String, amount: String): String = """
        {
          "partyId": "$partyId",
          "requestedAmount": {"amount": "$amount", "currency": {"code": "EUR"}},
          "nominalAnnualRate": 0.05,
          "termPeriods": 12,
          "firstDueDate": "${LocalDate.now().plusMonths(1)}"
        }
    """.trimIndent()

    private fun ValidatableResponse.extractedBody(): Map<String, Any> =
        extract().body().jsonPath().getMap<String, Any>("")

    private companion object {
        const val PROPOSER = "journey-maker"
        const val CHECKER = "journey-checker"
        const val LENDING_PATH = "/api/v1/lending"
        const val ADVANCES_TO_FOUR_EYES = 4
        const val WITHDRAWAL_REASON = "customer changed their mind"

        /** 64 hex chars — the contract-test artefact hash. */
        val ARTEFACT_HASH = "ab".repeat(32)
    }
}
