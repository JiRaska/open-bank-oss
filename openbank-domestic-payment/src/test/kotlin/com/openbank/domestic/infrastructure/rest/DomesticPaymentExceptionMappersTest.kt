// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.usecase.DomesticPaymentIdempotencyConflictException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DomesticPaymentExceptionMappersTest {
    @Test
    fun `idempotency conflict is a machine-readable problem response`() {
        val response = DomesticPaymentIdempotencyConflictMapper()
            .toResponse(DomesticPaymentIdempotencyConflictException())

        assertThat(response.status).isEqualTo(409)
        assertThat(response.mediaType.toString()).isEqualTo("application/problem+json")
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any>
        assertThat(body["type"]).isEqualTo("urn:openbank:error:idempotency-key-reused")
        assertThat(body["code"]).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(body["status"]).isEqualTo(409)
    }
}
