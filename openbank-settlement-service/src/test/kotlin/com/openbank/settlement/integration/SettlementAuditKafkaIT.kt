// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.settlement.it.SettlementKafkaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@TestProfile(SettlementAuditKafkaIT.RealKafkaProfile::class)
class SettlementAuditKafkaIT {
    class RealKafkaProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(SettlementKafkaTestResource::class.java))
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var objectMapper: ObjectMapper

    @Test
    @TestSecurity(user = "test-settlement-operator", roles = ["ROLE_OPERATOR"])
    fun `real scheduler publishes raw JSON and reclaims a lost acknowledgement with the same event id`() {
        consumer().use { consumer ->
            consumer.subscribe(listOf(SettlementKafkaTestResource.TOPIC))
            val id = given().contentType("application/json").body(
                mapOf(
                    "idempotencyKey" to UUID.randomUUID().toString(),
                    "payerAccountId" to UUID.randomUUID(),
                    "payeeAccountId" to UUID.randomUUID(),
                    "amount" to "321.45",
                    "currency" to "CZK",
                ),
            ).post("/api/v1/settlements").then().statusCode(201).extract().path<String>("id")
            val first = awaitEvent(consumer, id)
            val body = objectMapper.readTree(first.value())
            assertThat(body.isObject).isTrue()
            assertThat(body.path("status").asText()).isEqualTo("PENDING")
            assertThat(body.path("sourceService").asText()).isEqualTo("settlement-service")
            assertThat(body.path("schemaVersion").asInt()).isEqualTo(1)
            assertThat(first.key()).isEqualTo(id)
            val eventId = UUID.fromString(body.path("eventId").asText())
            awaitSent(eventId)
            // Simulate a crash after broker acceptance but before marking SENT. The actual
            // scheduler must reclaim this stale lease, without inventing a new event identity.
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE settlement_outbox SET status = 'DISPATCHING', claimed_at = now() - interval '1 hour' " +
                        "WHERE event_id = ?",
                ).use { query ->
                    query.setObject(1, eventId)
                    assertThat(query.executeUpdate()).isEqualTo(1)
                }
            }
            val retry = awaitEvent(consumer, id)
            assertThat(retry.value()).isEqualTo(first.value())
            assertThat(retry.key()).isEqualTo(first.key())
            awaitSent(eventId)
        }
    }

    private fun consumer() = KafkaConsumer<String, String>(
        mapOf(
            "bootstrap.servers" to ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "group.id" to "settlement-audit-test-${UUID.randomUUID()}",
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to false,
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
        ),
    )

    private fun awaitEvent(consumer: KafkaConsumer<String, String>, id: String): ConsumerRecord<String, String> {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).firstOrNull { it.key() == id }?.let { return it }
        }
        error("No settlement audit event received")
    }

    private fun awaitSent(eventId: UUID) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val sent = isSent(eventId)
            if (sent) return
            Thread.sleep(50)
        }
        error("Settlement audit event was not marked SENT")
    }
    private fun isSent(eventId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT status FROM settlement_outbox WHERE event_id = ?").use { query ->
            query.setObject(1, eventId)
            query.executeQuery().use { rows -> rows.next() && rows.getString(1) == "SENT" }
        }
    }
}
