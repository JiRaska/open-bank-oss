// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.openbank.audit.application.AuditConsumer
import com.openbank.audit.domain.model.AttributionSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.RecordBatch
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Supplier

/**
 * Issue #5338: audit-service was not subscribed to `openbank.sca.events` at all — absent from
 * both [com.openbank.audit.application.TopicAttribution] and `application.yaml`'s
 * `audit-events-in` topics list — so SCA device enrollment (`DEVICE_ENROLLED`) was never recorded
 * in the audit trail, a real PSD2/SCA compliance gap (sca-service is money-path).
 *
 * **Why this drives the real bean against a real database, not a mocked repository.** Every
 * existing attribution test in this module ([com.openbank.audit.application.AuditAttributionTest])
 * mocks [AuditRepository] — correct for pinning `resolveSourceService`'s logic in isolation, but
 * it cannot prove the topic is actually wired end-to-end: a mock happily "saves" a row for a topic
 * this service was never subscribed to. This test instead injects the REAL [AuditConsumer] bean
 * (CDI-managed, not `mockk`) against a real Testcontainers Postgres (the module's established
 * pattern — [AuditChainRoundTripIT]) and reads the persisted row back with a real JDBC query, so a
 * regression that removes `openbank.sca.events` from `TopicAttribution` or from the `topics:` list
 * — the exact defect this issue fixes — fails here, not silently.
 *
 * **What it does and does not prove.** There is no Kafka Testcontainers usage anywhere in this
 * repo (verified before writing this), so this does not drive a literal Kafka broker end to end.
 * What it DOES exercise is the real production code path: a [Message] carrying a genuine
 * [IncomingKafkaRecordMetadata] (topic `openbank.sca.events`, `ce-type` header `DEVICE_ENROLLED`,
 * matching what a real Kafka `ConsumerRecord` delivers) is handed to the actual
 * `@Incoming("audit-events-in") suspend fun consume(message: Message<String>)` entrypoint — the
 * same method SmallRye Kafka would invoke for a real delivery — with the EXACT payload shape
 * `ScaService.enroll` constructs (`ScaService.kt`, read directly rather than guessed), so
 * `TopicAttribution`'s new `openbank.sca.events -> "sca-service"` entry, `application.yaml`'s
 * topics list, and `AuditConsumer.resolveSourceService`/`inferAggregateType` all get exercised
 * together, against a real database write.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ScaEnrollmentAuditIT {

    @Inject
    lateinit var consumer: AuditConsumer

    @Inject
    lateinit var repository: AuditRepository

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    /**
     * The exact payload shape `ScaService.enroll` writes to the outbox (`ScaService.kt`), read
     * from that class directly rather than assumed. `sourceService` is PR #5337's field — this
     * test includes it so the strongest (EVENT) attribution path is exercised too; see the
     * companion test below for the TOPIC-derived path a pre-#5337 payload takes.
     */
    private fun enrollPayload(
        partyId: UUID,
        deviceId: UUID,
        occurredAt: OffsetDateTime,
        includeSourceService: Boolean,
    ): String {
        val sourceServiceField = if (includeSourceService) ""","sourceService":"sca-service"""" else ""
        return """
            {
              "eventType":"DEVICE_ENROLLED",
              "deviceId":"$deviceId",
              "partyId":"$partyId",
              "credentialId":"cred-$deviceId",
              "algorithm":"ES256",
              "occurredAt":"$occurredAt"$sourceServiceField
            }
        """.trimIndent()
    }

    private fun kafkaMessage(payload: String): Message<String> {
        val headers = RecordHeaders()
        headers.add(
            RecordHeader(
                OutboxKafkaHeaders.HEADER_EVENT_TYPE,
                "DEVICE_ENROLLED".toByteArray(StandardCharsets.UTF_8),
            ),
        )
        val record = ConsumerRecord(
            "openbank.sca.events",
            0,
            0L,
            RecordBatch.NO_TIMESTAMP,
            TimestampType.NO_TIMESTAMP_TYPE,
            ConsumerRecord.NULL_SIZE,
            ConsumerRecord.NULL_SIZE,
            null as String?,
            payload,
            headers,
            java.util.Optional.empty(),
        )
        val metadata = IncomingKafkaRecordMetadata(record, "audit-events-in")
        return Message.of(
            payload,
            Metadata.of(metadata),
            Supplier<CompletionStage<Void>> { CompletableFuture.completedFuture(null) },
        )
    }

    @Test
    fun `a real SCA enrollment event delivered on openbank sca events lands in audit_entries`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val occurredAt = OffsetDateTime.now()
        val message = kafkaMessage(enrollPayload(partyId, deviceId, occurredAt, includeSourceService = true))

        onEventLoop { consumer.consume(message) }

        val stored = onEventLoop { repository.findByAggregateId(partyId.toString()) }.single()
        assertThat(stored.eventType).isEqualTo("DEVICE_ENROLLED")
        assertThat(stored.aggregateType).isEqualTo("PARTY")
        assertThat(stored.aggregateId).isEqualTo(partyId.toString())
        // The producer's own claim (PR #5337) wins over the topic-derived attribution, and is
        // marked as the producer's own (AttributionSource.EVENT) — never silently folded into the
        // weaker TOPIC provenance.
        assertThat(stored.sourceService).isEqualTo("sca-service")
        assertThat(stored.sourceServiceSource).isEqualTo(AttributionSource.EVENT)
    }

    @Test
    fun `before PR 5337's sourceService field, the topic alone still attributes sca-service`(): Unit = runBlocking {
        // Guards the TopicAttribution table entry independently of #5337's payload change — the
        // two PRs are independent deployables, and this repo has been bitten before by a
        // dependency silently required in the wrong direction.
        val partyId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val occurredAt = OffsetDateTime.now()
        val message = kafkaMessage(
            enrollPayload(partyId, deviceId, occurredAt, includeSourceService = false),
        )

        onEventLoop { consumer.consume(message) }

        val stored = onEventLoop { repository.findByAggregateId(partyId.toString()) }.single()
        assertThat(stored.sourceService).isEqualTo("sca-service")
        assertThat(stored.sourceServiceSource).isEqualTo(AttributionSource.TOPIC)
    }
}
