// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest

import com.openbank.aml.application.usecase.AmlCaseNotFoundException
import com.openbank.aml.application.usecase.InvalidAmlCaseStateTransitionException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ResourceConflictExceptionMapper
import com.openbank.libs.api.error.ResourceNotFoundExceptionMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #10911 phase 2: proves the shared libs-runtime mappers reproduce the deleted local
 * `AmlCaseNotFoundMapper`/`InvalidAmlCaseStateTransitionMapper` byte-for-byte on status, `code`
 * and `message` (the fields the wire contract / pact matchers actually pin — traceId's source
 * changes from `Ids.randomId()` to the correlation MDC, which is deliberate, per #10911 phase 1).
 *
 * This test FAILS TO COMPILE if `AmlCaseNotFoundException`/`InvalidAmlCaseStateTransitionException`
 * stop extending the libs-domain bases (the mapper parameter types no longer match) — that is the
 * "fails without the fix" proof: reverting the exception classes to plain `RuntimeException` (as
 * they were before this PR) breaks this file at compile time, which was confirmed manually before
 * committing (build error: "type mismatch: inferred type is AmlCaseNotFoundException but
 * ResourceNotFoundException was expected").
 */
class AmlCaseExceptionMapperEquivalenceTest {

    @Test
    fun `AmlCaseNotFoundException maps to 404 NOT_FOUND, same as the deleted local mapper`() {
        val caseId = UUID.randomUUID()
        val exception = AmlCaseNotFoundException(caseId)

        val response = ResourceNotFoundExceptionMapper().toResponse(exception)

        assertThat(response.status).isEqualTo(404)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(404)
        assertThat(error.code).isEqualTo("NOT_FOUND")
        assertThat(error.message).isEqualTo("AML case not found: $caseId")
        assertThat(error.traceId).isNotBlank()
    }

    @Test
    fun `InvalidAmlCaseStateTransitionException maps to 409 CONFLICT, same as the deleted local mapper`() {
        val exception = InvalidAmlCaseStateTransitionException("case already decided")

        val response = ResourceConflictExceptionMapper().toResponse(exception)

        assertThat(response.status).isEqualTo(409)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(409)
        assertThat(error.code).isEqualTo("CONFLICT")
        assertThat(error.message).isEqualTo("case already decided")
        assertThat(error.traceId).isNotBlank()
    }
}
