// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.client

import jakarta.ws.rs.core.MultivaluedHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CallerTokenClientHeadersFactoryTest {
    private val factory = CallerTokenClientHeadersFactory()

    private fun headers(vararg pairs: Pair<String, String>) =
        MultivaluedHashMap<String, String>().apply { pairs.forEach { (k, v) -> add(k, v) } }

    @Test
    fun `forwards the caller's Authorization header verbatim`() {
        val out = factory.update(headers("authorization" to "Bearer person"), headers("X-Correlation-Id" to "c"))
        assertThat(out.getFirst("Authorization")).isEqualTo("Bearer person")
        assertThat(out.getFirst("X-Correlation-Id")).isEqualTo("c")
    }

    @Test
    fun `an Authorization header set elsewhere on the call is replaced by the caller's, never kept`() {
        val out = factory.update(
            headers("Authorization" to "Bearer person"),
            headers(
                "Authorization" to "Bearer service",
            ),
        )
        assertThat(out["Authorization"]).containsExactly("Bearer person")
    }

    @Test
    fun `no caller header means no Authorization at all, so audit-service answers 401`() {
        val out = factory.update(headers(), headers("Authorization" to "Bearer service"))
        assertThat(out.containsKey("Authorization")).isFalse()
    }
}
