// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.party.application.port.out.KybSignedCaseProjection
import com.openbank.party.application.port.out.KybSignedCaseProjectionRepository
import com.openbank.party.application.port.out.RepresentationPolicyRepository
import com.openbank.party.domain.model.RepresentationPolicySnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class KybRepresentationPolicyConsumerTest {
    private val policies = mockk<RepresentationPolicyRepository>()
    private val signedCases = mockk<KybSignedCaseProjectionRepository>()
    private val mapper = ObjectMapper()
    private val consumer = KybRepresentationPolicyConsumer(policies, signedCases, mapper)
    private val caseId = UUID.randomUUID()
    private val principalId = UUID.randomUUID()
    private val chairId = UUID.randomUUID()
    private val memberId = UUID.randomUUID()

    private fun event(principal: UUID = principalId, status: String = "SIGNED") = """
        {
          "eventType":"BUSINESS_AGREEMENT_SIGNED","caseId":"$caseId","entityPartyId":"$principalId",
          "sourceService":"kyb-service","status":"$status","requiredSignatures":2,"signedCount":2,
          "statutoryPolicy":{
            "sourceCaseId":"$caseId","principalPartyId":"$principal","attestationId":"${UUID.randomUUID()}",
            "ruleTextHash":"${"a".repeat(64)}","registrySource":"verified-registry",
            "registrySourceRef":"registry-entry","registryRepresentativeCount":2,"mode":"JOINT_ALL",
            "requiredSignatures":2,"requiredOffices":["Chair","Member"],
            "eligibleRepresentatives":[
              {"partyId":"$chairId","registryRepresentativeIndices":[0],"officeTags":["Chair"]},
              {"partyId":"$memberId","registryRepresentativeIndices":[1],"officeTags":["Member"]}
            ],"evidenceRef":"signed-case","effectiveFrom":"2026-09-17T00:00:00Z"
          }
        }
    """.trimIndent()

    @Test
    fun `signed verified rule is stored with allocated revision`(): Unit = runBlocking {
        var inserted: RepresentationPolicySnapshot? = null
        coEvery { policies.findBySourceCaseId(caseId) } returns null
        coEvery { policies.allocateRevision() } returns 42
        coEvery { policies.insert(any()) } answers { firstArg<RepresentationPolicySnapshot>().also { inserted = it } }

        consumer.consume(event())

        assertThat(inserted!!.revision).isEqualTo(42)
        assertThat(inserted!!.principalPartyId).isEqualTo(principalId)
        assertThat(inserted!!.satisfiedBy(setOf(chairId, memberId))).isTrue()
        assertThat(inserted!!.satisfiedBy(setOf(chairId))).isFalse()
    }

    @Test
    fun `same Kafka event can be replayed without creating another revision`(): Unit = runBlocking {
        val payload = event()
        var stored: RepresentationPolicySnapshot? = null
        coEvery { policies.findBySourceCaseId(caseId) } answers { stored }
        coEvery { policies.allocateRevision() } returns 42
        coEvery { policies.insert(any()) } answers { firstArg<RepresentationPolicySnapshot>().also { stored = it } }

        consumer.consume(payload)
        consumer.consume(payload)

        assertThat(stored!!.revision).isEqualTo(42)
        coVerify(exactly = 1) { policies.allocateRevision() }
        coVerify(exactly = 1) { policies.insert(any()) }
    }

    @Test
    fun `new signed event enters the atomic case projector instead of independent policy write`(): Unit = runBlocking {
        val root = mapper.readTree(event()) as ObjectNode
        root.put("legalFormClass", "LIMITED_COMPANY")
        root.put("occurredAt", "2026-09-17T00:00:00Z")
        root.set<com.fasterxml.jackson.databind.JsonNode>(
            "signedMandateHolders",
            mapper.readTree(
                """[{"signerId":"${UUID.randomUUID()}","partyId":"$chairId","registryRepresentativeIndex":0},
                    {"signerId":"${UUID.randomUUID()}","partyId":"$memberId","registryRepresentativeIndex":1}]""",
            ),
        )
        val projected = slot<KybSignedCaseProjection>()
        coEvery { signedCases.project(capture(projected)) } returns true

        consumer.consume(mapper.writeValueAsString(root))

        assertThat(projected.captured.caseId).isEqualTo(caseId)
        assertThat(projected.captured.holders.map { it.partyId }).containsExactlyInAnyOrder(chairId, memberId)
        assertThat(projected.captured.policy!!.requiredSignatures).isEqualTo(2)
        coVerify(exactly = 0) { policies.insert(any()) }
    }

    @Test
    fun `principal mismatch is rejected before any write`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { consumer.consume(event(principal = UUID.randomUUID())) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { policies.insert(any()) }
    }

    @Test
    fun `unsigned evidence is rejected before any write`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { consumer.consume(event(status = "MANUAL_REVIEW")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { policies.insert(any()) }
    }

    @Test
    fun `legacy signed event without policy grants no authority`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"BUSINESS_AGREEMENT_SIGNED","caseId":"$caseId"}""")
        coVerify(exactly = 0) { policies.insert(any()) }
    }
}
