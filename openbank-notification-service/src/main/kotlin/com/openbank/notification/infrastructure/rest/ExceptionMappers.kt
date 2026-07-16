// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

// ADR-0155/ADR-0176 D5: a checker can never decide their own PendingApproval — ApprovalStore.decide
// enforces this itself (defense-in-depth), surfaced here as a plain 403. Entity shape matches this
// service's existing convention (NotificationResource/DeviceResource: {"code", "message"}), not the
// shared ApiError DTO other services use — kept local rather than mixing two error shapes in one API.
@Provider
class SelfApprovalNotAllowedMapper : ExceptionMapper<SelfApprovalNotAllowedException> {
    override fun toResponse(exception: SelfApprovalNotAllowedException): Response =
        Response.status(Response.Status.FORBIDDEN)
            .entity(mapOf("code" to "FORBIDDEN", "message" to (exception.message ?: "Forbidden")))
            .build()
}

// decide()/markExecuted() reject re-deciding or re-consuming an approval that isn't in the
// expected status (e.g. an EXECUTED approval flipped back to APPROVED and replayed).
@Provider
class InvalidApprovalStateMapper : ExceptionMapper<InvalidApprovalStateException> {
    override fun toResponse(exception: InvalidApprovalStateException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(mapOf("code" to "CONFLICT", "message" to (exception.message ?: "Conflict")))
            .build()
}
