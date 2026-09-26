// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ResourceConflictExceptionMapper
import com.openbank.libs.api.error.ResourceNotFoundExceptionMapper
import com.openbank.pid.application.usecase.PartyAlreadyExistsException
import com.openbank.pid.application.usecase.PartyNotFoundException
import com.openbank.pid.application.usecase.RelationshipAlreadyExistsException
import com.openbank.pid.application.usecase.VerificationCaseNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #10911 phase 2: proves the shared libs-runtime mappers reproduce the deleted local
 * `PartyNotFoundMapper` / `PartyAlreadyExistsMapper` / `RelationshipAlreadyExistsMapper` /
 * `VerificationCaseNotFoundMapper` byte-for-byte on status, `code` and `message` (traceId's source
 * deliberately changes from `UUID.randomUUID()` to the correlation MDC, per #10911 phase 1). This
 * test fails to compile if any of the four exceptions stop extending the libs-domain bases --
 * checked manually before committing by reverting the classes to plain `RuntimeException` and
 * re-running `compileTestKotlin`, which errors with "type mismatch: inferred type is
 * PartyNotFoundException but ResourceNotFoundException was expected" (and the analogous error for
 * each of the other three).
 */
class PidResourceExceptionMapperEquivalenceTest {

    @Test
    fun `PartyNotFoundException maps to 404 NOT_FOUND, same as the deleted local mapper`() {
        val response = ResourceNotFoundExceptionMapper().toResponse(PartyNotFoundException("Party not found: p1"))

        assertThat(response.status).isEqualTo(404)
        val error = response.entity as ApiError
        assertThat(error.code).isEqualTo("NOT_FOUND")
        assertThat(error.message).isEqualTo("Party not found: p1")
    }

    @Test
    fun `PartyAlreadyExistsException maps to 409 CONFLICT, same as the deleted local mapper`() {
        val response = ResourceConflictExceptionMapper().toResponse(PartyAlreadyExistsException("Party already exists: p1"))

        assertThat(response.status).isEqualTo(409)
        val error = response.entity as ApiError
        assertThat(error.code).isEqualTo("CONFLICT")
        assertThat(error.message).isEqualTo("Party already exists: p1")
    }

    @Test
    fun `RelationshipAlreadyExistsException maps to 409 CONFLICT, same as the deleted local mapper`() {
        val response = ResourceConflictExceptionMapper().toResponse(
            RelationshipAlreadyExistsException("Relationship already exists: r1"),
        )

        assertThat(response.status).isEqualTo(409)
        val error = response.entity as ApiError
        assertThat(error.code).isEqualTo("CONFLICT")
        assertThat(error.message).isEqualTo("Relationship already exists: r1")
    }

    @Test
    fun `VerificationCaseNotFoundException maps to 404 NOT_FOUND, same as the deleted local mapper`() {
        val response = ResourceNotFoundExceptionMapper().toResponse(
            VerificationCaseNotFoundException("Verification case not found: c1"),
        )

        assertThat(response.status).isEqualTo(404)
        val error = response.entity as ApiError
        assertThat(error.code).isEqualTo("NOT_FOUND")
        assertThat(error.message).isEqualTo("Verification case not found: c1")
    }
}
