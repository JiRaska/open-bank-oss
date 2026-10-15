// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.statecontribution

import com.openbank.pension.application.usecase.ReturnNotFoundException
import com.openbank.pension.application.usecase.ReturnReportNotFoundException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/** 404s for the state-contribution return types (#12382). */
class StateContributionExceptionMappers {

    @ServerExceptionMapper(ReturnNotFoundException::class, ReturnReportNotFoundException::class)
    fun notFound(e: RuntimeException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()
}
