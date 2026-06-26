// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.application.usecase

import com.openbank.libs.identity.BlindIndex
import com.openbank.libs.identity.RodneCislo
import com.openbank.pid.application.port.`in`.RegisterIdentityCommand
import com.openbank.pid.application.port.`in`.RegisterIdentityUseCase
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.domain.model.AmlRiskScore
import com.openbank.pid.domain.model.ContactAttributes
import com.openbank.pid.domain.model.CoreAttributes
import com.openbank.pid.domain.model.ExternalId
import com.openbank.pid.domain.model.ExternalIdType
import com.openbank.pid.domain.model.KycAttributes
import com.openbank.pid.domain.model.KycLevel
import com.openbank.pid.domain.model.Party
import com.openbank.pid.domain.model.PartyStatus
import com.openbank.pid.domain.model.VerificationSource
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

/**
 * Populate the pid identity index from the onboarding flow (issue #1294).
 *
 * pid is the identity-resolution golden record (ADR-0072), but nothing was writing into it — the
 * live onboarding path creates parties in party-service, so pid stayed empty and [resolve] always
 * returned NO_MATCH (a vacuous dedup). This use case is the missing write side: at onboarding the
 * edge holds the full identity *including the Czech RČ* (sent as `taxId`), and — after the party is
 * created in party-service — registers a minimal identity record here under the **same** party id.
 *
 * Crucially it writes the **BIRTH_NUMBER blind index** (`HMAC-SHA256(pepper, canonical RČ)`), which
 * `createParty` never did, so tier-1 deterministic RČ dedup finally has data to match against. The
 * record is a derived index, not a new party: it emits **no** party event (it must not loop back
 * through party-events → party-service) and carries no case lifecycle or relationships.
 *
 * Idempotent: re-registering the same party id merges the external ids and refreshes the core
 * attributes. A BIRTH_NUMBER / KEYCLOAK_ID already bound to a *different* party is a collision (the
 * resolver should have caught it first) and is rejected.
 *
 * When the pepper is absent (no Vault) the RČ blind index is skipped with a WARN — the record still
 * registers with name/birthdate (tier-2), mirroring [IdentityResolutionService]'s degradation.
 */
@ApplicationScoped
class IdentityRegistrationService(
    private val partyRepository: PartyRepository,
    @ConfigProperty(name = "openbank.pid.birth-number-pepper")
    private val pepperHex: Optional<String>,
    private val clock: Clock,
) : RegisterIdentityUseCase {

    override suspend fun register(command: RegisterIdentityCommand): Party {
        val now = OffsetDateTime.now(clock)
        val blindIndex = blindIndexFor(command.birthNumberRaw)
        val eudiIndex = eudiBlindIndexFor(command.eudiPidSubVerified)

        blindIndex?.let { rejectIfBoundElsewhere(ExternalIdType.BIRTH_NUMBER, it, command.partyId) }
        command.keycloakSub?.let { rejectIfBoundElsewhere(ExternalIdType.KEYCLOAK_ID, it, command.partyId) }
        eudiIndex?.let { rejectIfBoundElsewhere(ExternalIdType.EUDI_PID_SUB, it, command.partyId) }

        val indices = Indices(blindIndex, eudiIndex)
        val existing = partyRepository.findById(command.partyId)
        return if (existing == null) {
            partyRepository.save(newIndexParty(command, indices, now))
        } else {
            partyRepository.update(mergeInto(existing, command, indices, now))
        }
    }

    /** The computed blind indices to write as external ids (RČ + EUDI PID), threaded through builders. */
    private data class Indices(val birthNumber: String?, val eudiPidSub: String?)

    private fun newIndexParty(command: RegisterIdentityCommand, indices: Indices, now: OffsetDateTime): Party = Party(
        id = command.partyId,
        partyType = command.partyType,
        status = PartyStatus.ACTIVE,
        externalIds = externalIdsFor(command, indices, now),
        coreAttributes = CoreAttributes(
            givenName = command.givenName,
            familyName = command.familyName,
            birthdate = command.birthdate,
            birthNumberEncrypted = null,
            gender = null,
            birthplace = command.birthplace,
            nationalities = command.nationalities,
            idDocuments = emptyList(),
            verificationSource = VerificationSource.API_UPLOAD,
            verifiedAt = now,
        ),
        addressAttributes = null,
        contactAttributes = ContactAttributes(null, null, null, null),
        kycAttributes = KycAttributes(
            kycLevel = KycLevel.NONE,
            kycCompletedAt = null,
            kycExpiresAt = null,
            amlRiskScore = AmlRiskScore.LOW,
            pepFlag = false,
            sanctionsFlag = false,
            uboVerifiedAt = null,
            lastAmlReviewAt = null,
        ),
        relationships = emptyList(),
        caseLifecycle = null,
        createdAt = now,
        updatedAt = now,
        version = 0,
    )

    private fun mergeInto(
        existing: Party,
        command: RegisterIdentityCommand,
        indices: Indices,
        now: OffsetDateTime,
    ): Party {
        val merged = (existing.externalIds + externalIdsFor(command, indices, now))
            .distinctBy { it.type to it.value }
        return existing.copy(
            externalIds = merged,
            coreAttributes = existing.coreAttributes.copy(
                givenName = command.givenName,
                familyName = command.familyName,
                birthdate = command.birthdate,
                birthplace = command.birthplace ?: existing.coreAttributes.birthplace,
                nationalities = command.nationalities.ifEmpty { existing.coreAttributes.nationalities },
            ),
            updatedAt = now,
            version = existing.version + 1,
        )
    }

    private fun externalIdsFor(
        command: RegisterIdentityCommand,
        indices: Indices,
        now: OffsetDateTime,
    ): List<ExternalId> = buildList {
        command.keycloakSub?.let { add(ExternalId(ExternalIdType.KEYCLOAK_ID, it, now)) }
        indices.birthNumber?.let { add(ExternalId(ExternalIdType.BIRTH_NUMBER, it, now)) }
        indices.eudiPidSub?.let { add(ExternalId(ExternalIdType.EUDI_PID_SUB, it, now)) }
    }

    private suspend fun rejectIfBoundElsewhere(type: ExternalIdType, value: String, partyId: UUID) {
        val owner = partyRepository.findByExternalId(type, value)
        if (owner != null && owner.id != partyId) {
            throw PartyAlreadyExistsException("$type already bound to party ${owner.id}, not $partyId")
        }
    }

    private fun blindIndexFor(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val pepper = pepperHex.orElse("").takeIf { it.isNotBlank() } ?: run {
            Log.warn(
                "IdentityRegistrationService: birth-number-pepper not configured — registering without the RČ blind index (tier-2 only)",
            )
            return null
        }
        val parsed = RodneCislo.parse(raw)
        if (parsed !is RodneCislo.Parsed) {
            Log.warn("IdentityRegistrationService: RČ unparseable — registering without the RČ blind index")
            return null
        }
        return BlindIndex.compute(pepper.decodeHex(), parsed.canonical)
    }

    /** Blind index of a verified EUDI PID subject identifier (ADR-0094). Generic — not RČ-parsed. */
    private fun eudiBlindIndexFor(eudiPidSubVerified: String?): String? {
        if (eudiPidSubVerified.isNullOrBlank()) return null
        val pepper = pepperHex.orElse("").takeIf { it.isNotBlank() } ?: run {
            Log.warn("IdentityRegistrationService: pepper not configured — registering without the EUDI PID index")
            return null
        }
        return BlindIndex.compute(pepper.decodeHex(), eudiPidSubVerified)
    }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte() }
    }

    private companion object {
        const val HEX_RADIX = 16
    }
}
