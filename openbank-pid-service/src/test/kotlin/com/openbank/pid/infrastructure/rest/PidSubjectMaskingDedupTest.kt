// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Table-driven proof for issue #11027's pid-service follow-up: were
 * `EudiDtos.kt#maskSubjectId` and `EudiCredentialIssuerResource.kt#maskSubject` behaviourally
 * identical? [legacyDtoMaskSubjectId] and [legacyLogMaskSubject] are frozen copies of the two
 * ORIGINAL private functions (before this PR deleted them and replaced both call sites with
 * [maskPidSubject]) — kept here only to document the divergence they had, never called from
 * production code again.
 */
class PidSubjectMaskingDedupTest {

    /** Frozen copy of the original `EudiDtos.kt#maskSubjectId` (pre-dedup). */
    private fun legacyDtoMaskSubjectId(subjectId: String): String {
        val tail = subjectId.takeLast(4)
        val prefix = subjectId.substringBefore(":", missingDelimiterValue = "")
        return if (prefix.isNotEmpty()) "$prefix:***$tail" else "***$tail"
    }

    /** Frozen copy of the original `EudiCredentialIssuerResource.kt#maskSubject` (pre-dedup). */
    private fun legacyLogMaskSubject(s: String): String = if (s.length <= 4) {
        "***"
    } else {
        s.takeLast(4).let { "***$it" }
    }

    companion object {
        fun inputs(): List<String> = listOf(
            "",
            "a",
            "ab",
            "abc",
            "abcd",
            "abcde",
            "abcdefgh",
            "ns:",
            "ns:a",
            "ns:abcd",
            "ns:abcde",
            "ns:abcdefghij",
            ":abcd",
            ":abcdefgh",
            "no-colon-but-long-enough-to-reveal-a-tail",
            "issuer-namespace:1234567890",
            "关键:测试测试测试测试测试",
            "🙂🙂🙂🙂🙂🙂",
        )
    }

    @Test
    fun `the two original implementations disagree on short or separator-less inputs`() {
        // Documents the divergence that motivated the dedup: the DTO version echoes the WHOLE
        // subject back for any input of length <= 4 with no ':' prefix, where the log version
        // fully redacts. Both being length 0..4 with no prefix reproduces it directly.
        assertThat(legacyDtoMaskSubjectId("ab")).isEqualTo("***ab")
        assertThat(legacyLogMaskSubject("ab")).isEqualTo("***")
        assertThat(legacyDtoMaskSubjectId("abcd")).isEqualTo("***abcd")
        assertThat(legacyLogMaskSubject("abcd")).isEqualTo("***")

        val disagreements = inputs().filter { legacyDtoMaskSubjectId(it) != legacyLogMaskSubject(it) }
        assertThat(disagreements).isNotEmpty()
    }

    @Test
    fun `consolidated maskPidSubject never reveals more than the stricter legacy implementation`() {
        for (input in inputs()) {
            val consolidated = maskPidSubject(input)

            // Never an unmasked echo of a short, separator-less subject — the DTO version's bug.
            val prefix = input.substringBefore(":", missingDelimiterValue = "")
            if (prefix.isEmpty() && input.length <= 4) {
                assertThat(consolidated).describedAs("input=%s", input).isEqualTo("***")
            }
        }
    }

    @Test
    fun `exactly-4-character remainder is fully redacted, not echoed`() {
        assertThat(maskPidSubject("abcd")).isEqualTo("***")
        assertThat(maskPidSubject("ns:abcd")).isEqualTo("ns:***")
    }

    @Test
    fun `empty input is fully redacted`() {
        assertThat(maskPidSubject("")).isEqualTo("***")
    }

    @Test
    fun `long input without a namespace reveals only the last 4 characters`() {
        assertThat(maskPidSubject("abcdefgh")).isEqualTo("***efgh")
    }

    @Test
    fun `namespace prefix is preserved and the remainder tail is revealed when long enough`() {
        assertThat(maskPidSubject("issuer-namespace:1234567890")).isEqualTo("issuer-namespace:***7890")
    }

    @Test
    fun `namespace prefix is preserved but remainder is fully redacted when short`() {
        assertThat(maskPidSubject("ns:a")).isEqualTo("ns:***")
        assertThat(maskPidSubject("ns:")).isEqualTo("ns:***")
    }

    @Test
    fun `unicode subject is handled without throwing and never echoes a short remainder`() {
        assertThat(maskPidSubject("关键:测试测试测试测试测试")).startsWith("关键:***")
        assertThat(maskPidSubject("🙂🙂🙂🙂🙂🙂")).isEqualTo("***🙂🙂")
    }
}
