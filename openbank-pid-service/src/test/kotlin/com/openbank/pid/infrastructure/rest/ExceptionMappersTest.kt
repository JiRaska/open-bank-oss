// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.application.usecase.InvalidPartyCaseTransitionException
import com.openbank.pid.application.usecase.PartyAlreadyExistsException
import com.openbank.pid.application.usecase.PartyNotFoundException
import com.openbank.pid.application.usecase.RelationshipAlreadyExistsException
import com.openbank.pid.application.usecase.VerificationCaseNotFoundException
import com.openbank.pid.domain.model.IllegalCaseTransition
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every JAX-RS [jakarta.ws.rs.ext.ExceptionMapper] maps its domain exception to the correct HTTP
 * status and echoes the exception message into the [ApiError] body. Pure mapping logic — no
 * Quarkus boot required.
 */
class ExceptionMappersTest {

    @Test
    fun `PartyNotFoundException maps to 404 with the exception message`() {
        val response = PartyNotFoundMapper().toResponse(PartyNotFoundException("party missing"))
        assertThat(response.status).isEqualTo(404)
        assertThat((response.entity as ApiError).message).isEqualTo("party missing")
    }

    @Test
    fun `PartyAlreadyExistsException maps to 409`() {
        val response = PartyAlreadyExistsMapper().toResponse(PartyAlreadyExistsException("dup party"))
        assertThat(response.status).isEqualTo(409)
        assertThat((response.entity as ApiError).message).isEqualTo("dup party")
    }

    @Test
    fun `RelationshipAlreadyExistsException maps to 409`() {
        val response = RelationshipAlreadyExistsMapper().toResponse(
            RelationshipAlreadyExistsException("dup relationship"),
        )
        assertThat(response.status).isEqualTo(409)
        assertThat((response.entity as ApiError).message).isEqualTo("dup relationship")
    }

    @Test
    fun `InvalidPartyCaseTransitionException maps to 400`() {
        val response = InvalidPartyCaseTransitionMapper().toResponse(
            InvalidPartyCaseTransitionException("bad transition"),
        )
        assertThat(response.status).isEqualTo(400)
        assertThat((response.entity as ApiError).message).isEqualTo("bad transition")
    }

    @Test
    fun `VerificationCaseNotFoundException maps to 404`() {
        val response = VerificationCaseNotFoundMapper().toResponse(
            VerificationCaseNotFoundException("case missing"),
        )
        assertThat(response.status).isEqualTo(Response.Status.NOT_FOUND.statusCode)
        assertThat((response.entity as ApiError).message).isEqualTo("case missing")
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
