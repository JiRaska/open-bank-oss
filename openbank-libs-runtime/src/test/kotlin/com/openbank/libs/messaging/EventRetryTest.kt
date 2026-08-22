// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.messaging

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jboss.logging.Logger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private class TransientFailure : RuntimeException("connection refused")

class EventRetryTest {

    private val log = Logger.getLogger(EventRetryTest::class.java)

    @Test
    fun `a successful block runs once and returns its value`(): Unit = runBlocking {
        var calls = 0
        val result = EventRetry.withRetry(log, "TEST_EVENT", "key-1", backoffMs = 1) {
            calls++
            "done"
        }

        assertThat(result).isEqualTo("done")
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `a transient failure is retried and then succeeds`(): Unit = runBlocking {
        var calls = 0
        val result = EventRetry.withRetry(log, "TEST_EVENT", "key-1", backoffMs = 1) {
            calls++
            if (calls < 3) throw TransientFailure()
            "done"
        }

        assertThat(result).isEqualTo("done")
        assertThat(calls).isEqualTo(3)
    }

    /**
     * The assertion that matters. Swallowing here is what acked a real customer's onboarding into
     * nothing (#5698); a version of this helper that logged and returned would leave every caller
     * with the same defect while looking like a fix.
     */
    @Test
    fun `a persistent failure is RETHROWN after the bounded attempts`(): Unit = runBlocking {
        var calls = 0

        assertThrows<TransientFailure> {
            runBlocking {
                EventRetry.withRetry(log, "TEST_EVENT", "key-1", backoffMs = 1) {
                    calls++
                    throw TransientFailure()
                }
            }
        }

        assertThat(calls).isEqualTo(EventRetry.DEFAULT_MAX_ATTEMPTS)
    }

    @Test
    fun `maxAttempts of 1 means no retry, and still rethrows`(): Unit = runBlocking {
        var calls = 0

        assertThrows<TransientFailure> {
            runBlocking {
                EventRetry.withRetry(log, "TEST_EVENT", "key-1", maxAttempts = 1, backoffMs = 1) {
                    calls++
                    throw TransientFailure()
                }
            }
        }

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `a failure the caller calls non-retryable is rethrown on the FIRST attempt`(): Unit = runBlocking {
        var calls = 0

        assertThrows<IllegalStateException> {
            runBlocking {
                EventRetry.withRetry(
                    log,
                    "TEST_EVENT",
                    "key-1",
                    backoffMs = 1,
                    isRetryable = { it !is IllegalStateException },
                ) {
                    calls++
                    error("no published template for this product")
                }
            }
        }

        // Deterministic: it fails identically every time, so retrying only delays the ack.
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `maxAttempts below 1 is rejected rather than silently running zero times`(): Unit = runBlocking {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                EventRetry.withRetry(log, "TEST_EVENT", "key-1", maxAttempts = 0, backoffMs = 1) { "never" }
            }
        }
        Unit
    }
}
