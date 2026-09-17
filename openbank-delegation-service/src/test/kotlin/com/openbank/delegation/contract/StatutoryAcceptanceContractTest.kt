// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.infrastructure.rest.StatutoryDecisionSummaryResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StatutoryAcceptanceContractTest {
    @Test
    fun `contract publishes every distinct joint acceptance step with both actor headers`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        val section = contract.substringAfter("  /api/v1/delegations/statutory-acceptances/for-grant/{grantId}:")
            .substringBefore("  /api/v1/delegations/recertifications/grantor/{partyId}:")

        assertThat(section).contains(
            "statutory-acceptances/{id}:",
            "statutory-acceptances/{id}/approval-intent:",
            "statutory-acceptances/{id}/decisions:",
            "statutory-acceptances/{id}/progress:",
            "statutory-acceptances/{id}/execute:",
            "#/components/parameters/CustomerPartyId",
            "#/components/parameters/CustomerActorPartyId",
            "StatutoryAcceptanceResponse",
        )
        assertThat(contract).contains("DELEGATION_STATUTORY_ACCEPTANCE")
    }

    @Test
    fun `acceptance decision summaries never reveal personal device session ids`() {
        val response = StatutoryDecisionSummaryResponse(
            UUID.randomUUID(),
            StatutoryDecisionVerdict.APPROVE,
            Instant.parse("2026-09-18T12:00:00Z"),
        )
        val json = ObjectMapper().registerModule(JavaTimeModule()).writeValueAsString(response)

        assertThat(json).contains("actorPartyId", "verdict", "decidedAt")
        assertThat(json).doesNotContain("scaSessionId")
    }
}
