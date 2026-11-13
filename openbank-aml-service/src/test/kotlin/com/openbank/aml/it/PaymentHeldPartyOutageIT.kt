// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.infrastructure.client.AccountServiceClient
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A hold whose debtor party the rail could not name must still get its case while the account
 * authority is unreachable — that outage is the usual reason the rail could not name it.
 *
 * End to end on the case side: a `PAYMENT_HELD` record with `debtorPartyId: null` on a real broker,
 * the production consumer, a real Postgres, and the production account rest-client pointed at an
 * account authority that this test takes down (connection refused, then 503), brings back, and
 * asks about an account it has never heard of (404). The resolution sweep runs on its REAL
 * schedule: a direct call would supply the Vert.x context the scheduler must supply itself.
 *
 * "Nothing was dead-lettered" is proved against a record that MUST be: a forged-key event is sent
 * last, and the dead-letter topic has to hold exactly that one.
 *
 * All ids are literals: a test profile loads in another classloader.
 */
@QuarkusTest
@TestProfile(PaymentHeldPartyOutageIT.OutageProfile::class)
class PaymentHeldPartyOutageIT {

    class OutageProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(PostgresRedpandaRedisTestResource::class.java),
            QuarkusTestProfile.TestResourceEntry(FakeAccountServiceTestResource::class.java),
        )

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "mp.messaging.incoming.payment-held-in.enabled" to "true",
            "quarkus.scheduler.enabled" to "true",
            "openbank.aml.party-resolution.interval" to "2s",
            "openbank.aml.party-resolution.initial-delay" to "1s",
            "openbank.aml.monitoring.attribution.enabled" to "false",
            "openbank.aml.monitoring.evaluation.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var pool: PgPool

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var accounts: AccountServiceClient

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private data class Case(val party: UUID?, val account: UUID?, val attempted: Boolean, val createdEvents: Long)

    /** `to_jsonb(c) ->> ...` so the query reads the attempt marker without assuming how it is stored. */
    private fun caseOf(payment: UUID): Case? = onEventLoop {
        pool.preparedQuery(
            """
            SELECT c.party_id, c.account_id, (to_jsonb(c) ->> 'party_resolution_attempted_at') IS NOT NULL,
                   (SELECT count(*) FROM aml_outbox o WHERE o.aggregate_id = c.case_id)
              FROM aml_cases c WHERE c.idempotency_key = $1
            """.trimIndent(),
        ).execute(Tuple.of("payment-hold:domestic:$payment")).awaitSuspending()
            .map { Case(it.getUUID(0), it.getUUID(1), it.getBoolean(2), it.getLong(3)) }
            .singleOrNull()
    }

    private fun accountService(mode: String) {
        val control = ConfigProvider.getConfig()
            .getValue(FakeAccountServiceTestResource.CONTROL_URL_KEY, String::class.java)
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$control/mode/$mode")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(response.statusCode()).describedAs("switching the fake account authority to %s", mode)
            .isEqualTo(HTTP_OK)
    }

    private fun held(payment: UUID, account: UUID): String = mapper.writeValueAsString(
        mapOf(
            "eventType" to "PAYMENT_HELD",
            "paymentId" to payment.toString(),
            "rail" to "DOMESTIC",
            "caseKey" to "payment-hold:domestic:$payment",
            "debtorAccountId" to account.toString(),
            "debtorPartyId" to null,
            "customerReference" to "1234567890/0800",
            "reasons" to listOf("STANDING_UNAVAILABLE"),
            "steps" to listOf(
                mapOf("step" to "STANDING", "outcome" to "UNAVAILABLE", "reason" to "STANDING_UNAVAILABLE"),
            ),
            "heldAt" to "2026-03-02T09:30:00Z",
            "expiresAt" to "2026-03-04T09:30:00Z",
            "sourceService" to "domestic-payment",
        ),
    )

    private fun connection(): Map<String, Any> =
        mapOf("bootstrap.servers" to ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java))

    private fun send(key: UUID, payload: String) {
        KafkaProducer<String, String>(
            connection() + mapOf("key.serializer" to SERIALIZER, "value.serializer" to SERIALIZER, "acks" to "all"),
        ).use { it.send(ProducerRecord(TOPIC, key.toString(), payload)).get(20, TimeUnit.SECONDS) }
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
    fun `a hold with no named party gets its case while the account authority is down, and its party later`() {
        // Known positive for the fake and for the production client in front of it, before anything is
        // taken down: the lookup works when the authority is up. It also makes the client's first use
        // happen on this thread. Under the test classloader a first use from a coroutine worker fails
        // to load the generated client class, permanently, and every sweep would then abort for a
        // reason that has nothing to do with the authority being down.
        assertThat(runBlocking { accounts.findPartyByAccountId(KNOWN_ACCOUNT) }).isEqualTo(OWNING_PARTY)
        assertThat(runBlocking { accounts.findPartyByAccountId(UNKNOWN_ACCOUNT) }).isNull()

        accountService(FakeAccountServiceTestResource.REFUSED)
        assertThat(runBlocking { accounts.findPartyByAccountId(KNOWN_ACCOUNT) })
            .describedAs("the authority really is unreachable now")
            .isNull()
        send(REFUSED_HOLD, held(REFUSED_HOLD, KNOWN_ACCOUNT))
        await("a case is opened while the account authority refuses connections") { caseOf(REFUSED_HOLD) != null }
        await("the sweep tried the refused lookup and left the party unresolved") {
            caseOf(REFUSED_HOLD)!!.attempted
        }
        assertThat(caseOf(REFUSED_HOLD)).isEqualTo(Case(party = null, account = KNOWN_ACCOUNT, true, 1))

        accountService(FakeAccountServiceTestResource.UNAVAILABLE)
        assertThat(runBlocking { accounts.findPartyByAccountId(KNOWN_ACCOUNT) })
            .describedAs("the authority really answers an error now")
            .isNull()
        send(UNAVAILABLE_HOLD, held(UNAVAILABLE_HOLD, KNOWN_ACCOUNT))
        await("a case is opened while the account authority answers 503") { caseOf(UNAVAILABLE_HOLD) != null }
        await("the sweep tried the 503 lookup and left the party unresolved") {
            caseOf(UNAVAILABLE_HOLD)!!.attempted
        }
        assertThat(caseOf(UNAVAILABLE_HOLD)!!.party).isNull()
        assertThat(caseOf(REFUSED_HOLD)!!.party).isNull()

        accountService(FakeAccountServiceTestResource.UP)
        send(UNKNOWN_ACCOUNT_HOLD, held(UNKNOWN_ACCOUNT_HOLD, UNKNOWN_ACCOUNT))
        await("the account authority is back, so both parties get resolved") {
            caseOf(REFUSED_HOLD)!!.party == OWNING_PARTY && caseOf(UNAVAILABLE_HOLD)!!.party == OWNING_PARTY
        }
        await("a case is opened for an account the authority does not know, and the sweep tried it") {
            caseOf(UNKNOWN_ACCOUNT_HOLD)?.attempted == true
        }
        assertThat(caseOf(UNKNOWN_ACCOUNT_HOLD))
            .describedAs("404 leaves the party unresolved: neither a made-up party nor the account id")
            .isEqualTo(Case(party = null, account = UNKNOWN_ACCOUNT, true, 1))

        // The known positive for the dead-letter topic: this one MUST be parked, and be the only one.
        val forged = held(FORGED_HOLD, KNOWN_ACCOUNT).replace("payment-hold:domestic:$FORGED_HOLD", "tm:forged")
        KafkaConsumer<String, String>(
            connection() + mapOf(
                "group.id" to "aml-payment-held-outage-dlq-proof",
                "auto.offset.reset" to "earliest",
                "key.deserializer" to DESERIALIZER,
                "value.deserializer" to DESERIALIZER,
            ),
        ).use { deadLetters ->
            deadLetters.subscribe(listOf(DLQ))
            send(FORGED_HOLD, forged)
            val parked = mutableListOf<String>()
            val deadline = System.nanoTime() + BUDGET_NANOS
            while (parked.isEmpty() && System.nanoTime() < deadline) {
                deadLetters.poll(Duration.ofMillis(POLL_MILLIS)).forEach { parked += it.value() }
            }
            // One more poll window: a second parked record would arrive behind the first.
            deadLetters.poll(Duration.ofSeconds(2)).forEach { parked += it.value() }
            assertThat(parked).describedAs("no hold was dead-lettered for want of a party").containsExactly(forged)
        }
    }

    private companion object {
        const val TOPIC = "openbank.domestic.payment.events"
        const val DLQ = "openbank.dlq.aml.payment-held-in"
        const val SERIALIZER = "org.apache.kafka.common.serialization.StringSerializer"
        const val DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer"
        const val BUDGET_NANOS = 90_000_000_000L
        const val POLL_MILLIS = 250L
        const val HTTP_OK = 200

        val KNOWN_ACCOUNT: UUID = UUID.fromString(FakeAccountServiceTestResource.KNOWN_ACCOUNT)
        val OWNING_PARTY: UUID = UUID.fromString(FakeAccountServiceTestResource.OWNING_PARTY)
        val UNKNOWN_ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-0000000ac0b2")
        val REFUSED_HOLD: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0b01")
        val UNAVAILABLE_HOLD: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0b02")
        val UNKNOWN_ACCOUNT_HOLD: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0b03")
        val FORGED_HOLD: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0b04")
    }
}
