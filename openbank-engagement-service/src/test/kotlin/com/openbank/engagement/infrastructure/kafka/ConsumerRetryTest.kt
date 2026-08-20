// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.kafka

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jboss.logging.Logger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A named type so detekt's TooGenericExceptionThrown does not fire at the throw sites, and so the
 * consumer tests can assert on the exact exception that escaped rather than on `RuntimeException`.
 *
 * Shared by every consumer test in this package: the five consumers all had the same catch-and-ack
 * defect (#5698) and are all fixed the same way, so one transient-failure type keeps the assertions
 * comparable.
 */
internal class TransientDbFailure : RuntimeException("connection refused")

/**
 * Direct cover for the retry helper, so each consumer test can assert the consumer's WIRING
 * (parse acks, write escapes) without also re-deriving the backoff semantics.
 */
class ConsumerRetryTest {

    private val log = Logger.getLogger(ConsumerRetryTest::class.java)

    @Test
    fun `a persistent failure is retried MAX_ATTEMPTS times and then RETHROWN`(): Unit = runBlocking {
        var calls = 0

        assertThrows<TransientDbFailure> {
            runBlocking {
                withBoundedRetry(log, "unit test") {
                    calls++
                    throw TransientDbFailure()
                }
            }
        }

        assertThat(calls).isEqualTo(MAX_ATTEMPTS)
    }

    @Test
    fun `a failure that recovers is retried to success and nothing escapes`(): Unit = runBlocking {
        var calls = 0

        withBoundedRetry(log, "unit test") {
            calls++
            if (calls < 2) throw TransientDbFailure()
        }

        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `a block that succeeds first time runs exactly once`(): Unit = runBlocking {
        var calls = 0

        withBoundedRetry(log, "unit test") { calls++ }

        assertThat(calls).isEqualTo(1)
    }
}
