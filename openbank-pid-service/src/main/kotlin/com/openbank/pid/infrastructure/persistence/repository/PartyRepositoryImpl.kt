// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.case.CaseId
import com.openbank.libs.domain.case.CaseReasonCode
import com.openbank.libs.domain.case.CaseStatus
import com.openbank.libs.domain.case.CaseType
import com.openbank.libs.identity.MatchKey
import com.openbank.pid.application.port.`in`.PartySearchQuery
import com.openbank.pid.application.port.out.PartyAnonymisation
import com.openbank.pid.application.port.out.PartyRelationshipRepository
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.application.usecase.PartyErasedException
import com.openbank.pid.domain.model.*
import com.openbank.pid.infrastructure.persistence.entity.*
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ApplicationScoped
class PartyExternalIdRepo : PanacheRepository<PartyExternalIdEntity>

@ApplicationScoped
class PartyIdDocumentRepo : PanacheRepository<PartyIdDocumentEntity>

@ApplicationScoped
class PartyRelationshipRepo : PanacheRepository<PartyRelationshipEntity>

/**
 * Every Panache reactive op is wrapped in [Panache.withSession] (reads) or [Panache.withTransaction]
 * (writes) so a Hibernate Reactive session is open when the op runs — required because these are
 * Kotlin `suspend` methods and the request-scoped auto-session is not propagated across the
 * coroutine (it surfaced as "No current Mutiny.Session found"). Mirrors account-service's repos.
 */
@ApplicationScoped
class PartyRepositoryImpl(
    private val extIdRepo: PartyExternalIdRepo,
    private val docRepo: PartyIdDocumentRepo,
    private val relRepo: PartyRelationshipRepo,
    private val objectMapper: ObjectMapper,
) : PartyRepository,
    PanacheRepository<PartyEntity> {

    override suspend fun findById(id: UUID): Party? {
        val entity = Panache.withSession { find("id", id).firstResult() }.awaitSuspending() ?: return null
        return entity.toDomain(
            Panache.withSession { extIdRepo.find("partyId", id).list() }.awaitSuspending(),
            Panache.withSession { docRepo.find("partyId", id).list() }.awaitSuspending(),
            Panache.withSession { relRepo.find("partyId", id).list() }.awaitSuspending(),
        )
    }

    override suspend fun findByExternalId(type: ExternalIdType, value: String): Party? {
        val extId = Panache.withSession {
            extIdRepo.find("idType = ?1 AND idValue = ?2", type.name, value).firstResult()
        }.awaitSuspending() ?: return null
        return findById(extId.partyId)
    }

    override suspend fun search(query: PartySearchQuery): List<Party> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        var idx = 1

        query.givenName?.let {
            conditions.add("givenName ILIKE ?${idx++}")
            params.add("%$it%")
        }
        query.familyName?.let {
            conditions.add("familyName ILIKE ?${idx++}")
            params.add("%$it%")
        }
        query.birthdate?.let {
            conditions.add("birthdate = ?${idx++}")
            params.add(it)
        }
        query.email?.let {
            conditions.add("email = ?${idx++}")
            params.add(it)
        }
        query.status?.let {
            conditions.add("status = ?${idx++}")
            params.add(it.name)
        }
        query.afterId?.let {
            conditions.add("id > ?${idx++}")
            params.add(it)
        }

        val where = if (conditions.isEmpty()) "1=1" else conditions.joinToString(" AND ")
        val entities = Panache.withSession {
            find(where, *params.toTypedArray()).page(0, query.limit).list()
        }.awaitSuspending()

        return entities.map { entity -> loadAggregate(entity) }
    }

    override suspend fun save(party: Party): Party {
        Panache.withTransaction { persist(party.toEntity()) }.awaitSuspending()

        party.externalIds.forEach { extId ->
            Panache.withTransaction {
                extIdRepo.persist(
                    PartyExternalIdEntity().also {
                        it.partyId = party.id
                        it.idType = extId.type.name
                        it.idValue = extId.value
                        it.verifiedAt = extId.verifiedAt
                    },
                )
            }.awaitSuspending()
        }

        party.coreAttributes.idDocuments.forEach { doc ->
            Panache.withTransaction {
                docRepo.persist(
                    PartyIdDocumentEntity().also {
                        it.partyId = party.id
                        it.docType = doc.type.name
                        it.docNumber = doc.number
                        it.issuingCountry = doc.issuingCountry
                        it.issuedAt = doc.issuedAt
                        it.expiresAt = doc.expiresAt
                    },
                )
            }.awaitSuspending()
        }

        party.relationships.forEach { rel ->
            Panache.withTransaction { relRepo.persist(rel.toEntity()) }.awaitSuspending()
        }

        return findById(party.id)!!
    }

    /**
     * The parent update and every child write run in ONE transaction that first takes
     * `SELECT ... FOR UPDATE` on the party row and checks `erased_at` under that lock (GDPR Art. 17,
     * #15755). [anonymize] takes the same lock, so a write and an erasure serialise: either the
     * write commits first and the erase then cleans it, or the erase commits first and the write is
     * refused. The child steps are chained inside the one `withTransaction` (not nested ones, which
     * would merely join it).
     */
    override suspend fun update(party: Party): Party {
        Panache.withTransaction {
            find("id", party.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { existing ->
                requireNotNull(existing) { "Party ${party.id} not found" }.apply {
                    if (erasedAt != null) throw PartyErasedException("Party ${party.id} was erased")
                    applyUpdate(party)
                }
                party.externalIds.fold(Uni.createFrom().voidItem()) { chain, extId ->
                    chain.flatMap {
                        extIdRepo.find("partyId = ?1 AND idType = ?2", party.id, extId.type.name).firstResult()
                            .flatMap { exists ->
                                if (exists == null) {
                                    extIdRepo.persist(
                                        PartyExternalIdEntity().also {
                                            it.partyId = party.id
                                            it.idType = extId.type.name
                                            it.idValue = extId.value
                                            it.verifiedAt = extId.verifiedAt
                                        },
                                    ).replaceWithVoid()
                                } else {
                                    Uni.createFrom().voidItem()
                                }
                            }
                    }
                }
            }
        }.awaitSuspending()

        return findById(party.id)!!
    }

    override suspend fun existsByExternalId(type: ExternalIdType, value: String): Boolean = Panache.withSession {
        extIdRepo.count("idType = ?1 AND idValue = ?2", type.name, value)
    }.awaitSuspending() > 0

    override suspend fun anonymize(partyId: UUID, erasedAt: Instant): PartyAnonymisation {
        val at = erasedAt.atOffset(ZoneOffset.UTC)
        return Panache.withTransaction {
            find("id", partyId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { entity ->
                if (entity == null) {
                    Uni.createFrom().item(PartyAnonymisation.UNKNOWN)
                } else {
                    // A repeat delivery re-runs the whole overwrite and the deletes (keeping the
                    // original erased_at / updated_at), so anything written after the first erase
                    // is cleaned. Unchanged values make no UPDATE, so an untouched row stays untouched.
                    val first = entity.erasedAt == null
                    entity.anonymise(at, first)
                    // The external ids carry the RČ and EUDI blind indices (dedup keys) and the
                    // other national identifiers; ID documents carry document numbers. Neither
                    // has a reason to survive the person, so they are deleted, not blanked.
                    extIdRepo.delete("partyId", partyId)
                        .flatMap { docRepo.delete("partyId", partyId) }
                        .flatMap { relRepo.update("terminationReason = null where partyId = ?1", partyId) }
                        .replaceWith(if (first) PartyAnonymisation.FIRST else PartyAnonymisation.REPEAT)
                }
            }
        }.awaitSuspending()
    }

    /**
     * Overwrites every personal column. NOT NULL columns get a neutral sentinel (`GDPR_ERASED`,
     * 1900-01-01) rather than a value derived from the old one, so nothing about the person can
     * be recovered from the shell. Status becomes TERMINATED: an erased party is no longer usable
     * and must never come back as ACTIVE.
     */
    private fun PartyEntity.anonymise(at: OffsetDateTime, first: Boolean) {
        givenName = ERASED
        familyName = ERASED
        birthdate = ERASED_BIRTHDATE
        birthNumberEncrypted = null
        gender = null
        birthplace = null
        nationalities = emptyArray()
        email = null
        emailVerifiedAt = null
        phone = null
        phoneVerifiedAt = null
        dataBoxId = null
        permanentAddressStreet = null
        permanentAddressHouseNumber = null
        permanentAddressCity = null
        permanentAddressPostalCode = null
        permanentAddressCountry = null
        permanentAddressRuianCode = null
        robSyncedAt = null
        caseMetadata = null
        status = PartyStatus.TERMINATED.name
        if (first) {
            erasedAt = at
            updatedAt = at
        }
    }

    private companion object {
        const val ERASED = "GDPR_ERASED"
        val ERASED_BIRTHDATE: LocalDate = LocalDate.of(1900, 1, 1)
    }

    override suspend fun findCandidatesByMatchKey(matchKey: String): List<Party> {
        // The match key is "familyNorm|givenNorm|birthdate|birthplaceNorm" (see MatchKey.of).
        // Split to extract the normalized family name and the birth year for an efficient
        // DB-indexed coarse filter, then refine in memory by the full key.
        val parts = matchKey.split("|")
        val familyNorm = parts.getOrElse(0) { "" }.takeIf { it.isNotBlank() } ?: return emptyList()
        val birthYear = parts.getOrElse(2) { "" }
            .substringBefore("-").toIntOrNull() ?: return emptyList()

        val startDate = LocalDate.of(birthYear, 1, 1)
        val endDate = LocalDate.of(birthYear, 12, 31)

        // Coarse filter using the (family_name, birthdate) DB index.
        // LOWER() on familyName is safe for the ASCII-normalized (diacritic-stripped) form.
        val coarse = Panache.withSession {
            find(
                "LOWER(familyName) = ?1 AND birthdate >= ?2 AND birthdate <= ?3",
                familyNorm,
                startDate,
                endDate,
            ).list()
        }.awaitSuspending()

        if (coarse.isEmpty()) return emptyList()

        // Refine in memory: compute the full normalized key per candidate and compare.
        return coarse
            .filter { entity ->
                MatchKey.of(entity.familyName, entity.givenName, entity.birthdate, entity.birthplace) == matchKey
            }
            .map { entity -> loadAggregate(entity) }
    }

    override suspend fun findCandidatesForProbabilistic(familyInitial: String, birthYear: Int): List<Party> {
        val initial = familyInitial.trim().lowercase().takeIf { it.isNotBlank() } ?: return emptyList()
        val startDate = LocalDate.of(birthYear, 1, 1)
        val endDate = LocalDate.of(birthYear, 12, 31)
        // Loose coarse filter on the (family_name, birthdate) index — no in-memory key refine; the
        // probabilistic scorer decides. LIKE 'x%' keeps it index-friendly on LOWER(familyName).
        val coarse = Panache.withSession {
            find(
                "LOWER(familyName) LIKE ?1 AND birthdate >= ?2 AND birthdate <= ?3",
                "$initial%",
                startDate,
                endDate,
            ).list()
        }.awaitSuspending()
        return coarse.map { entity -> loadAggregate(entity) }
    }

    private suspend fun loadAggregate(entity: PartyEntity): Party = entity.toDomain(
        Panache.withSession { extIdRepo.find("partyId", entity.id).list() }.awaitSuspending(),
        Panache.withSession { docRepo.find("partyId", entity.id).list() }.awaitSuspending(),
        Panache.withSession { relRepo.find("partyId", entity.id).list() }.awaitSuspending(),
    )

    private fun PartyEntity.applyUpdate(party: Party) {
        status = party.status.name
        givenName = party.coreAttributes.givenName
        familyName = party.coreAttributes.familyName
        birthdate = party.coreAttributes.birthdate
        birthNumberEncrypted = party.coreAttributes.birthNumberEncrypted
        gender = party.coreAttributes.gender?.name
        birthplace = party.coreAttributes.birthplace
        nationalities = party.coreAttributes.nationalities.toTypedArray()
        verificationSource = party.coreAttributes.verificationSource.name
        verifiedAt = party.coreAttributes.verifiedAt
        email = party.contactAttributes.email
        emailVerifiedAt = party.contactAttributes.emailVerifiedAt
        phone = party.contactAttributes.phone
        phoneVerifiedAt = party.contactAttributes.phoneVerifiedAt
        preferredLanguage = party.contactAttributes.preferredLanguage
        dataBoxId = party.contactAttributes.dataBoxId
        kycLevel = party.kycAttributes.kycLevel.name
        kycCompletedAt = party.kycAttributes.kycCompletedAt
        kycExpiresAt = party.kycAttributes.kycExpiresAt
        amlRiskScore = party.kycAttributes.amlRiskScore?.name
        pepFlag = party.kycAttributes.pepFlag
        sanctionsFlag = party.kycAttributes.sanctionsFlag
        uboVerifiedAt = party.kycAttributes.uboVerifiedAt
        lastAmlReviewAt = party.kycAttributes.lastAmlReviewAt
        caseId = party.caseLifecycle?.caseId?.value
        caseType = party.caseLifecycle?.caseType?.name
        caseStatus = party.caseLifecycle?.status?.name
        caseLastActor = party.caseLifecycle?.lastActor
        caseLastReasonCode = party.caseLifecycle?.lastReasonCode?.name
        caseLastTransitionAt = party.caseLifecycle?.lastTransitionAt
        caseMetadata = party.caseLifecycle?.metadata?.let { metadata -> objectMapper.writeValueAsString(metadata) }
        party.addressAttributes?.permanentAddress?.let { addr ->
            permanentAddressStreet = addr.street
            permanentAddressHouseNumber = addr.houseNumber
            permanentAddressCity = addr.city
            permanentAddressPostalCode = addr.postalCode
            permanentAddressCountry = addr.countryCode
            permanentAddressRuianCode = addr.ruianCode
        }
        robSyncedAt = party.addressAttributes?.robSyncedAt
        updatedAt = party.updatedAt
        version = party.version
    }

    private fun PartyEntity.toDomain(
        externalIds: List<PartyExternalIdEntity>,
        documents: List<PartyIdDocumentEntity>,
        relationships: List<PartyRelationshipEntity>,
    ) = Party(
        id = id, partyType = PartyType.valueOf(partyType), status = PartyStatus.valueOf(status),
        externalIds = externalIds.map { ExternalId(ExternalIdType.valueOf(it.idType), it.idValue, it.verifiedAt) },
        coreAttributes = CoreAttributes(
            givenName = givenName, familyName = familyName, birthdate = birthdate,
            birthNumberEncrypted = birthNumberEncrypted,
            gender = gender?.let { Gender.valueOf(it) }, birthplace = birthplace,
            nationalities = nationalities.toList(),
            idDocuments = documents.map { doc ->
                IdDocument(
                    IdDocumentType.valueOf(doc.docType),
                    doc.docNumber,
                    doc.issuingCountry,
                    doc.issuedAt,
                    doc.expiresAt,
                )
            },
            verificationSource = VerificationSource.valueOf(verificationSource), verifiedAt = verifiedAt,
        ),
        addressAttributes = if (permanentAddressCity != null || robSyncedAt != null) {
            AddressAttributes(
                permanentAddress = permanentAddressCity?.let {
                    Address(
                        permanentAddressStreet,
                        permanentAddressHouseNumber,
                        it,
                        permanentAddressPostalCode ?: "",
                        permanentAddressCountry ?: "CZ",
                        permanentAddressRuianCode,
                    )
                },
                mailingAddress = null,
                robSyncedAt = robSyncedAt,
            )
        } else {
            null
        },
        contactAttributes = ContactAttributes(
            email,
            emailVerifiedAt,
            phone,
            phoneVerifiedAt,
            preferredLanguage,
            dataBoxId,
        ),
        kycAttributes = KycAttributes(
            KycLevel.valueOf(kycLevel),
            kycCompletedAt,
            kycExpiresAt,
            amlRiskScore?.let(AmlRiskScore::valueOf),
            pepFlag,
            sanctionsFlag,
            uboVerifiedAt,
            lastAmlReviewAt,
        ),
        relationships = relationships.map { it.toDomain() },
        caseLifecycle = caseLifecycle(),
        createdAt = createdAt, updatedAt = updatedAt, version = version, erasedAt = erasedAt,
    )

    private fun Party.toEntity() = PartyEntity().also {
        it.id = id
        it.partyType = partyType.name
        it.status = status.name
        it.givenName = coreAttributes.givenName
        it.familyName = coreAttributes.familyName
        it.birthdate = coreAttributes.birthdate
        it.birthNumberEncrypted = coreAttributes.birthNumberEncrypted
        it.gender = coreAttributes.gender?.name
        it.birthplace = coreAttributes.birthplace
        it.nationalities = coreAttributes.nationalities.toTypedArray()
        it.verificationSource = coreAttributes.verificationSource.name
        it.verifiedAt = coreAttributes.verifiedAt
        it.email = contactAttributes.email
        it.emailVerifiedAt = contactAttributes.emailVerifiedAt
        it.phone = contactAttributes.phone
        it.phoneVerifiedAt = contactAttributes.phoneVerifiedAt
        it.preferredLanguage = contactAttributes.preferredLanguage
        it.dataBoxId = contactAttributes.dataBoxId
        it.kycLevel = kycAttributes.kycLevel.name
        it.kycCompletedAt = kycAttributes.kycCompletedAt
        it.kycExpiresAt = kycAttributes.kycExpiresAt
        it.amlRiskScore = kycAttributes.amlRiskScore?.name
        it.pepFlag = kycAttributes.pepFlag
        it.sanctionsFlag = kycAttributes.sanctionsFlag
        it.uboVerifiedAt = kycAttributes.uboVerifiedAt
        it.lastAmlReviewAt = kycAttributes.lastAmlReviewAt
        it.caseId = caseLifecycle?.caseId?.value
        it.caseType = caseLifecycle?.caseType?.name
        it.caseStatus = caseLifecycle?.status?.name
        it.caseLastActor = caseLifecycle?.lastActor
        it.caseLastReasonCode = caseLifecycle?.lastReasonCode?.name
        it.caseLastTransitionAt = caseLifecycle?.lastTransitionAt
        it.caseMetadata = caseLifecycle?.metadata?.let { metadata -> objectMapper.writeValueAsString(metadata) }
        it.createdAt = createdAt
        it.updatedAt = updatedAt
        it.version = version
    }

    private fun PartyEntity.caseLifecycle(): PartyCaseLifecycle? {
        val currentCaseId = caseId ?: return null
        val currentCaseType = caseType ?: return null
        val currentCaseStatus = caseStatus ?: return null
        val currentActor = caseLastActor ?: return null
        val currentReason = caseLastReasonCode ?: return null
        val currentTransitionAt = caseLastTransitionAt ?: return null

        return PartyCaseLifecycle(
            caseId = CaseId(currentCaseId),
            caseType = CaseType.valueOf(currentCaseType),
            status = CaseStatus.valueOf(currentCaseStatus),
            lastActor = currentActor,
            lastReasonCode = CaseReasonCode.valueOf(currentReason),
            lastTransitionAt = currentTransitionAt,
            metadata = caseMetadata
                ?.takeIf { it.isNotBlank() }
                ?.let { objectMapper.readValue(it, object : TypeReference<Map<String, String>>() {}) }
                .orEmpty(),
        )
    }

    private fun PartyRelationshipEntity.toDomain() = PartyRelationship(
        id = id,
        partyId = partyId,
        role = PartyRole.valueOf(role),
        status = RelationshipStatus.valueOf(status),
        onboardedAt = onboardedAt,
        onboardingChannel = OnboardingChannel.valueOf(onboardingChannel),
        terminatedAt = terminatedAt,
        terminationReason = terminationReason,
    )

    private fun PartyRelationship.toEntity() = PartyRelationshipEntity().also {
        it.id = id
        it.partyId = partyId
        it.role = role.name
        it.status = status.name
        it.onboardedAt = onboardedAt
        it.onboardingChannel = onboardingChannel.name
        it.terminatedAt = terminatedAt
        it.terminationReason = terminationReason
    }
}

@ApplicationScoped
class PartyRelationshipRepositoryImpl(private val relRepo: PartyRelationshipRepo) : PartyRelationshipRepository {

    override suspend fun findById(id: UUID): PartyRelationship? =
        Panache.withSession { relRepo.find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByPartyId(partyId: UUID): List<PartyRelationship> =
        Panache.withSession { relRepo.find("partyId", partyId).list() }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(relationship: PartyRelationship): PartyRelationship {
        Panache.withTransaction { relRepo.persist(relationship.toEntity()) }.awaitSuspending()
        return Panache.withSession { relRepo.find("id", relationship.id).firstResult() }.awaitSuspending()!!.toDomain()
    }

    override suspend fun update(relationship: PartyRelationship): PartyRelationship = Panache.withTransaction {
        relRepo.find("id", relationship.id).firstResult().map { existing ->
            requireNotNull(existing) { "Relationship ${relationship.id} not found" }.also {
                it.status = relationship.status.name
                it.terminatedAt = relationship.terminatedAt
                it.terminationReason = relationship.terminationReason
            }
        }
    }.awaitSuspending().toDomain()

    private fun PartyRelationshipEntity.toDomain() = PartyRelationship(
        id = id,
        partyId = partyId,
        role = PartyRole.valueOf(role),
        status = RelationshipStatus.valueOf(status),
        onboardedAt = onboardedAt,
        onboardingChannel = OnboardingChannel.valueOf(onboardingChannel),
        terminatedAt = terminatedAt,
        terminationReason = terminationReason,
    )

    private fun PartyRelationship.toEntity() = PartyRelationshipEntity().also {
        it.id = id
        it.partyId = partyId
        it.role = role.name
        it.status = status.name
        it.onboardedAt = onboardedAt
        it.onboardingChannel = onboardingChannel.name
        it.terminatedAt = terminatedAt
        it.terminationReason = terminationReason
    }
}
