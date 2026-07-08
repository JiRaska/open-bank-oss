// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.AccountNotFoundException
import com.openbank.account.application.usecase.AccountUpdateConflictException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.util.UUID

// Generic IllegalArgument/IllegalState/Exception mappers are provided by openbank-libs
// (com.openbank.libs.api.error.CommonExceptionMappers) with log-correlated traceIds.
// Declaring them here too is a non-deterministic JAX-RS @Provider collision (ADR-0048/0049 D4).
@Provider
class AccountNotFoundExceptionMapper : ExceptionMapper<AccountNotFoundException> {
    override fun toResponse(exception: AccountNotFoundException): Response = Response.status(Response.Status.NOT_FOUND)
        .entity(
            ApiError(
                traceId = UUID.randomUUID().toString(),
                status = 404,
                code = "ACCOUNT_NOT_FOUND",
                message = exception.message ?: "Account not found",
            ),
        )
        .build()
}

// 409 concurrent-modification conflict (#465): a lifecycle update (freeze/unfreeze/close/
// activate/goal) raced another writer — the caller read a version the row no longer has.
// Dedicated type so the status is deterministic (see issue #526 on the IllegalStateException
// mapper collision between libs-runtime and services).
@Provider
class AccountUpdateConflictExceptionMapper : ExceptionMapper<AccountUpdateConflictException> {
    override fun toResponse(exception: AccountUpdateConflictException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(
                ApiError(
                    traceId = Ids.randomId().toString(),
                    status = 409,
                    code = "CONCURRENT_MODIFICATION",
                    message = exception.message ?: "Account was modified concurrently",
                ),
            )
            .build()
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
// existing AccountUpdateConflictExceptionMapper convention for state-machine violations.
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
