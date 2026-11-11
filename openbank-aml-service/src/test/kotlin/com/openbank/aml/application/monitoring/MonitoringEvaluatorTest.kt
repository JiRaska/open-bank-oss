// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.monitoring

import com.openbank.aml.application.port.out.MovementCountQuery
import com.openbank.aml.application.port.out.MovementCounterReader
import com.openbank.aml.domain.monitoring.MonitoringEvaluationOutcome
import com.openbank.aml.domain.monitoring.MovementDirection
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MonitoringEvaluatorTest {
    private val party = UUID.fromString("00000000-0000-4000-8000-00000000e001")
    private val at = Instant.parse("2026-06-10T10:30:00Z")
    private val pack = MonitoringPackFixture.load()

    /** Counts per (currency, direction); records every question it was asked. */
    private class Counts(private val values: Map<Pair<String, MovementDirection?>, Long>) : MovementCounterReader {
        val asked = mutableListOf<MovementCountQuery>()

        override suspend fun countMovements(query: MovementCountQuery): Long {
            asked += query
            return values[query.currency to query.direction] ?: 0L
        }
    }

    private fun evaluate(counts: Counts, instant: Instant = at) = runBlocking {
        MonitoringEvaluator(pack, MonitoringPackFixture.scheme, counts).evaluate(party, instant)
    }

    @Test
    fun `a count at the pack's minimum is a finding that carries the evidence it was decided on`(): Unit {
        val counts = Counts(mapOf(("CZK" to MovementDirection.OUT) to 3L, ("EUR" to MovementDirection.OUT) to 2L))
        val record = evaluate(counts)

        assertThat(record.outcome).isEqualTo(MonitoringEvaluationOutcome.FINDING)
        assertThat(record.failure).isNull()
        assertThat(record.inputHash).matches("[0-9a-f]{64}")
        assertThat(record.pack).isEqualTo(pack.identity)
        val hit = record.hits.single()
        assertThat(hit.scenarioCode).isEqualTo("syn-structuring-czk-out")
        assertThat(hit.reasonCode).isEqualTo("SYN_REPEATED_BELOW_MARK")
        assertThat(hit.severityCode).isEqualTo("MEDIUM")
        assertThat(hit.observation.count).isEqualTo(3L)
        assertThat(hit.observation.currency).isEqualTo("CZK")
        assertThat(hit.observation.band).isEqualTo("SYN_CZK_BELOW_MARK")
        assertThat(hit.windowStart).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"))
    }

    @Test
    fun `every question is one currency, one band, one direction over the feature's own window`(): Unit {
        val counts = Counts(emptyMap())
        evaluate(counts)

        assertThat(counts.asked).hasSize(3)
        assertThat(counts.asked).allSatisfy { query ->
            assertThat(query.partyId).isEqualTo(party)
            assertThat(query.schemeRevision).isEqualTo(1)
            // 24h back, aligned to the scheme's bucket; the current bucket is inside the window.
            assertThat(query.fromInclusive).isEqualTo(Instant.parse("2026-06-09T10:00:00Z"))
            assertThat(query.toExclusive).isAfter(at)
        }
        assertThat(counts.asked.map { Triple(it.currency, it.bandCode, it.direction) }).containsExactlyInAnyOrder(
            Triple("CZK", "SYN_CZK_BELOW_MARK", MovementDirection.OUT),
            Triple("CZK", "SYN_CZK_BELOW_MARK", MovementDirection.IN),
            Triple("EUR", "SYN_EUR_BELOW_MARK", MovementDirection.OUT),
        )
    }

    @Test
    fun `counts below every minimum are NO_FINDING, which is not a statement about the party`(): Unit {
        val record = evaluate(Counts(mapOf(("CZK" to MovementDirection.OUT) to 2L)))
        assertThat(record.outcome).isEqualTo(MonitoringEvaluationOutcome.NO_FINDING)
        assertThat(record.hits).isEmpty()
        assertThat(record.inputHash).isNotNull()
    }

    @Test
    fun `a pack that cannot be evaluated is INCOMPLETE with the reason, even over counts that would match`(): Unit {
        val wouldMatch = Counts(mapOf(("CZK" to MovementDirection.OUT) to 99L))
        val record = evaluate(wouldMatch, Instant.parse("2100-01-01T00:00:00Z"))

        assertThat(record.outcome).isEqualTo(MonitoringEvaluationOutcome.INCOMPLETE)
        assertThat(record.failure).isEqualTo("OUTSIDE_VALIDITY")
        assertThat(record.hits).describedAs("no finding, and no clear, from a pack outside its validity").isEmpty()
        assertThat(record.inputHash).isNull()
    }
}
