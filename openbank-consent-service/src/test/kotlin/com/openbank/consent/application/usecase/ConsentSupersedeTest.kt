// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.application.usecase

import com.openbank.consent.application.port.`in`.CreateConsentCommand
import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.application.port.out.ScaChallengeClient
import com.openbank.consent.application.port.out.ScaChallengeSnapshot
import com.openbank.consent.domain.event.ConsentSuperseded
import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentStatus
import com.openbank.consent.domain.model.GranteeType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Issue #6487 — duplicate ACTIVE consents for the same grantee and scopes.
 *
 * Nothing ever wrote SUPERSEDED, `createConsent` never looked for an existing grant, the table has
 * no unique constraint, `revokeConsent` revokes ONE row by id, and `hasActiveConsent` answers over
 * a LIST. So duplicates accumulated, each independently sufficient to grant access, and a customer
 * withdrawing a TPP's access left the older ones granting it.
 *
 * Split out of ConsentServiceTest, which detekt already considers a LargeClass.
 */
class ConsentSupersedeTest {

    private val consentRepository = mockk<ConsentRepository>()
    private val scaChallengeClient = mockk<ScaChallengeClient>()
    private val fixedClock = Clock.fixed(Instant.parse("2024-01-15T10:00:00Z"), ZoneOffset.UTC)
    private val service = ConsentService(consentRepository, scaChallengeClient, fixedClock)

    private val partyId = UUID.randomUUID()
    private val consentId = UUID.randomUUID()
    private val granteeId = "tpp-123"
    private val now = OffsetDateTime.now().plusMinutes(1)

    @Test
    fun `activation supersedes an existing ACTIVE consent for the same grantee and scopes`(): Unit = runBlocking {
        val scaSessionId = UUID.randomUUID()
        val olderId = UUID.randomUUID()
        val superseded = slot<List<Pair<Consent, ConsentSuperseded>>>()
        coEvery { consentRepository.findById(consentId) } returns consent(status = ConsentStatus.PENDING_SCA)
        coEvery { scaChallengeClient.getChallenge(scaSessionId) } returns
            ScaChallengeSnapshot(scaSessionId, partyId, "CONSENT_GRANT", "COMPLETED")
        coEvery { consentRepository.findActiveByGranteeAndParty(granteeId, partyId) } returns
            listOf(consent(status = ConsentStatus.ACTIVE, id = olderId))
        coEvery { consentRepository.saveSuperseding(any(), any(), capture(superseded)) } answers { firstArg() }

        service.activateConsent(consentId, scaSessionId)

        assertThat(superseded.captured).hasSize(1)
        val (old, event) = superseded.captured.single()
        assertThat(old.id).isEqualTo(olderId)
        assertThat(old.status)
            .describedAs("SUPERSEDED, not REVOKED — the customer withdrew nothing")
            .isEqualTo(ConsentStatus.SUPERSEDED)
        assertThat(event.supersededBy).isEqualTo(consentId)
        assertThat(event.aggregateId).isEqualTo(olderId)
    }

    @Test
    fun `activation does NOT supersede a consent whose scope set merely overlaps`(): Unit = runBlocking {
        val scaSessionId = UUID.randomUUID()
        val superseded = slot<List<Pair<Consent, ConsentSuperseded>>>()
        coEvery { consentRepository.findById(consentId) } returns
            consent(status = ConsentStatus.PENDING_SCA, scopes = setOf(ConsentScope.ACCOUNTS_READ))
        coEvery { scaChallengeClient.getChallenge(scaSessionId) } returns
            ScaChallengeSnapshot(scaSessionId, partyId, "CONSENT_GRANT", "COMPLETED")
        // A WIDER grant. Retiring it because the scopes intersect would silently narrow what the
        // customer agreed to, which is a different decision from replacing a like-for-like grant.
        coEvery { consentRepository.findActiveByGranteeAndParty(granteeId, partyId) } returns listOf(
            consent(
                status = ConsentStatus.ACTIVE,
                scopes = setOf(ConsentScope.ACCOUNTS_READ, ConsentScope.BALANCES_READ),
                id = UUID.randomUUID(),
            ),
        )
        coEvery { consentRepository.saveSuperseding(any(), any(), capture(superseded)) } answers { firstArg() }

        service.activateConsent(consentId, scaSessionId)

        assertThat(superseded.captured).isEmpty()
    }

    @Test
    fun `creating an SCA-gated consent supersedes nothing — the old grant stands until the new one is ACTIVE`(): Unit =
        runBlocking {
            // The whole reason superseding lives in activation: a new consent is born PENDING_SCA, and
            // retiring the old one here would end access before the replacement was confirmed. If the
            // SCA then failed or was abandoned, the customer would be left with no consent at all.
            coEvery { consentRepository.save(any<Consent>()) } answers { firstArg() }

            val result = service.createConsent(
                CreateConsentCommand(
                    partyId = partyId,
                    granteeId = granteeId,
                    granteeType = GranteeType.TPP,
                    granteeName = "Test TPP",
                    scopes = setOf(ConsentScope.ACCOUNTS_READ),
                    accountIbans = null,
                    validTo = now.plusDays(30),
                    redirectUri = "https://example.com/redirect",
                    tppTransactionId = "txn-1",
                    ipAddress = "127.0.0.1",
                    userAgent = "JUnit",
                ),
            )

            assertThat(result.status).isEqualTo(ConsentStatus.PENDING_SCA)
            coVerify(exactly = 0) { consentRepository.saveSuperseding(any(), any(), any()) }
            coVerify(exactly = 0) { consentRepository.findActiveByGranteeAndParty(any(), any()) }
        }

    private fun consent(
        granteeId: String = this.granteeId,
        status: ConsentStatus = ConsentStatus.ACTIVE,
        scopes: Set<ConsentScope> = setOf(ConsentScope.ACCOUNTS_READ),
        id: UUID = this.consentId,
    ): Consent = Consent(
        id = id,
        partyId = partyId,
        granteeId = granteeId,
        granteeType = GranteeType.TPP,
        granteeName = "Test TPP",
        scopes = scopes,
        accountIbans = listOf("CZ6508000000192000145399"),
        status = status,
        validFrom = now,
        validTo = now.plusDays(1),
        scaSessionId = UUID.randomUUID(),
        redirectUri = "https://example.com/redirect",
        tppTransactionId = "txn-1",
        ipAddress = "127.0.0.1",
        userAgent = "JUnit",
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )
}
