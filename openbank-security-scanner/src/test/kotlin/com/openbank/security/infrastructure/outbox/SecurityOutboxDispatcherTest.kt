// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.outbox

import com.openbank.security.application.port.out.SecurityOutboxEntry
import com.openbank.security.application.port.out.SecurityOutboxStatus
import com.openbank.security.infrastructure.persistence.repository.SecurityOutboxRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.kafka.Record
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The outbox's whole promise is that a row marked SENT was actually published. This dispatcher broke
 * that promise for every failure mode that matters.
 *
 * `Emitter.send` returns a [CompletionStage] that completes when the broker acknowledges. Wrapping it
 * in `Uni.createFrom().item { … }` made the CompletionStage the Uni's *value* and completed the Uni
 * the instant `send` RETURNED — so `.chain { markSent }` ran unconditionally, and `.onFailure()`
 * could only ever observe a synchronous throw from `send` itself, which is the one case that does
 * not happen. A broker denial, an unreachable broker or a serialisation error each lost the event
 * while the outbox recorded success.
 *
 * That was not hypothetical here: security-scanner publishes as ANONYMOUS on a broker running
 * `allow.everyone.if.no.acl.found=false`, so every publish is refused (#3393) — and every one would
 * have been marked SENT.
 *
 * Both tests fail against the `item { }` shape and pass against `completionStage { }`, which is the
 * only reason they are worth having.
 */
class SecurityOutboxDispatcherTest {

    private fun entry(): SecurityOutboxEntry = SecurityOutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "security.scan.completed",
        payload = """{"kind":"test"}""",
        status = SecurityOutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        sentAt = null,
        lastError = null,
    )

    private fun dispatcherFor(
        repo: SecurityOutboxRepositoryImpl,
        sendResult: CompletionStage<Void>,
    ): SecurityOutboxDispatcher {
        val emitter = mockk<Emitter<Record<String, String>>>()
        every { emitter.send(any<Record<String, String>>()) } returns sendResult
        return SecurityOutboxDispatcher(repo, emitter)
    }

    @Test
    fun `a publish the broker rejects is marked FAILED, never SENT`() {
        val repo = mockk<SecurityOutboxRepositoryImpl>()
        val row = entry()
        every { repo.listProcessableUni(any()) } returns Uni.createFrom().item(listOf(row))
        every { repo.markSentUni(any(), any()) } returns Uni.createFrom().voidItem()
        every { repo.markFailedUni(any(), any(), any()) } returns Uni.createFrom().voidItem()

        val refused = CompletableFuture<Void>().apply {
            completeExceptionally(IllegalStateException("Not authorized to access topics"))
        }

        dispatcherFor(repo, refused).dispatchScheduledBatch().await().indefinitely()

        // The assertion that would have caught #3393's silent loss.
        verify(exactly = 0) { repo.markSentUni(row.eventId, any()) }
        verify(exactly = 1) { repo.markFailedUni(row.eventId, any(), any()) }
    }

    @Test
    fun `a publish the broker accepts is marked SENT`() {
        val repo = mockk<SecurityOutboxRepositoryImpl>()
        val row = entry()
        every { repo.listProcessableUni(any()) } returns Uni.createFrom().item(listOf(row))
        every { repo.markSentUni(any(), any()) } returns Uni.createFrom().voidItem()
        every { repo.markFailedUni(any(), any(), any()) } returns Uni.createFrom().voidItem()

        dispatcherFor(repo, CompletableFuture.completedFuture(null)).dispatchScheduledBatch()
            .await().indefinitely()

        verify(exactly = 1) { repo.markSentUni(row.eventId, any()) }
        verify(exactly = 0) { repo.markFailedUni(row.eventId, any(), any()) }
    }
}
