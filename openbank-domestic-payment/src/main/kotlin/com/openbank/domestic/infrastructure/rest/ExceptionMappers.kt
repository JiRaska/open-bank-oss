// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.usecase.DomesticPaymentNotFoundException
import com.openbank.domestic.application.usecase.InvalidDomesticPaymentStateTransitionException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.util.UUID

@Provider
class DomesticPaymentNotFoundMapper : ExceptionMapper<DomesticPaymentNotFoundException> {
    override fun toResponse(exception: DomesticPaymentNotFoundException): Response = Response.status(404)
        .entity(ApiError(UUID.randomUUID().toString(), 404, ErrorCode.NOT_FOUND.code, exception.message ?: "Not found"))
        .build()
}

@Provider
class InvalidDomesticPaymentStateTransitionMapper : ExceptionMapper<InvalidDomesticPaymentStateTransitionException> {
    override fun toResponse(exception: InvalidDomesticPaymentStateTransitionException): Response = Response.status(409)
        .entity(ApiError(UUID.randomUUID().toString(), 409, ErrorCode.CONFLICT.code, exception.message ?: "Conflict"))
        .build()
}
