// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.infrastructure.kafka.SanctionsListChangeWire
import com.openbank.kyc.it.CountingCoordinatorStub
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
 * Issue #15961: `openbank.kyc.screening-triggers.enabled` is the ONE switch for every path that
 * opens a case, and the queue records whether or not it is on. Real broker, real Postgres, a
 * live (counting) coordinator and the REAL retry cron ticking every second — so a retry job that
 * ignored the switch, or a consumer that dropped the change, would both be seen here.
 *
 * With the switch OFF:
 *  - two list changes are RECORDED as one deferred row per party (coalesced), not dropped;
 *  - rows already queued by an earlier on-period (an expired PENDING lease and a due OPEN_FAILED
 *    retry — "switched off mid-drain") are left alone;
 *  - the coordinator receives nothing across many retry ticks.
 * The drain half is [ScreeningTriggerMasterSwitchOnIT].
 */
@QuarkusTest
@TestProfile(ScreeningTriggerMasterSwitchOffIT.Profile::class)
class ScreeningTriggerMasterSwitchOffIT {
    class Profile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(SwitchOff::class.java),
            QuarkusTestProfile.TestResourceEntry(CountingCoordinatorStub::class.java),
        )
    }

    class SwitchOff : KycKafkaTestResource() {
        override fun overrides(): Map<String, String> = mapOf(
            "openbank.kyc.screening-triggers.enabled" to "false",
            // KycKafkaTestResource turns the scheduler off; this test IS about the real cron.
            "quarkus.scheduler.enabled" to "true",
            "openbank.kyc.screening-triggers.retry-interval" to "1s",
            "mp.messaging.incoming.sanctions-list-changes-in.group.id" to "kyc-master-switch-off-it",
            "mp.messaging.incoming.sanctions-list-changes-in.auto.offset.reset" to "earliest",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var meterRegistry: MeterRegistry

    @Test
    fun `switch off - list changes are recorded once per party and nothing reaches the coordinator`() {
        val partyId = UUID.randomUUID()
        val leftoverLease = UUID.randomUUID()
        val leftoverRetry = UUID.randomUUID()
        // Rows an earlier on-period left behind, both due NOW: the retry job would claim them
        // on its next tick if it ignored the master switch.
        insertLeftover(leftoverLease, state = "PENDING", attempts = 1)
        insertLeftover(leftoverRetry, state = "OPEN_FAILED", attempts = 2)

        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        producer().use { producer ->
            producer.send(ProducerRecord(KycKafkaTestResource.TOPIC, partyId.toString(), partyCreated(partyId)))
                .get(10, TimeUnit.SECONDS)
            await("an active KYC case for the party") {
                count("SELECT count(*) FROM kyc_cases WHERE party_id = ?", partyId) == 1L
            }
            listOf(first, second).forEach { publicationId ->
                val listId = UUID.randomUUID()
                val body = SanctionsListChangeWire.json(SanctionsListChangeWire.changedBody(publicationId, listId))
                producer.send(sanctionsRecord(body, listId)).get(10, TimeUnit.SECONDS)
            }
        }

        await("both list changes recorded while the loop is off") { counted("recorded_while_disabled") >= 2.0 }
        // Coalesced: one deferred row for the party, from the first publication, no attempt made.
        assertThat(count("SELECT count(*) FROM kyc_screening_triggers WHERE party_id = ?", partyId)).isEqualTo(1L)
        assertThat(
            count(
                "SELECT count(*) FROM kyc_screening_triggers WHERE party_id = ? AND state = 'PENDING' " +
                    "AND attempts = 0 AND publication_id = '$first'",
                partyId,
            ),
        ).isEqualTo(1L)

        // Many retry ticks (interval 1s): still no claim, no coordinator call, for any of them.
        Thread.sleep(SETTLE.toMillis())
        assertThat(attemptsOf(partyId)).isEqualTo(0L)
        assertThat(attemptsOf(leftoverLease)).isEqualTo(1L)
        assertThat(attemptsOf(leftoverRetry)).isEqualTo(2L)
        assertThat(CountingCoordinatorStub.opensFor(partyId)).isZero()
        assertThat(CountingCoordinatorStub.opensFor(leftoverLease)).isZero()
        assertThat(CountingCoordinatorStub.opensFor(leftoverRetry)).isZero()
        assertThat(counted("handled")).isZero()
    }

    private fun insertLeftover(partyId: UUID, state: String, attempts: Int) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO kyc_screening_triggers (trigger_id, party_id, trigger_type, publication_id, diff, " +
                    "state, attempts, next_attempt_at, created_at) VALUES (?, ?, 'SANCTIONS_LIST_CHANGED', ?, " +
                    "'{\"changedExternalIds\":[],\"deactivatedExternalIds\":[],\"changeCount\":0}', ?, ?, " +
                    "now() - interval '1 minute', now() - interval '20 minutes')",
            ).use { insert ->
                insert.setObject(1, UUID.randomUUID())
                insert.setObject(2, partyId)
                insert.setObject(3, UUID.randomUUID())
                insert.setString(4, state)
                insert.setInt(5, attempts)
                insert.executeUpdate()
            }
        }
    }

    private fun attemptsOf(partyId: UUID): Long =
        count("SELECT COALESCE(MAX(attempts), -1) FROM kyc_screening_triggers WHERE party_id = ?", partyId)

    private fun partyCreated(partyId: UUID): String = objectMapper.writeValueAsString(
        mapOf(
            "eventType" to "PARTY_CREATED",
            "partyId" to partyId.toString(),
            "partyType" to "COMPANY",
            "legalName" to "Synthetic KYB test company",
        ),
    )

    private fun sanctionsRecord(body: String, listId: UUID): ProducerRecord<String, String> {
        val entry = SanctionsListChangeWire.entry(SanctionsListChangeWire.EVENT_CHANGED, body, listId)
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

    private companion object {
        val SETTLE: Duration = Duration.ofSeconds(5)
    }
}
