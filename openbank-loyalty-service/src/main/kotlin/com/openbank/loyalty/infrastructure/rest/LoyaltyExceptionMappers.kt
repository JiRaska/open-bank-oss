// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.rest

import com.openbank.loyalty.domain.LoyaltyConflictException
import com.openbank.loyalty.domain.LoyaltyNotFoundException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * Only the two exceptions this service defines. `IllegalArgumentException` is deliberately NOT
 * mapped here — `openbank-libs-runtime` already maps it to 400 fleet-wide, and a service-local
 * mapper for it is the #526 defect: two mappers for one exception, and which one wins depends on
 * classpath order.
 */
@Provider
class LoyaltyNotFoundMapper : ExceptionMapper<LoyaltyNotFoundException> {
    override fun toResponse(exception: LoyaltyNotFoundException): Response = Response
        .status(Response.Status.NOT_FOUND)
        .entity(mapOf("error" to exception.message))
        .build()
}

@Provider
class LoyaltyConflictMapper : ExceptionMapper<LoyaltyConflictException> {
    override fun toResponse(exception: LoyaltyConflictException): Response = Response
        .status(Response.Status.CONFLICT)
        .entity(mapOf("error" to exception.message))
        .build()
}
