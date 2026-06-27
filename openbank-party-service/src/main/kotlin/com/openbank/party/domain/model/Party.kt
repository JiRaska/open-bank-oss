// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.party.domain.model

import java.time.Instant
import java.util.UUID

enum class PartyType { INDIVIDUAL, SOLE_TRADER, COMPANY, TRUST }
enum class PartyStatus { PENDING_KYC, ACTIVE, SUSPENDED, CLOSED }
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
    val address: Address?,
    val kycStatus: KycStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val keycloakSub: String? = null,
    /** AML screening outcome — the second key of the KYC+AML activation gate (ADR-0073). */
    val amlStatus: AmlStatus = AmlStatus.NOT_SCREENED,
    /** ADR-0072: HMAC-SHA256(pepper, canonical_rc) blind index — null if no RČ or non-Czech. */
    val rcBlindIndex: String? = null,
    val rcIndexKeyVersion: Int? = null,
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
