// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.rest

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class NotFoundMapper : ExceptionMapper<NotFoundException> {
    override fun toResponse(e: NotFoundException): Response =
        Response.status(404).entity(mapOf("error" to (e.message ?: "Not found"))).build()
}

@Provider
class BadRequestMapper : ExceptionMapper<BadRequestException> {
    override fun toResponse(e: BadRequestException): Response =
        Response.status(400).entity(mapOf("error" to (e.message ?: "Bad request"))).build()
}

// SelfApprovalNotAllowedMapper / InvalidApprovalStateMapper (403/409) moved to
// openbank-libs-runtime's CommonExceptionMappers (issue #1394) — a service-local copy of the
// same exact type would collide non-deterministically with the shared one (issue #526).
