// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.usecase.DomesticPaymentNotFoundException
import com.openbank.domestic.application.usecase.InvalidDomesticPaymentStateTransitionException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
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
// approval to be flipped back to APPROVED and replayed). Surfaced as a 409, matching the
// existing InvalidDomesticPaymentStateTransitionMapper convention for state-machine violations.
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
