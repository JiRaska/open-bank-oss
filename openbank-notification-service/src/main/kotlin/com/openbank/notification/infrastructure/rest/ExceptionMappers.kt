// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

// Generic IllegalArgument/IllegalState/Exception mappers are provided by openbank-libs
// (com.openbank.libs.api.error.CommonExceptionMappers) with log-correlated traceIds.
// Declaring them here too is a non-deterministic JAX-RS @Provider collision (ADR-0048/0049 D4).

// ADR-0155 (extended to opsmessage.compose by ADR-0176 D5): a checker can never decide their own
// PendingApproval — ApprovalStore.decide enforces this itself (defense-in-depth), surfaced here
// as a plain 403. Mirrors account-service's SelfApprovalNotAllowedMapper exactly.
@Provider
class SelfApprovalNotAllowedMapper : ExceptionMapper<SelfApprovalNotAllowedException> {
    override fun toResponse(exception: SelfApprovalNotAllowedException): Response =
        Response.status(Response.Status.FORBIDDEN)
            .entity(
                ApiError(
                    // ADR-0106: a per-response correlation id, not a durable/indexed identifier — Ids.randomId().
                    traceId = Ids.randomId().toString(),
                    status = 403,
                    code = "FORBIDDEN",
                    message = exception.message ?: "Forbidden",
                ),
            )
            .build()
}

// Rejects re-deciding or re-consuming an approval that isn't in the expected status (e.g. an
// EXECUTED approval flipped back to APPROVED and replayed). Surfaced as a 409, matching
// account-service's InvalidApprovalStateMapper convention for state-machine violations.
@Provider
class InvalidApprovalStateMapper : ExceptionMapper<InvalidApprovalStateException> {
    override fun toResponse(exception: InvalidApprovalStateException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(
                ApiError(
                    traceId = Ids.randomId().toString(),
                    status = 409,
                    code = "CONFLICT",
                    message = exception.message ?: "Conflict",
                ),
            )
            .build()
}
