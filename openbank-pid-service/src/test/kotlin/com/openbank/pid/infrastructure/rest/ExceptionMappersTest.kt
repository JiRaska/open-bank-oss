// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.application.usecase.InvalidPartyCaseTransitionException
import com.openbank.pid.domain.model.IllegalCaseTransition
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every JAX-RS [jakarta.ws.rs.ext.ExceptionMapper] maps its domain exception to the correct HTTP
 * status and echoes the exception message into the [ApiError] body. Pure mapping logic — no
 * Quarkus boot required.
 *
 * PartyNotFoundException / PartyAlreadyExistsException / RelationshipAlreadyExistsException /
 * VerificationCaseNotFoundException moved to libs-runtime's shared mappers (#10911 phase 2);
 * their equivalence is now covered by [PidResourceExceptionMapperEquivalenceTest].
 */
class ExceptionMappersTest {

    @Test
    fun `InvalidPartyCaseTransitionException maps to 400`() {
        val response = InvalidPartyCaseTransitionMapper().toResponse(
            InvalidPartyCaseTransitionException("bad transition"),
        )
        assertThat(response.status).isEqualTo(400)
        assertThat((response.entity as ApiError).message).isEqualTo("bad transition")
    }

    @Test
    fun `IllegalCaseTransition maps to 409 CONFLICT`() {
        val response = IllegalCaseTransitionMapper().toResponse(IllegalCaseTransition("illegal"))
        assertThat(response.status).isEqualTo(Response.Status.CONFLICT.statusCode)
        assertThat((response.entity as ApiError).message).isEqualTo("illegal")
    }

    @Test
    fun `PidVerificationException maps to 422 UNPROCESSABLE_ENTITY`() {
        val response = PidVerificationExceptionMapper().toResponse(PidVerificationException("bad presentation"))
        assertThat(response.status).isEqualTo(422)
        assertThat((response.entity as ApiError).message).isEqualTo("bad presentation")
    }
}
