// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "parties")
class PartyEntity : PanacheEntity() {
    @Column(name = "party_id", nullable = false, unique = true)
    lateinit var partyId: UUID

    @Column(name = "party_type", nullable = false)
    lateinit var partyType: String

    @Column(name = "classification", nullable = false)
    lateinit var classification: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "legal_name", nullable = false)
    lateinit var legalName: String

    @Column(name = "trading_name")
    var tradingName: String? = null

    @Column(name = "date_of_birth")
    var dateOfBirth: String? = null

    @Column(name = "nationality")
    var nationality: String? = null

    @Column(name = "tax_id")
    var taxId: String? = null

    @Column(name = "registration_number")
    var registrationNumber: String? = null

    @Column(name = "legal_form")
    var legalForm: String? = null

    @Column(name = "registration_country")
    var registrationCountry: String? = null

    @Column(name = "email", nullable = false, unique = true)
    lateinit var email: String

    @Column(name = "phone")
    var phone: String? = null

    /**
     * Whether this party may be FOUND by someone who knows their phone number (pay-to-phone).
     * Opt-in: false until the customer turns it on. See [PhoneDirectory] for what the hash does
     * and does not protect.
     */
    @Column(name = "discoverable", nullable = false)
    var discoverable: Boolean = false

    /** SHA-256 of the E.164 [phone]; kept in step with [phone] on every write. */
    @Column(name = "phone_hash")
    var phoneHash: String? = null

    @Column(name = "address_line1")
    var addressLine1: String? = null

    @Column(name = "address_line2")
    var addressLine2: String? = null

    @Column(name = "address_city")
    var addressCity: String? = null

    @Column(name = "address_postal_code")
    var addressPostalCode: String? = null

    @Column(name = "address_country_code")
    var addressCountryCode: String? = null

    @Column(name = "kyc_status", nullable = false)
    lateinit var kycStatus: String

    @Column(name = "aml_status", nullable = false)
    lateinit var amlStatus: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @Column(name = "keycloak_sub", unique = true)
    var keycloakSub: String? = null

    /** ADR-0072: HMAC-SHA256(pepper, canonical_rc) — null for non-Czech or no RČ. */
    @Column(name = "rc_blind_index", unique = true, length = 64)
    var rcBlindIndex: String? = null

    /** Which pepper key version produced [rcBlindIndex]; used during pepper rotation. */
    @Column(name = "rc_index_key_version")
    var rcIndexKeyVersion: Int? = null

    /** Onboarding consent capture (mobile app "Agreement" step) — null = not asked/answered. */
    @Column(name = "consent_gdpr")
    var consentGdpr: Boolean? = null

    @Column(name = "consent_marketing")
    var consentMarketing: Boolean? = null

    @Column(name = "consent_captured_at")
    var consentCapturedAt: Instant? = null

    @Column(name = "consent_marketing_updated_at")
    var consentMarketingUpdatedAt: Instant? = null

    /** ADR-0179: surviving party id — non-null iff [status] is MERGED. */
    @Column(name = "merged_into")
    var mergedInto: UUID? = null
}

@Entity
@Table(name = "party_documents")
class PartyDocumentEntity : PanacheEntity() {
    @Column(name = "document_id", nullable = false, unique = true)
    lateinit var documentId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "document_type", nullable = false)
    lateinit var documentType: String

    @Column(name = "document_number", nullable = false)
    lateinit var documentNumber: String

    @Column(name = "issuing_country", nullable = false)
    lateinit var issuingCountry: String

    @Column(name = "expiry_date")
    var expiryDate: String? = null

    @Column(name = "verified_at")
    var verifiedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

@Entity
@Table(name = "party_document_files")
class PartyDocumentFileEntity {
    @Id
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "document_type", nullable = false)
    lateinit var documentType: String

    @Column(name = "file_name")
    var fileName: String? = null

    @Column(name = "mime_type", nullable = false)
    lateinit var mimeType: String

    @Column(name = "content", nullable = false, columnDefinition = "BYTEA")
    lateinit var content: ByteArray

    @Column(name = "uploaded_at", nullable = false)
    lateinit var uploadedAt: java.time.Instant
}

@Entity
@Table(name = "party_mandates")
class PartyMandateEntity : PanacheEntity() {
    @Column(name = "mandate_id", nullable = false, unique = true)
    lateinit var mandateId: UUID

    @Column(name = "principal_party_id", nullable = false)
    lateinit var principalPartyId: UUID

    @Column(name = "agent_party_id", nullable = false)
    lateinit var agentPartyId: UUID

    @Column(name = "role", nullable = false)
    lateinit var role: String

    @Column(name = "authority", nullable = false)
    lateinit var authority: String

    @Column(name = "source", nullable = false)
    lateinit var source: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "evidence_ref")
    var evidenceRef: String? = null

    @Column(name = "valid_from", nullable = false)
    lateinit var validFrom: Instant

    @Column(name = "valid_to")
    var validTo: Instant? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "revoke_reason", columnDefinition = "TEXT")
    var revokeReason: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
