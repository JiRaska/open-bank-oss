// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ResourceConflictExceptionMapper
import com.openbank.libs.api.error.ResourceNotFoundExceptionMapper
import com.openbank.party.application.usecase.PartyAlreadyExistsException
import com.openbank.party.application.usecase.PartyNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #10911 phase 2: proves the shared libs-runtime mappers reproduce the deleted local
 * `PartyNotFoundMapper`/`PartyAlreadyExistsMapper` byte-for-byte on status, `code` and `message`
 * (traceId's source deliberately changes from `Ids.randomId()` to the correlation MDC, per phase
 * 1). This test fails to compile if the exceptions stop extending the libs-domain bases -- checked
 * manually before committing by reverting them to plain `RuntimeException` and re-running
 * `compileTestKotlin`, which errors with "type mismatch: inferred type is PartyNotFoundException
 * but ResourceNotFoundException was expected".
 */
class PartyExceptionMapperEquivalenceTest {

    @Test
    fun `PartyNotFoundException maps to 404 NOT_FOUND, same as the deleted local mapper`() {
        val id = UUID.randomUUID()
        val response = ResourceNotFoundExceptionMapper().toResponse(PartyNotFoundException(id))

        assertThat(response.status).isEqualTo(404)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(404)
        assertThat(error.code).isEqualTo("NOT_FOUND")
        assertThat(error.message).isEqualTo("Party not found: $id")
        assertThat(error.traceId).isNotBlank()
    }

    @Test
    fun `PartyAlreadyExistsException maps to 409 CONFLICT, same as the deleted local mapper`() {
        val response = ResourceConflictExceptionMapper().toResponse(PartyAlreadyExistsException("a@b.com"))

        assertThat(response.status).isEqualTo(409)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(409)
        assertThat(error.code).isEqualTo("CONFLICT")
        assertThat(error.message).isEqualTo("Party with email already exists: a@b.com")
        assertThat(error.traceId).isNotBlank()
    }
}
