// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Holds [PlatformErrorCode] to its committed baseline (ADR-0326): a published code is never removed,
 * renamed or re-categorised, and a new one is a deliberate, reviewed line in the baseline file.
 */
class PlatformErrorCodeCatalogueTest {

    private val baseline: Map<String, String> =
        checkNotNull(javaClass.getResourceAsStream("/error-codes/platform.baseline")) { "baseline file is missing" }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split("\t").let { it[0] to it[1] } }

    private val catalogue: Map<String, String> = PlatformErrorCode.entries.associate { it.code to it.category.name }

    @Test
    fun `the baseline is not empty`() {
        // Without this, a baseline that failed to parse would make both directions below vacuous.
        assertThat(baseline).hasSizeGreaterThanOrEqualTo(12)
    }

    @Test
    fun `no published code was removed, renamed or re-categorised`() {
        baseline.forEach { (code, category) ->
            assertThat(catalogue).`as`("published code $code").containsEntry(code, category)
        }
    }

    @Test
    fun `every code in the enum is recorded in the baseline`() {
        assertThat(baseline.keys).containsAll(catalogue.keys)
    }

    @Test
    fun `every code is well-formed and spelled as its enum name`() {
        PlatformErrorCode.entries.forEach {
            assertThat(ErrorCode.isWellFormed(it.code)).`as`(it.name).isTrue()
            assertThat(it.code).isEqualTo(it.name)
            assertThat(it.title).isNotBlank()
        }
    }

    @Test
    fun `codes are unique`() {
        assertThat(PlatformErrorCode.entries.map { it.code }).doesNotHaveDuplicates()
    }

    @Test
    fun `retryability follows the category unless the code overrides it`() {
        assertThat(PlatformErrorCode.CONFLICT.retryable).isFalse()
        assertThat(PlatformErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS.retryable).isTrue()
        assertThat(PlatformErrorCode.SERVICE_UNAVAILABLE.retryable).isTrue()
        assertThat(PlatformErrorCode.RATE_LIMIT_EXCEEDED.retryable).isTrue()
        assertThat(PlatformErrorCode.INTERNAL_ERROR.retryable).isFalse()
    }

    @Test
    fun `the wire format rejects what is not a code`() {
        listOf("not_found", "Not Found", "NOT-FOUND", "ACCOUNT.NOT_FOUND", "", "_X", "X_", "9X", "A".repeat(65))
            .forEach { assertThat(ErrorCode.isWellFormed(it)).`as`("'$it'").isFalse() }
        listOf("NOT_FOUND", "HTTP_405", "X", "A".repeat(64))
            .forEach { assertThat(ErrorCode.isWellFormed(it)).`as`("'$it'").isTrue() }
    }
}
