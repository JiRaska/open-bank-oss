// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.application.port.out.AccountPartyLookupPort
import com.openbank.billing.application.usecase.AnnualFeeSummaryService
import com.openbank.billing.domain.AnnualFeeSummary
import com.openbank.billing.infrastructure.outbox.BillingOutboxDispatcher
import com.openbank.billing.infrastructure.persistence.repository.BillingAssessmentRepositoryImpl
import com.openbank.billing.it.PostgresRedisTestResource
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * #12187: proves the annual-summary idempotency key is durable state, not an accidental property
 * of the outbox row's current retention window. Uses an isolated Postgres database and the real
 * billing dispatcher with Kafka's in-memory connector: PENDING → publish → SENT → purge → rerun.
 */
@QuarkusTest
@TestProfile(BillingAnnualFeeSummaryIdempotencyIT.Profile::class)
@QuarkusTestResource(BillingAnnualFeeSummaryIdempotencyIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_billing_annual_summary_it")],
)
class BillingAnnualFeeSummaryIdempotencyIT {

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "openbank.billing.annual-fee-summary.scheduler.enabled" to "false",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(ResolvedPartyLookup::class.java)
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("billing-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Alternative
    @ApplicationScoped
    class ResolvedPartyLookup : AccountPartyLookupPort {
        override suspend fun partyIdFor(accountId: String): String = "party-$accountId"
    }

    @Inject
    lateinit var assessments: BillingAssessmentRepositoryImpl

    @Inject
    lateinit var annualSummaryService: AnnualFeeSummaryService

    @Inject
    lateinit var dispatcher: BillingOutboxDispatcher

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    @Test
    fun `published summary remains issued after SENT outbox row is purged`() {
        val accountId = "annual-${UUID.randomUUID()}"
        val year = 2025
        val first = onVertxContext { annualSummaryService.publishForAccount(accountId, year, "CZK") }
        assertThat(first).isNotNull

        val eventId = sourceEventIdFor(accountId, year)
        val writer = issuanceAndOutboxXmins(eventId)
        assertThat(writer.outboxXmin)
            .describedAs("issuance key and Kafka outbox intent commit in one Postgres transaction")
            .isEqualTo(writer.issuanceXmin)
        assertThat(outboxStatus(eventId)).isEqualTo(OutboxStatus.PENDING.name)

        onVertxContext { dispatcher.dispatchScheduledBatch() }

        val publishedBeforePurge = messagesFor(eventId)
        assertThat(publishedBeforePurge).hasSize(1)
        assertThat(outboxStatus(eventId)).isEqualTo(OutboxStatus.SENT.name)

        val deleted = dataSource.connection.use { connection -> purgeSent(connection, eventId) }
        assertThat(deleted).isEqualTo(1)
        assertThat(outboxStatus(eventId)).isNull()
        assertThat(issuanceCount(accountId, year)).isEqualTo(1)

        // The scheduler rerun rebuilds the same annual account/year key after retention has
        // removed its original payload. It must not append a replacement event.
        assertThat(onVertxContext { annualSummaryService.publishForAccount(accountId, year, "CZK") })
            .isNotNull
        assertThat(issuanceCount(accountId, year)).isEqualTo(1)
        assertThat(outboxCount(accountId, year)).isZero
        assertThat(messagesFor(eventId)).hasSize(1)
    }

    @Test
    fun `concurrent scheduler reruns reserve one account year and append one outbox event`() {
        val accountId = "annual-race-${UUID.randomUUID()}"
        val year = 2024
        val readyGate = CountDownLatch(2)
        val startingGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        fun publish(): AnnualFeeSummary? {
            readyGate.countDown()
            check(startingGate.await(5, TimeUnit.SECONDS)) { "both scheduler calls did not reach the start gate" }
            return onVertxContext { annualSummaryService.publishForAccount(accountId, year, "CZK") }
        }

        try {
            val first = pool.submit<AnnualFeeSummary?> { publish() }
            val second = pool.submit<AnnualFeeSummary?> { publish() }
            check(readyGate.await(5, TimeUnit.SECONDS)) { "both workers did not reach the start gate" }
            startingGate.countDown()
            assertThat(listOf(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                .allMatch { it != null }
        } finally {
            pool.shutdownNow()
        }

        assertThat(issuanceCount(accountId, year)).isEqualTo(1)
        assertThat(outboxCount(accountId, year)).isEqualTo(1)
        val eventId = sourceEventIdFor(accountId, year)
        val writer = issuanceAndOutboxXmins(eventId)
        assertThat(writer.outboxXmin).isEqualTo(writer.issuanceXmin)
    }

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun sourceEventIdFor(accountId: String, year: Int): UUID = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT source_event_id FROM billing_annual_fee_summary_issuance " +
                "WHERE account_id = ? AND calendar_year = ?",
        ).use { statement ->
            statement.setString(1, accountId)
            statement.setInt(2, year)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "durable annual-summary issuance key missing for $accountId/$year" }
                rows.getObject(1, UUID::class.java)
            }
        }
    }

    private data class WriterXmins(val issuanceXmin: String, val outboxXmin: String)

    private fun issuanceAndOutboxXmins(eventId: UUID): WriterXmins = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT i.xmin::text, o.xmin::text FROM billing_annual_fee_summary_issuance i " +
                "JOIN billing_outbox o ON o.event_id = i.source_event_id WHERE i.source_event_id = ?",
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "issuance/outbox transaction pair missing for $eventId" }
                WriterXmins(rows.getString(1), rows.getString(2))
            }
        }
    }

    private fun outboxStatus(eventId: UUID): String? = dataSource.connection.use { connection ->
        queryOutboxStatus(connection, eventId)
    }

    private fun queryOutboxStatus(connection: Connection, eventId: UUID): String? =
        connection.prepareStatement("SELECT status FROM billing_outbox WHERE event_id = ?").use { statement ->
            statement.setObject(1, eventId)
            readString(statement)
        }

    private fun readString(statement: java.sql.PreparedStatement): String? = statement.executeQuery().use { rows ->
        if (rows.next()) rows.getString(1) else null
    }

    private fun purgeSent(connection: Connection, eventId: UUID): Int = connection.prepareStatement(
        "DELETE FROM billing_outbox WHERE event_id = ? AND status = ?",
    ).use { statement ->
        statement.setObject(1, eventId)
        statement.setString(2, OutboxStatus.SENT.name)
        statement.executeUpdate()
    }

    private fun issuanceCount(accountId: String, year: Int): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM billing_annual_fee_summary_issuance WHERE account_id = ? AND calendar_year = ?",
        ).use { statement ->
            statement.setString(1, accountId)
            statement.setInt(2, year)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun outboxCount(accountId: String, year: Int): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM billing_outbox WHERE event_type = ? " +
                "AND payload::jsonb ->> 'accountId' = ? AND payload::jsonb ->> 'year' = ?",
        ).use { statement ->
            statement.setString(1, BillingAssessmentRepositoryImpl.ANNUAL_FEE_SUMMARY_EVENT_TYPE)
            statement.setString(2, accountId)
            statement.setString(3, year.toString())
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun messagesFor(eventId: UUID): List<Message<String>> {
        val sink = connector.sink<String>("billing-events-out")
        @Suppress("UNCHECKED_CAST")
        return sink.received().map { it as Message<String> }.filter { message ->
            val metadata = message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()
            metadata.headers.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_ID)?.value()?.let {
                String(it, Charsets.UTF_8) == eventId.toString()
            } == true
        }
    }
}
