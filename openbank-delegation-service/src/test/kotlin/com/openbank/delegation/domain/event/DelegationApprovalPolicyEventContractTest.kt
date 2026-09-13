// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class DelegationApprovalPolicyEventContractTest {

    @Test
    fun `activation carries the exact approval policy and threshold on the wire`() {
        val event = DelegationActivated(
            aggregateId = UUID.randomUUID(),
            lifecycleRevision = 2,
            grantorPartyId = UUID.randomUUID(),
            granteePartyId = UUID.randomUUID(),
            resourceType = DelegationResourceType.SAVINGS_GOAL,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.SAVINGS_PROPOSE_WITHDRAW),
            approvalPolicy = ApprovalPolicy.N_OF_M,
            requiredApprovals = 3,
            validFrom = OffsetDateTime.parse("2026-09-09T06:00:00Z"),
            occurredAt = Instant.parse("2026-09-09T06:00:00Z"),
        )

        val json = jacksonObjectMapper().findAndRegisterModules().readTree(
            jacksonObjectMapper().findAndRegisterModules().writeValueAsString(event),
        )
        assertThat(json.get("approvalPolicy").asText()).isEqualTo("N_OF_M")
        assertThat(json.get("requiredApprovals").asInt()).isEqualTo(3)
    }
}
