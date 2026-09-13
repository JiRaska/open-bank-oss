// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import io.quarkus.security.UnauthorizedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The 401 body, which until #8993 was Quarkus's bare `Not Authorized` string while every other
 * error was an [ApiError] document.
 *
 * Two properties matter and only one is about the status. A client parsing the error body needs a
 * document, which is why this mapper exists — but an authentication failure is also the last place
 * to become chatty, so the message must be a constant and must NOT carry the exception's own text.
 * Quarkus puts the failed mechanism or the missing permission there, and a mapper that echoed
 * `exception.message` would pass a status-only test while telling an unauthenticated caller which
 * door it just tried.
 */
class UnauthorizedExceptionMapperTest {

    @Test
    fun `maps to 401 with the standard envelope`() {
        val response = UnauthorizedExceptionMapper().toResponse(UnauthorizedException())
        val body = response.entity as ApiError

        assertThat(response.status).isEqualTo(401)
        assertThat(body.status).isEqualTo(401)
        assertThat(body.code).isEqualTo(ErrorCode.UNAUTHORIZED.code)
        assertThat(body.message).isEqualTo("Authentication required")
    }

    @Test
    fun `does not echo the exception message`() {
        val leaky = "bearer token expired for principal service-account-openbank-edge on /api/v1/transactions"

        val body = UnauthorizedExceptionMapper().toResponse(UnauthorizedException(leaky)).entity as ApiError

        assertThat(body.message).isEqualTo("Authentication required")
        assertThat(body.message).doesNotContain("token", "service-account", "/api/v1/transactions")
    }
}
