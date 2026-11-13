// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.out.AccountPartyPort
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ADR-0374 D13 end to end on the case side: a `PAYMENT_HELD` record on a real broker -> the
 * production consumer -> a case row in a real Postgres, with its `aml.case.created.v1` outbox row.
 *
 * Nothing calls the consumer directly: a direct call would supply the Vert.x context the connector
 * must supply itself, and could not show a redelivery, a neighbouring event type or a poison record
 * as the connector delivers them.
 *
 * Only the account authority is stubbed. All ids are literals: a test profile loads in another
 * classloader, so a randomised companion value would differ between stub and test.
 */
@QuarkusTest
@TestProfile(PaymentHeldCaseIntakeIT.IntakeProfile::class)
class PaymentHeldCaseIntakeIT {

    class IntakeProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaRedisTestResource::class.java))

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "mp.messaging.incoming.payment-held-in.enabled" to "true",
            "quarkus.scheduler.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(StubHeldAccountParty::class.java)
    }

    @Alternative
    @ApplicationScoped
    class StubHeldAccountParty : AccountPartyPort {
        override suspend fun findPartyByAccountId(accountId: UUID): UUID? = if (accountId ==
            RESOLVED_ACCOUNT
        ) {
            PARTY
        } else {
            null
        }
    }

    @Inject
    lateinit var pool: PgPool

    @Inject
    lateinit var mapper: ObjectMapper

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private data class Case(
        val key: String,
        val party: UUID,
        val alert: String,
        val status: String,
        val type: String,
        val outboxEvents: Long,
    )

    private fun casesOf(payment: UUID): List<Case> = onEventLoop {
        pool.preparedQuery(
            """
            SELECT c.idempotency_key, c.party_id, c.alert_code, c.status, c.screening_type,
                   (SELECT count(*) FROM aml_outbox o WHERE o.aggregate_id = c.case_id) AS events
              FROM aml_cases c WHERE c.transaction_id = $1
            """.trimIndent(),
        ).execute(Tuple.of(payment)).awaitSuspending().map {
            Case(
                it.getString(0),
                it.getUUID(1),
                it.getString(2),
                it.getString(3),
                it.getString(4),
                it.getLong(5),
            )
        }
    }

    private fun held(payment: UUID, account: UUID, party: UUID?): String = mapper.writeValueAsString(
        mapOf(
            "eventType" to "PAYMENT_HELD",
            "paymentId" to payment.toString(),
            "rail" to "DOMESTIC",
            "caseKey" to "payment-hold:domestic:$payment",
            "debtorAccountId" to account.toString(),
            "debtorPartyId" to party?.toString(),
            "customerReference" to "1234567890/0800",
            "reasons" to listOf("FRAUD_REVIEW"),
            "steps" to listOf(
                mapOf(
                    "step" to "FRAUD",
                    "outcome" to "DECIDED",
                    "decision" to "HOLD",
                    "reason" to "FRAUD_REVIEW",
                    "evidence" to mapOf("verdict" to "REVIEW", "score" to "71"),
                ),
            ),
            "heldAt" to "2026-03-02T09:30:00Z",
            "expiresAt" to "2026-03-04T09:30:00Z",
            "sourceService" to "domestic-payment",
        ),
    )

    private fun connection(): Map<String, Any> =
        mapOf("bootstrap.servers" to ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java))

    private fun send(key: UUID, vararg payloads: String) {
        KafkaProducer<String, String>(
            connection() + mapOf("key.serializer" to SERIALIZER, "value.serializer" to SERIALIZER, "acks" to "all"),
        ).use { producer ->
            payloads.forEach { producer.send(ProducerRecord(TOPIC, key.toString(), it)).get(20, TimeUnit.SECONDS) }
        }
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
    fun `a held payment gets exactly one case, however often its event is delivered`() {
        val statusChange = """{"eventType":"DOMESTIC_PAYMENT_STATUS_CHANGED","paymentId":"$ONE","newStatus":"HELD"}"""
        send(ONE, statusChange, held(ONE, RESOLVED_ACCOUNT, PARTY), held(ONE, RESOLVED_ACCOUNT, PARTY))
        // A later hold on the same topic proves the two copies above were both consumed.
        send(TWO, held(TWO, RESOLVED_ACCOUNT, null))

        await("the later hold has its case") { casesOf(TWO).size == 1 }

        assertThat(casesOf(ONE)).containsExactly(
            Case("payment-hold:domestic:$ONE", PARTY, "PAYMENT_HELD", "UNDER_REVIEW", "TRANSACTION_MONITORING", 1),
        )
        // The event named no party; the consumer asked the account authority.
        assertThat(casesOf(TWO).single().party).isEqualTo(PARTY)
    }

    @Test
    fun `a hold whose case cannot be opened is dead-lettered and the channel keeps consuming`() {
        val forgedKey = held(THREE, RESOLVED_ACCOUNT, PARTY).replace("payment-hold:domestic:$THREE", "tm:forged")
        val unresolved = held(FOUR, UNKNOWN_ACCOUNT, null)
        KafkaConsumer<String, String>(
            connection() + mapOf(
                "group.id" to "aml-payment-held-dlq-proof",
                "auto.offset.reset" to "earliest",
                "key.deserializer" to DESERIALIZER,
                "value.deserializer" to DESERIALIZER,
            ),
        ).use { deadLetters ->
            deadLetters.subscribe(listOf(DLQ))
            send(THREE, forgedKey)
            send(FOUR, unresolved)
            send(FIVE, held(FIVE, RESOLVED_ACCOUNT, PARTY))

            val parked = mutableListOf<String>()
            val deadline = System.nanoTime() + BUDGET_NANOS
            while (parked.size < 2 && System.nanoTime() < deadline) {
                deadLetters.poll(Duration.ofMillis(POLL_MILLIS)).forEach { parked += it.value() }
            }
            assertThat(parked).containsExactlyInAnyOrder(forgedKey, unresolved)
        }
        await("the hold behind the parked records has its case") { casesOf(FIVE).size == 1 }
        assertThat(casesOf(THREE)).isEmpty()
        assertThat(casesOf(FOUR)).isEmpty()
    }

    private companion object {
        const val TOPIC = "openbank.domestic.payment.events"
        const val DLQ = "openbank.dlq.aml.payment-held-in"
        const val SERIALIZER = "org.apache.kafka.common.serialization.StringSerializer"
        const val DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer"
        const val BUDGET_NANOS = 90_000_000_000L
        const val POLL_MILLIS = 250L

        val PARTY: UUID = UUID.fromString("00000000-0000-4000-8000-0000000bb0a1")
        val RESOLVED_ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-0000000ac0a1")
        val UNKNOWN_ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-0000000ac0a2")
        val ONE: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0a01")
        val TWO: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0a02")
        val THREE: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0a03")
        val FOUR: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0a04")
        val FIVE: UUID = UUID.fromString("00000000-0000-4000-8000-0000000d0a05")
    }
}
