// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.AccountNotFoundException
import com.openbank.account.application.usecase.AccountUpdateConflictException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ResourceConflictExceptionMapper
import com.openbank.libs.api.error.ResourceNotFoundExceptionMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #10911/#11059 phase 3 (money-path): proves the shared libs-runtime mappers reproduce the deleted
 * local `AccountNotFoundExceptionMapper`/`AccountUpdateConflictExceptionMapper` byte-for-byte on
 * status, `code` and `message` (the fields the wire contract / pact matchers actually pin —
 * traceId's source changes from a random UUID to the correlation MDC, which is deliberate, per
 * #10911 phase 1). Both exceptions keep their own domain-specific `code`
 * ("ACCOUNT_NOT_FOUND"/"CONCURRENT_MODIFICATION") via the libs-domain base's `code` constructor
 * parameter, exactly as the deleted local mappers rendered.
 *
 * This test FAILS TO COMPILE if `AccountNotFoundException`/`AccountUpdateConflictException` stop
 * extending the libs-domain bases (the mapper parameter types no longer match) — confirmed
 * manually before committing (reverting the exception classes to plain `RuntimeException` errors
 * with "type mismatch: inferred type is AccountNotFoundException but ResourceNotFoundException
 * was expected").
 */
class AccountExceptionMapperEquivalenceTest {

    @Test
    fun `AccountNotFoundException maps to 404 ACCOUNT_NOT_FOUND, same as the deleted local mapper`() {
        val response = ResourceNotFoundExceptionMapper().toResponse(AccountNotFoundException("Account not found: 42"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(404)
        assertThat(body.status).isEqualTo(404)
        assertThat(body.code).isEqualTo("ACCOUNT_NOT_FOUND")
        assertThat(body.message).isEqualTo("Account not found: 42")
        assertThat(body.traceId).isNotBlank()
    }

    @Test
    fun `AccountUpdateConflictException maps to 409 CONCURRENT_MODIFICATION, same as the deleted local mapper`() {
        val response = ResourceConflictExceptionMapper()
            .toResponse(AccountUpdateConflictException("Account was modified concurrently"))
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(409)
        assertThat(body.status).isEqualTo(409)
        assertThat(body.code).isEqualTo("CONCURRENT_MODIFICATION")
        assertThat(body.message).isEqualTo("Account was modified concurrently")
        assertThat(body.traceId).isNotBlank()
    }
}
