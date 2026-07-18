// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.application.usecase

import com.openbank.consent.application.port.`in`.ActivateConsentUseCase
import com.openbank.consent.application.port.`in`.CreateConsentCommand
import com.openbank.consent.application.port.`in`.CreateConsentUseCase
import com.openbank.consent.application.port.`in`.GetConsentUseCase
import com.openbank.consent.application.port.`in`.RevokeConsentCommand
import com.openbank.consent.application.port.`in`.RevokeConsentUseCase
import com.openbank.consent.application.port.`in`.ValidateConsentCommand
import com.openbank.consent.application.port.`in`.ValidateConsentUseCase
import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.application.port.out.ScaChallengeClient
import com.openbank.consent.domain.event.ConsentGranted
import com.openbank.consent.domain.event.ConsentRejected
import com.openbank.consent.domain.event.ConsentRevoked
import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentStatus
import com.openbank.consent.domain.model.ConsentValidationResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class ConsentNotFoundException(id: UUID) : RuntimeException("Consent not found: $id")
class ConsentNotOwnedByPartyException(id: UUID, partyId: UUID) :
    RuntimeException("Consent $id does not belong to party $partyId")
class ConsentAlreadyActiveException(id: UUID) : RuntimeException("Consent $id is already active")
class ConsentScaChallengeNotFoundException(id: UUID, scaSessionId: UUID) :
    RuntimeException("SCA challenge $scaSessionId not found for consent $id")
class ConsentScaVerificationUnavailableException(id: UUID, scaSessionId: UUID) :
    RuntimeException("Unable to verify SCA challenge $scaSessionId for consent $id")
class ConsentScaChallengeMismatchException(id: UUID, scaSessionId: UUID) :
    RuntimeException("SCA challenge $scaSessionId does not match consent $id")
class ConsentScaNotCompletedException(id: UUID, scaSessionId: UUID) :
    RuntimeException("SCA challenge $scaSessionId is not completed for consent $id")

@ApplicationScoped
class ConsentService(
    private val consentRepository: ConsentRepository,
    private val scaChallengeClient: ScaChallengeClient,
    private val clock: Clock,
) : CreateConsentUseCase,
    RevokeConsentUseCase,
    GetConsentUseCase,
    ValidateConsentUseCase,
    ActivateConsentUseCase {

    @Inject
    constructor(
        consentRepository: ConsentRepository,
        scaChallengeClient: ScaChallengeClient,
    ) : this(consentRepository, scaChallengeClient, Clock.systemUTC())

    override suspend fun createConsent(command: CreateConsentCommand): Consent {
        val now = OffsetDateTime.now(clock)
        val aispScopes = setOf(
            ConsentScope.ACCOUNTS_READ,
            ConsentScope.BALANCES_READ,
            ConsentScope.TRANSACTIONS_READ,
            ConsentScope.STATEMENTS_READ,
        )
        val maxDays = if (command.scopes.any { s -> s in aispScopes }) 90L else 365L
        val validTo = if (command.validTo.isAfter(now.plusDays(maxDays))) {
            now.plusDays(maxDays)
        } else {
            command.validTo
        }

        val consent = Consent(
            partyId = command.partyId,
            granteeId = command.granteeId,
            granteeType = command.granteeType,
            granteeName = command.granteeName,
            scopes = command.scopes,
            accountIbans = command.accountIbans,
            status = ConsentStatus.PENDING_SCA,
            validFrom = now,
            validTo = validTo,
            scaSessionId = null,
            redirectUri = command.redirectUri,
            tppTransactionId = command.tppTransactionId,
            ipAddress = command.ipAddress,
            userAgent = command.userAgent,
            createdAt = now,
            updatedAt = now,
        )

        return consentRepository.save(consent)
    }

    override suspend fun activateConsent(consentId: UUID, scaSessionId: UUID): Consent {
        val consent = consentRepository.findById(consentId)
            ?: throw ConsentNotFoundException(consentId)

        if (consent.status == ConsentStatus.ACTIVE) {
            throw ConsentAlreadyActiveException(consentId)
        }

        val scaChallenge = try {
            scaChallengeClient.getChallenge(scaSessionId)
        } catch (e: NotFoundException) {
            throw ConsentScaChallengeNotFoundException(consentId, scaSessionId)
        } catch (e: Exception) {
            throw ConsentScaVerificationUnavailableException(consentId, scaSessionId)
        }

        if (scaChallenge.partyId != consent.partyId || scaChallenge.purpose != "CONSENT_GRANT") {
            throw ConsentScaChallengeMismatchException(consentId, scaSessionId)
        }

        if (scaChallenge.status != "COMPLETED") {
            throw ConsentScaNotCompletedException(consentId, scaSessionId)
        }

        val activated = consent.activate(scaSessionId, OffsetDateTime.now(clock))
        return consentRepository.save(
            activated,
            ConsentGranted(
                aggregateId = activated.id,
                partyId = activated.partyId,
                granteeId = activated.granteeId,
                granteeType = activated.granteeType,
                scopes = activated.scopes,
                validTo = activated.validTo,
                occurredAt = clock.instant(),
            ),
        )
    }

    override suspend fun rejectConsent(consentId: UUID, reason: String): Consent {
        val consent = consentRepository.findById(consentId)
            ?: throw ConsentNotFoundException(consentId)

        val rejected = consent.reject(OffsetDateTime.now(clock))
        return consentRepository.save(
            rejected,
            ConsentRejected(
                aggregateId = rejected.id,
                partyId = rejected.partyId,
                granteeId = rejected.granteeId,
                reason = reason,
                occurredAt = clock.instant(),
            ),
        )
    }

    override suspend fun revokeConsent(command: RevokeConsentCommand): Consent {
        val consent = consentRepository.findById(command.consentId)
            ?: throw ConsentNotFoundException(command.consentId)

        if (consent.partyId != command.partyId) {
            throw ConsentNotOwnedByPartyException(command.consentId, command.partyId)
        }

        val revoked = consent.revoke(command.reason, OffsetDateTime.now(clock))
        return consentRepository.save(
            revoked,
            ConsentRevoked(
                aggregateId = revoked.id,
                partyId = revoked.partyId,
                granteeId = revoked.granteeId,
                reason = command.reason,
                occurredAt = clock.instant(),
            ),
        )
    }

    override suspend fun getConsent(consentId: UUID): Consent =
        consentRepository.findById(consentId) ?: throw ConsentNotFoundException(consentId)

    override suspend fun listConsentsForParty(partyId: UUID): List<Consent> = consentRepository.findByPartyId(partyId)

    override suspend fun listConsentsForGrantee(granteeId: String): List<Consent> =
        consentRepository.findByGranteeId(granteeId)

    override suspend fun validateConsent(command: ValidateConsentCommand): ConsentValidationResult {
        val consent = consentRepository.findById(command.consentId)
            ?: return ConsentValidationResult.Invalid("Consent not found", "CONSENT_NOT_FOUND")

        if (consent.granteeId != command.granteeId) {
            return ConsentValidationResult.Invalid("Consent grantee mismatch", "CONSENT_GRANTEE_MISMATCH")
        }

        if (!consent.isActive(OffsetDateTime.now(clock))) {
            return ConsentValidationResult.Invalid(
                "Consent is not active (status=${consent.status})",
                "CONSENT_NOT_ACTIVE",
            )
        }

        if (!consent.hasScope(command.requiredScope)) {
            return ConsentValidationResult.Invalid(
                "Consent does not include scope ${command.requiredScope}",
                "CONSENT_SCOPE_MISSING",
            )
        }

        if (command.accountIban != null && !consent.coversAccount(command.accountIban)) {
            return ConsentValidationResult.Invalid(
                "Consent does not cover account ${command.accountIban}",
                "CONSENT_ACCOUNT_NOT_COVERED",
            )
        }

        return ConsentValidationResult.Valid(consent)
    }
}
