// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import com.openbank.fraud.it.PostgresRedisRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ListGroupsOptions
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * Reproduces the sandbox cluster boot-crash that followed PR #635 (ADR-0084 §3 v4 payee-history).
 *
 * Root cause (not PR #635 itself — a pre-existing, fleet-wide-pattern bug PR #635 merely exposed):
 * `application.yaml`'s `mp.messaging.incoming.transaction-signal.group.id` (and
 * `auto.offset.reset` / `dead-letter-queue.topic`) never actually applied. SmallRye Config's YAML
 * source unconditionally quotes any leaf map key containing a literal dot, so those three keys
 * registered ONLY as e.g. `mp.messaging.incoming.transaction-signal."group.id"` — never the plain
 * `...group.id` that `KafkaConnectorIncomingConfiguration.getGroupId()` looks up. `group.id` then
 * silently fell back to Quarkus's default (`quarkus.application.name` = "openbank-fraud-service"),
 * which the fraud-service KafkaUser ACL (kafka-fraud-mtls.yaml) never granted Read/Describe on —
 * every poll got `org.apache.kafka.common.errors.GroupAuthorizationException`. Normally fail-silent
 * (this is why it went unnoticed: velocity aggregates / payee history simply never populated, on
 * every fraud-service pod, since the channel was introduced), but landing inside Quarkus's
 * synchronous startup window turns it into a fatal, stack-trace-less "Failed to start application"
 * crash-loop — timing-dependent, which is why the long-running stable pod merely degraded silently
 * while the freshly-restarted canary pod (carrying the exact same latent bug) crashed on boot.
 *
 * Unlike [FraudBootSmokeIT] (Kafka disabled in `%test`), this boots the app with a REAL Redpanda
 * broker and the `transaction-signal` channel genuinely enabled, publishes a real
 * `openbank.transactions.transaction.initiated` message, and asserts the consumer actually joined
 * the intended `openbank-fraud-service-velocity` group (not the buggy fallback) with no
 * authorization failure — the deepest regression test practical without a live Kafka ACL/mTLS setup
 * in the test container (Redpanda here runs without ACL enforcement at all, so a naive "did the app
 * crash" check alone would NOT have caught the group-id bug: the fix is verified by asserting the
 * effective group id directly, not merely by absence of a crash).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisRedpandaTestResource::class)
class TransactionSignalConsumerBootIT {

    @Test
    fun `consumer joins the configured velocity group id, not the buggy default fallback`() {
        val bootstrap = PostgresRedisRedpandaTestResource.lastBootstrapServers
            ?: error("PostgresRedisRedpandaTestResource did not record its bootstrap servers")

        AdminClient.create(
            Properties().apply { put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap) },
        ).use { admin ->
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            var groupIds: Set<String> = emptySet()
            while (System.currentTimeMillis() < deadline) {
                groupIds = admin.listGroups(ListGroupsOptions.forConsumerGroups())
                    .all().get().map { it.groupId() }.toSet()
                if (groupIds.isNotEmpty()) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
            // The regression: without the fix this set contains "openbank-fraud-service" (the
            // Quarkus default-name fallback) instead of the intended, ACL'd
            // "openbank-fraud-service-velocity" — reproducing exactly what the sandbox cluster's
            // Kafka broker ACL rejected.
            assertThat(groupIds).contains(EXPECTED_GROUP_ID)
            assertThat(groupIds).doesNotContain(BUGGY_FALLBACK_GROUP_ID)
        }
    }

    @Test
    fun `a real transaction-initiated Kafka message is consumed without crashing the app`() {
        // Sanity: the app itself must still be up before we even send a message.
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }

        val bootstrap = PostgresRedisRedpandaTestResource.lastBootstrapServers
            ?: error("PostgresRedisRedpandaTestResource did not record its bootstrap servers")

        publishTransactionInitiatedBurst(bootstrap)
        val dlqRecords = readDlq(bootstrap)
        println("DLQ records observed: $dlqRecords")

        // The app must survive processing the message: readiness must stay UP well after the
        // message was almost certainly polled and processed.
        awaitReadiness()
    }

    /**
     * Topic is created with multiple partitions by [PostgresRedisRedpandaTestResource.start] BEFORE
     * the app boots (so the consumer subscribes to the real partition count, not a single
     * auto-created partition that would serialize processing).
     */
    private fun publishTransactionInitiatedBurst(bootstrap: String) {
        val partitions = PostgresRedisRedpandaTestResource.TOPIC_PARTITIONS
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        }
        KafkaProducer<String, String>(props).use { producer ->
            repeat(MESSAGE_BURST_SIZE) { i ->
                val payload = """
                    {
                      "aggregateId": "${UUID.randomUUID()}",
                      "sourceAccountId": "${UUID.randomUUID()}",
                      "targetAccountId": "${UUID.randomUUID()}",
                      "amount": 1250.00,
                      "currencyCode": "CZK",
                      "occurredAt": "2026-07-09T10:00:00Z"
                    }
                """.trimIndent()
                producer.send(
                    ProducerRecord(
                        "openbank.transactions.transaction.initiated",
                        i % partitions,
                        null,
                        payload,
                    ),
                )
            }
            producer.flush()
        }
    }

    /**
     * If a message is nacked, SmallRye's KafkaDeadLetterQueue publishes it here with
     * `dead-letter-exception-class-name` / `dead-letter-reason` headers carrying the swallowed
     * exception's class + message — the cluster's own logs never show a stack trace, so this is the
     * only way to recover the real cause. Subscribes to the real, intended DLQ topic name
     * (openbank.transactions.transaction.initiated.fraud.dlq) — before the fix,
     * dead-letter-queue.topic had the identical quoting bug, so SmallRye Kafka silently used its own
     * default DLQ topic name instead.
     */
    private fun readDlq(bootstrap: String): List<String> {
        val dlqProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlq-reader-${UUID.randomUUID()}")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        return KafkaConsumer<String, String>(dlqProps).use { consumer ->
            consumer.subscribe(listOf("openbank.transactions.transaction.initiated.fraud.dlq"))
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            val collected = mutableListOf<String>()
            while (System.currentTimeMillis() < deadline) {
                val records = consumer.poll(Duration.ofMillis(POLL_INTERVAL_MS))
                records.forEach { record ->
                    val headers = record.headers().associate { it.key() to String(it.value()) }
                    collected += "value=${record.value()} headers=$headers"
                }
            }
            collected
        }
    }

    private fun awaitReadiness() {
        val deadline = System.currentTimeMillis() + DEADLINE_MS
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
                lastError = null
                break
            } catch (ex: Throwable) {
                lastError = ex
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        lastError?.let { throw it }
    }

    private companion object {
        const val DEADLINE_MS = 20_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val MESSAGE_BURST_SIZE = 60
        const val EXPECTED_GROUP_ID = "openbank-fraud-service-velocity"
        const val BUGGY_FALLBACK_GROUP_ID = "openbank-fraud-service"
    }
}
