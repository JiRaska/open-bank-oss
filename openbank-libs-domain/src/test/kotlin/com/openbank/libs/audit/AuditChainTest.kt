// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class AuditChainTest {
    private val json = ObjectMapper()

    private fun event(operation: String) = AuditEvent(
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        actorId = "operator",
        actorType = "USER",
        operation = operation,
        resourceType = "LOAN",
        resourceId = "loan-1",
        timestamp = Instant.parse("2026-09-28T12:00:00Z"),
    )

    private fun decoded(envelope: HashLinkedAuditEnvelope): Map<String, Any?> =
        json.readValue(envelope.canonicalJson, Map::class.java).entries.associate { it.key.toString() to it.value }

    @Test
    fun `wire bytes form a verifiable producer chain`() {
        val first = AuditChain.link(event("CREATE"), "lending", null)
        val second = AuditChain.link(event("APPROVE"), "lending", first.link)

        assertThat(first.link.seq).isEqualTo(1)
        assertThat(first.link.prevHash).isEqualTo(AuditChain.GENESIS_HASH)
        assertThat(second.link.seq).isEqualTo(2)
        assertThat(second.link.prevHash).isEqualTo(first.link.hash)
        assertThat(AuditChain.verify(listOf(decoded(first), decoded(second))).intact).isTrue()
        assertThat(first.link.hash).isEqualTo(
            AuditChain.sha256Hex(CanonicalJson.write(decoded(first) - AuditChain.HASH_FIELD)),
        )
    }

    @Test
    fun `tampering dropping and reordering report the first broken link`() {
        val first = AuditChain.link(event("CREATE"), "lending", null)
        val second = AuditChain.link(event("APPROVE"), "lending", first.link)
        val original = decoded(first)
        val next = decoded(second)

        assertThat(AuditChain.verify(listOf(original + ("operation" to "DELETE"))).reason)
            .isEqualTo("hash mismatch")
        assertThat(AuditChain.verify(listOf(next)).reason).contains("expected 1")
        assertThat(AuditChain.verify(listOf(original, original)).firstBrokenIndex).isEqualTo(1)
        val spliced = next + ("prevHash" to AuditChain.GENESIS_HASH)
        val rehashed = spliced + ("hash" to AuditChain.sha256Hex(CanonicalJson.write(spliced - "hash")))
        assertThat(AuditChain.verify(listOf(original, rehashed)).reason)
            .isEqualTo("prevHash does not match previous hash")
    }

    @Test
    fun `producer identity cannot splice another chain`() {
        val head = AuditChain.link(event("CREATE"), "lending", null).link
        assertThatThrownBy { AuditChain.link(event("APPROVE"), "audit", head) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AuditChain.link(event("CREATE"), " ", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `canonical JSON preserves money scale and rejects lossy numbers`() {
        assertThat(CanonicalJson.write(mapOf("z" to BigDecimal("12.50"), "a" to "line\n\"quote\"")))
            .isEqualTo("{\"a\":\"line\\n\\\"quote\\\"\",\"z\":12.50}")
        assertThatThrownBy { CanonicalJson.write(mapOf("amount" to 0.1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CanonicalJson.write(mapOf("amount" to Long.MAX_VALUE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CanonicalJson.write(mapOf(1 to "non-string key")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
