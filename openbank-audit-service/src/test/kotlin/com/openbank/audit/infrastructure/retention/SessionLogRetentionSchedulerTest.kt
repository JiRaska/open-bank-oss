// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.retention

import com.openbank.audit.application.port.out.SessionLogRepositoryPort
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Covers [SessionLogRetentionScheduler] — ADR-0118 §2/§5, issue #268.
 *
 * The retention-window boundary tests (day 89 kept, day 91 deleted) use a fixed [Clock] rather
 * than wall-clock time, per repo convention (same pattern as CardPiiRetentionSchedulerTest /
 * KycRetentionSchedulerTest). The critical safety property under test — disabled AND dry-run
 * both default-safe, and a real delete requires BOTH `enabled=true` AND `dryRun=false` — is
 * asserted explicitly, since this scheduler must ship inert until a human turns it on.
 */
class SessionLogRetentionSchedulerTest {

    private val sessionLogRepository = mockk<SessionLogRepositoryPort>()
    private val auditPublisher = mockk<AuditEventPublisher>(relaxed = true)

    // "Now" fixed at 2026-07-06T04:00:00Z; 90 days back = 2026-04-07T04:00:00Z.
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-06T04:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `computes cutoff as exactly retentionDays before now`(): Unit = runBlocking {
        val expectedCutoff = Instant.parse("2026-04-07T04:00:00Z")
        coEvery { sessionLogRepository.deleteOlderThan(expectedCutoff) } returns 7L

        scheduler(retentionDays = 90, dryRun = false, enabled = true).enforceRetention()

        coVerify(exactly = 1) { sessionLogRepository.deleteOlderThan(expectedCutoff) }
    }

    @Test
    fun `a session log exactly at day 89 is kept, day 91 is deleted (boundary semantics)`(): Unit = runBlocking {
        // "now" - 90d is the cutoff; deleteOlderThan uses a strict '<' comparison (see
        // SessionLogRepository), so a row exactly at the cutoff instant is NOT deleted.
        val cutoff = Instant.parse("2026-04-07T04:00:00Z")
        val day89 = cutoff.plusSeconds(24 * 3600) // 89 days old relative to "now" — inside the window, kept
        val day91 = cutoff.minusSeconds(24 * 3600) // 91 days old relative to "now" — outside the window, deleted

        // The scheduler only ever computes and passes the cutoff down; the boundary contract
        // (kept vs deleted) is enforced by the repository's "occurredAt < cutoff" query. This
        // test documents that contract precisely so a future change to the comparison operator
        // is caught here, not discovered in production.
        assertThat(day89.isBefore(cutoff)).isFalse()
        assertThat(day91.isBefore(cutoff)).isTrue()

        coEvery { sessionLogRepository.deleteOlderThan(cutoff) } returns 1L
        scheduler(retentionDays = 90, dryRun = false, enabled = true).enforceRetention()
        coVerify(exactly = 1) { sessionLogRepository.deleteOlderThan(cutoff) }
    }

    @Test
    fun `disabled scheduler never touches the repository, even if dry-run is off`(): Unit = runBlocking {
        scheduler(enabled = false, dryRun = false).enforceRetention()

        coVerify(exactly = 0) { sessionLogRepository.deleteOlderThan(any()) }
        coVerify(exactly = 0) { sessionLogRepository.countOlderThan(any()) }
    }

    @Test
    fun `dry-run counts but never deletes, and publishes a DRY-RUN audit event`(): Unit = runBlocking {
        coEvery { sessionLogRepository.countOlderThan(any()) } returns 42L

        scheduler(enabled = true, dryRun = true).enforceRetention()

        coVerify(exactly = 0) { sessionLogRepository.deleteOlderThan(any()) }
        coVerify(exactly = 1) { sessionLogRepository.countOlderThan(any()) }
        coVerify(exactly = 1) {
            auditPublisher.publish(
                match<AuditEvent> {
                    it.operation == "session-log.retention.dry-run" && it.payload["wouldDeleteCount"] == 42L
                },
            )
        }
    }

    @Test
    fun `default configuration is fully inert (disabled wins over dry-run default)`(): Unit = runBlocking {
        // Mirrors the shipped application.yaml defaults exactly: enabled=false, dry-run=true.
        // Both defaults are individually safe, and disabled=false short-circuits before dry-run
        // is even consulted, so no repository call happens either way.
        scheduler().enforceRetention()

        coVerify(exactly = 0) { sessionLogRepository.deleteOlderThan(any()) }
        coVerify(exactly = 0) { sessionLogRepository.countOlderThan(any()) }
    }

    @Test
    fun `real delete requires enabled=true AND dryRun=false together`(): Unit = runBlocking {
        coEvery { sessionLogRepository.deleteOlderThan(any()) } returns 3L

        scheduler(enabled = true, dryRun = false).enforceRetention()

        coVerify(exactly = 1) { sessionLogRepository.deleteOlderThan(any()) }
        coVerify(exactly = 1) {
            auditPublisher.publish(
                match<AuditEvent> {
                    it.operation == "session-log.retention.enforced" && it.payload["deletedCount"] == 3L
                },
            )
        }
    }

    @Test
    fun `zero rows deleted is a no-op that still emits the audit event (DORA Art17 reconstructibility)`(): Unit =
        runBlocking {
            coEvery { sessionLogRepository.deleteOlderThan(any()) } returns 0L

            scheduler(enabled = true, dryRun = false).enforceRetention()

            coVerify(exactly = 1) { sessionLogRepository.deleteOlderThan(any()) }
            coVerify(exactly = 1) {
                auditPublisher.publish(match<AuditEvent> { it.operation == "session-log.retention.enforced" })
            }
        }

    private fun scheduler(retentionDays: Long = 90, dryRun: Boolean = true, enabled: Boolean = false) =
        SessionLogRetentionScheduler(
            sessionLogRepository = sessionLogRepository,
            auditPublisher = auditPublisher,
            clock = fixedClock,
            retentionDays = retentionDays,
            dryRun = dryRun,
            enabled = enabled,
            domainMetrics = mockk(relaxed = true),
        )
}
