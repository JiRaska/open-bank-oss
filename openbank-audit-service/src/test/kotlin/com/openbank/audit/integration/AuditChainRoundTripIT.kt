// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Regression coverage for #3505: the hash chain is written from the INCOMING domain object and
 * verified from the object reconstructed out of the database, so any field the round-trip does
 * not preserve byte-for-byte makes `verifyChain()` fail on the first chained row forever
 * (`openbank_audit_chain_entries_checked = 0` against 1066 chained rows in the sandbox).
 *
 * Every pre-existing test hashes and verifies in-process, which is exactly the step where the
 * two sides disagree — only a real database round-trip can see it.
 *
 * The instants below carry deliberate NANOSECOND digits. `TIMESTAMPTZ` stores microseconds, so a
 * nanosecond-precision `Instant` is silently rounded on persist while the hash was computed over
 * the unrounded value. Using a literal (rather than `Instant.now()`) makes the test prove the same
 * thing on macOS — whose `Clock.systemUTC()` already yields microseconds — as on Linux, where the
 * production pods run and the JDK clock yields nanoseconds.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AuditChainRoundTripIT {

    @Inject
    lateinit var repository: AuditRepository

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun entry(aggregateId: String) = AuditEntry(
        id = UUID.randomUUID(),
        eventType = "chain.roundtrip.test",
        aggregateType = "ACCOUNT",
        aggregateId = aggregateId,
        actorId = "tester",
        actorType = "HUMAN",
        payload = """{"aggregateId":"$aggregateId"}""",
        sourceService = "openbank-audit-service",
        correlationId = "corr-$aggregateId",
        // 123456789 ns and 987654321 ns — both lose their last three digits to TIMESTAMPTZ.
        occurredAt = Instant.ofEpochSecond(OCCURRED_EPOCH_SECOND, OCCURRED_NANOS),
        recordedAt = Instant.ofEpochSecond(RECORDED_EPOCH_SECOND, RECORDED_NANOS),
    )

    /**
     * The mechanism behind the defect, pinned with LITERAL expectations rather than by re-applying
     * the repository's own normalisation — a test that mirrors the implementation would stay green
     * however the truncation changed. `TIMESTAMPTZ` truncates (it does not round: `…456789` becomes
     * `…456`, never `…457`), and every other hashed field is returned verbatim.
     */
    @Test
    fun `TIMESTAMPTZ returns microseconds, so only the timestamps change across the round-trip`() {
        val aggregateId = "roundtrip-${UUID.randomUUID()}"
        val written = entry(aggregateId)

        onEventLoop { repository.save(written) }
        val readBack = onEventLoop { repository.findByAggregateId(aggregateId) }.single()

        assertThat(readBack.occurredAt)
            .describedAs("occurred_at loses its nanosecond digits to TIMESTAMPTZ")
            .isEqualTo(Instant.ofEpochSecond(OCCURRED_EPOCH_SECOND, 123_456_000L))
        assertThat(readBack.recordedAt)
            .describedAs("recorded_at loses its nanosecond digits to TIMESTAMPTZ")
            .isEqualTo(Instant.ofEpochSecond(RECORDED_EPOCH_SECOND, 987_654_000L))
        // Field-by-field for the rest, so a failure names the field the round-trip does not
        // preserve rather than only saying "the hash differs".
        assertThat(readBack)
            .describedAs("no other field the chain hash covers may change across the round-trip")
            .usingRecursiveComparison()
            .ignoringFields("occurredAt", "recordedAt")
            .isEqualTo(written)
    }

    @Test
    fun `verifyChain confirms a link that this process just wrote`() {
        val aggregateId = "verify-${UUID.randomUUID()}"
        val written = entry(aggregateId)

        onEventLoop { repository.save(written) }
        val verification = onEventLoop { repository.verifyChain(fromEntryId = written.id) }

        assertThat(verification.intact)
            .describedAs("chain must verify from the row just written (broken: %s)", verification.firstBrokenEntryId)
            .isTrue()
        assertThat(verification.checked)
            .describedAs("the freshly written row must actually be checked, not skipped")
            .isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `verifyChain confirms consecutive links, not just an isolated row`() {
        val first = entry("multi-a-${UUID.randomUUID()}")
        val second = entry("multi-b-${UUID.randomUUID()}")

        onEventLoop { repository.save(first) }
        onEventLoop { repository.save(second) }
        val verification = onEventLoop { repository.verifyChain(fromEntryId = first.id) }

        assertThat(verification.intact)
            .describedAs("both links must verify (first broken: %s)", verification.firstBrokenEntryId)
            .isTrue()
        assertThat(verification.checked)
            .describedAs("both rows written here must be recomputed")
            .isGreaterThanOrEqualTo(2)
    }

    private companion object {
        const val OCCURRED_EPOCH_SECOND = 1_785_704_401L
        const val OCCURRED_NANOS = 123_456_789L
        const val RECORDED_EPOCH_SECOND = 1_785_704_402L
        const val RECORDED_NANOS = 987_654_321L
    }
}
