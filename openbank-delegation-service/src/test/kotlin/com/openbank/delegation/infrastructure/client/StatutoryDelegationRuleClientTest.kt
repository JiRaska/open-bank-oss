// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import com.openbank.delegation.application.usecase.DelegationGrantorAuthorityUnavailableException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StatutoryDelegationRuleClientTest {
    private val party = mockk<PartyStatutoryRuleRestClient>()
    private val kyb = mockk<KybStatutoryRuleRestClient>()
    private val at = Instant.parse("2026-08-01T00:00:00Z")
    private val reader = RestStatutoryDelegationRuleReader(party, kyb, Clock.fixed(at, ZoneOffset.UTC))
    private val company = UUID.randomUUID()
    private val caseId = UUID.randomUUID()
    private val attestation = UUID.randomUUID()
    private val roster = List(2) { UUID.randomUUID() }
    private val response = StatutoryRuleResponse(
        principalPartyId = company,
        evidence = StatutoryEvidenceResponse(
            caseId = caseId,
            attestationId = attestation,
            ruleTextHash = "a".repeat(64),
            attestedAt = at.minusSeconds(20),
            mode = "JOINT_N",
            requiredSignatures = 2,
            requiredOffices = listOf("chair", "member"),
            representatives = listOf(
                StatutoryRepresentativeResponse(roster[0], setOf(0), "b".repeat(64)),
                StatutoryRepresentativeResponse(roster[1], setOf(1), "c".repeat(64)),
            ),
        ),
        mandatesByRepresentative = roster.associateWith { UUID.randomUUID() },
        effectiveFrom = at.minusSeconds(10),
        validTo = at.plusSeconds(600),
    )

    @Test
    fun `only a current exact source attestation makes the live Party snapshot usable`(): Unit = runBlocking {
        coEvery { party.latest(company) } returns response
        coEvery { kyb.verify(caseId, attestation, company) } returnsMany listOf(
            Response.noContent().build(),
            Response.status(Response.Status.NOT_FOUND).build(),
        )

        val current = reader.current(company)
        assertThat(current?.mandatesByRepresentative).isEqualTo(response.mandatesByRepresentative)
        assertThat(current?.attestationId).isEqualTo(attestation)
        assertThat(reader.current(company)).isNull()
    }

    @Test
    fun `unknown or expired Party evidence is refused before a KYB current check`(): Unit = runBlocking {
        coEvery { party.latest(company) } returnsMany listOf(
            response.copy(evidence = response.evidence.copy(mode = "UNKNOWN")),
            response.copy(validTo = at),
        )
        repeat(2) { assertThat(reader.current(company)).isNull() }
        coVerify(exactly = 0) { kyb.verify(any(), any(), any()) }
    }

    @Test
    fun `an unexpected result or source outage is unavailable`(): Unit = runBlocking {
        coEvery { party.latest(company) } returns response
        coEvery { kyb.verify(caseId, attestation, company) } returns Response.ok().build()
        assertThat(runCatching { reader.current(company) }.exceptionOrNull())
            .isInstanceOf(DelegationGrantorAuthorityUnavailableException::class.java)
        coEvery { kyb.verify(caseId, attestation, company) } throws ProcessingException("fixture unavailable")
        assertThat(runCatching { reader.current(company) }.exceptionOrNull())
            .isInstanceOf(DelegationGrantorAuthorityUnavailableException::class.java)
    }
}
