// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.repository

import com.openbank.party.application.port.out.PartyDocumentFileRepository
import com.openbank.party.application.port.out.PartyDocumentRepository
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.domain.model.*
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.infrastructure.persistence.entity.PartyDocumentEntity
import com.openbank.party.infrastructure.persistence.entity.PartyDocumentFileEntity
import com.openbank.party.infrastructure.persistence.entity.PartyEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class PartyRepositoryImpl :
    PartyRepository,
    PanacheRepository<PartyEntity> {
    @Inject lateinit var clock: Clock

    override suspend fun save(party: Party): Party {
        val e = party.toEntity()
        Panache.withTransaction { persist(e) }.awaitSuspending()
        return party.copy()
    }

    override suspend fun findById(id: UUID): Party? =
        Panache.withSession { find("partyId", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByEmail(email: String): Party? =
        Panache.withSession { find("email", email).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun listAll(page: Int, size: Int): List<Party> =
        Panache.withSession { findAll().page(page, size).list() }.awaitSuspending().map { it.toDomain() }

    override suspend fun listByStatus(status: PartyStatus, page: Int, size: Int): List<Party> = Panache.withSession {
        find("status", status.name).page(page, size).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun countByStatus(status: PartyStatus): Long =
        Panache.withSession { count("status", status.name) }.awaitSuspending()

    // ADR-0055 bounded name search, extended by ADR-0228 D1 to the business keys a backoffice
    // operator actually holds: email, phone, tax id and company registration number alongside
    // legal/trading name. Case-insensitive substring over all six (lower(...) + the V7
    // gin_trgm_ops indexes cover the names; the rest are bounded by the same page limit). The
    // term arrives already LIKE-escaped from SearchRequest, so `%`/`_` typed by the user match
    // literally (ESCAPE '\'). Keyset pagination by partyId for constant cost at any depth.
    // Birth number stays out by design (data-minimisation, see openapi.yaml) — the RČ blind
    // index is used for dedup, not search.
    override suspend fun searchByBusinessKeys(escapedTerm: String, limit: Int, afterId: UUID?): List<Party> =
        Panache.withSession {
            val pattern = "%${escapedTerm.lowercase()}%"
            val predicate =
                "(lower(legalName) like ?1 escape '\\' or lower(tradingName) like ?1 escape '\\' " +
                    "or lower(email) like ?1 escape '\\' or lower(phone) like ?1 escape '\\' " +
                    "or lower(taxId) like ?1 escape '\\' or lower(registrationNumber) like ?1 escape '\\') "
            val query = if (afterId != null) {
                find("$predicate and partyId > ?2 order by partyId", pattern, afterId)
            } else {
                find("$predicate order by partyId", pattern)
            }
            query.page(0, limit).list()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun countAll(): Long = Panache.withSession { count() }.awaitSuspending()

    override suspend fun anonymize(id: UUID) {
        Panache.withTransaction {
            find("partyId", id).firstResult().chain { e ->
                if (e == null) return@chain io.smallrye.mutiny.Uni.createFrom().voidItem()
                e.legalName = "ANONYMIZED"
                // GDPR Art. 17 erasure: the tombstone email must stay unique (DB unique
                // constraint) but must NOT be derivable from the data subject. A fresh
                // random UUID satisfies uniqueness without re-encoding partyId, so the
                // erased value can't be correlated back to the party (K5).
                e.email = "erased-${UUID.randomUUID()}@erased.invalid"
                e.phone = null
                e.tradingName = null
                e.dateOfBirth = null
                e.nationality = null
                e.taxId = null
                e.registrationNumber = null
                e.addressLine1 = null
                e.addressLine2 = null
                e.addressCity = null
                e.addressPostalCode = null
                e.addressCountryCode = null
                e.status = "CLOSED"
                e.updatedAt = java.time.Instant.now(clock)
                io.smallrye.mutiny.Uni.createFrom().voidItem()
            }
        }.awaitSuspending()
    }

    override suspend fun update(party: Party): Party = Panache.withTransaction {
        find("partyId", party.id).firstResult().map { e ->
            e?.also {
                it.status = party.status.name
                it.email = party.email
                it.phone = party.phone
                // The hash is derived state, never supplied by a caller — recomputing it here is
                // what keeps it from drifting out of step with the number it indexes.
                it.phoneHash = PhoneDirectory.hash(party.phone)
                it.discoverable = party.discoverable
                it.tradingName = party.tradingName
                it.kycStatus = party.kycStatus.name
                it.amlStatus = party.amlStatus.name
                it.addressLine1 = party.address?.line1
                it.addressLine2 = party.address?.line2
                it.addressCity = party.address?.city
                it.addressPostalCode = party.address?.postalCode
                it.addressCountryCode = party.address?.countryCode
                it.updatedAt = party.updatedAt
                // Written in the same UPDATE as `status`: the DB enforces
                // (status = 'MERGED') = (merged_into IS NOT NULL) as a CHECK, so setting one
                // without the other fails the statement (ADR-0179).
                it.mergedInto = party.mergedIntoPartyId
            }
        }.replaceWith(party)
    }.awaitSuspending()

    /**
     * Discoverable parties whose phone hash is in [hashes]. Non-discoverable rows are excluded in
     * the query, not filtered afterwards: a party that has not opted in must never leave this
     * method, however the caller behaves.
     */
    override suspend fun findDiscoverableByPhoneHashes(hashes: Collection<String>): List<Party> {
        if (hashes.isEmpty()) return emptyList()
        return Panache.withSession {
            find("discoverable = true and phoneHash in ?1", hashes.toList()).list()
        }.awaitSuspending().map { it.toDomain() }
    }

    /** Scoped UPDATE of the opt-in flag alone — no read-modify-write of the whole aggregate. */
    override suspend fun updateDiscoverable(partyId: UUID, discoverable: Boolean, at: Instant): Boolean =
        Panache.withTransaction {
            update("discoverable = ?1, updatedAt = ?2 where partyId = ?3", discoverable, at, partyId)
        }.awaitSuspending() > 0

    override suspend fun findByKeycloakSub(sub: String): Party? =
        Panache.withSession { find("keycloakSub", sub).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByRcBlindIndex(index: String): Party? =
        Panache.withSession { find("rcBlindIndex", index).firstResult() }.awaitSuspending()?.toDomain()

    // Scoped UPDATE (ADR-0205 D4), not find-then-mutate-then-save of the whole Party aggregate —
    // matches NotificationRepository.markTerminalStatus's pattern (issue #1393). No-op (0 rows
    // affected, no error) if the party row does not exist.
    override suspend fun updateMarketingConsentProjection(partyId: UUID, granted: Boolean, at: Instant) {
        Panache.withTransaction {
            update("consentMarketing = ?1, consentMarketingUpdatedAt = ?2 where partyId = ?3", granted, at, partyId)
        }.awaitSuspending()
    }

    private fun Party.toEntity() = PartyEntity().also {
        it.partyId = id
        it.partyType = partyType.name
        it.status = status.name
        it.legalName = legalName
        it.tradingName = tradingName
        it.dateOfBirth = dateOfBirth
        it.nationality = nationality
        it.taxId = taxId
        it.registrationNumber = registrationNumber
        it.email = email
        it.phone = phone
        it.phoneHash = PhoneDirectory.hash(phone)
        it.discoverable = discoverable
        it.kycStatus = kycStatus.name
        it.amlStatus = amlStatus.name
        it.addressLine1 = address?.line1
        it.addressLine2 = address?.line2
        it.addressCity = address?.city
        it.addressPostalCode = address?.postalCode
        it.addressCountryCode = address?.countryCode
        it.createdAt = createdAt
        it.updatedAt = updatedAt
        it.keycloakSub = keycloakSub
        it.rcBlindIndex = rcBlindIndex
        it.rcIndexKeyVersion = rcIndexKeyVersion
        it.consentGdpr = consentGdpr
        it.consentMarketing = consentMarketing
        it.consentCapturedAt = consentCapturedAt
        it.consentMarketingUpdatedAt = consentMarketingUpdatedAt
        it.mergedInto = mergedIntoPartyId
    }

    private fun PartyEntity.toDomain() = Party(
        id = partyId, partyType = PartyType.valueOf(partyType), status = PartyStatus.valueOf(status),
        legalName = legalName, tradingName = tradingName, dateOfBirth = dateOfBirth,
        nationality = nationality, taxId = taxId, registrationNumber = registrationNumber,
        email = email, phone = phone, discoverable = discoverable, kycStatus = KycStatus.valueOf(kycStatus),
        address = if (addressLine1 !=
            null
        ) {
            Address(
                addressLine1!!,
                addressLine2,
                addressCity ?: "",
                addressPostalCode ?: "",
                addressCountryCode ?: "",
            )
        } else {
            null
        },
        createdAt = createdAt, updatedAt = updatedAt,
        keycloakSub = keycloakSub,
        amlStatus = AmlStatus.valueOf(amlStatus),
        rcBlindIndex = rcBlindIndex,
        rcIndexKeyVersion = rcIndexKeyVersion,
        consentGdpr = consentGdpr,
        consentMarketing = consentMarketing,
        consentCapturedAt = consentCapturedAt,
        consentMarketingUpdatedAt = consentMarketingUpdatedAt,
        mergedIntoPartyId = mergedInto,
    )
}

@ApplicationScoped
class PartyDocumentRepositoryImpl :
    PartyDocumentRepository,
    PanacheRepository<PartyDocumentEntity> {

    override suspend fun save(doc: PartyDocument): PartyDocument {
        val e = PartyDocumentEntity().also {
            it.documentId = doc.id
            it.partyId = doc.partyId
            it.documentType = doc.documentType.name
            it.documentNumber = doc.documentNumber
            it.issuingCountry = doc.issuingCountry
            it.expiryDate = doc.expiryDate
            it.verifiedAt = doc.verifiedAt
            it.createdAt = doc.createdAt
        }
        Panache.withTransaction { persist(e) }.awaitSuspending()
        return doc
    }

    override suspend fun findByPartyId(partyId: UUID): List<PartyDocument> =
        Panache.withSession { find("partyId", partyId).list() }.awaitSuspending().map {
            PartyDocument(
                it.documentId,
                it.partyId,
                DocumentType.valueOf(it.documentType),
                it.documentNumber,
                it.issuingCountry,
                it.expiryDate,
                it.verifiedAt,
                it.createdAt,
            )
        }
}

@ApplicationScoped
class PartyDocumentFileRepositoryImpl :
    PartyDocumentFileRepository,
    PanacheRepository<PartyDocumentFileEntity> {

    override suspend fun save(file: PartyDocumentFile): PartyDocumentFile {
        val e = PartyDocumentFileEntity().also {
            it.id = file.id
            it.partyId = file.partyId
            it.documentType = file.documentType.name
            it.fileName = file.fileName
            it.mimeType = file.mimeType
            it.content = file.content
            it.uploadedAt = file.uploadedAt
        }
        Panache.withTransaction { persist(e) }.awaitSuspending()
        return file
    }

    override suspend fun findByPartyId(partyId: UUID): List<PartyDocumentFile> =
        Panache.withSession { find("partyId", partyId).list() }.awaitSuspending().map { it.toDomain() }

    override suspend fun findByIdAndPartyId(id: UUID, partyId: UUID): PartyDocumentFile? = Panache.withSession {
        find("id = ?1 AND partyId = ?2", id, partyId).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun deleteByPartyId(partyId: UUID) {
        Panache.withTransaction { delete("partyId", partyId) }.awaitSuspending()
    }

    private fun PartyDocumentFileEntity.toDomain() = PartyDocumentFile(
        id = id,
        partyId = partyId,
        documentType = DocumentType.valueOf(documentType),
        fileName = fileName,
        mimeType = mimeType,
        content = content,
        uploadedAt = uploadedAt,
    )
}
