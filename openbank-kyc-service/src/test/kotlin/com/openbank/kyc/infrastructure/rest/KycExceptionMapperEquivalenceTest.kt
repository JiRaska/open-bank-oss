// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.rest

import com.openbank.kyc.application.KycCaseConflictException
import com.openbank.kyc.application.KycCaseNotFoundException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ResourceConflictExceptionMapper
import com.openbank.libs.api.error.ResourceNotFoundExceptionMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #10911 phase 2: proves the shared libs-runtime mappers reproduce the deleted local
 * `KycNotFoundMapper`/`KycConflictMapper` byte-for-byte on status, `code` and `message` (the
 * fields the wire contract / pact matchers actually pin -- traceId's source changes from
 * `Ids.randomId()` to the correlation MDC, which is deliberate, per #10911 phase 1).
 *
 * This test FAILS TO COMPILE if `KycCaseNotFoundException`/`KycCaseConflictException` stop
 * extending the libs-domain bases (the mapper parameter types no longer match) -- that is the
 * "fails without the fix" proof: reverting the exception classes to plain `RuntimeException`
 * breaks this file at compile time (confirmed manually before committing: "type mismatch:
 * inferred type is KycCaseNotFoundException but ResourceNotFoundException was expected").
 */
class KycExceptionMapperEquivalenceTest {

    @Test
    fun `KycCaseNotFoundException maps to 404 NOT_FOUND, same as the deleted local mapper`() {
        val caseId = UUID.randomUUID()
        val exception = KycCaseNotFoundException(caseId)

        val response = ResourceNotFoundExceptionMapper().toResponse(exception)

        assertThat(response.status).isEqualTo(404)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(404)
        assertThat(error.code).isEqualTo("NOT_FOUND")
        assertThat(error.message).isEqualTo("KYC case not found: $caseId")
        assertThat(error.traceId).isNotBlank()
    }

    @Test
    fun `KycCaseConflictException maps to 409 CONFLICT, same as the deleted local mapper`() {
        val partyId = UUID.randomUUID()
        val existingCaseId = UUID.randomUUID()
        val exception = KycCaseConflictException(partyId, existingCaseId)

        val response = ResourceConflictExceptionMapper().toResponse(exception)

        assertThat(response.status).isEqualTo(409)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(409)
        assertThat(error.code).isEqualTo("CONFLICT")
        assertThat(error.message).isEqualTo("Party $partyId already has an active KYC case: $existingCaseId")
        assertThat(error.traceId).isNotBlank()
    }
}
