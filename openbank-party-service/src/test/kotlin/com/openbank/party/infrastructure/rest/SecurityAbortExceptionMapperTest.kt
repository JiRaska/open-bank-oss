// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.ForbiddenException
import io.quarkus.security.UnauthorizedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #8875: security aborts must render the JSON ApiError envelope — the built-in Quarkus
 * handling writes the exception message as plain text under the resource's negotiated
 * application/json content-type, which breaks any JSON-parsing client (and the anonymous
 * VoP party-lookup pact replay). Same defect class as account-service's #8803.
 */
class SecurityAbortExceptionMapperTest {

    @Test
    fun `maps UnauthorizedException to a 401 UNAUTHORIZED ApiError`() {
        val response = QuarkusUnauthorizedExceptionMapper()
            .toResponse(UnauthorizedException("Not Authenticated"))

        assertThat(response.status).isEqualTo(401)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(401)
        assertThat(error.code).isEqualTo("UNAUTHORIZED")
        // fixed message — the raw exception text is not leaked into the response
        assertThat(error.message).isEqualTo("Unauthorized")
        assertThat(error.traceId).isNotBlank()
    }

    @Test
    fun `maps AuthenticationFailedException to the same 401 envelope`() {
        val response = QuarkusAuthenticationFailedExceptionMapper()
            .toResponse(AuthenticationFailedException("bad token"))

        assertThat(response.status).isEqualTo(401)
        val error = response.entity as ApiError
        assertThat(error.code).isEqualTo("UNAUTHORIZED")
        assertThat(error.message).isEqualTo("Unauthorized")
    }

    @Test
    fun `maps ForbiddenException to a 403 FORBIDDEN ApiError`() {
        val response = QuarkusForbiddenExceptionMapper()
            .toResponse(ForbiddenException("wrong role"))

        assertThat(response.status).isEqualTo(403)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(403)
        assertThat(error.code).isEqualTo("FORBIDDEN")
        assertThat(error.message).isEqualTo("Forbidden")
    }
}
