// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestFingerprintTest {

    @Test
    fun `same request yields the same 64-hex fingerprint`() {
        val a = RequestFingerprint.of("POST", "/api/v1/payments", """{"amount":"10.00"}""")
        assertThat(a).matches("[0-9a-f]{64}")
        assertThat(RequestFingerprint.of("post", "/api/v1/payments", """{"amount":"10.00"}""")).isEqualTo(a)
    }

    @Test
    fun `method, path and body each change the fingerprint`() {
        val base = RequestFingerprint.of("POST", "/api/v1/payments", """{"amount":"10.00"}""")
        assertThat(RequestFingerprint.of("PUT", "/api/v1/payments", """{"amount":"10.00"}""")).isNotEqualTo(base)
        assertThat(RequestFingerprint.of("POST", "/api/v1/transfers", """{"amount":"10.00"}""")).isNotEqualTo(base)
        assertThat(RequestFingerprint.of("POST", "/api/v1/payments", """{"amount":"99.00"}""")).isNotEqualTo(base)
    }

    @Test
    fun `known SHA-256 vector`() {
        // printf 'GET\n/\n' | shasum -a 256
        assertThat(RequestFingerprint.of("GET", "/", null))
            .isEqualTo("139fb16fdd19069e75f6f3dd0e619a89fb157dab65e8722c66e6b3fd43f7900c")
    }
}
