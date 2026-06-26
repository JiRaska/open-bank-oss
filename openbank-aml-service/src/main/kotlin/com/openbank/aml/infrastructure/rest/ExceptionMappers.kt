// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.aml.infrastructure.rest

import com.openbank.aml.application.usecase.AmlCaseNotFoundException
import com.openbank.aml.application.usecase.InvalidAmlCaseStateTransitionException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.util.UUID

@Provider
class AmlCaseNotFoundMapper : ExceptionMapper<AmlCaseNotFoundException> {
    override fun toResponse(exception: AmlCaseNotFoundException): Response = Response.status(404)
        .entity(ApiError(UUID.randomUUID().toString(), 404, ErrorCode.NOT_FOUND.code, exception.message ?: "Not found"))
        .build()
}

@Provider
class InvalidAmlCaseStateTransitionMapper : ExceptionMapper<InvalidAmlCaseStateTransitionException> {
    override fun toResponse(exception: InvalidAmlCaseStateTransitionException): Response = Response.status(409)
        .entity(ApiError(UUID.randomUUID().toString(), 409, ErrorCode.CONFLICT.code, exception.message ?: "Conflict"))
        .build()
}
