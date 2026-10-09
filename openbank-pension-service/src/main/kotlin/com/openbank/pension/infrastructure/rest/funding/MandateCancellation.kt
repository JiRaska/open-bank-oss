// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest.funding

import com.openbank.pension.application.usecase.ForeignDebtorAccountException
import com.openbank.pension.application.usecase.PaymentMandateNotFoundException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** What the SCA challenge of a mandate cancellation signs (#12378): this contract, this mandate. */
object PaymentMandateCancellation {
    fun documentHash(contractId: UUID, mandateId: UUID): String = MessageDigest.getInstance("SHA-256")
        .digest("pension-mandate-cancel|$contractId|$mandateId".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class MandateScaFailedException : RuntimeException("strong customer authentication failed for this cancellation")

class PaymentMandateExceptionMappers {
    @ServerExceptionMapper(PaymentMandateNotFoundException::class)
    fun notFound(e: PaymentMandateNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper(ForeignDebtorAccountException::class)
    fun foreignAccount(e: ForeignDebtorAccountException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper(MandateScaFailedException::class)
    fun scaFailed(e: MandateScaFailedException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to e.message)).build()
}
