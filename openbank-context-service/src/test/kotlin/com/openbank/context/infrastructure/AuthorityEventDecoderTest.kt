// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuthorityEventDecoderTest {
    private val mapper = ObjectMapper().findAndRegisterModules()
    private val decoder = AuthorityEventDecoder(mapper)

    @Test
    fun `activation preserves source identities revision validity and approval conditions`() {
        val evidence = requireNotNull(decoder.decode(payload()))

        assertThat(evidence.delegationId).isEqualTo(UUID.fromString(DELEGATION))
        assertThat(evidence.grantorPartyId).isEqualTo(UUID.fromString(GRANTOR))
        assertThat(evidence.granteePartyId).isEqualTo(UUID.fromString(GRANTEE))
        assertThat(evidence.revision).isEqualTo(7L)
        assertThat(evidence.eventType).isEqualTo("DelegationActivated")
        assertThat(evidence.resourceType).isEqualTo("ACCOUNT")
        assertThat(evidence.resourceId).isEqualTo(UUID.fromString(RESOURCE))
        assertThat(evidence.capabilities).containsExactly("INITIATE_PAYMENT", "VIEW_BALANCE")
        assertThat(evidence.approvalPolicy).isEqualTo("JOINT")
        assertThat(evidence.requiredApprovals).isEqualTo(2)
        assertThat(evidence.validFrom).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"))
        assertThat(evidence.validTo).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"))
        assertThat(evidence.occurredAt).isEqualTo(Instant.parse("2026-09-17T10:00:00Z"))
    }

    @Test
    fun `suspension and revocation retain unknown validity and conditions`() {
        listOf("DelegationSuspended", "DelegationRevoked").forEach { eventType ->
            val evidence = requireNotNull(
                decoder.decode(
                    payload(
                        mapOf("eventType" to eventType),
                        setOf(
                            "validFrom",
                            "validTo",
                            "resourceType",
                            "resourceId",
                            "capabilities",
                            "approvalPolicy",
                            "requiredApprovals",
                        ),
                    ),
                ),
            )

            assertThat(evidence.eventType).isEqualTo(eventType)
            assertThat(evidence.validFrom).isNull()
            assertThat(evidence.validTo).isNull()
            assertThat(evidence.resourceType).isNull()
            assertThat(evidence.resourceId).isNull()
            assertThat(evidence.capabilities).isEmpty()
            assertThat(evidence.approvalPolicy).isNull()
            assertThat(evidence.requiredApprovals).isNull()
            assertThat(evidence.occurredAt).isEqualTo(Instant.parse("2026-09-17T10:00:00Z"))
        }
    }

    @Test
    fun `initial offered revision zero is preserved without inventing approval`() {
        val evidence = requireNotNull(
            decoder.decode(
                payload(
                    mapOf("eventType" to "DelegationOffered", "lifecycleRevision" to 0, "approvalPolicy" to "SOLO"),
                    setOf("requiredApprovals"),
                ),
            ),
        )
        assertThat(evidence.revision).isZero()
        assertThat(evidence.eventType).isEqualTo("DelegationOffered")
        assertThat(evidence.requiredApprovals).isNull()
    }

    @Test
    fun `only known non lifecycle events are ignored`() {
        listOf("SpendReserved", "SpendConfirmed", "SpendReleased").forEach { type ->
            assertThat(decoder.decode(payload(mapOf("eventType" to type), setOf("lifecycleRevision")))).isNull()
        }
        assertThatThrownBy { decoder.decode(payload(mapOf("eventType" to "DelegationUnknown"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `schema version cannot substitute for lifecycle revision`() {
        assertThatThrownBy {
            decoder.decode(payload(mapOf("schemaVersion" to 7, "version" to 7), setOf("lifecycleRevision")))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("lifecycleRevision")
        listOf(-1, "7", 1.5).forEach { revision ->
            assertThatThrownBy { decoder.decode(payload(mapOf("lifecycleRevision" to revision))) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `malformed identities are not admitted as evidence`() {
        listOf("aggregateId", "grantorPartyId", "granteePartyId", "resourceId").forEach { field ->
            assertThatThrownBy { decoder.decode(payload(mapOf(field to "not-a-uuid"))) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `active observations require resource capabilities and effective start`() {
        listOf("DelegationActivated", "DelegationReinstated").forEach { eventType ->
            listOf("resourceType", "resourceId", "capabilities", "validFrom").forEach { field ->
                assertThatThrownBy { decoder.decode(payload(mapOf("eventType" to eventType), setOf(field))) }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("incomplete")
            }
        }
        assertThatThrownBy { decoder.decode(payload(mapOf("capabilities" to emptyList<String>()))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `validity must end after its start`() {
        listOf("2026-09-01T00:00:00Z", "2026-08-31T23:59:59Z").forEach { end ->
            assertThatThrownBy { decoder.decode(payload(mapOf("validTo" to end))) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("validity")
        }
        assertThatThrownBy { decoder.decode(payload(mapOf("validFrom" to "unknown"))) }
            .isInstanceOf(java.time.format.DateTimeParseException::class.java)
    }

    @Test
    fun `free text credentials and additional personal fields never enter returned evidence`() {
        val clean = decoder.decode(payload())
        val minimized = decoder.decode(
            payload(
                mapOf(
                    "reason" to "Synthetic private case narrative",
                    "credentialId" to "synthetic-credential-secret",
                    "email" to "synthetic@example.invalid",
                    "personalData" to mapOf("name" to "Synthetic Person", "dateOfBirth" to "1990-01-01"),
                ),
            ),
        )

        assertThat(minimized).isEqualTo(clean)
        val serialized = mapper.writeValueAsString(minimized)
        assertThat(
            serialized,
        ).doesNotContain("reason", "credentialId", "email", "personalData", "Synthetic", "dateOfBirth")
    }

    private fun payload(overrides: Map<String, Any> = emptyMap(), omitted: Set<String> = emptySet()): String =
        mapper.writeValueAsString(
            (
                mapOf(
                    "eventType" to "DelegationActivated",
                    "aggregateId" to DELEGATION,
                    "lifecycleRevision" to 7,
                    "grantorPartyId" to GRANTOR,
                    "granteePartyId" to GRANTEE,
                    "resourceType" to "ACCOUNT",
                    "resourceId" to RESOURCE,
                    "capabilities" to listOf("VIEW_BALANCE", "INITIATE_PAYMENT", "VIEW_BALANCE"),
                    "approvalPolicy" to "JOINT",
                    "requiredApprovals" to 2,
                    "validFrom" to "2026-09-01T00:00:00Z",
                    "validTo" to "2026-10-01T00:00:00Z",
                    "occurredAt" to "2026-09-17T10:00:00Z",
                ) + overrides
                ).filterKeys { it !in omitted },
        )

    private companion object {
        const val DELEGATION = "00000000-0000-4000-8000-000000000001"
        const val GRANTOR = "00000000-0000-4000-8000-000000000002"
        const val GRANTEE = "00000000-0000-4000-8000-000000000003"
        const val RESOURCE = "00000000-0000-4000-8000-000000000004"
    }
}
