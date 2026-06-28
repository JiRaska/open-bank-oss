// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.`in`

import com.openbank.libs.api.pagination.CursorPage
import com.openbank.party.domain.model.*
import java.util.UUID

/** Command for customer self-registration via mobile app. Keycloak sub is the identity anchor. */
data class SelfRegisterPartyCommand(
    val keycloakSub: String,
    val emailVerified: Boolean,
    val partyType: PartyType,
    val legalName: String,
    val email: String,
    val phone: String?,
    val dateOfBirth: String?,
    val nationality: String?,
    val address: Address?,
)

/**
 * ADR-0072: resolve a party by Czech RČ blind index (1 person = 1 party dedup gate).
 * The [rawRc] is the caller-supplied RČ; the service normalises and hashes it server-side.
 * Returns null when the pepper is unconfigured (dedup off) or when no match is found.
 */
data class ResolvePartyByRcCommand(val rawRc: String)

/** Command to upload a KYC document binary via mobile. documentNumber is filled by compliance later. */
data class UploadDocumentCommand(
    val partyId: UUID,
    val documentType: DocumentType,
    val fileName: String?,
    val mimeType: String,
    val content: ByteArray,
)

data class CreatePartyCommand(
    val idempotencyKey: String,
    val partyType: PartyType,
    val legalName: String,
    val tradingName: String?,
    val dateOfBirth: String?,
    val nationality: String?,
    val taxId: String?,
    val registrationNumber: String?,
    val email: String,
    val phone: String?,
    val address: Address?,
    /** Explicit party id (ADR-0069 §B1: id == Keycloak sub for self-service onboarding). */
    val id: UUID? = null,
)

data class UpdatePartyCommand(
    val id: UUID,
    val email: String?,
    val phone: String?,
    val address: Address?,
    val tradingName: String?,
)

data class AddDocumentCommand(
    val partyId: UUID,
    val documentType: DocumentType,
    val documentNumber: String,
    val issuingCountry: String,
    val expiryDate: String?,
)

data class ErasePartyCommand(val id: UUID)

/** ADR-0055 bounded name search. `q` is normalised via SearchRequest; a blank/`*`/sub-2-char term returns an empty page. */
data class SearchPartiesQuery(val q: String?, val limit: Int = 20, val cursor: String? = null)

interface PartyUseCase {
    suspend fun searchParties(query: SearchPartiesQuery): CursorPage<Party>
    suspend fun createParty(cmd: CreatePartyCommand): Party
    suspend fun getParty(id: UUID): Party
    suspend fun updateParty(cmd: UpdatePartyCommand): Party
    suspend fun addDocument(cmd: AddDocumentCommand): PartyDocument
    suspend fun listDocuments(partyId: UUID): List<PartyDocument>

    /** GDPR Art. 15 (Right of Access): all party-service-direct PII for the subject (ADR-0118 §6). */
    suspend fun exportPartyData(id: UUID): PartyGdprExport
    suspend fun updateKycStatus(partyId: UUID, status: KycStatus): Party

    /** Record the AML screening outcome; re-evaluates the KYC+AML activation gate (ADR-0073). */
    suspend fun updateAmlStatus(partyId: UUID, amlStatus: AmlStatus): Party

    /**
     * List parties, optionally filtered by [status]. When [status] is null all parties are
     * returned (existing behaviour). When provided the result is scoped to that funnel stage,
     * enabling the onboarding cockpit KPI tiles and per-stage board views (ADR-0068).
     */
    suspend fun listParties(page: Int, size: Int, status: PartyStatus? = null): Map<String, Any>
    suspend fun eraseParty(cmd: ErasePartyCommand)

    /** Self-registration from mobile: idempotent by keycloakSub. Returns existing party if already registered. */
    suspend fun selfRegisterParty(cmd: SelfRegisterPartyCommand): Pair<Party, Boolean> // Party, isNew

    /** Returns party for the calling Keycloak sub, or null if not yet registered. */
    suspend fun getMyParty(keycloakSub: String): Party?

    /** Upload a KYC document binary file. Content stored as bytea (Sprint 1; replace with S3 in prod). */
    suspend fun uploadDocument(cmd: UploadDocumentCommand): PartyDocumentFile

    /**
     * Fetch the binary content of a KYC document file.
     * Returns null if the file does not exist or does not belong to [partyId] (access boundary enforcement).
     */
    suspend fun getDocumentContent(partyId: UUID, fileId: UUID): PartyDocumentFile?

    /** ADR-0072: resolve a party by RČ blind index. Returns null when pepper is off or no match. */
    suspend fun resolvePartyByRc(cmd: ResolvePartyByRcCommand): Party?

    /** ADR-0072: true when the RČ dedup pepper is configured; callers use this to distinguish 503 (pepper off) from 404 (not found). */
    fun isDedupAvailable(): Boolean
}
