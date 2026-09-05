// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.out.AccountScreeningUnavailableException
import com.openbank.account.application.usecase.AccountOpeningBlockedByScreeningException
import com.openbank.libs.api.error.ApiError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

/**
 * Unit cover for the two mappers added in #8512. Asserts the SHAPE — and above all what the
 * body must NOT carry: the matched sanctions name and the partyId stay server-side, or the
 * endpoint is a free sanctions-list oracle. That the status is actually reached over HTTP is
 * the half a mocked test cannot see; it is asserted by driving the real endpoint (schemathesis
 * lane and the account-opening ITs).
 */
class ScreeningExceptionMapperTest {

    private val partyId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val matchedName = "A. Sanctioned Person"

    @Test
    fun `maps a screening refusal to 422 with a body that names neither the match nor the party`() {
        val response = AccountOpeningBlockedByScreeningExceptionMapper()
            .toResponse(AccountOpeningBlockedByScreeningException(partyId, matchedName))

        assertThat(response.status).isEqualTo(422)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(422)
        assertThat(error.code).isEqualTo("ACCOUNT_OPENING_BLOCKED")
        assertThat(error.traceId).isNotBlank()
        // The disclosure guard — the reason this is a dedicated mapper and not a re-parenting
        // to IllegalStateException, whose 422 mapper echoes exception.message on the wire.
        assertThat(error.message).doesNotContain(matchedName)
        assertThat(error.message).doesNotContain(partyId.toString())
        assertThat(error.details?.toString() ?: "").doesNotContain(matchedName)
    }

    @Test
    fun `maps screening unavailability to 503 with Retry-After and no upstream detail`() {
        val response = AccountScreeningUnavailableExceptionMapper()
            .toResponse(AccountScreeningUnavailableException(IOException("connection refused")))

        assertThat(response.status).isEqualTo(503)
        assertThat(response.getHeaderString("Retry-After")).isEqualTo("30")
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(503)
        assertThat(error.code).isEqualTo("SCREENING_UNAVAILABLE")
        assertThat(error.message).doesNotContain("connection refused")
    }

    @Test
    fun `a refusal with no matched name is indistinguishable from one with`() {
        val withMatch = AccountOpeningBlockedByScreeningExceptionMapper()
            .toResponse(AccountOpeningBlockedByScreeningException(partyId, matchedName)).entity as ApiError
        val withoutMatch = AccountOpeningBlockedByScreeningExceptionMapper()
            .toResponse(AccountOpeningBlockedByScreeningException(partyId, null)).entity as ApiError

        assertThat(withMatch.status).isEqualTo(withoutMatch.status)
        assertThat(withMatch.code).isEqualTo(withoutMatch.code)
        assertThat(withMatch.message).isEqualTo(withoutMatch.message)
        assertThat(withMatch.details).isEqualTo(withoutMatch.details)
    }
}
