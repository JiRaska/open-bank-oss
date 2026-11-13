// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntityV2
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "kyb_cases")
class BusinessOnboardingCaseEntity : PanacheEntity() {
    @Column(name = "case_id", nullable = false, unique = true)
    lateinit var caseId: UUID

    @Column(name = "identifier_scheme", nullable = false)
    lateinit var identifierScheme: String

    @Column(name = "identifier_value", nullable = false)
    lateinit var identifierValue: String

    @Column(name = "initiator_party_id", nullable = false)
    lateinit var initiatorPartyId: UUID

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "extract_json", columnDefinition = "TEXT")
    var extractJson: String? = null

    @Column(name = "entity_party_id")
    var entityPartyId: UUID? = null

    @Column(name = "entity_party_active", nullable = false)
    var entityPartyActive: Boolean = false

    @Column(name = "required_signer_roles", nullable = false, columnDefinition = "TEXT")
    lateinit var requiredSignerRoles: String

    @Column(name = "required_signatures")
    var requiredSignatures: Int? = null

    @Column(name = "signers_json", nullable = false, columnDefinition = "TEXT")
    var signersJson: String = "[]"

    /** Denormalised for the invitation-claim lookup; the source of truth stays in [signersJson]. */
    @Column(name = "invitation_tokens", columnDefinition = "TEXT")
    var invitationTokens: String? = null

    /** Denormalised for "cases I am involved in"; the source of truth stays in [signersJson]. */
    @Column(name = "signer_party_ids", columnDefinition = "TEXT")
    var signerPartyIds: String? = null

    @Column(name = "review_reason", columnDefinition = "TEXT")
    var reviewReason: String? = null

    @Column(name = "questionnaire_json", columnDefinition = "TEXT")
    var questionnaireJson: String? = null

    @Column(name = "declarations_json", columnDefinition = "TEXT")
    var declarationsJson: String? = null

    @Column(name = "agreement_json", columnDefinition = "TEXT")
    var agreementJson: String? = null

    @Column(name = "screening_json", columnDefinition = "TEXT")
    var screeningJson: String? = null

    /** The status the case held when ongoing monitoring sent it to review; null when it did not. */
    @Column(name = "monitoring_review_from")
    var monitoringReviewFrom: String? = null

    @Column(name = "monitoring_review_opened_at")
    var monitoringReviewOpenedAt: Instant? = null

    /** Optimistic lock: bumped by Hibernate on every write of the row. */
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

@Entity
@Table(name = "kyb_registry_extracts")
class RegistryExtractEntity : PanacheEntity() {
    @Column(name = "identifier_scheme", nullable = false)
    lateinit var identifierScheme: String

    @Column(name = "identifier_value", nullable = false)
    lateinit var identifierValue: String

    @Column(name = "extract_json", nullable = false, columnDefinition = "TEXT")
    lateinit var extractJson: String

    @Column(name = "source", nullable = false)
    lateinit var source: String

    @Column(name = "fetched_at", nullable = false)
    lateinit var fetchedAt: Instant
}

/**
 * One human confirmation of a representation rule (#9711). Superseded rows are KEPT — the history
 * of who confirmed what, and when the rule changed, is the audit trail this control is for.
 */
@Entity
@Table(name = "kyb_representation_attestations")
class RepresentationAttestationEntity : PanacheEntity() {
    @Column(name = "attestation_id", nullable = false, unique = true)
    lateinit var attestationId: UUID

    @Column(name = "identifier_scheme", nullable = false)
    lateinit var identifierScheme: String

    @Column(name = "identifier_value", nullable = false)
    lateinit var identifierValue: String

    @Column(name = "rule_text_hash", nullable = false)
    lateinit var ruleTextHash: String

    @Column(name = "rule_text", columnDefinition = "TEXT")
    var ruleText: String? = null

    @Column(name = "parsed_mode", nullable = false)
    lateinit var parsedMode: String

    @Column(name = "parsed_signers")
    var parsedSigners: Int? = null

    @Column(name = "confirmed_signers", nullable = false)
    var confirmedSigners: Int = 0

    @Column(name = "confirmed_roles", nullable = false, columnDefinition = "TEXT")
    lateinit var confirmedRoles: String

    @Column(name = "attested_by", nullable = false)
    lateinit var attestedBy: String

    @Column(name = "attested_at", nullable = false)
    lateinit var attestedAt: Instant

    @Column(name = "superseded_at")
    var supersededAt: Instant? = null

    @Column(name = "note", columnDefinition = "TEXT")
    var note: String? = null
}

/**
 * Desired registration state of one screened subject of an approved case in the monitored-subject
 * registry (ADR-0376), and whether it was delivered. No personal data: the reference is opaque.
 */
@Entity
@Table(name = "kyb_monitored_subjects")
class MonitoredSubjectEntity : PanacheEntity() {
    @Column(name = "case_id", nullable = false)
    lateinit var caseId: UUID

    @Column(name = "subject_ref", nullable = false)
    lateinit var subjectRef: String

    @Column(name = "desired", nullable = false)
    lateinit var desired: String

    @Column(name = "sync_state", nullable = false)
    lateinit var syncState: String

    /** Moves on every change of [desired]; a delivery is recorded only against its own generation. */
    @Column(name = "generation", nullable = false)
    var generation: Long = 0

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @Column(name = "next_attempt_at", nullable = false)
    lateinit var nextAttemptAt: Instant

    /** When the row last became PENDING; the age the registration alert reads. */
    @Column(name = "pending_since")
    var pendingSince: Instant? = null

    @Column(name = "synced_at")
    var syncedAt: Instant? = null

    /** A short machine reason of the last failed attempt, never a response body. */
    @Column(name = "last_error")
    var lastError: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

/**
 * Outbox row on the kernel v2 base (ADR-0327): `claimed_at` and `next_attempt_at` come from
 * [PanacheOutboxEntityV2]; the table gains them in `V12__outbox_v2.sql`.
 */
@Entity
@Table(name = "kyb_outbox")
class KybOutboxEntity : PanacheOutboxEntityV2()
