// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.time.Instant
import java.util.UUID

enum class PartyType { INDIVIDUAL, SOLE_TRADER, COMPANY, TRUST }

/**
 * [CLOSED] and [MERGED] are both terminal, and deliberately distinct (ADR-0179): CLOSED is written
 * only by GDPR Art. 17 erasure, which anonymizes PII; MERGED retires a duplicate identity and
 * preserves everything, pointing at the survivor via [Party.mergedIntoPartyId].
 */
enum class PartyStatus { PENDING_KYC, ACTIVE, SUSPENDED, CLOSED, MERGED }
enum class DocumentType { NATIONAL_ID, PASSPORT, DRIVING_LICENCE, COMPANY_REGISTRATION, TAX_ID }

data class Party(
    val id: UUID,
    val partyType: PartyType,
    val status: PartyStatus,
    val legalName: String,
    val tradingName: String?,
    val dateOfBirth: String?,
    val nationality: String?,
    val taxId: String?,
    val registrationNumber: String?,
    val email: String,
    val phone: String?,
    /** Pay-to-phone findability. Opt-in — see [PhoneDirectory]. */
    val discoverable: Boolean = false,
    val address: Address?,
    val kycStatus: KycStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val keycloakSub: String? = null,
    /** AML screening outcome — the second key of the KYC+AML activation gate. */
    val amlStatus: AmlStatus = AmlStatus.NOT_SCREENED,
    /** ADR-0072: HMAC-SHA256(pepper, canonical_rc) blind index — null if no RČ or non-Czech. */
    val rcBlindIndex: String? = null,
    val rcIndexKeyVersion: Int? = null,
    /**
     * Onboarding consent capture (mobile app "Agreement" step). Null = not asked / not answered
     * (e.g. operator-created parties). `consentCapturedAt` is stamped whenever either value is
     * present, giving a minimal "when was this consent given" audit trail — it does NOT version
     * the consent text itself; that's a follow-up if the wording needs to change over time.
     */
    val consentGdpr: Boolean? = null,
    val consentMarketing: Boolean? = null,
    val consentCapturedAt: Instant? = null,
    /**
     * Stamped whenever [consentMarketing] changes AFTER onboarding (Profile screen toggle).
     * Null until the customer changes it post-onboarding — [consentCapturedAt] alone covers
     * the original onboarding-time value.
     */
    val consentMarketingUpdatedAt: Instant? = null,
    /**
     * ADR-0179: the surviving party this one was merged into. Non-null iff [status] is
     * [PartyStatus.MERGED] — the DB carries the same biconditional as a CHECK constraint.
     */
    val mergedIntoPartyId: UUID? = null,
)

data class Address(
    val line1: String,
    val line2: String?,
    val city: String,
    val postalCode: String,
    val countryCode: String,
)

enum class KycStatus { NOT_STARTED, IN_PROGRESS, APPROVED, REJECTED, EXPIRED }

enum class AmlStatus { NOT_SCREENED, CLEARED, BLOCKED }

data class PartyDocument(
    val id: UUID,
    val partyId: UUID,
    val documentType: DocumentType,
    val documentNumber: String,
    val issuingCountry: String,
    val expiryDate: String?,
    val verifiedAt: Instant?,
    val createdAt: Instant,
)

data class PartyDocumentFile(
    val id: UUID,
    val partyId: UUID,
    val documentType: DocumentType,
    val fileName: String?,
    val mimeType: String,
    val content: ByteArray,
    val uploadedAt: java.time.Instant,
)
