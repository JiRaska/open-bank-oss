// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.persistence.repository

import com.openbank.sepainstant.application.port.out.SctInstOutboxMessage
import com.openbank.sepainstant.application.port.out.SctInstOutboxStatus
import com.openbank.sepainstant.infrastructure.persistence.entity.SctInstOutboxEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheQuery
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Supplier

/**
 * Unit tests for the outbox row lifecycle (PENDING → SENT / FAILED, ADR-0077 / ADR-0079). The static
 * [Panache] session/transaction wrappers are passed through with mockkStatic and the Panache query
 * plumbing is stubbed on a spy, so the tests exercise the real mapping and mutation logic: status
 * transitions, attempt counting, error truncation and the entity↔entry conversions.
 */
class SctInstOutboxRepositoryImplTest {

    private val fixedInstant = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val repo = spyk(SctInstOutboxRepositoryImpl(clock))

    private val eventId = UUID.fromString("77777777-7777-7777-7777-777777777777")
    private val aggregateId = UUID.fromString("88888888-8888-8888-8888-888888888888")

    @BeforeEach
    fun passPanacheWrappersThrough() {
        mockkStatic(Panache::class)
        every { Panache.withSession(any<Supplier<Uni<Any>>>()) } answers { firstArg<Supplier<Uni<Any>>>().get() }
        every { Panache.withTransaction(any<Supplier<Uni<Any>>>()) } answers { firstArg<Supplier<Uni<Any>>>().get() }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Panache::class)
    }

    private fun entity(status: SctInstOutboxStatus = SctInstOutboxStatus.PENDING, attemptCount: Int = 0) =
        SctInstOutboxEntity().also {
            it.eventId = eventId
            it.aggregateId = aggregateId
            it.eventType = "SctInstPaymentSettled"
            it.payload = """{"paymentId":"$aggregateId"}"""
            it.status = status.name
            it.attemptCount = attemptCount
            it.createdAt = fixedInstant.minusSeconds(60)
            it.updatedAt = fixedInstant.minusSeconds(60)
        }

    private fun stubFindByEventId(result: SctInstOutboxEntity?): PanacheQuery<SctInstOutboxEntity> {
        val query = mockk<PanacheQuery<SctInstOutboxEntity>>()
        every { repo.find("eventId", eventId) } returns query
        every { query.firstResult() } returns Uni.createFrom().item(result)
        return query
    }

    @Test
    fun `persistInTransaction writes a PENDING row with zero attempts`() {
        val entitySlot = slot<SctInstOutboxEntity>()
        every { repo.persist(capture(entitySlot)) } answers { Uni.createFrom().item(entitySlot.captured) }

        val message = SctInstOutboxMessage(
            aggregateId = aggregateId,
            eventType = "SctInstPaymentSubmitted",
            payload = """{"amount":"12.34"}""",
            eventId = eventId,
            createdAt = fixedInstant,
        )
        repo.persistInTransaction(message).await().indefinitely()

        val persisted = entitySlot.captured
        assertThat(persisted.eventId).isEqualTo(eventId)
        assertThat(persisted.aggregateId).isEqualTo(aggregateId)
        assertThat(persisted.eventType).isEqualTo("SctInstPaymentSubmitted")
        assertThat(persisted.payload).isEqualTo("""{"amount":"12.34"}""")
        assertThat(persisted.status).isEqualTo(SctInstOutboxStatus.PENDING.name)
        assertThat(persisted.attemptCount).isZero()
        assertThat(persisted.createdAt).isEqualTo(fixedInstant)
        assertThat(persisted.updatedAt).isEqualTo(fixedInstant)
    }

    @Test
    fun `listProcessable maps rows to entries and caps the range at the batch limit`(): Unit = runBlocking {
        val query = mockk<PanacheQuery<SctInstOutboxEntity>>()
        every {
            repo.find(
                "status in (?1, ?2) order by createdAt asc",
                SctInstOutboxStatus.PENDING.name,
                SctInstOutboxStatus.FAILED.name,
            )
        } returns query
        every { query.range(0, 24) } returns query
        every { query.list() } returns Uni.createFrom().item(listOf(entity(attemptCount = 2)))

        val entries = repo.listProcessable(25)

        assertThat(entries).hasSize(1)
        val entry = entries.single()
        assertThat(entry.eventId).isEqualTo(eventId)
        assertThat(entry.aggregateId).isEqualTo(aggregateId)
        assertThat(entry.eventType).isEqualTo("SctInstPaymentSettled")
        assertThat(entry.status).isEqualTo(SctInstOutboxStatus.PENDING)
        assertThat(entry.attemptCount).isEqualTo(2)
        assertThat(entry.sentAt).isNull()
        assertThat(entry.lastError).isNull()
    }

    @Test
    fun `listProcessableUni coerces a non-positive limit to a single row`() {
        val query = mockk<PanacheQuery<SctInstOutboxEntity>>()
        every { repo.find(any<String>(), any<Any>(), any<Any>()) } returns query
        every { query.range(0, 0) } returns query
        every { query.list() } returns Uni.createFrom().item(emptyList())

        val entries = repo.listProcessableUni(0).await().indefinitely()

        assertThat(entries).isEmpty()
    }

    @Test
    fun `countProcessable counts only PENDING and FAILED rows`(): Unit = runBlocking {
        every {
            repo.count(
                "status in (?1, ?2)",
                SctInstOutboxStatus.PENDING.name,
                SctInstOutboxStatus.FAILED.name,
            )
        } returns Uni.createFrom().item(3L)

        assertThat(repo.countProcessable()).isEqualTo(3L)
    }

    @Test
    fun `markSentUni transitions the row to SENT and clears the last error`() {
        val row = entity(status = SctInstOutboxStatus.FAILED, attemptCount = 1).also { it.lastError = "boom" }
        stubFindByEventId(row)

        repo.markSentUni(eventId).await().indefinitely()

        assertThat(row.status).isEqualTo(SctInstOutboxStatus.SENT.name)
        assertThat(row.attemptCount).isEqualTo(2)
        assertThat(row.sentAt).isEqualTo(fixedInstant)
        assertThat(row.updatedAt).isEqualTo(fixedInstant)
        assertThat(row.lastError).isNull()
    }

    @Test
    fun `markSentUni is a no-op for an unknown event id`() {
        stubFindByEventId(null)

        repo.markSentUni(eventId).await().indefinitely()
    }

    @Test
    fun `markFailedUni transitions the row to FAILED and truncates the error to 4000 chars`() {
        val row = entity(attemptCount = 0)
        stubFindByEventId(row)

        repo.markFailedUni(eventId, "x".repeat(5000)).await().indefinitely()

        assertThat(row.status).isEqualTo(SctInstOutboxStatus.FAILED.name)
        assertThat(row.attemptCount).isEqualTo(1)
        assertThat(row.lastError).hasSize(4000)
        assertThat(row.updatedAt).isEqualTo(fixedInstant)
        assertThat(row.sentAt).isNull()
    }

    @Test
    fun `suspending markSent stamps the caller-provided sent timestamp`(): Unit = runBlocking {
        val row = entity(attemptCount = 3)
        stubFindByEventId(row)
        val sentAt = fixedInstant.plusSeconds(5)

        repo.markSent(eventId, sentAt)

        assertThat(row.status).isEqualTo(SctInstOutboxStatus.SENT.name)
        assertThat(row.attemptCount).isEqualTo(4)
        assertThat(row.sentAt).isEqualTo(sentAt)
        assertThat(row.updatedAt).isEqualTo(sentAt)
        assertThat(row.lastError).isNull()
    }

    @Test
    fun `suspending markFailed records the error and the failure timestamp`(): Unit = runBlocking {
        val row = entity(attemptCount = 0)
        stubFindByEventId(row)
        val failedAt = fixedInstant.plusSeconds(9)

        repo.markFailed(eventId, "broker down", failedAt)

        assertThat(row.status).isEqualTo(SctInstOutboxStatus.FAILED.name)
        assertThat(row.attemptCount).isEqualTo(1)
        assertThat(row.lastError).isEqualTo("broker down")
        assertThat(row.updatedAt).isEqualTo(failedAt)
    }
}
