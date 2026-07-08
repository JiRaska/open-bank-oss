// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class NotFoundMapper : ExceptionMapper<NotFoundException> {
    override fun toResponse(e: NotFoundException): Response =
        Response.status(404).entity(mapOf("error" to (e.message ?: "Not found"))).build()
}

@Provider
class BadRequestMapper : ExceptionMapper<BadRequestException> {
    override fun toResponse(e: BadRequestException): Response =
        Response.status(400).entity(mapOf("error" to (e.message ?: "Bad request"))).build()
}

// ADR-0155: a checker can never decide their own PendingApproval — ApprovalStore.decide
// enforces this itself (defense-in-depth), surfaced here as a plain 403.
@Provider
class SelfApprovalNotAllowedMapper : ExceptionMapper<SelfApprovalNotAllowedException> {
    override fun toResponse(exception: SelfApprovalNotAllowedException): Response {
        val status = Response.Status.FORBIDDEN.statusCode
        return Response.status(status)
            .entity(
                ApiError(
                    // ADR-0106: a per-response correlation id, not a durable/indexed identifier — Ids.randomId().
                    Ids.randomId().toString(),
                    status,
                    ErrorCode.FORBIDDEN.code,
                    exception.message ?: "Forbidden",
                ),
            )
            .build()
    }
}

// Code review finding: decide()/markExecuted() now reject re-deciding or re-consuming an
// approval that isn't in the expected status (was previously unguarded, allowing an EXECUTED
// approval to be flipped back to APPROVED and replayed). Surfaced as a 409.
@Provider
class InvalidApprovalStateMapper : ExceptionMapper<InvalidApprovalStateException> {
    override fun toResponse(exception: InvalidApprovalStateException): Response {
        val status = Response.Status.CONFLICT.statusCode
        return Response.status(status)
            .entity(
                ApiError(Ids.randomId().toString(), status, ErrorCode.CONFLICT.code, exception.message ?: "Conflict"),
            )
            .build()
    }
}
