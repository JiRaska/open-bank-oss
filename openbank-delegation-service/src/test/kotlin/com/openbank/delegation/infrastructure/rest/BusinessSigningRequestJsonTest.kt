// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.delegation.infrastructure.rest.dto.CreatePaymentApprovalRequest
import com.openbank.delegation.infrastructure.signing.JacksonSigningPayloadCodec
import jakarta.ws.rs.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BusinessSigningRequestJsonTest {
    private val mapper = jacksonObjectMapper()
    private val json = BusinessSigningRequestJson(mapper)

    @Test
    fun `claim and bound approval preserve exact floating JSON numbers`() {
        val amount = "99999999999999.99"
        val payload = """{"amount":"$amount","railRequest":{"amount":$amount}}"""
        val claim = json.readClaim("""{"payload":$payload}""")
        val approval = json.readApproval("""{"submissionKeyDigest":"fixture","payload":$payload}""")

        assertThat(approval.payload).isEqualTo(claim.payload)
        val codec = JacksonSigningPayloadCodec(mapper)
        assertThat(codec.canonical(claim.payload)).isEqualTo(payload)
        val rail = claim.payload?.get("railRequest") as Map<*, *>
        assertThat(rail["amount"]).isEqualTo(BigDecimal(amount))
        assertThat(mapper.writeValueAsString(rail)).isEqualTo("""{"amount":$amount}""")
    }

    @Test
    fun `legacy approval keeps the existing JSON decoding semantics`() {
        val request = """{"payload":{"railRequest":{"amount":100.00}}}"""
        val legacy = mapper.readValue(request, CreatePaymentApprovalRequest::class.java)
        assertThat(json.readApproval(request)).isEqualTo(legacy)
    }

    @Test
    fun `invalid JSON is refused at the request boundary`() {
        assertThatThrownBy { json.readClaim("{") }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { json.readApproval("{") }.isInstanceOf(BadRequestException::class.java)
    }
}
