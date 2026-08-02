// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #3267: the original transport fault of a resilient call must be surfaced before fault tolerance
 * masks it.
 *
 * The issue is explicit that the obvious test does not work — "a test asserting the adapter throws
 * `ScreeningUnavailableException` passes today and would still pass after the fix". The assertion
 * has to be on what the caller was TOLD about the underlying fault. That is why the failure handler
 * is a parameter: these pin it directly, with no log capture and no fault-tolerance runtime.
 */
class FirstFailureVisibilityTest {

    @Test
    fun `the original throwable is reported, not a masked one`(): Unit = runBlocking {
        val seen = mutableListOf<Throwable>()
        val transportFault = IllegalStateException(
            """duplicate key value violates unique constraint "sanctions_checks_idempotency_key_key"""",
        )

        assertThatThrownBy {
            runBlocking {
                reportingFirstFailure<Unit>(onFailure = { seen += it }) { throw transportFault }
            }
        }.isSameAs(transportFault)

        // The point of #3267: without this, the only thing anyone ever saw was
        // CircuitBreakerOpenException, which names nothing about what actually failed.
        assertThat(seen).containsExactly(transportFault)
        assertThat(seen.single().message).contains("sanctions_checks_idempotency_key_key")
    }

    @Test
    fun `the exception is rethrown unchanged, so fault tolerance still sees it`(): Unit = runBlocking {
        // Swallowing here would turn a held payment into a released one — the helper must observe,
        // never intercept. @Retry/@CircuitBreaker only count a failure they actually receive.
        val fault = RuntimeException("boom")
        assertThatThrownBy {
            runBlocking { reportingFirstFailure<Unit>(onFailure = { }) { throw fault } }
        }.isSameAs(fault)
    }

    @Test
    fun `a successful call reports nothing and returns its value`(): Unit = runBlocking {
        val seen = mutableListOf<Throwable>()
        val result = reportingFirstFailure(onFailure = { seen += it }) { "CLEAR" }
        assertThat(result).isEqualTo("CLEAR")
        assertThat(seen).isEmpty()
    }

    @Test
    fun `an Error is reported too`(): Unit = runBlocking {
        // Throwable, not Exception, on purpose: a Timeout surfaces as an InterruptedException on
        // some paths and a breaker rejection is unchecked. Narrowing the catch would re-create the
        // blind spot for exactly the faults this exists to show.
        val seen = mutableListOf<Throwable>()
        val err = StackOverflowError("deep")
        assertThatThrownBy {
            runBlocking { reportingFirstFailure<Unit>(onFailure = { seen += it }) { throw err } }
        }.isSameAs(err)
        assertThat(seen).containsExactly(err)
    }
}
