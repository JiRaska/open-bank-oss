// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.application.usecase.BalanceNotFoundException
import com.openbank.balance.application.usecase.HoldNotFoundException
import com.openbank.balance.application.usecase.InsufficientFundsException
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

// SelfApprovalNotAllowedMapper / InvalidApprovalStateMapper (403/409) moved to
// openbank-libs-runtime's CommonExceptionMappers (issue #1394) — a service-local copy of the
// same exact type would collide non-deterministically with the shared one (issue #526).
