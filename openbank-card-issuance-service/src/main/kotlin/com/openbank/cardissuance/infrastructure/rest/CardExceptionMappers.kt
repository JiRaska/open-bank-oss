// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.domain.model.CardEntitlementException
import com.openbank.cardissuance.domain.model.SecureDetailsForbiddenException
import com.openbank.cardissuance.domain.model.SecureDetailsNotStoredException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.domain.identifiers.Ids
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.MDC
import java.time.Instant

/**
 * Card-issuance business exceptions → HTTP. JAX-RS picks the most specific mapper, so these win
 * over libs' generic `GenericExceptionMapper` without displacing it (see `CommonExceptionMappers`).
 *
 * Each response carries the domain's own machine-readable `code`; the human message is secondary.
 * Nothing here ever touches a PAN or CVV — the secure-details exceptions are raised before any
 * decryption happens, and their messages name only the card id, type and status.
 */
private fun traceId(): String = (MDC.get("correlationId") as? String) ?: Ids.randomId().toString()

private fun apiError(status: Int, code: String, message: String) =
    ApiError(traceId = traceId(), status = status, code = code, message = message, timestamp = Instant.now())

/** A product rule forbids this issue — 409, the state of the world conflicts with the request. */
@Provider
class CardEntitlementExceptionMapper : ExceptionMapper<CardEntitlementException> {
    override fun toResponse(exception: CardEntitlementException): Response = Response.status(CONFLICT)
        .entity(apiError(CONFLICT, exception.code.name, exception.message ?: "Card entitlement rule violated"))
        .build()
}

/** The caller may not read this card's PAN — 403. */
@Provider
class SecureDetailsForbiddenExceptionMapper : ExceptionMapper<SecureDetailsForbiddenException> {
    override fun toResponse(exception: SecureDetailsForbiddenException): Response = Response.status(FORBIDDEN)
        .entity(apiError(FORBIDDEN, exception.code.name, exception.message ?: "Secure details not available"))
        .header("Cache-Control", "no-store")
        .build()
}

/** There is nothing stored to return — 404, distinct from "you may not see it". */
@Provider
class SecureDetailsNotStoredExceptionMapper : ExceptionMapper<SecureDetailsNotStoredException> {
    override fun toResponse(exception: SecureDetailsNotStoredException): Response = Response.status(NOT_FOUND)
        .entity(apiError(NOT_FOUND, exception.code.name, exception.message ?: "No stored card credential"))
        .header("Cache-Control", "no-store")
        .build()
}

private const val CONFLICT = 409
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
