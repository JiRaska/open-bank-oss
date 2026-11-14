// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.infrastructure.kafka.SanctionsListChangeWire
import com.openbank.kyc.it.KycKafkaTestResource
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * ADR-0256 list-change trigger loop over a real broker, fed the PRODUCER's wire shape: the event
 * type only on the `ce-type` header and a body with no `eventType` ([SanctionsListChangeWire]).
 *
 * The observable is the durable `kyc_screening_triggers` row, not a mock: a consumer that reads
 * the type from the body matches no branch, acks every record and writes nothing, while its
 * hand-written unit test stays green. Only a record that arrives through the real channel with
 * real headers can tell those apart.
 *
 * No coordinator is configured, so the open is parked (NOT_CONFIGURED) — the row still proves the
 * record was recognised and handled, which is what this class is about.
 */
@QuarkusTest
@TestProfile(SanctionsListChangeWireShapeIT.Profile::class)
class SanctionsListChangeWireShapeIT {
    class Profile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(TriggerLoopEnabled::class.java))
    }

    class TriggerLoopEnabled : KycKafkaTestResource() {
        override fun overrides(): Map<String, String> = mapOf(
            "openbank.kyc.screening-triggers.enabled" to "true",
            "openbank.kyc.case-coordinator.url" to "",
            "mp.messaging.incoming.sanctions-list-changes-in.group.id" to "kyc-sanctions-list-wire-it",
            "mp.messaging.incoming.sanctions-list-changes-in.auto.offset.reset" to "earliest",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var meterRegistry: MeterRegistry

    @Test
    fun `a list change typed only by the ce-type header opens a durable trigger and a storm opens none`() {
        val partyId = UUID.randomUUID()
        val publicationId = UUID.randomUUID()
        val listId = UUID.randomUUID()
        val storm = SanctionsListChangeWire.json(SanctionsListChangeWire.stormBody(listId))
        val changed = SanctionsListChangeWire.json(SanctionsListChangeWire.changedBody(publicationId, listId))

        producer().use { producer ->
            // An active (non-terminal) KYC case makes the party a candidate for the fan-out.
            val party = mapOf(
                "eventType" to "PARTY_CREATED",
                "partyId" to partyId.toString(),
                "partyType" to "COMPANY",
                "legalName" to "Synthetic KYB test company",
            )
            producer.send(
                ProducerRecord(KycKafkaTestResource.TOPIC, partyId.toString(), objectMapper.writeValueAsString(party)),
            ).get(10, TimeUnit.SECONDS)
            await("an active KYC case for the party") {
                count("SELECT count(*) FROM kyc_cases WHERE party_id = ?", partyId) == 1L
            }

            // Same key, so the storm is consumed before the diff: by the time the diff's row
            // exists, the storm has been through the consumer too.
            producer.send(sanctionsRecord(SanctionsListChangeWire.EVENT_STORM, storm, listId)).get(10, TimeUnit.SECONDS)
            producer.send(sanctionsRecord(SanctionsListChangeWire.EVENT_CHANGED, changed, listId))
                .get(10, TimeUnit.SECONDS)
        }

        await("a screening trigger for the publication — the consumer recognised the record") {
            count(
                "SELECT count(*) FROM kyc_screening_triggers WHERE publication_id = ? AND party_id = '$partyId'",
                publicationId,
            ) == 1L
        }
        // The storm carries no publication and must have produced nothing of its own.
        assertThat(count("SELECT count(*) FROM kyc_screening_triggers WHERE publication_id <> ?", publicationId))
            .isZero()
        assertThat(counted("storm")).isEqualTo(1.0)
        assertThat(counted("unrecognised")).isZero()
        await("the handled outcome is counted once the handler returns") { counted("handled") == 1.0 }
    }

    private fun sanctionsRecord(eventType: String, body: String, listId: UUID): ProducerRecord<String, String> {
        val entry = SanctionsListChangeWire.entry(eventType, body, listId)
        // Exactly what the producer's publisher does: verbatim payload, aggregate-id key, outbox headers.
        return ProducerRecord(
            KycKafkaTestResource.SANCTIONS_TOPIC,
            OutboxKafkaHeaders.partitionKey(entry),
            entry.payload,
        ).also { record ->
            SanctionsListChangeWire.headers(entry).forEach { (name, value) ->
                record.headers().add(name, value.toByteArray())
            }
        }
    }

    private fun producer(): KafkaProducer<String, String> = KafkaProducer(
        mapOf(
            "bootstrap.servers" to ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "acks" to "all",
        ),
    )

    private fun counted(outcome: String): Double =
        meterRegistry.find("openbank.kyc.sanctions_list_change_events").tag("outcome", outcome).counter()?.count()
            ?: 0.0

    private fun count(sql: String, id: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        error("Timed out waiting for $what")
    }
}
