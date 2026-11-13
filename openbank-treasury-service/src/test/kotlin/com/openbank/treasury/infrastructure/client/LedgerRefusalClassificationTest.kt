// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.client

import io.mockk.mockk
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * #13883 / #14220 review: only the ledger's coded closed-day refusal, raised after its idempotency
 * replay check, proves no journal exists under the key. Every other failure must leave the intent
 * OPEN, because a REFUSED intent releases its day's close hold.
 */
class LedgerRefusalClassificationTest {
    private val adapter = LedgerPostingAdapter(mockk(relaxed = true), false)

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 408, 409, 422, 425, 429, 500, 502, 503])
    fun `a bare status is never a definitive refusal`(status: Int) {
        assertThat(adapter.isDefinitiveRefusal(WebApplicationException("ledger answered $status", status))).isFalse()
    }

    @Test
    fun `a GL validation 422 and a frozen-period 409 carry no ledger code and stay unknown`() {
        assertThat(adapter.isDefinitiveRefusal(WebApplicationException("GL account 1234 is disabled", 422))).isFalse()
        assertThat(adapter.isDefinitiveRefusal(WebApplicationException("Accounting period is frozen", 409))).isFalse()
    }

    @Test
    fun `the ledger's coded 409 ACCOUNTING_DAY_CLOSED is definitive, wrapped or not`() {
        val closed = WebApplicationException("""{"error":"day closed","code":"ACCOUNTING_DAY_CLOSED"}""", 409)
        assertThat(adapter.isDefinitiveRefusal(closed)).isTrue()
        assertThat(adapter.isDefinitiveRefusal(RuntimeException("post failed", closed))).isTrue()
        // The code on any status but 409 is not the ledger's refusal.
        assertThat(adapter.isDefinitiveRefusal(WebApplicationException("ACCOUNTING_DAY_CLOSED", 400))).isFalse()
    }

    @Test
    fun `no answer at all is unknown`() {
        assertThat(adapter.isDefinitiveRefusal(ProcessingException("connection reset"))).isFalse()
    }
}
