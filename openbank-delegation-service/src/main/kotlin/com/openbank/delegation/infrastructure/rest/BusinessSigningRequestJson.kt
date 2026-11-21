// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.openbank.delegation.infrastructure.rest.dto.CreatePaymentApprovalRequest
import com.openbank.delegation.infrastructure.rest.dto.DomesticSubmissionClaimBody
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException

/** Preserve decimal JSON numbers before the mode-bound payload is compared or fingerprinted. */
@ApplicationScoped
class BusinessSigningRequestJson(mapper: ObjectMapper) {
    private val approval = mapper.readerFor(CreatePaymentApprovalRequest::class.java)
    private val exactApproval = approval.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val exactClaim = mapper.readerFor(DomesticSubmissionClaimBody::class.java)
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun readApproval(request: String?): CreatePaymentApprovalRequest {
        val parsed = approval.readRequest<CreatePaymentApprovalRequest>(request)
        // Existing approvals must replay against the same canonical payload used before mode claims.
        return if (parsed.submissionKeyDigest == null) parsed else exactApproval.readRequest(request)
    }

    fun readClaim(request: String?): DomesticSubmissionClaimBody = exactClaim.readRequest(request)

    private fun <T : Any> ObjectReader.readRequest(request: String?): T {
        require(!request.isNullOrBlank()) { "request body is required" }
        val parsed = try {
            readValue<T?>(request)
        } catch (_: JsonProcessingException) {
            throw BadRequestException("Invalid request body")
        }
        return requireNotNull(parsed) { "request body is required" }
    }
}
