// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.consent.application.usecase

import com.openbank.consent.application.port.`in`.CreateConsentCommand
import com.openbank.consent.application.port.`in`.RevokeConsentCommand
import com.openbank.consent.application.port.`in`.ValidateConsentCommand
import com.openbank.consent.application.port.out.ConsentEventPublisher
import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.application.port.out.ScaChallengeClient
import com.openbank.consent.application.port.out.ScaChallengeSnapshot
import com.openbank.consent.domain.event.ConsentGranted
import com.openbank.consent.domain.event.ConsentRejected
import com.openbank.consent.domain.event.ConsentRevoked
import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentStatus
import com.openbank.consent.domain.model.ConsentValidationResult
import com.openbank.consent.domain.model.GranteeType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ConsentServiceTest {

    private val consentRepository = mockk<ConsentRepository>()
    private val eventPublisher = mockk<ConsentEventPublisher>(relaxed = true)
    private val scaChallengeClient = mockk<ScaChallengeClient>()
    private val fixedClock = Clock.fixed(Instant.parse("2024-01-15T10:00:00Z"), ZoneOffset.UTC)
    private val service = ConsentService(consentRepository, eventPublisher, scaChallengeClient, fixedClock)

    private val partyId = UUID.randomUUID()
    private val consentId = UUID.randomUUID()
    private val granteeId = "tpp-123"
    private val now = OffsetDateTime.now().plusMinutes(1)

    @Test
    fun `createConsent saves with PENDING_SCA status and clamps validTo to max 90 days for AISP scopes`(): Unit =
        runBlocking {
            val savedConsent = slot<Consent>()
            coEvery { consentRepository.save(capture(savedConsent)) } answers { firstArg() }

            val command = CreateConsentCommand(
                partyId = partyId,
                granteeId = granteeId,
                granteeType = GranteeType.TPP,
                granteeName = "Test TPP",
                scopes = setOf(ConsentScope.ACCOUNTS_READ),
                accountIbans = listOf("CZ6508000000192000145399"),
                validTo = now.plusDays(180),
                redirectUri = "https://example.com/redirect",
                tppTransactionId = "txn-1",
                ipAddress = "127.0.0.1",
                userAgent = "JUnit",
            )

            val result = service.createConsent(command)

            assertThat(result.status).isEqualTo(ConsentStatus.PENDING_SCA)
            assertThat(savedConsent.captured.status).isEqualTo(ConsentStatus.PENDING_SCA)
            assertThat(savedConsent.captured.validTo).isBefore(command.validTo)
            assertThat(savedConsent.captured.validTo).isBefore(savedConsent.captured.validFrom.plusDays(91))
            coVerify(exactly = 1) { consentRepository.save(any()) }
        }

    @Test
    fun `getConsent returns consent from repo`(): Unit = runBlocking {
        val consent = consent()
        coEvery { consentRepository.findById(consentId) } returns consent

        val result = service.getConsent(consentId)

        assertThat(result).isEqualTo(consent)
    }

    @Test
    fun `getConsent throws ConsentNotFoundException when not found`() {
        coEvery { consentRepository.findById(consentId) } returns null

        assertThrows<ConsentNotFoundException> {
            runBlocking { service.getConsent(consentId) }
        }
    }

    @Test
    fun `revokeConsent throws ConsentNotOwnedByPartyException when partyId mismatch`() {
        coEvery { consentRepository.findById(consentId) } returns consent()

        assertThrows<ConsentNotOwnedByPartyException> {
            runBlocking {
                service.revokeConsent(
                    RevokeConsentCommand(
                        consentId = consentId,
                        partyId = UUID.randomUUID(),
                        reason = "customer request",
                    ),
                )
            }
        }
    }

    @Test
    fun `revokeConsent publishes ConsentRevoked event`(): Unit = runBlocking {
        val consent = consent()
        val savedConsent = slot<Consent>()
        coEvery { consentRepository.findById(consentId) } returns consent
        coEvery { consentRepository.save(capture(savedConsent)) } answers { firstArg() }

        val result = service.revokeConsent(
            RevokeConsentCommand(
                consentId = consentId,
                partyId = partyId,
                reason = "customer request",
            ),
        )

        assertThat(result.status).isEqualTo(ConsentStatus.REVOKED)
        coVerify(exactly = 1) {
            eventPublisher.publish(
                match<ConsentRevoked> {
                    it.aggregateId == consentId && it.partyId == partyId && it.reason == "customer request"
                },
            )
        }
    }

    @Test
    fun `validateConsent returns Invalid when consent not found`(): Unit = runBlocking {
        coEvery { consentRepository.findById(consentId) } returns null

        val result = service.validateConsent(
            ValidateConsentCommand(
                consentId = consentId,
                granteeId = granteeId,
                requiredScope = ConsentScope.ACCOUNTS_READ,
                accountIban = null,
            ),
        )

        assertThat(result).isEqualTo(ConsentValidationResult.Invalid("Consent not found", "CONSENT_NOT_FOUND"))
    }

    @Test
    fun `validateConsent returns Invalid when grantee mismatch`(): Unit = runBlocking {
        coEvery { consentRepository.findById(consentId) } returns consent(granteeId = "other-grantee")

        val result = service.validateConsent(
            ValidateConsentCommand(
                consentId = consentId,
                granteeId = granteeId,
                requiredScope = ConsentScope.ACCOUNTS_READ,
                accountIban = null,
            ),
        )

        assertThat(
            result,
        ).isEqualTo(ConsentValidationResult.Invalid("Consent grantee mismatch", "CONSENT_GRANTEE_MISMATCH"))
    }

    @Test
    fun `validateConsent returns Valid when all checks pass`(): Unit = runBlocking {
        val consent = consent()
        coEvery { consentRepository.findById(consentId) } returns consent

        val result = service.validateConsent(
            ValidateConsentCommand(
                consentId = consentId,
                granteeId = granteeId,
                requiredScope = ConsentScope.ACCOUNTS_READ,
                accountIban = "CZ6508000000192000145399",
            ),
        )

        assertThat(result).isEqualTo(ConsentValidationResult.Valid(consent))
    }

    @Test
    fun `createConsent clamps validTo to 365 days for non-AISP scopes`(): Unit = runBlocking {
        val saved = slot<Consent>()
        coEvery { consentRepository.save(capture(saved)) } answers { firstArg() }

        val command = CreateConsentCommand(
            partyId = partyId,
            granteeId = granteeId,
            granteeType = GranteeType.CUSTOMER_AGENT,
            granteeName = "Agent",
            scopes = setOf(ConsentScope.TELEMETRY_RUM),
            accountIbans = null,
            validTo = now.plusDays(500),
            redirectUri = null,
            tppTransactionId = null,
            ipAddress = null,
            userAgent = null,
        )

        service.createConsent(command)

        assertThat(saved.captured.validTo).isBefore(saved.captured.validFrom.plusDays(366))
        assertThat(saved.captured.validTo).isAfter(saved.captured.validFrom.plusDays(364))
    }

    @Test
    fun `createConsent keeps the requested validTo when within the limit`(): Unit = runBlocking {
        val saved = slot<Consent>()
        coEvery { consentRepository.save(capture(saved)) } answers { firstArg() }
        val requested = OffsetDateTime.now(fixedClock).plusDays(30)

        val command = CreateConsentCommand(
            partyId = partyId,
            granteeId = granteeId,
            granteeType = GranteeType.TPP,
            granteeName = "Test TPP",
            scopes = setOf(ConsentScope.ACCOUNTS_READ),
            accountIbans = null,
            validTo = requested,
            redirectUri = null,
            tppTransactionId = null,
            ipAddress = null,
            userAgent = null,
        )

        service.createConsent(command)

        assertThat(saved.captured.validTo).isEqualTo(requested)
    }

    @Test
    fun `activateConsent throws when consent not found`() {
        coEvery { consentRepository.findById(consentId) } returns null

        assertThrows<ConsentNotFoundException> {
            runBlocking { service.activateConsent(consentId, UUID.randomUUID()) }
        }
    }

    @Test
    fun `activateConsent throws when already active`() {
        coEvery { consentRepository.findById(consentId) } returns consent(status = ConsentStatus.ACTIVE)

        assertThrows<ConsentAlreadyActiveException> {
            runBlocking { service.activateConsent(consentId, UUID.randomUUID()) }
        }
    }

    @Test
    fun `activateConsent maps a NotFoundException from sca client to challenge-not-found`() {
        val scaSessionId = UUID.randomUUID()
        coEvery { consentRepository.findById(consentId) } returns consent(status = ConsentStatus.PENDING_SCA)
        coEvery { scaChallengeClient.getChallenge(scaSessionId) } throws NotFoundException()

        assertThrows<ConsentScaChallengeNotFoundException> {
            runBlocking { service.activateConsent(consentId, scaSessionId) }
        }
    }

    @Test
    fun `activateConsent maps a generic sca client failure to verification-unavailable`() {
        val scaSessionId = UUID.randomUUID()
        coEvery { consentRepository.findById(consentId) } returns consent(status = ConsentStatus.PENDING_SCA)
        coEvery { scaChallengeClient.getChallenge(scaSessionId) } throws RuntimeException("timeout")

        assertThrows<ConsentScaVerificationUnavailableException> {
            runBlocking { service.activateConsent(consentId, scaSessionId) }
        }
    }

    @Test
    fun `activateConsent throws mismatch when challenge party or purpose differs`() {
        val scaSessionId = UUID.randomUUID()
        coEvery { consentRepository.findById(consentId) } returns consent(status = ConsentStatus.PENDING_SCA)
        coEvery { scaChallengeClient.getChallenge(scaSessionId) } returns
            ScaChallengeSnapshot(scaSessionId, UUID.randomUUID(), "CONSENT_GRANT", "COMPLETED")

        assertThrows<ConsentScaChallengeMismatchException> {
            runBlocking { service.activateConsent(consentId, scaSessionId) }
        }
    }

    @Test
    fun `activateConsent throws not-completed when challenge status is not COMPLETED`() {
        val scaSessionId = UUID.randomUUID()
        coEvery { consentRepository.findById(consentId) } returns consent(status = ConsentStatus.PENDING_SCA)
        coEvery { scaChallengeClient.getChallenge(scaSessionId) } returns
            ScaChallengeSnapshot(scaSessionId, partyId, "CONSENT_GRANT", "PENDING")

        assertThrows<ConsentScaNotCompletedException> {
            runBlocking { service.activateConsent(consentId, scaSessionId) }
        }
    }

    @Test
    fun `activateConsent activates and publishes ConsentGranted on the happy path`(): Unit = runBlocking {
        val scaSessionId = UUID.randomUUID()
        val saved = slot<Consent>()
        coEvery { consentRepository.findById(consentId) } returns consent(status = ConsentStatus.PENDING_SCA)
        coEvery { scaChallengeClient.getChallenge(scaSessionId) } returns
            ScaChallengeSnapshot(scaSessionId, partyId, "CONSENT_GRANT", "COMPLETED")
        coEvery { consentRepository.save(capture(saved)) } answers { firstArg() }

        val result = service.activateConsent(consentId, scaSessionId)

        assertThat(result.status).isEqualTo(ConsentStatus.ACTIVE)
        assertThat(saved.captured.scaSessionId).isEqualTo(scaSessionId)
        coVerify(exactly = 1) {
            eventPublisher.publish(
                match<ConsentGranted> { it.aggregateId == consentId && it.partyId == partyId },
            )
        }
    }

    @Test
    fun `rejectConsent throws when consent not found`() {
        coEvery { consentRepository.findById(consentId) } returns null

        assertThrows<ConsentNotFoundException> {
            runBlocking { service.rejectConsent(consentId, "declined") }
        }
    }

    @Test
    fun `rejectConsent rejects and publishes ConsentRejected`(): Unit = runBlocking {
        coEvery { consentRepository.findById(consentId) } returns consent(status = ConsentStatus.PENDING_SCA)
        coEvery { consentRepository.save(any()) } answers { firstArg() }

        val result = service.rejectConsent(consentId, "declined")

        assertThat(result.status).isEqualTo(ConsentStatus.REJECTED)
        coVerify(exactly = 1) {
            eventPublisher.publish(
                match<ConsentRejected> { it.aggregateId == consentId && it.reason == "declined" },
            )
        }
    }

    @Test
    fun `revokeConsent throws when consent not found`() {
        coEvery { consentRepository.findById(consentId) } returns null

        assertThrows<ConsentNotFoundException> {
            runBlocking {
                service.revokeConsent(RevokeConsentCommand(consentId, partyId, "reason"))
            }
        }
    }

    @Test
    fun `listConsentsForParty delegates to the repository`(): Unit = runBlocking {
        val consents = listOf(consent())
        coEvery { consentRepository.findByPartyId(partyId) } returns consents

        assertThat(service.listConsentsForParty(partyId)).isEqualTo(consents)
    }

    @Test
    fun `listConsentsForGrantee delegates to the repository`(): Unit = runBlocking {
        val consents = listOf(consent())
        coEvery { consentRepository.findByGranteeId(granteeId) } returns consents

        assertThat(service.listConsentsForGrantee(granteeId)).isEqualTo(consents)
    }

    @Test
    fun `validateConsent returns Invalid when consent not active`(): Unit = runBlocking {
        coEvery { consentRepository.findById(consentId) } returns consent(status = ConsentStatus.REVOKED)

        val result = service.validateConsent(
            ValidateConsentCommand(consentId, granteeId, ConsentScope.ACCOUNTS_READ, null),
        )

        assertThat(result).isInstanceOf(ConsentValidationResult.Invalid::class.java)
        assertThat((result as ConsentValidationResult.Invalid).code).isEqualTo("CONSENT_NOT_ACTIVE")
    }

    @Test
    fun `validateConsent returns Invalid when scope missing`(): Unit = runBlocking {
        coEvery { consentRepository.findById(consentId) } returns consent()

        val result = service.validateConsent(
            ValidateConsentCommand(consentId, granteeId, ConsentScope.PAYMENTS_INITIATE, null),
        )

        assertThat((result as ConsentValidationResult.Invalid).code).isEqualTo("CONSENT_SCOPE_MISSING")
    }

    @Test
    fun `validateConsent returns Invalid when account not covered`(): Unit = runBlocking {
        coEvery { consentRepository.findById(consentId) } returns consent()

        val result = service.validateConsent(
            ValidateConsentCommand(consentId, granteeId, ConsentScope.ACCOUNTS_READ, "CZ9999999999999999999999"),
        )

        assertThat((result as ConsentValidationResult.Invalid).code).isEqualTo("CONSENT_ACCOUNT_NOT_COVERED")
    }

    private fun consent(granteeId: String = this.granteeId, status: ConsentStatus = ConsentStatus.ACTIVE): Consent =
        Consent(
            id = consentId,
            partyId = partyId,
            granteeId = granteeId,
            granteeType = GranteeType.TPP,
            granteeName = "Test TPP",
            scopes = setOf(ConsentScope.ACCOUNTS_READ),
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
