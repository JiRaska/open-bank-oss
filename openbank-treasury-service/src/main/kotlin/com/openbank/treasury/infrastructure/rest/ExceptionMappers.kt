// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.treasury.application.port.out.DealNotFoundException
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.model.LimitBreachedException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Only the types this service owns. `IllegalArgumentException` is 400 fleet-wide through
 * libs-runtime and must not be re-mapped here (ADR-0049, #526). `IllegalStateException` is the
 * aggregate's `check()` on a lifecycle rule — a conflict with the current state, so 409.
 *
 * Four-eyes and limit breaches are 422: the request is well-formed and the deal is in the right
 * state, but a business rule refuses it. A non-human principal attempting a person's step is 403.
 */
class ExceptionMappers {

    @ServerExceptionMapper
    fun notFound(e: DealNotFoundException): Response = error(Response.Status.NOT_FOUND.statusCode, "NOT_FOUND", e.message)

    @ServerExceptionMapper
    fun conflict(e: IllegalStateException): Response = error(Response.Status.CONFLICT.statusCode, "INVALID_STATE", e.message)

    @ServerExceptionMapper
    fun fourEyes(e: FourEyesViolationException): Response = error(UNPROCESSABLE, "FOUR_EYES_VIOLATION", e.message)

    @ServerExceptionMapper
    fun limit(e: LimitBreachedException): Response = error(UNPROCESSABLE, "LIMIT_BREACHED", e.message)

    @ServerExceptionMapper
    fun notPermitted(e: ActorNotPermittedException): Response =
        error(Response.Status.FORBIDDEN.statusCode, "ACTOR_NOT_PERMITTED", e.message)

    private fun error(status: Int, code: String, message: String?): Response =
        Response.status(status).entity(mapOf("error" to code, "message" to message)).build()

    private companion object {
        const val UNPROCESSABLE = 422
    }
}
