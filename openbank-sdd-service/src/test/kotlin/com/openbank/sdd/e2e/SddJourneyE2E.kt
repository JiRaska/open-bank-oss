// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ValidatableResponse
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end journey for a SEPA Direct Debit B2B mandate: **register it, read it back over a
 * different code path, retry the registration the way a client with a lost response would,
 * watch the fail-closed authorisation refuse an unverified mandate, verify it, then walk the
 * debtor controls — cap refusal first, a clean acceptance that stamps the collection, an
 * idempotent replay of the same due date, and the EUR-only rule refusing a foreign currency
 * before anything is persisted.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against
 * a real Postgres (full Flyway chain) via `PostgresTestResource`. No use case, repository or
 * domain object is touched directly and nothing is mocked out of the request path.
 *
 * ### Service boundaries — stated, not pretended
 *
 * An accepted collection emits `sdd.mandate.collection-authorised` onto the outbox for the
 * downstream debit-posting path. The outbox channel is switched to the in-memory connector, so
 * the event is written durably to this service's own database but nothing reaches a broker and
 * no downstream consumer runs. The journey therefore stops at durable local state; the actual
 * debit booking (`so-sdd-...` postings in ledger) is downstream of this service's boundary and
 * is never claimed here.
 *
 * ### Why the read-back is the observable
 *
 * `POST` returns the aggregate the handler just built in memory. The `GET` is a different code
 * path over the committed row, so the field-by-field comparison is what distinguishes "the
 * mandate was accepted" from "the mandate was accepted and stored as submitted". The
 * `lastCollectionDate` read-back after an ACCEPT is what distinguishes "the decision was
 * returned" from "the decision was persisted" — and the account-list cardinality after the
 * retry is what distinguishes "the retry was deduplicated" from "the retry inserted a second
 * mandate".
 *
 * The authorisation endpoint answers 200 with a decision body (ACCEPT / REJECT / REFUSE) by
 * design — ADR-0036 §C fail-closed semantics — so the assertions below pin the decision and
 * the EPC reason code, never the transport status.
 */
@QuarkusTest
@TestProfile(SddJourneyE2E.AuthzOffProfile::class)
@QuarkusTestResource(SddJourneyE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class SddJourneyE2E {

    /**
     * sdd ships `authz.enforce` defaulted to true; with no OPA sidecar reachable the
     * interceptor fails closed and every endpoint here answers 503, so the journey needs the
     * same override as the other HTTP-driven tests in this module.
     */
    class AuthzOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("sdd-events-out").toMutableMap()
            props.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("sdd-collection-authorised-in"))
            props["quarkus.kafka.devservices.enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    private data class JourneyMandate(
        val id: String,
        val accountId: UUID,
        val creditorIdentifier: String,
        val umr: String,
        val payload: String,
        val submitted: Map<String, Any>,
    )

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = ACTOR),
            Claim(key = "preferred_username", value = ACTOR),
        ],
    )
    fun `a B2B mandate is stored, deduplicated on retry, verified, and its collections pass the fail-closed policy`() {
        val mandate = registerB2bMandate()

        // The committed row agrees with what the handler returned in memory — over a
        // different code path (GET by id), which is what makes this storage evidence.
        val stored = getById(mandate.id)
        for (field in STORED_FIELDS) {
            assertThat(stored[field])
                .`as`("stored mandate field %s must equal the submitted one", field)
                .isEqualTo(mandate.submitted[field])
        }

        // A client that lost the response retries with the same (creditorIdentifier, UMR):
        // the same mandate comes back and no second row exists for the account.
        replayDoesNotDuplicate(mandate)

        // Fail-closed rule, evaluated before anything is stamped: a B2B mandate that the
        // debtor bank never verified must be REJECTED with MD01 even though the mandate exists.
        authorise(mandate, amount = WITHIN_CAP, currency = "EUR").decisionIs("REJECT", "MD01")
        assertThat(getById(mandate.id)["lastCollectionDate"]).isNull()

        // The debtor bank verifies the mandate; only then can a collection be authorised.
        val confirmed = confirm(mandate.id)
        assertThat(confirmed["status"] as String).isEqualTo("ACTIVE")
        assertThat(confirmed["b2bConfirmed"] as Boolean).isTrue()

        // Debtor control exercised: amount over the per-collection cap is a REFUSE (MS02) —
        // the mandate's fault ladder was cleared, so this is the debtor's own refusal, and it
        // must not stamp the collection either.
        authorise(mandate, amount = OVER_CAP, currency = "EUR", cap = CAP).decisionIs("REFUSE", "MS02")
        assertThat(getById(mandate.id)["lastCollectionDate"]).isNull()

        // A clean collection is ACCEPTED and the acceptance is persisted: the mandate now
        // carries the collection's due date, readable over the read-back path.
        authorise(mandate, amount = WITHIN_CAP, currency = "EUR", cap = CAP, dueDate = DUE_DATE)
            .decisionIs("ACCEPT", null)
        assertThat(getById(mandate.id)["lastCollectionDate"] as String).isEqualTo(DUE_DATE)

        // Replay guard (#8351): re-authorising the SAME due date is the same collection, so the
        // decision repeats and the stored stamp is untouched. The next due date is a NEW
        // collection and moves the stamp.
        authorise(mandate, amount = WITHIN_CAP, currency = "EUR", cap = CAP, dueDate = DUE_DATE)
            .decisionIs("ACCEPT", null)
        authorise(mandate, amount = WITHIN_CAP, currency = "EUR", cap = CAP, dueDate = NEXT_DUE_DATE)
            .decisionIs("ACCEPT", null)
        assertThat(getById(mandate.id)["lastCollectionDate"] as String).isEqualTo(NEXT_DUE_DATE)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = ACTOR),
            Claim(key = "preferred_username", value = ACTOR),
        ],
    )
    fun `a suspended mandate rejects collections and the EUR-only rule leaves nothing behind`() {
        val mandate = registerB2bMandate()
        confirm(mandate.id)

        // Suspend is immediate: the fail-closed ladder now rejects on mandate state (MD01).
        assertThat(suspend(mandate.id)["status"] as String).isEqualTo("SUSPENDED")
        authorise(mandate, amount = WITHIN_CAP, currency = "EUR").decisionIs("REJECT", "MD01")
        assertThat(getById(mandate.id)["lastCollectionDate"]).isNull()

        // Resumed, the mandate authorises again.
        assertThat(resume(mandate.id)["status"] as String).isEqualTo("ACTIVE")
        authorise(mandate, amount = WITHIN_CAP, currency = "EUR").decisionIs("ACCEPT", null)
        assertThat(getById(mandate.id)["lastCollectionDate"] as String).isEqualTo(DUE_DATE)

        // The euro-only scheme rule refuses a non-EUR collection AFTER the mandate checks and
        // BEFORE any stamping: a REFUSE/reject decision with FF05 and the stamp stays at the
        // earlier due date — no side effect behind the refusal.
        authorise(mandate, amount = WITHIN_CAP, currency = "CZK").decisionIs("REJECT", "FF05")
        assertThat(getById(mandate.id)["lastCollectionDate"] as String).isEqualTo(DUE_DATE)
    }

    private fun registerB2bMandate(): JourneyMandate {
        val accountId = UUID.randomUUID()
        val creditorIdentifier = "CZ98ZZZ${UUID.randomUUID().toString().take(8)}"
        val umr = "UMR-${UUID.randomUUID().toString().take(UMR_SUFFIX_LENGTH)}"
        val payload = """
            {
              "accountId": "$accountId",
              "debtorIban": "CZ6508000000192000145399",
              "creditorIdentifier": "$creditorIdentifier",
              "umr": "$umr",
              "scheme": "B2B",
              "sequenceType": "RCUR",
              "creditorName": "Energie a.s.",
              "debtorName": "Jan Novak",
              "signatureDate": "2026-01-15"
            }
        """.trimIndent()

        val created = register(payload).statusCode(201).extractedBody()
        val id = created["id"] as String
        assertThat(id).isNotBlank()
        // Arrangement assertion: only a B2B mandate is born PENDING_CONFIRMATION — the first
        // authorisation below pins the unverified-mandate rule, so the state must be real.
        assertThat(created["status"] as String).isEqualTo("PENDING_CONFIRMATION")
        assertThat(created["b2bConfirmed"] as Boolean).isFalse()
        return JourneyMandate(id, accountId, creditorIdentifier, umr, payload, created)
    }

    private fun replayDoesNotDuplicate(mandate: JourneyMandate) {
        val replay = register(mandate.payload).statusCode(201).extractedBody()
        assertThat(replay["id"] as String).isEqualTo(mandate.id)
        assertThat(accountList(mandate.accountId)).containsExactly(mandate.id)
    }

    private fun authorise(
        mandate: JourneyMandate,
        amount: String,
        currency: String,
        cap: String? = null,
        dueDate: String = DUE_DATE,
    ): ValidatableResponse = Given {
        contentType("application/json")
        body(
            """
            {
              "creditorIdentifier": "${mandate.creditorIdentifier}",
              "umr": "${mandate.umr}",
              "scheme": "B2B",
              "sequenceType": "RCUR",
              "amount": $amount,
              "currency": "$currency",
              "dueDate": "$dueDate",
              "controls": { ${cap?.let { "\"maxAmountPerCollection\": $it" } ?: ""} }
            }
            """.trimIndent(),
        )
    } When {
        post("/api/v1/sdd/collections/authorise")
    } Then {
        statusCode(200)
    }

    private fun ValidatableResponse.decisionIs(decision: String, reasonCode: String?) {
        val body = extractedBody()
        assertThat(body["decision"] as String)
            .describedAs("authorisation decision (reason=%s)", body["reason"])
            .isEqualTo(decision)
        assertThat(body["reasonCode"] as? String).isEqualTo(reasonCode)
    }

    private fun register(payload: String): ValidatableResponse = Given {
        contentType("application/json")
        body(payload)
    } When {
        post("/api/v1/sdd/mandates")
    } Then {}

    private fun confirm(id: String): Map<String, Any> = lifecycle(id, "confirm")

    private fun suspend(id: String): Map<String, Any> = lifecycle(id, "suspend")

    private fun resume(id: String): Map<String, Any> = lifecycle(id, "resume")

    private fun lifecycle(id: String, action: String): Map<String, Any> {
        val response = Given {
            contentType("application/json")
        } When {
            post("/api/v1/sdd/mandates/$id/$action")
        } Then {
            statusCode(200)
        }
        return response.extractedBody()
    }

    private fun getById(id: String): Map<String, Any> {
        val response = Given {
            contentType("application/json")
        } When {
            get("/api/v1/sdd/mandates/$id")
        } Then {
            statusCode(200)
        }
        return response.extractedBody()
    }

    private fun accountList(accountId: UUID): List<String> {
        val response = Given {
            contentType("application/json")
        } When {
            get("/api/v1/sdd/mandates?accountId=$accountId")
        } Then {
            statusCode(200)
        }
        return response.extractList("id")
    }

    private fun ValidatableResponse.extractedBody(): Map<String, Any> =
        extract().body().jsonPath().getMap<String, Any>("")

    private fun ValidatableResponse.extractList(field: String): List<String> =
        extract().body().jsonPath().getList<String>(field)

    private companion object {
        const val ACTOR = "e2e-journey-operator"

        /** `sdd_mandate.umr` is `varchar(35)`; a full UUID after the `UMR-` prefix overflows it. */
        const val UMR_SUFFIX_LENGTH = 20

        /** A B2B mandate is born PENDING_CONFIRMATION — the journey's first policy assertion. */
        val STORED_FIELDS = listOf(
            "id",
            "accountId",
            "debtorIban",
            "creditorIdentifier",
            "umr",
            "scheme",
            "sequenceType",
            "creditorName",
            "debtorName",
            "signatureDate",
            "status",
        )

        const val DUE_DATE = "2026-03-01"
        const val NEXT_DUE_DATE = "2026-04-01"
        const val WITHIN_CAP = "50.00"
        const val OVER_CAP = "100.00"
        const val CAP = "75.00"
    }
}
