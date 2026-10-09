// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest

import com.openbank.pension.application.port.out.ContractNotFoundException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Only what this service owns. `IllegalArgumentException` (including `PackNotFoundException`) is
 * already a 400 through libs-runtime and must not be re-mapped here (#526). A broken lifecycle rule
 * surfaces as `IllegalStateException` from `check()` — a conflict with the current state.
 */
class ExceptionMappers {

    @ServerExceptionMapper
    fun notFound(e: ContractNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun conflict(e: IllegalStateException): Response =
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
}
