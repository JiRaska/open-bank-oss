// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.vop.infrastructure.rest

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * A malformed request ⇒ 400. Covers both the DTO's own `require` checks and `Iban.of`'s check-digit
 * validation, which both surface as [IllegalArgumentException].
 *
 * The message is echoed because these are all statements about the *caller's own input* ("an IBAN
 * is at most 34 characters", "Invalid IBAN: …") — nothing about our accounts or our data leaks
 * through this path. A VoP *outcome* is never an error: an unknown IBAN is a 200 with `no_data`,
 * precisely so that a 404-vs-200 distinction cannot be used to enumerate which IBANs are ours.
 */
@Provider
class IllegalArgumentMapper : ExceptionMapper<IllegalArgumentException> {
    override fun toResponse(e: IllegalArgumentException): Response = Response.status(Response.Status.BAD_REQUEST)
        .entity(mapOf("error" to (e.message ?: "malformed request")))
        .build()
}
