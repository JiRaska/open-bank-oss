// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.rest

import com.openbank.cardprocessing.application.usecase.CardNotFoundException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * The ONE service-local mapper: an authorisation for a card card-issuance does not know is a 404,
 * and nothing in libs can know that.
 *
 * `IllegalArgumentException` is deliberately NOT mapped here — libs-runtime already maps it to 400
 * fleet-wide, and a service-local duplicate is what #526 exists to prevent.
 */
@Provider
class CardNotFoundExceptionMapper : ExceptionMapper<CardNotFoundException> {
    override fun toResponse(exception: CardNotFoundException): Response = Response.status(Response.Status.NOT_FOUND)
        .entity(mapOf("error" to "CARD_NOT_FOUND", "message" to exception.message))
        .build()
}
