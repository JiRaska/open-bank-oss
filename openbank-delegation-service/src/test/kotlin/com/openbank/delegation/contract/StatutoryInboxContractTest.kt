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

class StatutoryInboxContractTest {
    @Test
    fun `signing progress never serializes the device challenge id`() {
        val response = StatutoryDecisionSummaryResponse(
            UUID.randomUUID(),
            StatutoryDecisionVerdict.APPROVE,
            Instant.parse("2026-09-17T12:00:00Z"),
        )
        val json = ObjectMapper().registerModule(JavaTimeModule()).writeValueAsString(response)

        assertThat(json).contains("actorPartyId", "verdict", "decidedAt")
        assertThat(json).doesNotContain("scaSessionId")
    }
}
