// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.application.usecase.BalanceNotFoundException
import com.openbank.balance.application.usecase.HoldNotFoundException
import com.openbank.balance.application.usecase.InsufficientFundsException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class BalanceNotFoundMapper : ExceptionMapper<BalanceNotFoundException> {
    override fun toResponse(e: BalanceNotFoundException): Response =
        Response.status(404).entity(mapOf("error" to "NOT_FOUND", "message" to e.message)).build()
}

@Provider
class InsufficientFundsMapper : ExceptionMapper<InsufficientFundsException> {
    override fun toResponse(e: InsufficientFundsException): Response =
        Response.status(422).entity(mapOf("error" to "INSUFFICIENT_FUNDS", "message" to e.message)).build()
}

@Provider
class HoldNotFoundMapper : ExceptionMapper<HoldNotFoundException> {
    override fun toResponse(e: HoldNotFoundException): Response =
        Response.status(404).entity(mapOf("error" to "NOT_FOUND", "message" to e.message)).build()
}

// ADR-0155: a checker can never decide their own PendingApproval — ApprovalStore.decide
// enforces this itself (defense-in-depth), surfaced here as a plain 403.
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

// Code review finding: decide()/markExecuted() now reject re-deciding or re-consuming an
// approval that isn't in the expected status (was previously unguarded, allowing an EXECUTED
// approval to be flipped back to APPROVED and replayed). Surfaced as a 409, matching the
// existing BalanceNotFoundMapper-style convention for domain-exception mapping.
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
