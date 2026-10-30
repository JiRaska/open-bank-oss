// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.openbank.libs.domain.error.RateLimitExceeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ConcurrencyLimitTest {

    @Test
    fun `a full limit refuses at once, and a finished run frees its slot`(): Unit = runBlocking {
        val limit = ConcurrencyLimit(1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async {
            limit.run {
                started.complete(Unit)
                release.await()
                "first"
            }
        }
        started.await()
        assertThatThrownBy { runBlocking { limit.run { "second" } } }.isInstanceOf(RateLimitExceeded::class.java)
        release.complete(Unit)
        assertThat(holder.await()).isEqualTo("first")
        assertThat(limit.run { "third" }).isEqualTo("third")
    }

    @Test
    fun `a run that throws still frees its slot`(): Unit = runBlocking {
        val limit = ConcurrencyLimit(1)
        assertThatThrownBy {
            runBlocking { limit.run { error("boom") } }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(limit.run { "after" }).isEqualTo("after")
    }
}
