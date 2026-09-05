// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.AuthorizationNotFoundException
import com.openbank.account.application.usecase.AuthorizationNotOnAccountException
import com.openbank.libs.api.error.ApiError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit cover for the two mappers added in #5913. This asserts the SHAPE; that the shape is
 * actually reached over HTTP — the half a mocked test cannot see — is asserted by
 * `AccountAuthorizationLifecycleIT`, which drives the real endpoint and reads the status code.
 */
class AuthorizationNotFoundExceptionMapperTest {

    @Test
    fun `maps AuthorizationNotFoundException to a 404 AUTHORIZATION_NOT_FOUND ApiError`() {
        val response = AuthorizationNotFoundExceptionMapper()
            .toResponse(AuthorizationNotFoundException(UUID.randomUUID()))

        assertThat(response.status).isEqualTo(404)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(404)
        assertThat(error.code).isEqualTo("AUTHORIZATION_NOT_FOUND")
        assertThat(error.traceId).isNotBlank()
    }

    @Test
    fun `maps AuthorizationNotOnAccountException to a 404 too`() {
        val response = AuthorizationNotOnAccountExceptionMapper()
            .toResponse(AuthorizationNotOnAccountException(UUID.randomUUID(), UUID.randomUUID()))

        assertThat(response.status).isEqualTo(404)
        assertThat((response.entity as ApiError).code).isEqualTo("AUTHORIZATION_NOT_FOUND")
    }

    /**
     * The two responses must be indistinguishable, or the endpoint is an existence oracle: a
     * caller scoped to one account could otherwise tell "this authorization id exists elsewhere"
     * from "this id does not exist". Compares every field except the per-request traceId.
     */
    @Test
    fun `an unknown id and an id on another account are indistinguishable on the wire`() {
        val unknown = AuthorizationNotFoundExceptionMapper()
            .toResponse(AuthorizationNotFoundException(UUID.randomUUID())).entity as ApiError
        val otherAccount = AuthorizationNotOnAccountExceptionMapper()
            .toResponse(AuthorizationNotOnAccountException(UUID.randomUUID(), UUID.randomUUID())).entity as ApiError

        assertThat(otherAccount.status).isEqualTo(unknown.status)
        assertThat(otherAccount.code).isEqualTo(unknown.code)
        assertThat(otherAccount.message).isEqualTo(unknown.message)
        assertThat(otherAccount.details).isEqualTo(unknown.details)
    }
}
