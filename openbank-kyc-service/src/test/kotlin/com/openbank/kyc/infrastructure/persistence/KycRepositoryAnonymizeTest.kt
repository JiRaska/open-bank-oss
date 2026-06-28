// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.kyc.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycCheck
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KycRepositoryAnonymizeTest {

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())

    @Test
    fun `anonymizeChecksJson nulls result while preserving non-PII check metadata`() {
        val check = KycCheck(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            caseId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            checkType = CheckType.IDENTITY,
            status = CheckStatus.PASSED,
            result = "PII: Jane Doe, 1990-01-01, PASS",
            provider = "Onfido",
            performedAt = Instant.parse("2026-01-15T10:00:00Z"),
            createdAt = Instant.parse("2026-01-15T10:00:00Z"),
        )
        val checksJson = objectMapper.writeValueAsString(listOf(check))

        val anonymized = KycRepository.anonymizeChecksJson(checksJson, objectMapper)

        val checks: List<KycCheck> = objectMapper.readValue(
            anonymized,
            objectMapper.typeFactory.constructCollectionType(List::class.java, KycCheck::class.java),
        )
        assertThat(checks).hasSize(1)
        assertThat(checks[0].result).isNull()
        assertThat(checks[0].checkType).isEqualTo(CheckType.IDENTITY)
        assertThat(checks[0].status).isEqualTo(CheckStatus.PASSED)
        assertThat(checks[0].provider).isEqualTo("Onfido")
        assertThat(checks[0].performedAt).isEqualTo(Instant.parse("2026-01-15T10:00:00Z"))
    }

    @Test
    fun `anonymizeChecksJson returns empty array when checksJson is corrupted`() {
        val result = KycRepository.anonymizeChecksJson("NOT_VALID_JSON{{{", objectMapper)

        assertThat(result).isEqualTo("[]")
    }

    @Test
    fun `anonymizeChecksJson handles empty checks array`() {
        val result = KycRepository.anonymizeChecksJson("[]", objectMapper)

        assertThat(result).isEqualTo("[]")
    }
}
