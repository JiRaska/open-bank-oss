// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.infrastructure.persistence.entity.LedgerOutboxEntity
import com.openbank.ledger.infrastructure.persistence.repository.LedgerOutboxRepositoryImpl
import com.openbank.ledger.it.PostgresRedpandaTestResource
import com.openbank.libs.persistence.outbox.OutboxDispatch
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.scheduler.Scheduler
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@QuarkusTest
@TestProfile(LedgerFencedOutboxKafkaIT.SchedulerProfile::class)
class LedgerFencedOutboxKafkaIT {

    class SchedulerProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources(): Boolean = true

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaTestResource::class.java))

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "quarkus.scheduler.start-mode" to "halted",
            "openbank.outbox.dispatch-enabled" to "true",
            "openbank.outbox.poll-interval" to "1s",
            "openbank.outbox.initial-delay" to "1s",
        )
    }

    @Inject
    lateinit var scheduler: Scheduler

    @Inject
    lateinit var outbox: LedgerOutboxRepositoryImpl

    @Inject
    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrapServers: String

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `a ledger journal and a full outbox batch reach Kafka through the real scheduler`() {
        assertThat(scheduler.isRunning).isFalse()
        val journal = postJournal()
        val posted = onEventLoop {
            Panache.withSession { outbox.find("aggregateId", journal).singleResult() }.awaitSuspending()
        }
        assertThat(posted.status).isEqualTo(OutboxStatus.PENDING.name)
        val expected = seedFullBatch(posted)

        scheduler.scheduledJobs.filter { it.id != OUTBOX_JOB }.forEach { scheduler.pause(it.id) }
        assertThat(scheduler.getScheduledJob(OUTBOX_JOB)).isNotNull()
        try {
            val records = receiveScheduledBatch(expected.keys)
            assertThat(records.map { it.header(OutboxKafkaHeaders.HEADER_EVENT_ID) })
                .containsExactlyInAnyOrderElementsOf(expected.keys)
            records.forEach { record ->
                val eventId = requireNotNull(record.header(OutboxKafkaHeaders.HEADER_EVENT_ID))
                assertThat(record.key()).isEqualTo(expected.getValue(eventId))
                assertThat(record.header(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY)).isEqualTo(eventId)
            }
            assertAcknowledged(expected.keys.map(UUID::fromString))
        } finally {
            scheduler.pause()
        }
    }

    private fun seedFullBatch(posted: LedgerOutboxEntity): Map<String, String> = buildMap {
        put(posted.eventId.toString(), posted.aggregateId.toString())
        repeat(OutboxDispatch.DEFAULT_BATCH_SIZE - 1) {
            val row = OutboxMessage(
                aggregateId = UUID.randomUUID(),
                eventType = posted.eventType,
                payload = "{}",
                synthetic = true,
            )
            onEventLoop { Panache.withTransaction { outbox.persistInTransaction(row) }.awaitSuspending() }
            put(row.eventId.toString(), row.aggregateId.toString())
        }
    }

    private fun receiveScheduledBatch(expected: Set<String>): List<ConsumerRecord<String, String>> = KafkaConsumer(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        StringDeserializer(),
    ).use { consumer ->
        // Wait for the fresh broker's topic metadata before allowing the first scheduled send.
        val partitions = consumer.partitionsFor(TOPIC, Duration.ofSeconds(40))
        assertThat(partitions).describedAs("ledger outbox topic partitions").isNotEmpty()
        val assigned = partitions.map { TopicPartition(it.topic(), it.partition()) }
        consumer.assign(assigned)
        consumer.seekToBeginning(assigned)
        scheduler.resume()
        val records = mutableListOf<ConsumerRecord<String, String>>()
        val deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos()
        while (records.size < expected.size && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250))
                .filterTo(records) { it.header(OutboxKafkaHeaders.HEADER_EVENT_ID) in expected }
        }
        records
    }

    private fun assertAcknowledged(eventIds: List<UUID>) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var rows = readRows(eventIds)
        while (rows.any { it.status != OutboxStatus.SENT.name } && System.nanoTime() < deadline) {
            Thread.sleep(50)
            rows = readRows(eventIds)
        }
        assertThat(rows).hasSize(eventIds.size).allSatisfy {
            assertThat(it.status).isEqualTo(OutboxStatus.SENT.name)
            assertThat(it.attemptCount).isOne()
            assertThat(it.sentAt).isNotNull
            assertThat(it.claimToken).isNull()
        }
    }

    private fun readRows(eventIds: List<UUID>): List<LedgerOutboxEntity> = onEventLoop {
        Panache.withSession { outbox.list("eventId in ?1", eventIds) }.awaitSuspending()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun postJournal(): UUID {
        val date = LocalDate.now()
        val response = RestAssured.given().contentType("application/json").body(
            """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "${UUID.randomUUID()}",
              "entryDate": "$date",
              "valueDate": "$date",
              "description": "Fenced ledger outbox integration test",
              "createdBy": "$ACTOR",
              "lines": [
                {"glAccountId": "a0000000-0000-0000-0000-000000000001", "side": "DEBIT", "amount": "1",
                 "currencyCode": "CZK", "baseAmount": "1", "baseCurrencyCode": "CZK"},
                {"glAccountId": "a0000000-0000-0000-0000-000000000002", "side": "CREDIT", "amount": "1",
                 "currencyCode": "CZK", "baseAmount": "1", "baseCurrencyCode": "CZK"}
              ]
            }
            """.trimIndent(),
        ).post("/api/v1/journals")
        assertThat(response.statusCode).describedAs("Ledger post response: %s", response.body.asString()).isEqualTo(201)
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    private fun ConsumerRecord<String, String>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.decodeToString()

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"
        const val OUTBOX_JOB = "ledger-outbox-dispatcher"
        const val TOPIC = "openbank.ledger.journal.posted"
    }
}
