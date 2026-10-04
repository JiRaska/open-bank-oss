// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.it.PostgresRedpandaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * #11604: both Kafka consumers build kernel Money / CurrencyCode before anything is persisted, and
 * an event Money cannot hold is PARKED on the channel's configured dead-letter topic — the channel
 * does not stop, nothing about the record is written, and the next valid record on the same
 * partition is consumed normally.
 *
 * On main before this change: a `-40.005 CZK` delta was booked as a sub-haléř figure, a `XYZ` delta
 * created a phantom `XYZ` pocket, and an `AccountCreated` in `XYZ` initialised a `XYZ` balance.
 * Real Kafka -> production consumers -> PostgreSQL; HTTP identity is a test fixture.
 */
@QuarkusTest
@TestProfile(BalanceConsumerMoneyDlqIT.Profile::class)
class BalanceConsumerMoneyDlqIT {
    class Profile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaTestResource::class.java))
        override fun getConfigOverrides() = mapOf(
            "openbank.balance.projection.enabled" to "true",
            "mp.messaging.incoming.ledger-events-in.group.id" to LEDGER_GROUP,
            "mp.messaging.incoming.ledger-events-in.auto.offset.reset" to "earliest",
            "mp.messaging.incoming.balance-init-in.group.id" to INIT_GROUP,
            "mp.messaging.incoming.balance-init-in.auto.offset.reset" to "earliest",
        )
    }

    @Inject lateinit var mapper: ObjectMapper

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `a ledger delta Money cannot hold is dead-lettered and moves nothing`() {
        val account = UUID.randomUUID()
        given().contentType("application/json").body(mapOf("currency" to "CZK", "initialAmount" to "100.00"))
            .post("/api/v1/balances/$account/initialize").then().statusCode(201)
        val poison = listOf(
            booked(account, "CZK", "-40.005"),
            booked(account, "XYZ", "-40.00"),
            booked(account, "CZK", "1E+19"),
        )
        val valid = booked(account, "CZK", "-40.00")
        withKafka(DLQ_LEDGER) { producer, admin, deadLetters ->
            send(producer, admin, LEDGER_TOPIC, LEDGER_GROUP, "$account", poison + valid)
            assertDeadLetters(deadLetters, "$account", poison)
        }
        val pocket = given().get("/api/v1/balances/$account/CZK").then().statusCode(200).extract().jsonPath()
        assertThat(BigDecimal(pocket.getString("bookedAmount"))).isEqualByComparingTo("60.00")
        assertThat(BigDecimal(pocket.getString("availableAmount"))).isEqualByComparingTo("60.00")
        given().get("/api/v1/balances/$account/XYZ").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `an AccountCreated in an unsupported currency is dead-lettered and creates no balance`() {
        val poisoned = UUID.randomUUID()
        val valid = UUID.randomUUID()
        val poison = listOf(created(poisoned, "XYZ"), created(poisoned, "XAU"))
        withKafka(DLQ_INIT) { producer, admin, deadLetters ->
            send(producer, admin, INIT_TOPIC, INIT_GROUP, "k-$poisoned", poison + created(valid, " eur"))
            assertDeadLetters(deadLetters, "k-$poisoned", poison)
        }
        given().get("/api/v1/balances/$poisoned").then().statusCode(200)
            .extract().jsonPath().getList<Any>("balances").let { assertThat(it).isEmpty() }
        given().get("/api/v1/balances/$valid/EUR").then().statusCode(200)
    }

    private fun booked(account: UUID, currency: String, delta: String) = mapper.writeValueAsString(
        mapOf(
            "eventType" to "AccountBookedChanged",
            "aggregateId" to "$account",
            "currency" to currency,
            "delta" to delta,
            "journalEntryId" to "${UUID.randomUUID()}",
            "transactionId" to "${UUID.randomUUID()}",
            "entryDate" to LocalDate.now().toString(),
            "version" to 1,
        ),
    )

    private fun created(account: UUID, currency: String) = mapper.writeValueAsString(
        mapOf("eventType" to "AccountCreated", "aggregateId" to "$account", "currency" to currency),
    )

    private fun withKafka(
        dlq: String,
        block: (KafkaProducer<String, String>, Admin, KafkaConsumer<String, String>) -> Unit,
    ) {
        val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
        val connection = mapOf("bootstrap.servers" to bootstrap)
        KafkaProducer<String, String>(
            connection + mapOf("key.serializer" to SER, "value.serializer" to SER, "acks" to "all"),
        ).use { producer ->
            Admin.create(connection).use { admin ->
                KafkaConsumer<String, String>(
                    connection + mapOf(
                        "group.id" to "money-dlq-proof-${UUID.randomUUID()}",
                        "auto.offset.reset" to "earliest",
                        "key.deserializer" to DESER,
                        "value.deserializer" to DESER,
                    ),
                ).use { deadLetters ->
                    deadLetters.subscribe(listOf(dlq))
                    block(producer, admin, deadLetters)
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun send(
        producer: KafkaProducer<String, String>,
        admin: Admin,
        topic: String,
        group: String,
        key: String,
        payloads: List<String>,
    ) {
        for (payload in payloads) {
            // One key: poison and valid records share a partition, so a wedged channel would show.
            val sent = producer.send(ProducerRecord(topic, key, payload)).get(10, TimeUnit.SECONDS)
            awaitCommitted(admin, group, TopicPartition(sent.topic(), sent.partition()), sent.offset() + 1)
        }
    }

    private fun awaitCommitted(admin: Admin, group: String, partition: TopicPartition, offset: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS)[partition]
            if (committed != null && committed.offset() >= offset) return
            Thread.sleep(100)
        }
        error("Consumer group $group did not acknowledge the record")
    }

    private fun assertDeadLetters(consumer: KafkaConsumer<String, String>, key: String, expected: List<String>) {
        val received = mutableListOf<String>()
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (received.size < expected.size && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).filter { it.key() == key }.forEach { record ->
                assertThat(record.headers().lastHeader("dead-letter-reason")).isNotNull()
                received += record.value()
            }
        }
        assertThat(received).containsExactlyElementsOf(expected)
    }

    companion object {
        private const val LEDGER_GROUP = "balance-money-ledger-dlq-it"
        private const val INIT_GROUP = "balance-money-init-dlq-it"
        private const val LEDGER_TOPIC = "openbank.ledger.journal.posted"
        private const val INIT_TOPIC = "openbank.accounts.account.created"
        private const val DLQ_LEDGER = "openbank.dlq.balance.ledger-events-in"
        private const val DLQ_INIT = "openbank.dlq.balance.balance-init-in"
        private const val SER = "org.apache.kafka.common.serialization.StringSerializer"
        private const val DESER = "org.apache.kafka.common.serialization.StringDeserializer"
    }
}
