// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.integration

import com.openbank.anacredit.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ListGroupsOptions
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Guards the `mp.messaging.incoming.lending-events-in` dotted-key YAML flattening bug (issue #686):
 * SmallRye Config's YAML source unconditionally quotes any leaf map key containing a literal dot, so
 * `group.id: anacredit-service-lending` written as a YAML key never actually resolves — it registers
 * only as the quoted property name `"group.id"`, which `KafkaConnectorIncomingConfiguration`'s plain
 * lookup never finds. `group.id` then silently falls back to Quarkus's default
 * (`quarkus.application.name` = "openbank-anacredit-service"), which the anacredit-service KafkaUser
 * ACL (`kafka-anacredit-mtls.yaml`) does NOT grant Read on — every consumer poll would hit
 * `org.apache.kafka.common.errors.GroupAuthorizationException`.
 *
 * `application.yaml` no longer sets `group.id`/`auto.offset.reset` as YAML keys at all (fixed here);
 * production sets them via `MP_MESSAGING_INCOMING_LENDING_EVENTS_IN_*` env vars
 * (`openbank-infra/gitops/components/anacredit/anacredit-service.yaml`). This test can't exercise env
 * vars directly, so [PostgresRedpandaTestResource] sets the equivalent property via its returned Map
 * (a real runtime property source, not YAML) — the point of this test is to assert the consumer
 * actually joins the intended group, not the buggy fallback, proving `LoanStageEventConsumer`'s
 * `@Incoming("lending-events-in")` wiring reads whatever `group.id` is configured correctly.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class LendingEventsConsumerGroupIdBootIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `consumer joins the configured lending group id, not the buggy default fallback`() {
        val bootstrap = PostgresRedpandaTestResource.lastBootstrapServers
            ?: error("PostgresRedpandaTestResource did not record its bootstrap servers")

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
            // The regression: without the fix this set contains "openbank-anacredit-service" (the
            // Quarkus default-name fallback) instead of the intended, ACL'd
            // "anacredit-service-lending" — reproducing exactly what a real Kafka broker ACL rejects.
            assertThat(groupIds).contains(EXPECTED_GROUP_ID)
            assertThat(groupIds).doesNotContain(BUGGY_FALLBACK_GROUP_ID)
        }
    }

    @Test
    fun `lending lifecycle deliveries keep immutable dated facts outside the exposure return`() {
        val bootstrap = checkNotNull(PostgresRedpandaTestResource.lastBootstrapServers)
        val loanId = UUID.randomUUID()
        val applicationId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val installmentId = UUID.randomUUID()
        val earlier = Instant.parse("2026-01-10T09:00:00Z")
        val later = Instant.parse("2026-02-10T09:00:00Z")
        val disbursementId = UUID.randomUUID()
        val repaymentId = UUID.randomUUID()
        fun payload(eventId: UUID, kind: String, at: Instant, reference: String, fields: String): String = """
            {"eventType":"loan.exposure_fact","eventVersion":1,"eventId":"$eventId",
             "aggregateType":"LOAN","aggregateId":"$loanId","sourceService":"lending",
             "loanId":"$loanId","applicationId":"$applicationId","partyId":"$partyId",
             "effectiveAt":"$at","occurredAt":"$at","loanStatus":"ACTIVE","kind":"$kind",
             "sourceReference":"loan:$loanId:$reference","currency":"CZK",
             "originalPrincipal":9007199254740993.01,$fields}
        """.trimIndent()
        val repayment = payload(
            repaymentId,
            "REPAID",
            later,
            "installment:$installmentId",
            """"installmentId":"$installmentId","principalRepaid":100,"interestRepaid":5,""" +
                """"unpaidScheduledPrincipal":9007199254740893.01""",
        )
        val disbursement = payload(
            disbursementId,
            "DISBURSED",
            earlier,
            "disbursement",
            """"cashConfirmedAt":"$earlier"""",
        )
        val offset = KafkaProducer(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to false,
            ),
            StringSerializer(),
            StringSerializer(),
        ).use { producer ->
            listOf(repayment, disbursement, disbursement).map {
                producer.send(ProducerRecord(LENDING_TOPIC, 0, loanId.toString(), it)).get(10, TimeUnit.SECONDS)
            }.last().offset()
        }
        awaitOffset(bootstrap, offset)
        assertFactHistory(loanId, disbursementId, earlier, repaymentId, later)
    }

    private fun awaitOffset(bootstrap: String, offset: Long) {
        AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
            val partition = TopicPartition(LENDING_TOPIC, 0)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
            while (System.nanoTime() < deadline) {
                val offsets = admin.listConsumerGroupOffsets(EXPECTED_GROUP_ID)
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                if ((offsets[partition]?.offset() ?: -1L) > offset) return
                Thread.sleep(POLL_INTERVAL_MS)
            }
            error("Lending exposure facts were not acknowledged")
        }
    }

    private fun assertFactHistory(
        loanId: UUID,
        disbursementId: UUID,
        earlier: Instant,
        repaymentId: UUID,
        later: Instant,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT event_id, effective_at, payload->>'originalPrincipal',
                       (SELECT count(*) FROM credit_exposures WHERE instrument_id = CAST(f.loan_id AS text))
                FROM loan_exposure_facts f WHERE f.loan_id = ? ORDER BY effective_at
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, loanId)
                statement.executeQuery().use { rows ->
                    assertFactRow(rows, disbursementId, earlier)
                    assertFactRow(rows, repaymentId, later)
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    private fun assertFactRow(rows: ResultSet, id: UUID, at: Instant) {
        assertThat(rows.next()).isTrue()
        assertThat(rows.getObject(1, UUID::class.java)).isEqualTo(id)
        assertThat(rows.getObject(2, OffsetDateTime::class.java).toInstant()).isEqualTo(at)
        assertThat(rows.getString(3)).isEqualTo("9007199254740993.01")
        assertThat(rows.getInt(4)).isZero()
    }

    private companion object {
        const val DEADLINE_MS = 20_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val EXPECTED_GROUP_ID = "anacredit-service-lending"
        const val BUGGY_FALLBACK_GROUP_ID = "openbank-anacredit-service"
        const val LENDING_TOPIC = "openbank.lending.events"
    }
}
