// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.application.usecase

import com.openbank.consent.application.port.`in`.ActivateConsentUseCase
import com.openbank.consent.application.port.`in`.CheckConsentCommand
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
import com.openbank.consent.domain.event.ConsentSuperseded
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
class ConsentMixedScopeException(scopes: Set<ConsentScope>) :
    RuntimeException(
        "Cannot mix GDPR-only scopes with SCA-required scopes in one consent request: $scopes. " +
            "Request the GDPR-only and SCA-required scopes separately (ADR-0205 D1).",
    )
class ConsentGranteeMismatchException(id: UUID, expectedGranteeId: String) :
    RuntimeException(
        "Consent $id does not belong to grantee $expectedGranteeId — refusing to revoke. " +
            "The M2M OPA rule scopes consent.revoke to this exact grantee (ADR-0206); a caller " +
            "presenting the right grantee string for the wrong consent is rejected here too.",
    )

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
        val maxDays = if (command.scopes.any { s -> s in Consent.AISP_SCOPES }) 90L else 365L
        val validTo = if (command.validTo.isAfter(now.plusDays(maxDays))) {
            now.plusDays(maxDays)
        } else {
            command.validTo
        }

        // ADR-0205 D1: a request mixing a GDPR-only scope with any other scope is rejected
        // outright rather than silently falling back to the SCA-gated path — mixing would let a
        // GDPR-only request ride an SCA-required scope in without the SCA guarantee it needs.
        val anyGdprOnly = command.scopes.any { it in Consent.GDPR_ONLY_SCOPES }
        val allGdprOnly = command.scopes.isNotEmpty() && command.scopes.all { it in Consent.GDPR_ONLY_SCOPES }
        if (anyGdprOnly && !allGdprOnly) {
            throw ConsentMixedScopeException(command.scopes)
        }

        val consent = Consent(
            partyId = command.partyId,
            granteeId = command.granteeId,
            granteeType = command.granteeType,
            granteeName = command.granteeName,
            scopes = command.scopes,
            accountIbans = command.accountIbans,
            // ADR-0205 D1: a consent made entirely of GDPR_ONLY_SCOPES activates immediately — no
            // SCA challenge exists for it to wait on. Every other consent keeps the existing
            // PENDING_SCA -> activateConsent(scaSessionId) flow unchanged.
            status = if (allGdprOnly) ConsentStatus.ACTIVE else ConsentStatus.PENDING_SCA,
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

        // A GDPR-only consent is born ACTIVE (ADR-0205 D1), so it supersedes on creation — the
        // PENDING_SCA reasoning in persistActivation does not apply where there is no SCA to wait
        // for. Every other consent supersedes at activateConsent, not here.
        return if (allGdprOnly) {
            persistActivation(consent, now)
        } else {
            consentRepository.save(consent)
        }
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

        val now = OffsetDateTime.now(clock)
        val activated = consent.activate(scaSessionId, now)
        return persistActivation(activated, now)
    }

    /**
     * Commits an activation together with the retirement of every consent it replaces (#6487).
     *
     * Superseding happens HERE and not at creation, and the difference matters: a new consent is
     * born PENDING_SCA, so retiring the old one at creation would end the customer's access before
     * the replacement had been confirmed — and if the SCA then failed or was abandoned, they would
     * be left with no consent at all. The old grant stands until the new one is genuinely ACTIVE.
     *
     * Without this, `createConsent` never looked for an existing grant, the table has no unique
     * constraint, `revokeConsent` revokes one row by id, and `hasActiveConsent` answers over a
     * LIST — so duplicates accumulated and withdrawing access left the older ones granting it.
     */
    private suspend fun persistActivation(activated: Consent, now: OffsetDateTime): Consent {
        val granted = ConsentGranted(
            aggregateId = activated.id,
            partyId = activated.partyId,
            granteeId = activated.granteeId,
            granteeType = activated.granteeType,
            scopes = activated.scopes,
            validTo = activated.validTo,
            occurredAt = clock.instant(),
            sourceService = "consent-service",
        )

        val superseded = consentRepository
            .findActiveByGranteeAndParty(activated.granteeId, activated.partyId)
            .filter { activated.supersedes(it) }
            .map { old ->
                old.supersede(now) to ConsentSuperseded(
                    aggregateId = old.id,
                    partyId = old.partyId,
                    granteeId = old.granteeId,
                    scopes = old.scopes,
                    supersededBy = activated.id,
                    occurredAt = clock.instant(),
                    sourceService = "consent-service",
                )
            }

        // No log line here on purpose: this class logs nothing, and the durable record is the
        // ConsentSuperseded outbox event, which is queryable long after a log line has aged out.
        return consentRepository.saveSuperseding(activated, granted, superseded)
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
                sourceService = "consent-service",
            ),
        )
    }

    override suspend fun revokeConsent(command: RevokeConsentCommand): Consent {
        val consent = consentRepository.findById(command.consentId)
            ?: throw ConsentNotFoundException(command.consentId)

        if (consent.partyId != command.partyId) {
            throw ConsentNotOwnedByPartyException(command.consentId, command.partyId)
        }
        if (command.expectedGranteeId != null && consent.granteeId != command.expectedGranteeId) {
            throw ConsentGranteeMismatchException(command.consentId, command.expectedGranteeId)
        }

        val revoked = consent.revoke(command.reason, OffsetDateTime.now(clock))
        return consentRepository.save(
            revoked,
            ConsentRevoked(
                aggregateId = revoked.id,
                partyId = revoked.partyId,
                granteeId = revoked.granteeId,
                scopes = revoked.scopes,
                reason = command.reason,
                occurredAt = clock.instant(),
                sourceService = "consent-service",
            ),
        )
    }

    override suspend fun getConsent(consentId: UUID): Consent =
        consentRepository.findById(consentId) ?: throw ConsentNotFoundException(consentId)

    override suspend fun listConsentsForParty(partyId: UUID): List<Consent> = consentRepository.findByPartyId(partyId)

    override suspend fun listConsentsForGrantee(granteeId: String): List<Consent> =
        consentRepository.findByGranteeId(granteeId)

    /**
     * ADR-0198 D4's per-send marketing check, answered without disclosing the consent.
     *
     * Uses [ConsentRepository.findActiveByGranteeAndParty], which has existed since the repository
     * was written and had **no caller at all** — the query for this question was already here; only
     * a way to ask it was missing.
     *
     * `isActive` is re-checked in the domain rather than trusted from the repository's ACTIVE
     * filter, because "status = ACTIVE" and "active right now" are different claims: a consent with
     * `validTo` in the past still carries status ACTIVE until something transitions it. Asking a
     * yes/no question is exactly where that difference decides a send.
     */
    override suspend fun hasActiveConsent(command: CheckConsentCommand): Boolean {
        val now = OffsetDateTime.now(clock)
        return consentRepository
            .findActiveByGranteeAndParty(command.granteeId, command.partyId)
            .any { it.isActive(now) && it.hasScope(command.requiredScope) }
    }

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
