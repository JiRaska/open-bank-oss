// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.infrastructure.rest

import com.openbank.wealth.application.port.out.HoldingNotFoundException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Only the two this service actually owns.
 *
 * `IllegalArgumentException` is mapped to 400 by libs-runtime fleet-wide, and a service-local
 * mapper for a JDK exception type libs already maps is forbidden (ADR-0049, #526). What is NOT
 * already covered is `IllegalStateException`: the aggregate raises it through `check()` when a
 * lifecycle rule is broken — withdrawing a pledged holding, revaluing a withdrawn one — and that
 * is a conflict with the current state, not a malformed request.
 */
class ExceptionMappers {

    @ServerExceptionMapper
    fun notFound(e: HoldingNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun conflict(e: IllegalStateException): Response =
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
}
