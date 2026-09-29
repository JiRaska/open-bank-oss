// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class DelegationLifecycleEventPayloadTest {
    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val grant = UUID.fromString("00000000-0000-0000-0000-000000000201")
    private val grantor = UUID.fromString("00000000-0000-0000-0000-000000000202")
    private val grantee = UUID.fromString("00000000-0000-0000-0000-000000000203")
    private val account = UUID.fromString("00000000-0000-0000-0000-000000000204")
    private val at = Instant.parse("2026-09-01T10:00:00Z")
    private val validFrom = OffsetDateTime.parse("2026-09-01T10:00:00Z")
    private val capability = setOf(DelegationCapability.SAVINGS_PROPOSE_WITHDRAW)

    @Test
    fun `offered event carries exact projection and approval terms`() {
        val event = DelegationOffered(
            grant, 3, grantor, grantee, DelegationResourceType.SAVINGS_GOAL, account,
            capability, ApprovalPolicy.N_OF_M, 2, validFrom, validFrom.plusDays(7),
            EventMoney(BigDecimal("250.00"), "CZK"), at,
        )
        val node = mapper.readTree(mapper.writeValueAsString(event))

        assertCommonProjection(node, "DelegationOffered")
        assertThat(node["approvalPolicy"].asText()).isEqualTo("N_OF_M")
        assertThat(node["requiredApprovals"].asInt()).isEqualTo(2)
        assertThat(node["validFrom"].asText()).isEqualTo("2026-09-01T10:00:00Z")
        assertThat(node["validTo"].asText()).isEqualTo("2026-09-08T10:00:00Z")
        assertThat(node["perTransactionLimit"]["amount"].decimalValue()).isEqualByComparingTo("250.00")
        assertThat(node["perTransactionLimit"]["currency"].asText()).isEqualTo("CZK")
    }

    @Test
    fun `reinstated event carries renewed projection and approval terms`() {
        val event = DelegationReinstated(
            grant, 3, grantor, grantee, DelegationResourceType.SAVINGS_GOAL, account,
            capability, ApprovalPolicy.N_OF_M, 2, validFrom, validFrom.plusDays(7),
            EventMoney(BigDecimal("250.00"), "CZK"), at,
        )
        val node = mapper.readTree(mapper.writeValueAsString(event))

        assertCommonProjection(node, "DelegationReinstated")
        assertThat(node["approvalPolicy"].asText()).isEqualTo("N_OF_M")
        assertThat(node["requiredApprovals"].asInt()).isEqualTo(2)
        assertThat(node["validFrom"].asText()).isEqualTo("2026-09-01T10:00:00Z")
        assertThat(node["validTo"].asText()).isEqualTo("2026-09-08T10:00:00Z")
        assertThat(node["perTransactionLimit"]["amount"].decimalValue()).isEqualByComparingTo("250.00")
        assertThat(node["perTransactionLimit"]["currency"].asText()).isEqualTo("CZK")
    }

    @Test
    fun `revoked event carries affected authority and reason`() {
        val event = DelegationRevoked(
            grant, 3, grantor, grantee, DelegationResourceType.SAVINGS_GOAL, account,
            capability, "owner-revoked", at,
        )
        val node = mapper.readTree(mapper.writeValueAsString(event))

        assertCommonProjection(node, "DelegationRevoked")
        assertThat(node["reason"].asText()).isEqualTo("owner-revoked")
    }

    @Test
    fun `suspended event carries affected authority and reason`() {
        val event = DelegationSuspended(
            grant, 3, grantor, grantee, DelegationResourceType.SAVINGS_GOAL, account,
            capability, "risk-hold", at,
        )
        val node = mapper.readTree(mapper.writeValueAsString(event))

        assertCommonProjection(node, "DelegationSuspended")
        assertThat(node["reason"].asText()).isEqualTo("risk-hold")
    }

    private fun assertCommonProjection(node: com.fasterxml.jackson.databind.JsonNode, type: String) {
        assertThat(node["aggregateId"].asText()).isEqualTo(grant.toString())
        assertThat(node["aggregateType"].asText()).isEqualTo("DelegationGrant")
        assertThat(node["eventType"].asText()).isEqualTo(type)
        assertThat(node["version"].asLong()).isEqualTo(1)
        assertThat(node["lifecycleRevision"].asLong()).isEqualTo(3)
        assertThat(node["grantorPartyId"].asText()).isEqualTo(grantor.toString())
        assertThat(node["granteePartyId"].asText()).isEqualTo(grantee.toString())
        assertThat(node["resourceType"].asText()).isEqualTo("SAVINGS_GOAL")
        assertThat(node["resourceId"].asText()).isEqualTo(account.toString())
        assertThat(node["capabilities"].single().asText()).isEqualTo("SAVINGS_PROPOSE_WITHDRAW")
        assertThat(node["occurredAt"].asText()).isEqualTo("2026-09-01T10:00:00Z")
    }
}
