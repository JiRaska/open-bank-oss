// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.infrastructure.rest

import com.openbank.balance.application.usecase.*
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
