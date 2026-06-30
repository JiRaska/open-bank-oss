// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VelocityFeaturesTest {

    private val asOf = Instant.parse("2026-06-29T10:30:00Z") // 10:30, so the H1 bucket starts 10:00
    private val entity = "acc-1"

    private fun txn(at: String, type: String = TRANSACTION_INITIATED) =
        FeatureEvent(entityId = entity, eventType = type, occurredAt = Instant.parse(at))

    @Test
    fun `H1 counts only TransactionInitiated events in the current hour strictly before asOf`() {
        val events = listOf(
            txn("2026-06-29T10:00:00Z"), // bucket start — counted
            txn("2026-06-29T10:15:00Z"), // counted
            txn("2026-06-29T10:29:59Z"), // counted
            txn("2026-06-29T10:30:00Z"), // == asOf — EXCLUDED (strict <, anti-leakage)
            txn("2026-06-29T10:45:00Z"), // after asOf — excluded
            txn("2026-06-29T09:59:59Z"), // previous hour — excluded
        )
        assertThat(VELOCITY_TXN_COUNT_H1.compute(asOf, events)).isEqualTo(3.0)
    }

    @Test
    fun `H24 counts events in the current day strictly before asOf`() {
        val events = listOf(
            txn("2026-06-29T00:00:00Z"), // day start — counted
            txn("2026-06-29T07:00:00Z"), // counted
            txn("2026-06-29T10:00:00Z"), // counted
            txn("2026-06-29T10:30:00Z"), // == asOf — excluded
            txn("2026-06-28T23:59:59Z"), // previous day — excluded
        )
        assertThat(VELOCITY_TXN_COUNT_H24.compute(asOf, events)).isEqualTo(3.0)
    }

    @Test
    fun `non-matching event types are ignored`() {
        val events = listOf(
            txn("2026-06-29T10:10:00Z"),
            txn("2026-06-29T10:11:00Z", type = "TransactionCompleted"),
        )
        assertThat(VELOCITY_TXN_COUNT_H1.compute(asOf, events)).isEqualTo(1.0)
    }

    @Test
    fun `empty history yields zero`() {
        assertThat(VELOCITY_TXN_COUNT_H1.compute(asOf, emptyList())).isEqualTo(0.0)
    }

    @Test
    fun `H1 is stale across an hour boundary and fresh within the same hour`() {
        val sameHour = Instant.parse("2026-06-29T10:59:00Z")
        val nextHour = Instant.parse("2026-06-29T11:00:00Z")
        assertThat(VELOCITY_TXN_COUNT_H1.isStale(asOf, sameHour)).isFalse()
        assertThat(VELOCITY_TXN_COUNT_H1.isStale(asOf, nextHour)).isTrue()
    }

    @Test
    fun `H24 is stale across a day boundary`() {
        val sameDay = Instant.parse("2026-06-29T23:59:00Z")
        val nextDay = Instant.parse("2026-06-30T00:00:00Z")
        assertThat(VELOCITY_TXN_COUNT_H24.isStale(asOf, sameDay)).isFalse()
        assertThat(VELOCITY_TXN_COUNT_H24.isStale(asOf, nextDay)).isTrue()
    }

    @Test
    fun `windowBucketStart returns the tumbling bucket for windowed features`() {
        assertThat(windowBucketStart(VELOCITY_TXN_COUNT_H1, asOf)).isEqualTo(Instant.parse("2026-06-29T10:00:00Z"))
        assertThat(windowBucketStart(VELOCITY_TXN_COUNT_H24, asOf)).isEqualTo(Instant.parse("2026-06-29T00:00:00Z"))
    }

    @Test
    fun `phase-1 declares H1 and H24 only`() {
        assertThat(PHASE1_FEATURES.map { it.name })
            .containsExactly("velocity_txn_count_h1", "velocity_txn_count_h24")
    }
}
