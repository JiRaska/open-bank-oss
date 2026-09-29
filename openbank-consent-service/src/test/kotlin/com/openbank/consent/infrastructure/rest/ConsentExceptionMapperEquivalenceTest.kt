// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.rest

import com.openbank.consent.application.usecase.ConsentAlreadyActiveException
import com.openbank.consent.application.usecase.ConsentNotFoundException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ResourceConflictExceptionMapper
import com.openbank.libs.api.error.ResourceNotFoundExceptionMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #10911 phase 2: proves the shared libs-runtime mappers reproduce the deleted local
 * `ConsentNotFoundMapper`/`ConsentAlreadyActiveMapper` byte-for-byte on status, `code` and
 * `message` (traceId's source deliberately changes from `Ids.randomId()` to the correlation MDC,
 * per #10911 phase 1). This test fails to compile if the exceptions stop extending the libs-domain
 * bases -- checked manually before committing by reverting them to plain `RuntimeException` and
 * re-running `compileTestKotlin`, which errors with "type mismatch: inferred type is
 * ConsentNotFoundException but ResourceNotFoundException was expected".
 */
class ConsentExceptionMapperEquivalenceTest {

    @Test
    fun `ConsentNotFoundException maps to 404 NOT_FOUND, same as the deleted local mapper`() {
        val id = UUID.randomUUID()
        val response = ResourceNotFoundExceptionMapper().toResponse(ConsentNotFoundException(id))

        assertThat(response.status).isEqualTo(404)
        val error = response.entity as ApiError
        assertThat(error.code).isEqualTo("NOT_FOUND")
        assertThat(error.message).isEqualTo("Consent not found: $id")
    }

    @Test
    fun `ConsentAlreadyActiveException maps to 409 CONFLICT, same as the deleted local mapper`() {
        val id = UUID.randomUUID()
        val response = ResourceConflictExceptionMapper().toResponse(ConsentAlreadyActiveException(id))

        assertThat(response.status).isEqualTo(409)
        val error = response.entity as ApiError
        assertThat(error.code).isEqualTo("CONFLICT")
        assertThat(error.message).isEqualTo("Consent $id is already active")
    }
}
