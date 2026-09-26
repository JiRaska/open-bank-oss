// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
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

/** Real Kafka -> production consumer -> PostgreSQL; HTTP identity is a test fixture, not OIDC proof. */
@QuarkusTest
@TestProfile(LedgerProjectionDlqIT.ProjectionProfile::class)
class LedgerProjectionDlqIT {
    class ProjectionProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaTestResource::class.java))
        override fun getConfigOverrides() = mapOf(
            "openbank.balance.projection.enabled" to "true",
            "mp.messaging.incoming.ledger-events-in.group.id" to GROUP,
            "mp.messaging.incoming.ledger-events-in.auto.offset.reset" to "earliest",
        )
    }

    @Inject lateinit var mapper: ObjectMapper

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `poison records are parked and the next projection consumes cover exactly once`() {
        val account = UUID.randomUUID()
        val transaction = UUID.randomUUID()
        given().contentType("application/json").body(mapOf("currency" to "CZK", "initialAmount" to "100.00"))
            .post("/api/v1/balances/$account/initialize").then().statusCode(201)
        given().contentType("application/json").body(
            mapOf("currency" to "CZK", "amount" to "40.00", "reason" to "settlement", "referenceId" to "$transaction"),
        ).post("/api/v1/balances/$account/holds").then().statusCode(201)
        val valid = mapper.writeValueAsString(
            mapOf(
                "eventType" to "AccountBookedChanged",
                "aggregateId" to "$account",
                "currency" to "CZK",
                "delta" to "-40.00",
                "journalEntryId" to "${UUID.randomUUID()}",
                "transactionId" to "$transaction",
                "entryDate" to LocalDate.now().toString(),
                "version" to 1,
            ),
        )
        val poison = listOf("{not json", """{"eventType":"AccountBookedChanged","aggregateId":"$account"}""")
        val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
        val connection = mapOf("bootstrap.servers" to bootstrap)
        KafkaProducer<String, String>(
            connection + mapOf(
                "key.serializer" to STRING_SERIALIZER,
                "value.serializer" to STRING_SERIALIZER,
                "acks" to "all",
            ),
        ).use { producer ->
            Admin.create(connection).use { admin ->
                KafkaConsumer<String, String>(
                    connection + mapOf(
                        "group.id" to "dlq-proof-${UUID.randomUUID()}",
                        "auto.offset.reset" to "earliest",
                        "key.deserializer" to STRING_DESERIALIZER,
                        "value.deserializer" to STRING_DESERIALIZER,
                    ),
                ).use { deadLetters ->
                    deadLetters.subscribe(listOf(DLQ))
                    sendAndAwait(producer, admin, "$account", poison + valid + valid)
                    assertDeadLetters(deadLetters, "$account", poison)
                    val balance = given().get(
                        "/api/v1/balances/$account/CZK",
                    ).then().statusCode(200).extract().jsonPath()
                    assertThat(BigDecimal(balance.getString("bookedAmount"))).isEqualByComparingTo("60.00")
                    assertThat(BigDecimal(balance.getString("reservedAmount"))).isEqualByComparingTo("0.00")
                    assertThat(BigDecimal(balance.getString("availableAmount"))).isEqualByComparingTo("60.00")
                }
            }
        }
    }

    private fun sendAndAwait(
        producer: KafkaProducer<String, String>,
        admin: Admin,
        key: String,
        payloads: List<String>,
    ) {
        for (payload in payloads) {
            // A common key puts poison and valid records on the same partition.
            val sent = producer.send(ProducerRecord(TOPIC, key, payload)).get(10, TimeUnit.SECONDS)
            awaitCommitted(admin, TopicPartition(sent.topic(), sent.partition()), sent.offset() + 1)
        }
    }

    private fun awaitCommitted(admin: Admin, partition: TopicPartition, offset: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS)[partition]
            if (committed != null && committed.offset() >= offset) return
            Thread.sleep(100)
        }
        error("Projection consumer did not acknowledge the record")
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
        private const val GROUP = "balance-projection-dlq-it"
        private const val TOPIC = "openbank.ledger.journal.posted"
        private const val DLQ = "openbank.dlq.balance.ledger-events-in"
        private const val STRING_SERIALIZER = "org.apache.kafka.common.serialization.StringSerializer"
        private const val STRING_DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer"
    }
}
