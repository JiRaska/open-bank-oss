// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CallerPartyId
import com.openbank.delegation.application.port.`in`.ExternalDisclosureUseCase
import com.openbank.delegation.application.port.`in`.IssueExternalDisclosureCommand
import com.openbank.delegation.application.port.`in`.IssuedExternalDisclosure
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.ExternalDisclosureArtifact
import com.openbank.delegation.application.port.out.ExternalDisclosureDocumentExporter
import com.openbank.delegation.application.port.out.ExternalDisclosureRepository
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.ExternalDisclosure
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** The only issuer path for D7b; recipient-facing delivery remains a separate boundary. */
@ApplicationScoped
class ExternalDisclosureService(
    private val delegationRepository: DelegationRepository,
    private val disclosureRepository: ExternalDisclosureRepository,
    private val documentExporter: ExternalDisclosureDocumentExporter,
    private val clock: Clock,
    private val random: SecureRandom,
) : ExternalDisclosureUseCase {

    @Inject
    constructor(
        delegationRepository: DelegationRepository,
        disclosureRepository: ExternalDisclosureRepository,
        documentExporter: ExternalDisclosureDocumentExporter,
    ) : this(delegationRepository, disclosureRepository, documentExporter, Clock.systemUTC(), SecureRandom())

    override suspend fun issue(command: IssueExternalDisclosureCommand): IssuedExternalDisclosure {
        val now = OffsetDateTime.now(clock)
        val grant = delegationRepository.findById(command.delegationId)
            ?: throw NotFoundException("delegation grant not found")
        requireGrantor(command.callerPartyId, grant.grantorPartyId)
        require(grant.isActiveOn(now)) { "external disclosure requires an active delegation" }
        require(grant.resourceType == DelegationResourceType.DOCUMENT && grant.resourceId == command.documentId) {
            "external disclosure must name the delegation's single document"
        }
        require(grant.hasCapability(DelegationCapability.OBJECT_READ)) {
            "external disclosure requires OBJECT_READ"
        }
        val exposure = requireNotNull(grant.exposure) { "external disclosure requires explicit exposure controls" }
        require(command.maxViews <= (exposure.maxViews ?: command.maxViews)) {
            "external disclosure view limit exceeds delegation exposure"
        }
        require(command.expiresAt.isAfter(now) && !command.expiresAt.isAfter(now.plusDays(MAX_VALIDITY_DAYS))) {
            "external disclosure must expire within $MAX_VALIDITY_DAYS days"
        }
        require(grant.validTo == null || !command.expiresAt.isAfter(grant.validTo)) {
            "external disclosure cannot outlive its delegation"
        }

        val linkSecret = randomSecret()
        val otp = "%06d".format(Locale.ROOT, random.nextInt(OTP_BOUND))
        val disclosureId = Ids.newId()
        val disclosure = ExternalDisclosure(
            id = disclosureId,
            delegationId = grant.id,
            documentId = command.documentId,
            recipientLabel = command.recipientLabel,
            linkSecretHash = ExternalDisclosure.secretHash(disclosureId, linkSecret),
            otpHash = ExternalDisclosure.secretHash(disclosureId, otp),
            expiresAt = command.expiresAt,
            maxViews = command.maxViews,
            createdAt = now,
        )
        return IssuedExternalDisclosure(disclosureRepository.issue(disclosure), linkSecret, otp)
    }

    override suspend fun revoke(disclosureId: UUID, callerPartyId: CallerPartyId): ExternalDisclosure {
        val disclosure =
            disclosureRepository.findById(disclosureId) ?: throw NotFoundException("external disclosure not found")
        val grant = delegationRepository.findById(disclosure.delegationId)
            ?: throw NotFoundException("delegation grant not found")
        requireGrantor(callerPartyId, grant.grantorPartyId)
        return requireNotNull(disclosureRepository.mutateById(disclosureId) { it.revoke(OffsetDateTime.now(clock)) }) {
            "external disclosure disappeared during revocation"
        }
    }

    override suspend fun verifyOtp(disclosureId: UUID, linkSecret: String, otp: String): ExternalDisclosure {
        val linkSecretHash = ExternalDisclosure.secretHash(disclosureId, linkSecret)
        val updated = disclosureRepository.mutateByLinkSecretHash(linkSecretHash) {
            it.verifyOtpAttempt(otp, OffsetDateTime.now(clock))
        } ?: throw NotFoundException("external disclosure unavailable")
        if (!updated.matchesOtp(otp)) throw NotFoundException("external disclosure unavailable")
        return updated
    }

    override suspend fun release(disclosureId: UUID, linkSecret: String): ExternalDisclosureArtifact {
        val now = OffsetDateTime.now(clock)
        val disclosure = disclosureRepository.findById(disclosureId)
            ?: throw NotFoundException("external disclosure unavailable")
        disclosure.requireReleasable(linkSecret, now)

        // The remote service sees a sealed derivative only. If it fails, nothing reaches the
        // caller and the later CAS is never attempted, so a transient export outage cannot burn
        // an allowed view. A concurrent revoke/view wins at the CAS and likewise returns no bytes.
        val artifact = documentExporter.export(
            documentId = disclosure.documentId,
            disclosureId = disclosure.id,
            recipientLabel = disclosure.recipientLabel,
            issuedAt = disclosure.createdAt.toInstant(),
        )
        requireNotNull(disclosureRepository.mutateById(disclosureId) { it.consumeView(linkSecret, now) }) {
            "external disclosure unavailable"
        }
        return artifact
    }

    private fun requireGrantor(callerPartyId: CallerPartyId, grantorPartyId: UUID) {
        if (callerPartyId != grantorPartyId) {
            throw ForbiddenException("external disclosure is grantor-only")
        }
    }

    private fun randomSecret(): String = ByteArray(LINK_SECRET_BYTES).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private companion object {
        const val LINK_SECRET_BYTES = 32
        const val OTP_BOUND = 1_000_000
        const val MAX_VALIDITY_DAYS = 7L
    }
}
