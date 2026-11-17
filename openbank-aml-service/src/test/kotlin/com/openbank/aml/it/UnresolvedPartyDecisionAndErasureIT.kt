// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.infrastructure.client.AccountServiceClient
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.ClaimType
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What must still be true of a case that was decided, or whose party was erased, BEFORE the case
 * knew whose it was.
 *
 * A held payment's case can be opened with no party and decided in that state: the rail needs the
 * decision to release or reject the payment, and it reads the case key, not the party. But a BLOCK
 * also has a party-level meaning, and a decision published with no party reaches no party. Likewise an erasure of the party cannot find a case that does not yet name it.
 * Both are owed to the party the moment the case is resolved to it, and this proves they are paid:
 *
 *  - the BLOCK is published again, with the real party, in the transaction that resolves the case;
 *  - a case resolved to a party that was erased meanwhile ends anonymised, as if it had been
 *    found by the erasure itself.
 *
 * Real broker, real Postgres, the production account rest-client against an account authority that
 * is down while the decision and the erasure happen, and the resolution sweep on its real schedule.
 * All ids are literals: a test profile loads in another classloader.
 */
@QuarkusTest
@TestProfile(PaymentHeldPartyOutageIT.OutageProfile::class)
class UnresolvedPartyDecisionAndErasureIT {

    @Inject
    lateinit var pool: PgPool

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var accounts: AccountServiceClient

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private data class Case(
        val id: UUID,
        val party: UUID?,
        val status: String,
        val reference: String,
        val detail: String?,
        val matched: String?,
    )

    private fun caseByKey(key: String): Case? = onEventLoop {
        pool.preparedQuery(
            """
            SELECT case_id, party_id, status, customer_reference, alert_detail, matched_entity
              FROM aml_cases WHERE idempotency_key = $1
            """.trimIndent(),
        ).execute(Tuple.of(key)).awaitSuspending()
            .map {
                Case(it.getUUID(0), it.getUUID(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
            }
            .singleOrNull()
    }

    private fun proposeAs(caseId: UUID, analyst: String, outcome: String, reason: String) = onEventLoop {
        pool.preparedQuery(
            """
            UPDATE aml_cases SET hold_proposal_outcome = $2, hold_proposal_reason = $3, hold_proposed_by = $4,
                   hold_proposed_at = now(), hold_proposer_person_id = $4 || '-person',
                   hold_proposal_expires_at = now() + interval '1 hour' WHERE case_id = $1
            """.trimIndent(),
        ).execute(Tuple.of(caseId, outcome, reason, analyst)).awaitSuspending()
    }

    private fun holdCase(payment: UUID): Case? = caseByKey("payment-hold:domestic:$payment")

    /** Status-changed events of [caseId] in the outbox, oldest first. */
    private fun statusEvents(caseId: UUID): List<JsonNode> = onEventLoop {
        pool.preparedQuery(
            "SELECT payload FROM aml_outbox WHERE aggregate_id = $1 AND event_type = $2 ORDER BY created_at, id",
        ).execute(Tuple.of(caseId, "aml.case.status_changed.v1")).awaitSuspending()
            .map { mapper.readTree(it.getString(0)) }
    }

    private fun accountService(mode: String) {
        val control = ConfigProvider.getConfig()
            .getValue(FakeAccountServiceTestResource.CONTROL_URL_KEY, String::class.java)
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$control/mode/$mode")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(response.statusCode()).isEqualTo(HTTP_OK)
    }

    private fun held(payment: UUID): String = mapper.writeValueAsString(
        mapOf(
            "eventType" to "PAYMENT_HELD",
            "paymentId" to payment.toString(),
            "rail" to "DOMESTIC",
            "caseKey" to "payment-hold:domestic:$payment",
            "debtorAccountId" to ACCOUNT.toString(),
            "debtorPartyId" to null,
            "customerReference" to "9876543210/0800",
            "reasons" to listOf("SANCTIONS_POTENTIAL_MATCH"),
            "steps" to listOf(
                mapOf("step" to "STANDING", "outcome" to "UNAVAILABLE", "reason" to "STANDING_UNAVAILABLE"),
                mapOf(
                    "step" to "SANCTIONS",
                    "outcome" to "DECIDED",
                    "decision" to "HOLD",
                    "reason" to "SANCTIONS_POTENTIAL_MATCH",
                    "evidence" to mapOf("creditor_matched_entity" to "Listed 9"),
                ),
            ),
            "expiresAt" to "2026-03-04T09:30:00Z",
        ),
    )

    private fun send(topic: String, key: UUID, payload: String) {
        val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "key.serializer" to SERIALIZER,
                "value.serializer" to SERIALIZER,
                "acks" to "all",
            ),
        ).use { it.send(ProducerRecord(topic, key.toString(), payload)).get(20, TimeUnit.SECONDS) }
    }

    /** An ordinary case that already names the party: the erasure's known positive. */
    private fun seedCaseOfParty(key: String) = onEventLoop {
        pool.preparedQuery(
            """
            INSERT INTO aml_cases
              (id, case_id, idempotency_key, party_id, customer_reference, screening_type, risk_level, status,
               alert_code, alert_detail, matched_entity, screened_at, created_at, updated_at)
            VALUES ($1, $2, $3, $4, 'marker-ref', 'MANUAL_INVESTIGATION', 'LOW', 'OPEN',
                    'MARKER', 'marker detail', 'marker entity', now(), now(), now())
            """.trimIndent(),
        ).execute(Tuple.of(MARKER_ROW_ID, UUID.randomUUID(), key, PARTY)).awaitSuspending()
    }

    private fun await(what: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(ready()).describedAs(what).isTrue()
    }

    @Test
    @TestSecurity(user = "u-operator", roles = ["ROLE_OPERATOR", "ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-operator"),
            Claim(key = "preferred_username", value = "u-operator"),
            Claim(key = "person_id", value = "u-operator-person"),
            Claim(key = "amr", value = "[\"pwd\",\"otp\"]", type = ClaimType.JSON_ARRAY),
        ],
    )
    fun `a block and an erasure that precede the party's resolution both reach the party when it is resolved`() {
        // First use of the production client on this thread; see PaymentHeldPartyOutageIT.
        assertThat(runBlocking { accounts.findPartyByAccountId(ACCOUNT) }).isEqualTo(PARTY)
        accountService(FakeAccountServiceTestResource.REFUSED)

        send(HOLD_TOPIC, BLOCKED_HOLD, held(BLOCKED_HOLD))
        send(HOLD_TOPIC, ERASED_HOLD, held(ERASED_HOLD))
        await("both holds have a case while the account authority is down") {
            holdCase(BLOCKED_HOLD) != null && holdCase(ERASED_HOLD) != null
        }
        val blocked = holdCase(BLOCKED_HOLD)!!
        assertThat(blocked.party).isNull()

        // The decision the rail is waiting for is taken while nobody knows whose case this is. A held
        // payment's case takes two analysts (ADR-0374 point 3): a colleague's proposal is seeded, and
        // this analyst confirms it.
        proposeAs(blocked.id, "u-colleague", "BLOCK", "listed counterparty")
        Given {
            contentType("application/json")
            body("""{"outcome":"BLOCK","reason":"listed counterparty"}""")
        } When {
            post("/api/v1/aml/cases/${blocked.id}/hold-decision/confirmation")
        } Then {
            statusCode(HTTP_OK)
            body("status", equalTo("BLOCKED"))
            body("partyResolved", equalTo(false))
        }
        assertPublishedForTheRailOnly(statusEvents(blocked.id).single())

        // The party is erased while two of its cases still do not name it.
        seedCaseOfParty(MARKER_KEY)
        send(PARTY_TOPIC, PARTY, """{"eventType":"PARTY_ERASED","partyId":"$PARTY"}""")
        await("the erasure was consumed: the case that names the party is anonymised") {
            caseByKey(MARKER_KEY)!!.reference == "ERASED-$PARTY"
        }
        assertThat(holdCase(ERASED_HOLD)!!.party).describedAs("still unresolved at erasure time").isNull()

        accountService(FakeAccountServiceTestResource.UP)
        await("the account authority is back and both cases are resolved to the party") {
            holdCase(BLOCKED_HOLD)!!.party == PARTY && holdCase(ERASED_HOLD)!!.party == PARTY
        }

        assertBothDebtsPaid(statusEvents(blocked.id))
    }

    private fun assertPublishedForTheRailOnly(forTheRail: JsonNode) {
        // Everything the rail matches on is there...
        assertThat(forTheRail.path("newStatus").asText()).isEqualTo("BLOCKED")
        assertThat(forTheRail.path("alertCode").asText()).isEqualTo("PAYMENT_HELD")
        assertThat(forTheRail.path("idempotencyKey").asText()).isEqualTo("payment-hold:domestic:$BLOCKED_HOLD")
        assertThat(forTheRail.path("transactionId").asText()).isEqualTo(BLOCKED_HOLD.toString())
        // ...and nothing in it names a party.
        assertThat(forTheRail.has("partyId")).isTrue()
        assertThat(forTheRail.path("partyId").isNull).isTrue()
    }

    private fun assertBothDebtsPaid(events: List<JsonNode>) {
        val marker = caseByKey(MARKER_KEY)!!
        // Soft, so that one unpaid debt does not hide the other.
        SoftAssertions.assertSoftly { soft ->
            // The BLOCK is owed to the party: published again with the real party, and not as a
            // decision a rail could act on a second time.
            soft.assertThat(events).describedAs("the decision, then its restatement for the party").hasSize(2)
            val forTheParty = events.last()
            soft.assertThat(forTheParty.path("partyId").asText()).describedAs("party of the last event")
                .isEqualTo(PARTY.toString())
            soft.assertThat(forTheParty.path("newStatus").asText()).isEqualTo("BLOCKED")
            soft.assertThat(
                forTheParty.path("previousStatus").asText(),
            ).describedAs("a restatement").isEqualTo("BLOCKED")
            soft.assertThat(forTheParty.path("idempotencyKey").isNull).describedAs("no case key").isTrue()
            soft.assertThat(forTheParty.path("alertCode").isNull).describedAs("no alert code").isTrue()
            soft.assertThat(forTheParty.path("transactionId").isNull).describedAs("no payment id").isTrue()
            soft.assertThat(holdCase(BLOCKED_HOLD)!!.status).isEqualTo("BLOCKED")

            // The erasure is owed to the party too: same fields cleared as on a case it found directly.
            soft.assertThat(marker.detail).isNull()
            soft.assertThat(marker.matched).isNull()
            for (payment in listOf(ERASED_HOLD, BLOCKED_HOLD)) {
                val case = holdCase(payment)!!
                soft.assertThat(case.reference).describedAs("customer reference of %s", payment)
                    .isEqualTo("ERASED-$PARTY")
                soft.assertThat(case.detail).describedAs("alert detail of %s", payment).isNull()
                soft.assertThat(case.matched).describedAs("matched entity of %s", payment).isNull()
            }
        }
    }

    private companion object {
        const val HOLD_TOPIC = "openbank.domestic.payment.events"
        const val PARTY_TOPIC = "openbank.party.events"
        const val SERIALIZER = "org.apache.kafka.common.serialization.StringSerializer"
        const val BUDGET_NANOS = 90_000_000_000L
        const val POLL_MILLIS = 250L
        const val HTTP_OK = 200
        const val MARKER_KEY = "erasure-marker-0c1"
        const val MARKER_ROW_ID = 9_000_000_101L

        val ACCOUNT: UUID = UUID.fromString(FakeAccountServiceTestResource.SECOND_ACCOUNT)
        val PARTY: UUID = UUID.fromString(FakeAccountServiceTestResource.SECOND_PARTY)
        val BLOCKED_HOLD: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0c01")
        val ERASED_HOLD: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0c02")
    }
}
