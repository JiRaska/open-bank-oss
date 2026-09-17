// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.openbank.delegation.application.port.out.StatutoryRuleResolution
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RestStatutoryRuleClientTest {
    private val now = Instant.parse("2026-09-17T12:00:00Z")
    private val principal = UUID.randomUUID()
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()
    private val case = UUID.randomUUID()

    @Test
    fun `joint rule requires live same-case mandates for every identified representative`(): Unit = runBlocking {
        val rest = Fixture()
        val resolver = RestStatutoryRuleClient(rest, Clock.fixed(now, ZoneOffset.UTC))
        val verified = resolver.resolve(principal, first)
        assertThat(verified).isInstanceOf(StatutoryRuleResolution.RosterMatched::class.java)
        val rule = (verified as StatutoryRuleResolution.RosterMatched).rule
        assertThat(rule.satisfiedBy(setOf(first))).isFalse()
        assertThat(rule.satisfiedBy(setOf(first, second))).isTrue()
        assertThat(rule.satisfiedBy(setOf(first, UUID.randomUUID()))).isFalse()

        rest.mandates = rest.mandates.map { mandate ->
            if (mandate.agentPartyId == second) mandate.copy(status = "REVOKED") else mandate
        }
        assertThat(resolver.resolve(principal, first)).isEqualTo(StatutoryRuleResolution.Denied)
        rest.mandates = rest.mandates.map { mandate -> mandate.copy(status = "ACTIVE", evidenceRef = "kyb-case:old") }
        assertThat(resolver.resolve(principal, first)).isEqualTo(StatutoryRuleResolution.Denied)
    }

    @Test
    fun `office slots need distinct people and unknown policy cannot authorize`(): Unit = runBlocking {
        val rest = Fixture()
        val resolver = RestStatutoryRuleClient(rest, Clock.fixed(now, ZoneOffset.UTC))
        rest.policy = rest.policy.copy(
            requiredOffices = listOf("chair", "finance"),
            eligibleRepresentatives = listOf(
                rest.policy.eligibleRepresentatives[0].copy(officeTags = setOf("chair", "finance")),
                rest.policy.eligibleRepresentatives[1].copy(officeTags = setOf("member")),
            ),
        )
        assertThat(resolver.resolve(principal, first)).isEqualTo(StatutoryRuleResolution.Unverifiable)
        rest.policy = rest.policy.copy(mode = "SOLE")
        assertThat(resolver.resolve(principal, first)).isEqualTo(StatutoryRuleResolution.Unverifiable)
        rest.policy = rest.policy.copy(mode = "JOINT_N", requiredOffices = listOf("chair"))
        assertThat(resolver.resolve(principal, UUID.randomUUID())).isEqualTo(StatutoryRuleResolution.Denied)
    }

    @Test
    fun `missing mandate metadata and upstream outage fail closed`(): Unit = runBlocking {
        val rest = Fixture()
        val resolver = RestStatutoryRuleClient(rest, Clock.fixed(now, ZoneOffset.UTC))
        rest.mandates = rest.mandates.map { it.copy(requiredSignatures = null) }
        assertThat(resolver.resolve(principal, first)).isEqualTo(StatutoryRuleResolution.Denied)
        rest.fail = true
        assertThat(resolver.resolve(principal, first)).isEqualTo(StatutoryRuleResolution.Unverifiable)
    }

    private inner class Fixture : PartyAuthorityRestClient {
        var fail = false
        var policy = RepresentationPolicyResponse(
            id = UUID.randomUUID(),
            principalPartyId = principal,
            revision = 1,
            sourceCaseId = case,
            attestationId = UUID.randomUUID(),
            ruleTextHash = "a".repeat(64),
            mode = "JOINT_N",
            requiredSignatures = 2,
            requiredOffices = listOf("chair", "member"),
            registryRepresentativeCount = 2,
            eligibleRepresentatives = listOf(
                StatutoryRepresentativeResponse(first, setOf(0), setOf("chair")),
                StatutoryRepresentativeResponse(second, setOf(1), setOf("member")),
            ),
            effectiveFrom = now.minusSeconds(60),
        )
        var mandates = listOf(first, second).map { actor ->
            StatutoryMandateResponse(
                principalPartyId = principal,
                agentPartyId = actor,
                role = "LEGAL_REPRESENTATIVE",
                authority = "JOINT",
                requiredSignatures = 2,
                status = "ACTIVE",
                evidenceRef = "kyb-case:$case:signer:${UUID.randomUUID()}",
                validFrom = now.minusSeconds(60),
                validTo = null,
            )
        }

        override suspend fun getParty(id: UUID): AuthorityPartyResponse {
            if (fail) error("party unavailable")
            return AuthorityPartyResponse(id, "COMPANY", "ACTIVE")
        }

        override suspend fun actingFor(actorPartyId: UUID): List<ActingForResponse> = emptyList()

        override suspend fun representationPolicy(principalPartyId: UUID): RepresentationPolicyResponse = policy

        override suspend fun mandates(principalPartyId: UUID): List<StatutoryMandateResponse> = mandates
    }
}
