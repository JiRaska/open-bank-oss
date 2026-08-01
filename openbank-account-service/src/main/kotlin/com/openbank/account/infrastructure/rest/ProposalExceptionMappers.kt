// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.ProposalForbiddenException
import com.openbank.account.application.usecase.ProposalNotFoundException
import com.openbank.account.application.usecase.ProposalScaException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

private fun errorBody(status: Int, message: String?): Map<String, Any?> = mapOf(
    "status" to status,
    "error" to message,
)

@Provider
class ProposalNotFoundExceptionMapper : ExceptionMapper<ProposalNotFoundException> {
    override fun toResponse(exception: ProposalNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(errorBody(404, exception.message)).build()
}

@Provider
class ProposalForbiddenExceptionMapper : ExceptionMapper<ProposalForbiddenException> {
    override fun toResponse(exception: ProposalForbiddenException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(errorBody(403, exception.message)).build()
}

@Provider
class ProposalScaExceptionMapper : ExceptionMapper<ProposalScaException> {
    override fun toResponse(exception: ProposalScaException): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(errorBody(400, exception.message)).build()
}
