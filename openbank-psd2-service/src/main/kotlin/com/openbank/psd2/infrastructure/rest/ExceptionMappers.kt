// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.psd2.application.usecase.ConsentNotFoundException
import com.openbank.psd2.application.usecase.ConsentUnauthorizedException
import com.openbank.psd2.application.usecase.InvalidPaymentProductException
import com.openbank.psd2.application.usecase.Psd2RequestFormatException
import com.openbank.psd2.application.usecase.TppNotAuthorizedException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

private fun tppMsg(category: String, code: String, text: String) =
    mapOf("tppMessages" to listOf(mapOf("category" to category, "code" to code, "text" to text)))

@Provider
class ConsentNotFoundMapper : ExceptionMapper<ConsentNotFoundException> {
    override fun toResponse(e: ConsentNotFoundException): Response =
        Response.status(404).entity(tppMsg("ERROR", "CONSENT_UNKNOWN", e.message ?: "Consent not found")).build()
}

@Provider
class ConsentUnauthorizedMapper : ExceptionMapper<ConsentUnauthorizedException> {
    override fun toResponse(e: ConsentUnauthorizedException): Response =
        Response.status(401).entity(tppMsg("ERROR", "CONSENT_INVALID", e.message ?: "Unauthorized")).build()
}

@Provider
class TppNotAuthorizedMapper : ExceptionMapper<TppNotAuthorizedException> {
    override fun toResponse(e: TppNotAuthorizedException): Response =
        Response.status(401).entity(tppMsg("ERROR", "CERTIFICATE_INVALID", e.message ?: "TPP not authorized")).build()
}

@Provider
class InvalidPaymentProductMapper : ExceptionMapper<InvalidPaymentProductException> {
    override fun toResponse(e: InvalidPaymentProductException): Response =
        Response.status(400).entity(tppMsg("ERROR", "PRODUCT_INVALID", e.message ?: "Invalid payment product")).build()
}

// Replaces ExceptionMapper<IllegalArgumentException> (issue #526): a service-local mapper for a
// JDK type openbank-libs-runtime already maps to a DIFFERENT response shape (its generic
// ApiError, not this service's Berlin Group tppMessages envelope) collides non-deterministically
// — both happen to answer 400, but which body shape a TPP client got was a per-request lottery.
@Provider
class Psd2RequestFormatMapper : ExceptionMapper<Psd2RequestFormatException> {
    override fun toResponse(e: Psd2RequestFormatException): Response = Response.status(Response.Status.BAD_REQUEST)
        .entity(tppMsg("ERROR", "FORMAT_ERROR", e.message ?: "Bad request"))
        .build()
}
