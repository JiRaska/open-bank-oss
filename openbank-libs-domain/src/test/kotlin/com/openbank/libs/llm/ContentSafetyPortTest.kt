// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.llm

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The point of this port is that "the classifier could not run" is its own value and never folds
 * into SAFE — the shape that shipped a push channel reporting every notification as delivered while
 * its credentials were absent. So the assertions below are about UNAVAILABLE specifically: that the
 * unwired default reports it, and that a caller must state its own fail-closed policy to interpret it.
 */
class ContentSafetyPortTest {

    private fun verdict(d: ContentSafetyPort.Decision) = ContentSafetyPort.SafetyVerdict(d)

    @Test
    fun `UNSAFE blocks regardless of the caller's fail-closed policy`() {
        assertThat(verdict(ContentSafetyPort.Decision.UNSAFE).isBlocking(failClosed = true)).isTrue()
        assertThat(verdict(ContentSafetyPort.Decision.UNSAFE).isBlocking(failClosed = false)).isTrue()
    }

    @Test
    fun `SAFE never blocks regardless of the caller's fail-closed policy`() {
        assertThat(verdict(ContentSafetyPort.Decision.SAFE).isBlocking(failClosed = true)).isFalse()
        assertThat(verdict(ContentSafetyPort.Decision.SAFE).isBlocking(failClosed = false)).isFalse()
    }

    @Test
    fun `UNAVAILABLE is the only decision whose blocking outcome the caller decides`() {
        val u = verdict(ContentSafetyPort.Decision.UNAVAILABLE)
        assertThat(u.isBlocking(failClosed = true)).isTrue()
        assertThat(u.isBlocking(failClosed = false)).isFalse()
    }

    @Test
    fun `the disabled port classifies nothing and says so, never SAFE`(): Unit = runBlocking {
        val v = ContentSafetyPort.DISABLED.classify(ContentSafetyPort.SafetyRole.USER, "anything at all")
        assertThat(v.decision).isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE)
        assertThat(v.decision).isNotEqualTo(ContentSafetyPort.Decision.SAFE)
        assertThat(v.reason).isEqualTo(ContentSafetyPort.REASON_NOT_CONFIGURED)
        assertThat(v.categories).isEmpty()
        assertThat(v.model).isEmpty()
    }

    @Test
    fun `the disabled port answers the same for an assistant completion as for user input`(): Unit = runBlocking {
        val user = ContentSafetyPort.DISABLED.classify(ContentSafetyPort.SafetyRole.USER, "x")
        val assistant = ContentSafetyPort.DISABLED.classify(ContentSafetyPort.SafetyRole.ASSISTANT, "x")
        assertThat(assistant).isEqualTo(user)
    }

    @Test
    fun `an unwired guardrail blocks a money-path caller and lets a help-desk caller through`(): Unit = runBlocking {
        val v = ContentSafetyPort.DISABLED.classify(ContentSafetyPort.SafetyRole.USER, "how do I move money")
        assertThat(v.isBlocking(failClosed = true)).isTrue()
        assertThat(v.isBlocking(failClosed = false)).isFalse()
    }

    @Test
    fun `the unavailable reasons are a closed low-cardinality vocabulary fit for a metric label`() {
        val reasons = listOf(
            ContentSafetyPort.REASON_NOT_CONFIGURED,
            ContentSafetyPort.REASON_TRANSPORT,
            ContentSafetyPort.REASON_UNPARSEABLE,
        )
        assertThat(reasons).doesNotHaveDuplicates()
        assertThat(reasons).allSatisfy { assertThat(it).matches { r -> Regex("^[a-z_]+$").matches(r) } }
    }

    @Test
    fun `a verdict defaults to no categories, no model and no reason so an UNSAFE one must state them`() {
        val bare = ContentSafetyPort.SafetyVerdict(ContentSafetyPort.Decision.SAFE)
        assertThat(bare.categories).isEmpty()
        assertThat(bare.model).isEmpty()
        assertThat(bare.reason).isEmpty()

        val flagged = ContentSafetyPort.SafetyVerdict(
            ContentSafetyPort.Decision.UNSAFE,
            categories = listOf("S2", "S9"),
            model = "llama-guard-3-8b",
        )
        assertThat(flagged.categories).containsExactly("S2", "S9")
        assertThat(flagged.isBlocking(failClosed = false)).isTrue()
    }
}
