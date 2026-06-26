// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepainstant.infrastructure.rest

import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.BadRequestException
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
