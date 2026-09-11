// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.rest.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #8351 burn-down: the creation POST's idempotency handle is enforced by the DTO itself —
 * `idempotencyKey` is a non-default constructor parameter, so Jackson rejects a body that
 * omits it (MissingKotlinParameterException), which libs-runtime maps to 400. The spec
 * (openapi.yaml 1.6.0) documents exactly this. No Quarkus boot needed: the 400 happens in
 * deserialization, before the resource method runs.
 */
class CreateStandingOrderRequestIdempotencyTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    private val validBody = """
        {
          "idempotencyKey": "so-create-0001",
          "partyId": "11111111-1111-1111-1111-111111111111",
          "debitAccountId": "22222222-2222-2222-2222-222222222222",
          "creditorIban": "CZ6508000000192000145399",
          "creditorName": "Energie a.s.",
          "creditorBic": "GIBACZPX",
          "amountMinorUnits": 150000,
          "currency": "CZK",
          "frequency": "MONTHLY",
          "paymentType": "SEPA_CREDIT",
          "remittanceInfo": "electricity",
          "startDate": "2026-10-01",
          "endDate": null
        }
    """.trimIndent()

    private fun bodyWithout(field: String): String {
        val map = mapper.readValue<Map<String, Any?>>(validBody).toMutableMap()
        map.remove(field)
        return mapper.writeValueAsString(map)
    }

    @Test
    fun `a body without idempotencyKey is rejected`() {
        assertThatThrownBy { mapper.readValue<CreateStandingOrderRequest>(bodyWithout("idempotencyKey")) }
            .hasMessageContaining("idempotencyKey")
    }

    @Test
    fun `a body with idempotencyKey parses and carries it`() {
        val parsed = mapper.readValue<CreateStandingOrderRequest>(validBody)
        assertThat(parsed.idempotencyKey).isEqualTo("so-create-0001")
        assertThat(parsed.amountMinorUnits).isEqualTo(150000)
    }

    @Test
    fun `a zero or negative amount is rejected even though the primitive would deserialize`() {
        // Jackson fills a missing primitive with 0 — the DTO's init guard is what makes
        // the spec's `required: [amountMinorUnits]` true in effect (libs-runtime -> 400).
        assertThatThrownBy { mapper.readValue<CreateStandingOrderRequest>(bodyWithout("amountMinorUnits")) }
            .hasMessageContaining("amountMinorUnits")
        val zero = validBody.replace("\"amountMinorUnits\": 150000", "\"amountMinorUnits\": 0")
        assertThatThrownBy { mapper.readValue<CreateStandingOrderRequest>(zero) }
            .hasMessageContaining("amountMinorUnits")
    }

    @Test
    fun `the other DTO-required fields the corrected spec declares are really required`() {
        // Guards the spec-vs-DTO alignment this PR asserts: if a future DTO change drops one
        // of these from the constructor (or adds a default), the spec must move with it.
        listOf("partyId", "debitAccountId", "creditorIban", "creditorName", "currency", "paymentType", "startDate")
            .forEach { field ->
                val threw = runCatching { mapper.readValue<CreateStandingOrderRequest>(bodyWithout(field)) }.isFailure
                assertThat(threw).describedAs("omitting %s must fail deserialization", field).isTrue()
            }
    }
}
