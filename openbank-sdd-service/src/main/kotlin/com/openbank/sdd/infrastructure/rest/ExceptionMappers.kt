// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.rest

import com.openbank.sdd.application.usecase.MandateNotFoundException
import com.openbank.sdd.domain.lifecycle.IllegalMandateTransition
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/** Unknown mandate id ⇒ 404. */
@Provider
class MandateNotFoundMapper : ExceptionMapper<MandateNotFoundException> {
    override fun toResponse(e: MandateNotFoundException): Response = Response.status(Response.Status.NOT_FOUND)
        .entity(mapOf("error" to e.message, "mandateId" to e.mandateId.toString()))
        .build()
}

/** Illegal lifecycle transition ⇒ 409 Conflict. */
@Provider
class IllegalMandateTransitionMapper : ExceptionMapper<IllegalMandateTransition> {
    override fun toResponse(e: IllegalMandateTransition): Response = Response.status(Response.Status.CONFLICT)
        .entity(mapOf("error" to e.message))
        .build()
}

/** ADR-0335 D6: a scoped initiator acting outside its creditor identifier or subject party. */
@Provider
class MandateScopeViolationMapper :
    ExceptionMapper<com.openbank.sdd.application.usecase.MandateScopeViolationException> {
    override fun toResponse(e: com.openbank.sdd.application.usecase.MandateScopeViolationException): Response =
        Response.status(
            Response.Status.FORBIDDEN,
        ).entity(mapOf("error" to (e.message ?: "outside caller scope"))).build()
}
