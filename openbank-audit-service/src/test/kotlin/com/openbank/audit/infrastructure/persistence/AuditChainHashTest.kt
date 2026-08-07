// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.persistence

import com.openbank.audit.domain.model.AuditEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuditChainHashTest {

    private fun entry(
        eventType: String = "CUSTOMER_TRANSFER_INITIATED",
        actorId: String? = "8044e05a-a93e-4403-a7db-1a7adf01f298",
        payload: String = """{"amount":"250.00"}""",
    ) = AuditEntry(
        id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        eventType = eventType,
        aggregateType = "CUSTOMER_ACTION",
        aggregateId = "8044e05a-a93e-4403-a7db-1a7adf01f298",
        actorId = actorId,
        actorType = "CUSTOMER",
        payload = payload,
        sourceService = "customer-edge",
        correlationId = "corr-1",
        occurredAt = Instant.parse("2026-06-12T10:00:00Z"),
        recordedAt = Instant.parse("2026-06-12T10:00:01Z"),
        occurredAtSource = com.openbank.audit.domain.model.OccurredAtSource.EVENT,
    )

    private val genesis = "0".repeat(64)

    @Test
    fun `hash is deterministic for identical entries`() {
        assertThat(AuditRepository.chainHash(genesis, entry()))
            .isEqualTo(AuditRepository.chainHash(genesis, entry()))
    }

    @Test
    fun `any field edit changes the hash (tamper evidence)`() {
        val original = AuditRepository.chainHash(genesis, entry())
        assertThat(
            AuditRepository.chainHash(genesis, entry(payload = """{"amount":"999.00"}""")),
        ).isNotEqualTo(original)
        assertThat(
            AuditRepository.chainHash(genesis, entry(actorId = UUID.randomUUID().toString())),
        ).isNotEqualTo(original)
        assertThat(AuditRepository.chainHash(genesis, entry(eventType = "OTHER"))).isNotEqualTo(original)
    }

    @Test
    fun `the previous link is part of the hash so re-ordering breaks the chain`() {
        val first = AuditRepository.chainHash(genesis, entry())
        assertThat(AuditRepository.chainHash(first, entry())).isNotEqualTo(AuditRepository.chainHash(genesis, entry()))
    }

    @Test
    fun `hash is a 64-char lowercase hex sha-256`() {
        assertThat(AuditRepository.chainHash(genesis, entry())).matches("[0-9a-f]{64}")
    }
}
