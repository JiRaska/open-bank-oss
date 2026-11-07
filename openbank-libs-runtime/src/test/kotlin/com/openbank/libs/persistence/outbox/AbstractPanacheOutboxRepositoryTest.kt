// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.persistence.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Supplier
import kotlin.random.Random

/** Tests repository decisions and query bindings, not database execution or transaction isolation. */
class AbstractPanacheOutboxRepositoryTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val shape = OutboxTableShape("test_outbox")
    private val session = mockk<Mutiny.Session>()
    private val update = mockk<Mutiny.Query<Int>>()
    private val parameters = mutableMapOf<String, Any?>()
    private val repository = object : AbstractPanacheOutboxRepository<PanacheOutboxEntity>(
        shape,
        PanacheOutboxEntity::class.java,
        Clock.fixed(now, ZoneOffset.UTC),
        Random(7),
    ) {}

    @BeforeEach
    fun transactionBoundary() {
        mockkStatic(Panache::class)
        every { Panache.withTransaction(any<Supplier<Uni<Any>>>()) } answers {
            firstArg<Supplier<Uni<Any>>>().get()
        }
        every { Panache.getSession() } returns Uni.createFrom().item(session)
        every { update.setParameter(any<String>(), any()) } answers {
            parameters[firstArg()] = secondArg()
            update
        }
        every { update.executeUpdate() } returns Uni.createFrom().item(3)
    }

    @AfterEach
    fun restoreBoundary() = unmockkStatic(Panache::class)

    @Test
    @Suppress("DEPRECATION")
    fun `repository style entity projects all fields without inventing synthetic provenance`() {
        val source = row()
        val entity = object : AbstractOutboxEntity() {}.apply {
            eventId = source.eventId
            aggregateId = source.aggregateId
            eventType = source.eventType
            payload = source.payload
            status = source.status
            attemptCount = source.attemptCount
            createdAt = source.createdAt
            updatedAt = source.updatedAt
            sentAt = source.sentAt
            lastError = source.lastError
        }
        assertThat(entity.toEntry()).isEqualTo(source.toEntry().copy(synthetic = false))
        entity.sentAt = null
        entity.lastError = null
        assertThat(entity.toEntry()).isEqualTo(
            source.toEntry().copy(sentAt = null, lastError = null, synthetic = false),
        )
    }

    private fun row(): PanacheOutboxEntity = PanacheOutboxEntity().apply {
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000022")
        eventType = "test.changed"
        payload = "{\"amount\":17}"
        status = "FAILED"
        attemptCount = 4
        createdAt = now.minusSeconds(31)
        updatedAt = now.minusSeconds(7)
        sentAt = now.minusSeconds(3)
        lastError = "temporary failure"
        synthetic = true
    }

    private fun selection(sql: String): Mutiny.SelectionQuery<PanacheOutboxEntity> {
        val query = mockk<Mutiny.SelectionQuery<PanacheOutboxEntity>>()
        every { session.createNativeQuery(sql, PanacheOutboxEntity::class.java) } returns query
        every { query.setParameter(any<String>(), any()) } answers {
            parameters[firstArg()] = secondArg()
            query
        }
        every { query.resultList } returns Uni.createFrom().item(listOf(row()))
        return query
    }

    @Test
    fun `peek maps every persisted field and uses clock eligibility with safe limit`() {
        selection(OutboxSql.listProcessable(shape))
        val entries = runBlocking { repository.listProcessable(0) }
        assertThat(entries).containsExactly(
            OutboxEntry(
                eventId = UUID.fromString("00000000-0000-0000-0000-000000000011"),
                aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000022"),
                eventType = "test.changed",
                payload = "{\"amount\":17}",
                status = OutboxStatus.FAILED,
                attemptCount = 4,
                createdAt = now.minusSeconds(31),
                updatedAt = now.minusSeconds(7),
                sentAt = now.minusSeconds(3),
                lastError = "temporary failure",
                synthetic = true,
            ),
        )
        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "pending" to "PENDING",
                "failed" to "FAILED",
                "dispatching" to "DISPATCHING",
                "stale" to now.minus(Duration.ofMinutes(2)),
                "now" to now,
                "limit" to 1,
            ),
        )
    }

    @Test
    fun `claim uses caller stale interval and preserves positive limit`() {
        selection(OutboxSql.claim(shape))
        val result = runBlocking { repository.claimProcessable(17, Duration.ofSeconds(23)) }
        assertThat(result).hasSize(1)
        assertThat(result.single().eventType).isEqualTo("test.changed")
        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "pending" to "PENDING",
                "failed" to "FAILED",
                "dispatching" to "DISPATCHING",
                "stale" to now.minusSeconds(23),
                "now" to now,
                "limit" to 17,
            ),
        )
    }

    @Test
    fun `count returns actual database count for both retryable statuses`() {
        val query = mockk<Mutiny.SelectionQuery<java.lang.Long>>()
        every {
            session.createNativeQuery(OutboxSql.countProcessable(shape), java.lang.Long::class.java)
        } returns query
        every { query.setParameter(any<String>(), any()) } answers {
            parameters[firstArg()] = secondArg()
            query
        }
        every { query.singleResult } returns Uni.createFrom().item(java.lang.Long(17))
        assertThat(runBlocking { repository.countProcessable() }).isEqualTo(17L)
        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(
            mapOf("pending" to "PENDING", "failed" to "FAILED"),
        )
    }

    @Test
    fun `oldest age distinguishes absent row past row and future timestamp`() {
        val query = mockk<Mutiny.SelectionQuery<Instant>>()
        every { session.createNativeQuery(OutboxSql.oldestProcessable(shape), Instant::class.java) } returns query
        every { query.setParameter(any<String>(), any()) } answers {
            parameters[firstArg()] = secondArg()
            query
        }
        val cases = listOf(
            null to null,
            now.minusSeconds(19) to Duration.ofSeconds(19),
            now.plusSeconds(1) to Duration.ZERO,
        )
        for ((oldest, expected) in cases) {
            every { query.singleResultOrNull } returns Uni.createFrom().item(oldest)
            assertThat(runBlocking { repository.oldestProcessableAge(now) }).isEqualTo(expected)
        }
        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "pending" to "PENDING",
                "failed" to "FAILED",
                "dispatching" to "DISPATCHING",
                "stale" to now.minus(Duration.ofMinutes(2)),
                "now" to now,
            ),
        )
    }

    @Test
    fun `empty sent batch does not start a transaction`() {
        runBlocking { repository.markSentBatch(emptyList(), now) }
        verify(exactly = 0) { Panache.getSession() }
    }

    @Test
    fun `single sent event delegates with exact timestamp and identity`() {
        val id = UUID.randomUUID()
        every { session.createNativeQuery<Int>(OutboxSql.markSentBatch(shape)) } returns update
        runBlocking { repository.markSent(id, now) }
        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(
            mapOf("sent" to "SENT", "now" to now, "ids" to listOf(id)),
        )
        verify(exactly = 1) { update.executeUpdate() }
    }

    @Test
    fun `retention binds distinct terminal status cutoff and safe batch`() {
        every { session.createNativeQuery<Int>(OutboxSql.purgeSent(shape)) } returns update
        every { session.createNativeQuery<Int>(OutboxSql.purgeDead(shape)) } returns update
        val age = Duration.ofDays(3)
        assertThat(runBlocking { repository.purgeSent(age, 0, now) }).isEqualTo(3)
        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(
            mapOf("sent" to "SENT", "cut" to now.minus(age), "limit" to 1),
        )
        parameters.clear()
        assertThat(runBlocking { repository.purgeDead(age, 19, now) }).isEqualTo(3)
        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(
            mapOf("dead" to "DEAD", "cut" to now.minus(age), "limit" to 19),
        )
    }

    private fun lock(attempts: Int?) {
        val query = mockk<Mutiny.SelectionQuery<java.lang.Integer>>()
        every {
            session.createNativeQuery(OutboxSql.lockForFailure(shape), java.lang.Integer::class.java)
        } returns query
        every { query.setParameter("eventId", any()) } returns query
        every { query.singleResultOrNull } returns Uni.createFrom().item(attempts?.let { java.lang.Integer(it) })
        every { session.createNativeQuery<Int>(OutboxSql.markFailed(shape)) } returns update
    }

    @Test
    fun `missing failure row returns failed without an update`() {
        lock(null)
        val result = runBlocking { repository.markFailed(UUID.randomUUID(), "error", now) }
        assertThat(result).isEqualTo(OutboxStatus.FAILED)
        verify(exactly = 0) { update.executeUpdate() }
    }

    @Test
    fun `retry increments attempts truncates persisted error and schedules backoff`() {
        lock(1)
        val id = UUID.randomUUID()
        val error = "x".repeat(4001)
        assertThat(runBlocking { repository.markFailed(id, error, now) }).isEqualTo(OutboxStatus.FAILED)
        assertThat(parameters).containsEntry("status", "FAILED").containsEntry("attempts", 2)
            .containsEntry("error", "x".repeat(4000)).containsEntry("now", now).containsEntry("eventId", id)
        val expected = OutboxBackoff.nextAttemptAt(2, now, Random(7))
        assertThat(parameters["nextAttemptAt"]).isEqualTo(expected)
        verify(exactly = 1) { update.executeUpdate() }
    }

    @Test
    fun `last permitted failure is dead with no further schedule`() {
        lock(9)
        val id = UUID.randomUUID()
        assertThat(runBlocking { repository.markFailed(id, "poison", now) }).isEqualTo(OutboxStatus.DEAD)
        assertThat(parameters).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "status" to "DEAD",
                "attempts" to 10,
                "error" to "poison",
                "now" to now,
                "nextAttemptAt" to null,
                "eventId" to id,
            ),
        )
    }
}
