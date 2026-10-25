// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.eclipse.microprofile.config.ConfigProvider
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * Base for the settlement E2E suite: origination over the real HTTP surface, durable facts over
 * plain JDBC, and outbox wire facts read back from the real Redpanda. Concrete suites carry
 * `@QuarkusTest` + `@TestProfile(SettlementE2EProfile::class)` — the annotations do not inherit,
 * and Quarkus needs them on the concrete class to register the test class as a CDI bean.
 */
abstract class SettlementE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** The settlement row as the assertions need it, or null when absent. */
    data class SettlementRow(val id: UUID, val status: String, val amount: String, val currency: String)

    /** One `SETTLEMENT_STATE_CHANGED` outbox fact as committed. */
    data class StateFact(val previousStatus: String?, val status: String, val protocol: String)

    protected fun instruction(
        key: String,
        payer: UUID = UUID.randomUUID(),
        payee: UUID = UUID.randomUUID(),
        amount: String = "250.00",
        currency: String = "CZK",
    ): Map<String, Any?> = mapOf(
        "idempotencyKey" to key,
        "payerAccountId" to payer.toString(),
        "payeeAccountId" to payee.toString(),
        "amount" to amount,
        "currency" to currency,
    )

    /** POST /api/v1/settlements expecting [expectedStatus], parsed as JSON. */
    protected fun originate(body: Map<String, Any?>, expectedStatus: Int): JsonNode {
        val raw = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/settlements")
        } Then {
            statusCode(expectedStatus)
        } Extract {
            asString()
        }
        return if (raw.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)
    }

    /** POST /api/v1/settlements expecting [expectedStatus] with no entity asserted. */
    protected fun originateExpectingOnlyStatus(body: Map<String, Any?>, expectedStatus: Int) {
        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/settlements")
        } Then {
            statusCode(expectedStatus)
        }
    }

    /** GET /api/v1/settlements/{id} expecting [expectedStatus], parsed as JSON. */
    protected fun getSettlement(id: String, expectedStatus: Int): JsonNode {
        val raw = Given { this } When {
            get("/api/v1/settlements/$id")
        } Then {
            statusCode(expectedStatus)
        } Extract {
            asString()
        }
        return if (raw.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)
    }

    /** The settlement row, or null when it does not exist. */
    protected fun settlementRow(id: UUID): SettlementRow? = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT id, status, amount::text, currency FROM settlements WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) mapSettlementRow(rs) else null
        }
    }

    private fun mapSettlementRow(rs: java.sql.ResultSet): SettlementRow = SettlementRow(
        id = rs.getObject("id", UUID::class.java),
        status = rs.getString("status"),
        amount = rs.getString("amount"),
        currency = rs.getString("currency"),
    )

    /** Every `SETTLEMENT_STATE_CHANGED` outbox fact for the aggregate, in commit order. */
    protected fun stateFacts(id: UUID): List<StateFact> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT payload::jsonb ->> 'previousStatus' AS prev, " +
                "payload::jsonb ->> 'status' AS status, " +
                "payload::jsonb ->> 'protocol' AS protocol " +
                "FROM settlement_outbox WHERE event_type = 'SETTLEMENT_STATE_CHANGED' " +
                "AND aggregate_id = ? ORDER BY id",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) {
                        StateFact(
                            previousStatus = rs.getString("prev"),
                            status = rs.getString("status"),
                            protocol = rs.getString("protocol"),
                        )
                    } else {
                        null
                    }
                }.toList()
            }
        }
    }

    /** Count of settlement rows created by this instruction's deterministic id. */
    protected fun settlementCount(id: UUID): Long = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT count(*) FROM settlements WHERE id = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }
    }

    /**
     * Drains `openbank.settlement.events` until a record for [aggregateId] appears (the outbox
     * dispatcher polls on a 1 s interval, so the wire fact lands shortly after the commit).
     */
    protected fun awaitWireEvent(aggregateId: UUID): JsonNode {
        val bootstrap = ConfigProvider.getConfig()
            .getValue("kafka.bootstrap.servers", String::class.java)
        KafkaConsumer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "group.id" to "settlement-e2e-events",
                "auto.offset.reset" to "earliest",
                "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                "enable.auto.commit" to "false",
            ),
        ).use { consumer ->
            consumer.subscribe(listOf(SETTLEMENT_KAFKA_TOPIC))
            return drainUntil(consumer, aggregateId)
        }
    }

    private fun drainUntil(consumer: KafkaConsumer<String, String>, aggregateId: UUID): JsonNode {
        val deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos()
        while (System.nanoTime() < deadline) {
            val records: ConsumerRecords<String, String> = consumer.poll(Duration.ofMillis(500))
            for (record in records) {
                val node = objectMapper.readTree(record.value())
                if (node.get("aggregateId")?.asText() == aggregateId.toString()) return node
            }
        }
        error("no settlement wire event for $aggregateId within 45s")
    }

    protected companion object {
        const val SETTLEMENT_KAFKA_TOPIC = "openbank.settlement.events"
    }
}
