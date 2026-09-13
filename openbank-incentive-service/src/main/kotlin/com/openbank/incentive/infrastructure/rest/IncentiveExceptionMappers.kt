// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.rest

import com.openbank.incentive.domain.IncentiveConflict
import com.openbank.incentive.domain.IncentiveNotFound
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

data class IncentiveError(val error: String)

@Provider
class IncentiveConflictMapper : ExceptionMapper<IncentiveConflict> {
    override fun toResponse(exception: IncentiveConflict): Response =
        Response.status(Response.Status.CONFLICT).entity(IncentiveError(exception.message ?: "conflict")).build()
}

@Provider
class IncentiveNotFoundMapper : ExceptionMapper<IncentiveNotFound> {
    override fun toResponse(exception: IncentiveNotFound): Response =
        Response.status(Response.Status.NOT_FOUND).entity(IncentiveError(exception.message ?: "not found")).build()
}
