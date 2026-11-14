// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.transaction.integration

import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.transaction.infrastructure.persistence.entity.TransactionOutboxEntity
import com.openbank.transaction.infrastructure.persistence.repository.TransactionOutboxRepositoryImpl
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.scheduler.Scheduler
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured
import io.restassured.config.HttpClientConfig
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.temporal.client.WorkflowClient
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@QuarkusTest
@TestProfile(TransactionFencedOutboxKafkaIT.SchedulerProfile::class)
class TransactionFencedOutboxKafkaIT {

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
    lateinit var workflowClient: WorkflowClient

    @Inject
    lateinit var outbox: TransactionOutboxRepositoryImpl

    @Inject
    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrapServers: String

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `a written aggregate event reaches Kafka and is acknowledged by the scheduler`() {
        assertThat(scheduler.isRunning).isFalse()
        workflowClient.workflowServiceStubs.connect(Duration.ofSeconds(20))
        val aggregate = postAggregate()
        val posted = onEventLoop {
            Panache.withSession {
                outbox.find("aggregateId = ?1 and eventType = ?2", aggregate, EVENT_TYPE).singleResult()
            }.awaitSuspending()
        }
        assertThat(posted.status).isEqualTo(OutboxStatus.PENDING.name)
        val eventId = posted.eventId.toString()

        scheduler.scheduledJobs.filter { it.id != OUTBOX_JOB }.forEach { scheduler.pause(it.id) }
        assertThat(scheduler.getScheduledJob(OUTBOX_JOB)).isNotNull()
        try {
            val record = receiveScheduledEvent(eventId)
            assertThat(record.key()).isEqualTo(aggregate.toString())
            assertThat(record.header(OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY)).isEqualTo(eventId)
            assertThat(record.header(OutboxKafkaHeaders.HEADER_EVENT_TYPE)).isEqualTo(EVENT_TYPE)
            assertAcknowledged(posted.eventId)
        } finally {
            scheduler.pause()
        }
    }

    private fun receiveScheduledEvent(eventId: String): ConsumerRecord<String, String> = KafkaConsumer(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "transaction-outbox-it-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        StringDeserializer(),
    ).use { consumer ->
        consumer.subscribe(listOf(TOPIC))
        scheduler.resume()
        val deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos()
        while (System.nanoTime() < deadline) {
            val record = consumer.poll(Duration.ofMillis(250))
                .firstOrNull { it.header(OutboxKafkaHeaders.HEADER_EVENT_ID) == eventId }
            if (record != null) return@use record
        }
        error("Scheduled outbox event $eventId did not reach Kafka")
    }

    private fun assertAcknowledged(eventId: UUID) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var row = readRow(eventId)
        while (row.status != OutboxStatus.SENT.name && System.nanoTime() < deadline) {
            Thread.sleep(50)
            row = readRow(eventId)
        }
        assertThat(row.status).isEqualTo(OutboxStatus.SENT.name)
        assertThat(row.attemptCount).isOne()
        assertThat(row.sentAt).isNotNull
        assertThat(row.claimToken).isNull()
    }

    private fun readRow(eventId: UUID): TransactionOutboxEntity = onEventLoop {
        Panache.withSession { outbox.find("eventId", eventId).singleResult() }.awaitSuspending()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun postAggregate(): UUID {
        val response = RestAssured.given()
            .config(
                RestAssured.config().httpClient(
                    HttpClientConfig.httpClientConfig().setParam("http.socket.timeout", 60_000),
                ),
            )
            .contentType("application/json")
            .body(
                """
                {
                  "idempotencyKey": "${UUID.randomUUID()}",
                  "type": "TRANSFER",
                  "sourceAccountId": "${UUID.randomUUID()}",
                  "targetAccountId": "${UUID.randomUUID()}",
                  "amount": "1234.56",
                  "currencyCode": "CZK",
                  "baseAmount": "1234.56",
                  "baseCurrencyCode": "CZK",
                  "description": "Outbox atomicity IT",
                  "valueDate": "${LocalDate.now()}",
                  "bookingDate": "${LocalDate.now()}"
                }
                """.trimIndent(),
            )
            .post("/api/v1/transactions")
        assertThat(response.statusCode).isEqualTo(201)
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    private fun ConsumerRecord<String, String>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.decodeToString()

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"
        const val OUTBOX_JOB = "transaction-outbox-dispatcher"
        const val TOPIC = "openbank.transactions.transaction.initiated"
        const val EVENT_TYPE = "openbank.transactions.transaction.initiated"
    }
}
