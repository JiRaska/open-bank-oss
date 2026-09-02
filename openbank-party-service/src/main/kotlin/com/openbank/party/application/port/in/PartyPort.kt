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
    /** Onboarding consent capture (mobile app "Agreement" step). Null = not asked/answered. */
    val consentGdpr: Boolean? = null,
    val consentMarketing: Boolean? = null,
    /** Bank-owned canary only; the REST adapter authorizes this transition to ROLE_ADMIN. */
    val classification: PartyClassification = PartyClassification.CUSTOMER,
)

/**
 * ADR-0179: retire [id] as a duplicate of [mergedIntoPartyId]. Both parties must be live, distinct,
 * and the target must not itself be merged (the caller resolves chains to the final survivor).
 * [reason] is free text for the audit trail; [approvalReference] is the `mergeReference` passed to
 * POST /api/v1/transactions/merge-sweep (transaction-service), which posts the balance sweep as an
 * ADJUSTMENT transaction, so the money movement and the identity retirement are traceable to one
 * another from either end.
 */
data class MergePartyCommand(
    val id: UUID,
    val mergedIntoPartyId: UUID,
    val reason: String,
    val approvalReference: String?,
)

/**
 * A PATCH of a party record: every field is optional and null means "leave as it is".
 *
 * [legalName], [dateOfBirth] and [nationality] are the MATERIAL master-data fields (ADR-0256 D1,
 * #4458). They are here — and were not before — because the materiality classification on
 * `PARTY_UPDATED` is otherwise a branch nothing can reach: the only editable fields were contact
 * details, so every event this service could ever emit would be `NON_MATERIAL` while the contract
 * advertised a trigger. `legalName` was already documented in `openapi.yaml` and silently ignored
 * by the handler; this makes the spec true.
 */
data class UpdatePartyCommand(
    val id: UUID,
    val email: String?,
    val phone: String?,
    val address: Address?,
    val tradingName: String?,
    val legalName: String? = null,
    val dateOfBirth: String? = null,
    val nationality: String? = null,
)

/**
 * Revoke/re-grant marketing consent post-onboarding (mobile app Profile screen). Deliberately
 * NOT a general "update consent" command: `consentGdpr` is an immutable onboarding-time record
 * (data processing needed to run the account is a contract/legal-obligation basis, not GDPR
 * Art 6(1)(a) consent, so it isn't something to "revoke" while keeping the account open) —
 * only the marketing opt-in is genuinely revocable consent under Art 6(1)(a)/ePrivacy.
 */
data class UpdateMarketingConsentCommand(val id: UUID, val marketingConsent: Boolean)

data class AddDocumentCommand(
    val partyId: UUID,
    val documentType: DocumentType,
    val documentNumber: String,
    val issuingCountry: String,
    val expiryDate: String?,
)

data class ErasePartyCommand(val id: UUID)

data class SavePayeeCommand(val partyId: UUID, val name: String, val iban: String, val bic: String?)

/** Mirrors the app's own MAX_PAYEES = 30 hard cap (PayeeStore.kt). */
class PayeeLimitExceededException(partyId: UUID) :
    RuntimeException("Party $partyId already has the maximum of 30 saved payees")

/** ADR-0055 bounded name search. `q` is normalised via SearchRequest; a blank/`*`/sub-2-char term returns an empty page. */
data class SearchPartiesQuery(val q: String?, val limit: Int = 20, val cursor: String? = null)

interface PartyUseCase {
    suspend fun searchParties(query: SearchPartiesQuery): CursorPage<Party>
    suspend fun createParty(cmd: CreatePartyCommand): Party
    suspend fun getParty(id: UUID): Party
    suspend fun updateParty(cmd: UpdatePartyCommand): Party

    /**
     * Pay-to-phone directory lookup. Answers ONLY about parties that opted into being
     * discoverable, and only for hashes the caller already supplied — it cannot be used to
     * enumerate customers. Hashes that do not match are not recorded anywhere.
     */
    suspend fun lookupByPhoneHashes(hashes: Collection<String>): List<PhoneDirectoryMatch>

    /** Turn pay-to-phone findability on or off for one party (revocable, opt-in). */
    suspend fun updateDiscoverable(partyId: UUID, discoverable: Boolean): Boolean

    /** Post-onboarding marketing-consent toggle (mobile app Profile screen, revocable). */
    suspend fun updateMarketingConsent(cmd: UpdateMarketingConsentCommand): Party
    suspend fun addDocument(cmd: AddDocumentCommand): PartyDocument
    suspend fun listDocuments(partyId: UUID): List<PartyDocument>

    /** GDPR Art. 15 (Right of Access): all party-service-direct PII for the subject (ADR-0118 §6). */
    suspend fun exportPartyData(id: UUID): PartyGdprExport

    /**
     * GDPR Art. 20 (Right to Data Portability): the scoped, filtered projection of the Art. 15
     * export (ADR-0204). Consent/contract-basis data only — no Art. 6(1)(c) legal-obligation
     * fields (KYC/AML), counterparty IBANs redacted to their bank-code prefix (Art. 20(4)),
     * transaction history included as the primary portable dataset.
     */
    suspend fun exportPartyPortabilityData(id: UUID): PartyPortabilityExport
    suspend fun updateKycStatus(partyId: UUID, status: KycStatus): Party

    /** Record the AML screening outcome; re-evaluates the KYC+AML activation gate. */
    suspend fun updateAmlStatus(partyId: UUID, amlStatus: AmlStatus): Party

    /**
     * List parties, optionally filtered by [status]. When [status] is null all parties are
     * returned (existing behaviour). When provided the result is scoped to that funnel stage,
     * enabling the onboarding cockpit KPI tiles and per-stage board views (ADR-0068).
     */
    suspend fun listParties(page: Int, size: Int, status: PartyStatus? = null): Map<String, Any>
    suspend fun eraseParty(cmd: ErasePartyCommand)

    /**
     * ADR-0179: retire a duplicate identity into a surviving party. Returns the retired party.
     * Distinct from [eraseParty] — nothing is anonymized and no subject-rights event is emitted.
     */
    suspend fun mergeParty(cmd: MergePartyCommand): Party

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

    /**
     * Returns the Keycloak subject bound to [id], or null if not found or not bound.
     * Used by the GDPR Art. 15 export endpoint to verify subject-access self-service.
     */
    suspend fun getPartyKeycloakSub(id: UUID): String?

    /** Saved payees (TOP-10 #5), newest first — server side of the mobile app's device-local list. */
    suspend fun listPayees(partyId: UUID): List<Payee>

    /**
     * Upsert by (partyId, iban). Throws [PayeeLimitExceededException] when [partyId] would exceed
     * the 30-payee cap AND [iban] is not already one of its existing payees (a re-save of an
     * existing IBAN is always allowed — it can never itself push the count over the limit).
     */
    suspend fun savePayee(cmd: SavePayeeCommand): Payee

    /** No-op (not an error) if no such payee exists — matches the app's own idempotent remove(). */
    suspend fun deletePayee(partyId: UUID, iban: String)
}

/**
 * One directory hit. Carries the party id and the LEGAL NAME only — the caller already knows the
 * phone number they asked about, and nothing else about the payee is theirs to learn from a
 * lookup. In particular no account, no email and no address: the payment rail resolves the
 * account from the party id server-side.
 */
data class PhoneDirectoryMatch(val phoneHash: String, val partyId: UUID, val legalName: String)
