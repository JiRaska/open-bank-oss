// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.openbank.domestic.infrastructure.rest.dto.CreateDelegatedDomesticPaymentRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.full.primaryConstructor

/** #16000 part 3 — the published bound-create contract is the one the resource deserializes. */
class DelegatedBoundCreateOpenApiTest {
    private val spec: JsonNode = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())
    private val operation = spec.path("paths").path("/api/v1/domestic-payments/delegated").path("post")
    private val schema = spec.path("components").path("schemas").path("CreateDelegatedDomesticPaymentRequest")

    @Test
    fun `request schema properties are exactly the DTO's`() {
        val dto = requireNotNull(CreateDelegatedDomesticPaymentRequest::class.primaryConstructor)
            .parameters.map { it.name }.toSet()
        assertThat(schema.path("properties").fieldNames().asSequence().toSet()).isEqualTo(dto)
        val required = schema.path("required").map { it.asText() }.toSet()
        assertThat(required).contains("reservationId", "delegationId", "actingPartyId", "amount", "currency")
        assertThat(dto).containsAll(required)
    }

    @Test
    fun `the route documents edge-only access, the key and every refusal it answers`() {
        assertThat(operation.path("operationId").asText()).isEqualTo("createDelegatedDomesticPayment")
        assertThat(operation.path("security")[0].has("bearerAuth")).isTrue()
        val key = operation.path("parameters").single { it.path("name").asText() == "Idempotency-Key" }
        assertThat(key.path("required").asBoolean()).isTrue()
        assertThat(operation.path("responses").fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("201", "400", "403", "409", "503")
        val codes = spec.path("components").path("schemas").path("DelegatedPaymentRefusal")
            .path("properties").path("code").path("enum").map { it.asText() }
        assertThat(codes).containsExactlyInAnyOrder(
            "RESERVATION_PROJECTION_PENDING",
            "RESERVATION_NOT_RESERVED",
            "RESERVATION_MISMATCH",
            "ACCOUNT_AUTHORITY_UNAVAILABLE",
            "IDEMPOTENCY_KEY_REUSED",
        )
    }

    @Test
    fun `the owner request schema still has no delegation fields`() {
        val owner = spec.path("components").path("schemas").path("CreateDomesticPaymentRequest").path("properties")
        listOf("reservationId", "delegationId", "actingPartyId").forEach { assertThat(owner.has(it)).isFalse() }
    }
}
