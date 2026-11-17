// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.integration

import com.openbank.domestic.infrastructure.persistence.entity.DomesticPaymentOutboxEntity
import com.openbank.domestic.infrastructure.persistence.repository.DomesticPaymentOutboxRepositoryImpl
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.scheduler.Scheduler
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
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
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

@QuarkusTest
@TestProfile(DomesticPaymentFencedOutboxKafkaIT.SchedulerProfile::class)
class DomesticPaymentFencedOutboxKafkaIT {

    class SchedulerProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources(): Boolean = true

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaRedisTestResource::class.java))

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
    lateinit var outbox: DomesticPaymentOutboxRepositoryImpl

    @Inject
    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrapServers: String

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = ACTOR),
            Claim(key = "preferred_username", value = ACTOR),
        ],
    )
    fun `a written aggregate event reaches Kafka and is acknowledged by the scheduler`() {
        assertThat(scheduler.isRunning).isFalse()
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
            ConsumerConfig.GROUP_ID_CONFIG to "domestic-outbox-it-${UUID.randomUUID()}",
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

    private fun readRow(eventId: UUID): DomesticPaymentOutboxEntity = onEventLoop {
        Panache.withSession { outbox.find("eventId", eventId).singleResult() }.awaitSuspending()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun postAggregate(): UUID {
        val response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", "outbox-v2-${UUID.randomUUID()}")
            .body(
                """
                {
                  "debtorAccountId": "${UUID.randomUUID()}",
                  "debtorAccountNumber": "1234567890",
                  "debtorBankCode": "0800",
                  "debtorName": "Alice Example",
                  "creditorAccountNumber": "9876543210",
                  "creditorBankCode": "0100",
                  "creditorName": "Brno Utility",
                  "amount": ${BigDecimal("1234.56")},
                  "currency": "CZK",
                  "variableSymbol": "2026001",
                  "specificSymbol": null,
                  "constantSymbol": "0308",
                  "messageForPayee": "Utility bill",
                  "priority": "STANDARD",
                  "transferScope": null,
                  "technicalAccountCode": null,
                  "statementLabel": "Monthly settlement",
                  "endToEndId": "E2E-ATOMICITY-IT"
                }
                """.trimIndent(),
            )
            .post("/api/v1/domestic-payments")

        assertThat(response.statusCode).isEqualTo(201)
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    private fun ConsumerRecord<String, String>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.decodeToString()

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"
        const val OUTBOX_JOB = "domestic-payment-outbox-dispatcher"
        const val TOPIC = "openbank.domestic.payment.events"
        const val EVENT_TYPE = "domestic.payment.created"
    }
}
