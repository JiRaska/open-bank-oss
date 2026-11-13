// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/** #13883: the proof-of-absence grace must outlast one whole ledger call by a wide margin. */
class PostingIntentGraceTest {
    @Test
    fun `the shipped grace (2m) clears the shipped ledger timeouts (2s connect + 5s read)`() {
        assertThatCode { requireGraceAboveLedgerTimeout(Duration.ofMinutes(2), 2000, 5000) }.doesNotThrowAnyException()
    }

    @Test
    fun `the boot observer refuses a misconfigured grace`() {
        val check = PostingIntentGraceCheck(Duration.ofSeconds(10), 2000, 5000)
        assertThatThrownBy { check.onStart(io.mockk.mockk(relaxed = true)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode {
            PostingIntentGraceCheck(Duration.ofMinutes(2), 2000, 5000).onStart(io.mockk.mockk(relaxed = true))
        }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a grace inside four ledger calls refuses to start`() {
        assertThatThrownBy { requireGraceAboveLedgerTimeout(Duration.ofSeconds(20), 2000, 5000) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("posting-intent.grace")
    }
}
