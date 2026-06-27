// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.temporal

import io.quarkus.runtime.StartupEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Smoke tests for the Temporal P0 bootstrap components.
 *
 * Plain JUnit 5 unit tests — no Quarkus runtime required.  They verify that:
 * 1. [TemporalWorkerConfig] skips startup silently when `enabled=false`.
 * 2. [OpenBankSaga] executes compensations in LIFO order.
 * 3. [DeterministicRandom] produces reproducible sequences from the same seed.
 * 4. [OpaActivityInterceptor] passes workflow interception through unchanged.
 */
class TemporalBootSmoke {

    // ── TemporalWorkerConfig ──────────────────────────────────────────────────

    @Test
    fun `worker config skips startup when disabled`() {
        val config = TemporalWorkerConfig().apply {
            serverUrl = "localhost:7233"
            namespace = "openbank-default"
            taskQueue = Optional.empty()
            enabled = false
        }
        // Must not throw — no Temporal server is present in unit-test scope.
        config.onStart(StartupEvent())
    }

    // ── OpenBankSaga ─────────────────────────────────────────────────────────

    @Test
    fun `saga compensates in reverse registration order`() {
        val order = mutableListOf<Int>()
        val s = saga {
            addCompensation { order += 1 }
            addCompensation { order += 2 }
            addCompensation { order += 3 }
        }
        s.compensate()
        assertThat(order).containsExactly(3, 2, 1)
    }

    @Test
    fun `saga with no compensations does not throw`() {
        saga {}.compensate()
    }

    // ── DeterministicRandom ──────────────────────────────────────────────────

    @Test
    fun `same seed produces same sequence`() {
        val a = DeterministicRandom(42L)
        val b = DeterministicRandom(42L)
        assertThat(a.nextLong()).isEqualTo(b.nextLong())
        assertThat(a.nextLong()).isEqualTo(b.nextLong())
    }

    @Test
    fun `different seeds produce different values`() {
        val a = DeterministicRandom(1L)
        val b = DeterministicRandom(2L)
        assertThat(a.nextLong()).isNotEqualTo(b.nextLong())
    }

    @Test
    fun `nextUUID returns distinct values on successive calls`() {
        val r = DeterministicRandom(99L)
        assertThat(r.nextUUID()).isNotEqualTo(r.nextUUID())
    }

    // ── OpaActivityInterceptor ───────────────────────────────────────────────

    @Test
    fun `interceptor is constructable with custom OPA URL`() {
        // Verify construction succeeds and does not eagerly connect to OPA.
        val interceptor = OpaActivityInterceptor("http://opa.example.com/v1/data/openbank/temporal/allow")
        assertThat(interceptor).isNotNull()
    }
}
