// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.integration

import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.campaign.infrastructure.persistence.PanacheSendLogRepository
import com.openbank.campaign.it.CampaignPostgresRedisTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real PostgreSQL coverage for the outcome/finalization ordering at the notification boundary. */
@QuarkusTest
@QuarkusTestResource(CampaignRestContractIT.NoBrokerNoWorkerResource::class)
@QuarkusTestResource(CampaignPostgresRedisTestResource::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CampaignSendHandoffIntentIT {

    @Inject
    lateinit var dataSource: AgroalDataSource

    @Inject
    lateinit var sendLog: PanacheSendLogRepository

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    @Test
    fun `early outcome followed by ambiguous publish exception is recorded as handed off`() {
        val campaignId = insertCampaign()
        val partyId = UUID.randomUUID()
        val sendId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-10-14T09:15:00Z")
        val channel = Channel.EMAIL

        onVertxContext { sendLog.reserveHandoff(sendId, campaignId, partyId, 0, channel) }
        assertThat(
            onVertxContext {
                sendLog.applyDeliveryOutcome(sendId, "SUPPRESSED", "private provider detail", occurredAt)
            },
        ).isTrue()

        // requestSend can throw after notification-service accepted it. The captured outcome is
        // stronger evidence than the local transport exception and must govern the send record.
        onVertxContext {
            sendLog.record(
                SendRecord(
                    id = sendId,
                    campaignId = campaignId,
                    partyId = partyId,
                    stepOrder = 0,
                    outcome = SendOutcome.FAILED,
                    occurredAt = occurredAt,
                    channel = channel,
                ),
            )
        }

        val row = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT outcome, delivery_status, delivery_reason FROM send_log WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, sendId)
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    Triple(result.getString(1), result.getString(2), result.getString(3))
                }
            }
        }
        assertThat(row.first).isEqualTo(SendOutcome.SENT.name)
        assertThat(row.second).isEqualTo("SUPPRESSED")
        assertThat(row.third).isNull()
        assertThat(onVertxContext { sendLog.wasHandedOff(sendId) }).isTrue()
        assertThat(intentCount(sendId)).isZero()
    }

    @Test
    fun `early outcome materializes handed off row if activity never finalizes`() {
        val campaignId = insertCampaign()
        val partyId = UUID.randomUUID()
        val sendId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-10-14T09:15:00Z")

        onVertxContext { sendLog.reserveHandoff(sendId, campaignId, partyId, 0, Channel.EMAIL) }
        assertThat(
            onVertxContext { sendLog.applyDeliveryOutcome(sendId, "SENT", null, occurredAt) },
        ).isTrue()

        assertThat(rowOutcome(sendId)).isEqualTo(SendOutcome.SENT.name)
        assertThat(rowDeliveryStatus(sendId)).isEqualTo("CONFIRMED")
        assertThat(rowOccurredAt(sendId)).isEqualTo(occurredAt)
        assertThat(intentCount(sendId)).isZero()
        assertThat(onVertxContext { sendLog.wasHandedOff(sendId) }).isTrue()
    }

    @Test
    fun `ambiguous failure remains represented until a later outcome arrives`() {
        val campaignId = insertCampaign()
        val partyId = UUID.randomUUID()
        val sendId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-10-14T09:15:00Z")

        onVertxContext { sendLog.reserveHandoff(sendId, campaignId, partyId, 0, Channel.PUSH) }
        onVertxContext {
            sendLog.record(
                SendRecord(
                    id = sendId,
                    campaignId = campaignId,
                    partyId = partyId,
                    stepOrder = 0,
                    outcome = SendOutcome.FAILED,
                    occurredAt = occurredAt,
                    channel = Channel.PUSH,
                ),
            )
        }

        assertThat(rowOutcome(sendId)).isEqualTo(SendOutcome.FAILED.name)
        assertThat(intentCount(sendId)).isEqualTo(1)
        val providerProofAt = occurredAt.plusSeconds(1)
        assertThat(
            onVertxContext { sendLog.applyDeliveryOutcome(sendId, "SENT", null, occurredAt.plusSeconds(1)) },
        ).isTrue()
        assertThat(rowOutcome(sendId)).isEqualTo(SendOutcome.SENT.name)
        assertThat(rowOccurredAt(sendId)).isEqualTo(providerProofAt)
        assertThat(intentCount(sendId)).isZero()
    }

    @Test
    fun `unowned shared-topic outcomes remain ignored`() {
        val unknownId = UUID.randomUUID()
        assertThat(
            onVertxContext {
                sendLog.applyDeliveryOutcome(unknownId, "SUPPRESSED", null, Instant.now())
            },
        ).isFalse()
        assertThat(intentCount(unknownId)).isZero()
    }

    @Test
    fun `retry cannot reuse a logical send id for a different channel`() {
        val campaignId = insertCampaign()
        val partyId = UUID.randomUUID()
        val sendId = UUID.randomUUID()
        onVertxContext { sendLog.reserveHandoff(sendId, campaignId, partyId, 0, Channel.EMAIL) }

        org.assertj.core.api.Assertions.assertThatThrownBy {
            onVertxContext { sendLog.reserveHandoff(sendId, campaignId, partyId, 0, Channel.PUSH) }
        }.hasMessageContaining("identity collision")

        assertThat(intentCount(sendId)).isEqualTo(1)
    }

    @Test
    fun `concurrent send finalization and bounced outcome preserve terminal projection`() {
        val campaignId = insertCampaign()
        val partyId = UUID.randomUUID()
        val sendId = UUID.randomUUID()
        val sentAt = Instant.parse("2026-10-14T09:15:00Z")
        val bouncedAt = sentAt.plusSeconds(30)
        onVertxContext { sendLog.reserveHandoff(sendId, campaignId, partyId, 0, Channel.EMAIL) }

        val lockHolder = holdIntentRowLock(sendId)

        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val record = pool.submit<Boolean> {
            ready.countDown()
            check(start.await(5, TimeUnit.SECONDS))
            onVertxContext {
                sendLog.record(
                    SendRecord(
                        id = sendId,
                        campaignId = campaignId,
                        partyId = partyId,
                        stepOrder = 0,
                        outcome = SendOutcome.SENT,
                        occurredAt = sentAt,
                        channel = Channel.EMAIL,
                    ),
                )
                true
            }
        }
        val outcome = pool.submit<Boolean> {
            ready.countDown()
            check(start.await(5, TimeUnit.SECONDS))
            onVertxContext { sendLog.applyDeliveryOutcome(sendId, "BOUNCED", null, bouncedAt) }
        }

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            assertThat(awaitLockWaiters(2, 5, TimeUnit.SECONDS)).isTrue()
            lockHolder.commit()
            lockHolder.close()
            assertThat(record.get(10, TimeUnit.SECONDS)).isTrue()
            assertThat(outcome.get(10, TimeUnit.SECONDS)).isTrue()
        } finally {
            start.countDown()
            runCatching { lockHolder.commit() }
            runCatching { lockHolder.close() }
            pool.shutdownNow()
        }

        assertThat(rowOutcome(sendId)).isEqualTo(SendOutcome.SENT.name)
        assertThat(rowDeliveryStatus(sendId)).isEqualTo("FAILED")
        assertThat(rowDeliveryReason(sendId)).isNull()
        assertThat(rowOccurredAt(sendId)).isIn(sentAt, bouncedAt)
        assertThat(intentCount(sendId)).isZero()
        assertThat(onVertxContext { sendLog.wasHandedOff(sendId) }).isTrue()
    }

    private fun awaitLockWaiters(count: Int, timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            val waiters = query(
                """SELECT count(*) FROM pg_stat_activity
                   WHERE datname = current_database() AND pid <> pg_backend_pid()
                     AND wait_event_type = 'Lock'
                     AND query LIKE '%campaign_send_handoff_intents%'
                """.trimIndent(),
            ) { getInt(1) } ?: 0
            if (waiters >= count) return true
            Thread.yield()
        }
        return false
    }

    private fun holdIntentRowLock(sendId: UUID): Connection {
        val connection = dataSource.connection
        connection.autoCommit = false
        connection.prepareStatement(
            "SELECT send_id FROM campaign_send_handoff_intents WHERE send_id = ? FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, sendId)
            statement.executeQuery().use { result -> assertThat(result.next()).isTrue() }
        }
        return connection
    }

    private fun insertCampaign(): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO campaigns (
                    id, name, goal, product_kind, segment_name, segment_version, steps_json,
                    holdout_percent, state, created_by, created_at, updated_at
                ) VALUES (?, 'intent integration fixture', 'goal', 'NONE', 'actives', 1, ?, 0, 'ACTIVE', ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(
                    2,
                    """[{"order":0,"primary":{"channel":"EMAIL","template":"MARKETING_PRODUCT_OFFER"}}]""",
                )
                statement.setString(3, "fixture")
                statement.setObject(4, now)
                statement.setObject(5, now)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun rowOutcome(sendId: UUID): String? =
        query("SELECT outcome FROM send_log WHERE id = ?", sendId) { getString(1) }

    private fun rowDeliveryStatus(sendId: UUID): String? =
        query("SELECT delivery_status FROM send_log WHERE id = ?", sendId) { getString(1) }

    private fun rowDeliveryReason(sendId: UUID): String? =
        query("SELECT delivery_reason FROM send_log WHERE id = ?", sendId) { getString(1) }

    private fun rowOccurredAt(sendId: UUID): Instant? =
        query("SELECT occurred_at FROM send_log WHERE id = ?", sendId) { getTimestamp(1).toInstant() }

    private fun intentCount(sendId: UUID): Int =
        query("SELECT count(*) FROM campaign_send_handoff_intents WHERE send_id = ?", sendId) { getInt(1) }
            ?: error("intent count query returned no row")

    private fun <T> query(sql: String, sendId: UUID? = null, read: ResultSet.() -> T): T? =
        dataSource.connection.use { connection ->
            readQuery(connection, sql, sendId, read)
        }

    private fun <T> readQuery(connection: Connection, sql: String, sendId: UUID?, read: ResultSet.() -> T): T? {
        val statement = connection.prepareStatement(sql)
        if (sendId != null) statement.setObject(1, sendId)
        return statement.use { queryResult(it, read) }
    }

    private fun <T> queryResult(statement: PreparedStatement, read: ResultSet.() -> T): T? =
        statement.executeQuery().use { result ->
            if (result.next()) result.read() else null
        }
}
