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

    /** How many monitoring findings the case has; 0 means there is nothing to load. */
    @Column(name = "monitoring_finding_count", nullable = false)
    var monitoringFindingCount: Int = 0

    /** Since when a monitoring finding has been waiting for an operator; for queries and alerts. */
    @Column(name = "monitoring_review_since")
    var monitoringReviewSince: Instant? = null

    /** Since when a confirmed sanctions match has required an exit that has not been carried out. */
    @Column(name = "monitoring_exit_owed_since")
    var monitoringExitOwedSince: Instant? = null

    @Column(name = "monitoring_exit_completed_at")
    var monitoringExitCompletedAt: Instant? = null

    @Column(name = "monitoring_exit_completed_by", columnDefinition = "TEXT")
    var monitoringExitCompletedBy: String? = null

    @Column(name = "monitoring_exit_evidence", columnDefinition = "TEXT")
    var monitoringExitEvidence: String? = null

    /** A pending (or, once confirmed, the confirmed) exit proposal: by whom, when, on what evidence (V15). */
    @Column(name = "monitoring_exit_proposed_at")
    var monitoringExitProposedAt: Instant? = null

    @Column(name = "monitoring_exit_proposed_by", columnDefinition = "TEXT")
    var monitoringExitProposedBy: String? = null

    @Column(name = "monitoring_exit_proposal_evidence", columnDefinition = "TEXT")
    var monitoringExitProposalEvidence: String? = null

    /**
     * The [version] this row has once the write that synchronised the monitoring store commits.
     * A release that does not know the store bumps [version] and leaves this behind, which is how
     * its writes are found and repaired.
     */
    @Column(name = "monitoring_synced_version")
    var monitoringSyncedVersion: Long? = null

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
 * Outbox row on the kernel v2 base (ADR-0327): `claimed_at` and `next_attempt_at` come from
 * [PanacheOutboxEntityV2]; the table gains them in `V12__outbox_v2.sql`.
 */
@Entity
@Table(name = "kyb_outbox")
class KybOutboxEntity : PanacheOutboxEntityV2()
