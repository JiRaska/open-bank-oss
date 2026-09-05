// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.sca.application.usecase.CredentialAlreadyEnrolledException
import com.openbank.sca.application.usecase.DeviceNotEnrolledException
import com.openbank.sca.application.usecase.DeviceOwnershipMismatchException
import com.openbank.sca.application.usecase.InvalidDeviceAssertionException
import com.openbank.sca.application.usecase.ScaChallengeAlreadyConsumedException
import com.openbank.sca.application.usecase.ScaChallengeExpiredException
import com.openbank.sca.application.usecase.ScaChallengeMaxAttemptsException
import com.openbank.sca.application.usecase.ScaChallengeNotApprovedException
import com.openbank.sca.application.usecase.ScaChallengeNotAwaitingException
import com.openbank.sca.application.usecase.ScaChallengeNotFoundException
import com.openbank.sca.application.usecase.ScaChallengePartyMismatchException
import com.openbank.sca.application.usecase.ScaDynamicLinkingMismatchException
import com.openbank.sca.application.usecase.ScaMethodNotDeliverableException
import com.openbank.sca.application.usecase.ScaVerificationFailedException
import com.openbank.sca.domain.model.ScaMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Every SCA exception → HTTP mapper is an authorization/state boundary: the wrong status (or a
 * leaked exception message) on a money-path auth service changes what the caller does next — a 404
 * masquerading as a 409 could turn a genuine "already consumed" replay into a silent retry. These
 * mappers were entirely uncovered, so the status/body contract went unverified.
 */
class ScaExceptionMapperTest {

    private val id: UUID = UUID.randomUUID()

    private fun bodyOf(response: jakarta.ws.rs.core.Response): ApiError = response.entity as ApiError

    @Test
    fun `not-found maps to 404 with NOT_FOUND and carries the exception message`() {
        val ex = ScaChallengeNotFoundException(id)
        val response = ScaNotFoundMapper().toResponse(ex)

        assertThat(response.status).isEqualTo(404)
        val body = bodyOf(response)
        assertThat(body.code).isEqualTo(ErrorCode.NOT_FOUND.code)
        assertThat(body.message).isEqualTo(ex.message)
        assertThat(body.traceId).isNotBlank()
    }

    @Test
    fun `expired maps to 422 VALIDATION_ERROR`() {
        val response = ScaExpiredMapper().toResponse(ScaChallengeExpiredException(id))
        assertThat(response.status).isEqualTo(422)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
    }

    @Test
    fun `method-not-deliverable maps to 422 VALIDATION_ERROR`() {
        val response = ScaMethodNotDeliverableMapper().toResponse(ScaMethodNotDeliverableException(ScaMethod.TOTP))
        assertThat(response.status).isEqualTo(422)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
    }

    @Test
    fun `max-attempts maps to 429 VALIDATION_ERROR`() {
        val response = ScaMaxAttemptsMapper().toResponse(ScaChallengeMaxAttemptsException(id))
        assertThat(response.status).isEqualTo(429)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
    }

    @Test
    fun `verification-failed maps to 401 UNAUTHORIZED`() {
        val response = ScaVerificationFailedMapper().toResponse(ScaVerificationFailedException(id))
        assertThat(response.status).isEqualTo(401)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.UNAUTHORIZED.code)
    }

    @Test
    fun `not-awaiting maps to 409 VALIDATION_ERROR`() {
        val response = ScaNotAwaitingMapper().toResponse(ScaChallengeNotAwaitingException(id))
        assertThat(response.status).isEqualTo(409)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
    }

    @Test
    fun `device-not-enrolled maps to 404 NOT_FOUND`() {
        val response = DeviceNotEnrolledMapper().toResponse(DeviceNotEnrolledException("cred-1"))
        assertThat(response.status).isEqualTo(404)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.NOT_FOUND.code)
    }

    @Test
    fun `credential-conflict maps to 409 CONFLICT`() {
        val response = DeviceCredentialConflictMapper().toResponse(CredentialAlreadyEnrolledException("cred-1"))
        assertThat(response.status).isEqualTo(409)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
    }

    @Test
    fun `device-ownership-mismatch maps to 403 FORBIDDEN`() {
        val response = DeviceOwnershipMismatchMapper().toResponse(DeviceOwnershipMismatchException("cred-1"))
        assertThat(response.status).isEqualTo(403)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.FORBIDDEN.code)
    }

    @Test
    fun `invalid-device-assertion maps to 401 UNAUTHORIZED`() {
        val response = InvalidDeviceAssertionMapper().toResponse(InvalidDeviceAssertionException(id))
        assertThat(response.status).isEqualTo(401)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.UNAUTHORIZED.code)
    }

    @Test
    fun `not-approved maps to the VALIDATION_ERROR status`() {
        val response = ScaNotApprovedMapper().toResponse(ScaChallengeNotApprovedException(id))
        assertThat(response.status).isEqualTo(ErrorCode.VALIDATION_ERROR.httpStatus)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
    }

    @Test
    fun `already-consumed maps to 409 CONFLICT`() {
        val response = ScaAlreadyConsumedMapper().toResponse(ScaChallengeAlreadyConsumedException(id))
        assertThat(response.status).isEqualTo(409)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
    }

    @Test
    fun `party-mismatch maps to 403 FORBIDDEN`() {
        val response = ScaPartyMismatchMapper().toResponse(ScaChallengePartyMismatchException(id))
        assertThat(response.status).isEqualTo(403)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.FORBIDDEN.code)
    }

    @Test
    fun `dynamic-linking-mismatch maps to 409 CONFLICT`() {
        val response = ScaDynamicLinkingMismatchMapper().toResponse(ScaDynamicLinkingMismatchException(id))
        assertThat(response.status).isEqualTo(409)
        assertThat(bodyOf(response).code).isEqualTo(ErrorCode.VALIDATION_ERROR.code)
    }
}
