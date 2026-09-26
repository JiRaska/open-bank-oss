// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.api.error.ResourceConflictExceptionMapper
import com.openbank.libs.api.error.ResourceNotFoundExceptionMapper
import com.openbank.sepa.application.usecase.InvalidSepaPaymentStateTransitionException
import com.openbank.sepa.application.usecase.SepaPaymentNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #10911/#11059 phase 3 (money-path): proves the shared libs-runtime mappers reproduce the
 * deleted local `SepaPaymentNotFoundMapper`/`InvalidSepaPaymentStateTransitionMapper` byte-for-byte
 * on status, `code` and `message` (the fields the wire contract / pact matchers actually pin —
 * traceId's source changes from `Ids.randomId()` to the correlation MDC, which is deliberate, per
 * #10911 phase 1).
 *
 * This test FAILS TO COMPILE if `SepaPaymentNotFoundException`/
 * `InvalidSepaPaymentStateTransitionException` stop extending the libs-domain bases (the mapper
 * parameter types no longer match) — confirmed manually before committing (reverting the exception
 * classes to plain `RuntimeException` errors with "type mismatch: inferred type is
 * SepaPaymentNotFoundException but ResourceNotFoundException was expected").
 */
class SepaPaymentExceptionMapperEquivalenceTest {

    @Test
    fun `SepaPaymentNotFoundException maps to 404 NOT_FOUND, same as the deleted local mapper`() {
        val paymentId = UUID.randomUUID()
        val response = ResourceNotFoundExceptionMapper().toResponse(SepaPaymentNotFoundException(paymentId))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(404)
        assertThat(body.status).isEqualTo(404)
        assertThat(body.code).isEqualTo(ErrorCode.NOT_FOUND.code)
        assertThat(body.message).isEqualTo("SEPA payment not found: $paymentId")
        assertThat(body.traceId).isNotBlank()
    }

    @Test
    fun `InvalidSepaPaymentStateTransitionException maps to 409 CONFLICT, same as the deleted local mapper`() {
        val response = ResourceConflictExceptionMapper()
            .toResponse(InvalidSepaPaymentStateTransitionException("cannot transition from SETTLED to CREATED"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(409)
        assertThat(body.status).isEqualTo(409)
        assertThat(body.code).isEqualTo(ErrorCode.CONFLICT.code)
        assertThat(body.message).isEqualTo("cannot transition from SETTLED to CREATED")
    }
}
