// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.domain.model.FourEyesViolationException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Only the exceptions this service owns. `IllegalArgumentException` is mapped to 400 fleet-wide by
 * libs-runtime and must not be re-mapped here (#526); `IllegalStateException` is a rule broken by
 * the current state — a closed fund, a NAV already published — and is a 409.
 */
class ExceptionMappers {

    @ServerExceptionMapper
    fun notFound(e: NotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun conflict(e: IllegalStateException): Response =
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()

    /** A concurrent writer changed a holding between our read and our write; nothing was applied. */
    @ServerExceptionMapper
    fun staleHolding(e: jakarta.persistence.OptimisticLockException): Response =
        Response.status(Response.Status.CONFLICT).entity(
            mapOf(
                "error" to "concurrent update, retry",
                "cause" to e.javaClass.simpleName,
            ),
        ).build()

    @ServerExceptionMapper
    fun staleHoldingHibernate(e: org.hibernate.StaleStateException): Response =
        Response.status(Response.Status.CONFLICT).entity(
            mapOf(
                "error" to "concurrent update, retry",
                "cause" to e.javaClass.simpleName,
            ),
        ).build()

    /** The maker tried to be their own checker: forbidden for this caller, whatever their role. */
    /** No published NAV backs the period yet: not an error in the request, and never a report of zeroes. */
    @ServerExceptionMapper
    fun notReportable(e: com.openbank.pensionfund.domain.model.PeriodNotReportableException): Response =
        Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun fourEyes(e: FourEyesViolationException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to e.message)).build()
}
