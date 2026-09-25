// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.GraphGuaranteeReceipt
import com.openbank.lending.application.port.out.GraphGuaranteeRepository
import com.openbank.lending.application.port.out.LendingGraphProofPort
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.domain.model.GraphGuaranteeFact
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import com.openbank.libs.domain.identifiers.LoanId
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Source-owned registration. External proofs run only on this administrative path, never when a
 * loan is disbursed, repaid or read. No route calls this until scoped authorization is delivered.
 */
@ApplicationScoped
class GraphGuaranteeRegistrationService(
    private val loans: LoanRepository,
    private val proofs: LendingGraphProofPort,
    private val guarantees: GraphGuaranteeRepository,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.lending.graph.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.lending.graph.writer-enabled") private val writerEnabled: Boolean,
) {
    suspend fun propose(proposal: GraphGuaranteeProposal, actor: String): GraphGuaranteeFact {
        requireEnabled()
        requireHumanActor(actor)
        proposal.validate()
        verifyCurrentSources(proposal)
        return guarantees.propose(proposal, actor, Instant.now(clock))
    }

    suspend fun proposeIdempotent(
        proposal: GraphGuaranteeProposal,
        actor: String,
        key: String,
        fingerprint: String,
    ): GraphGuaranteeReceipt {
        requireEnabled()
        requireHumanActor(actor)
        proposal.validate()
        guarantees.findReceipt("PROPOSE", key, fingerprint)?.let { return it }
        verifyCurrentSources(proposal)
        return guarantees.proposeIdempotent(proposal, actor, Instant.now(clock), key, fingerprint)
    }

    suspend fun decideIdempotent(
        loanId: UUID,
        guaranteeId: UUID,
        decision: GraphGuaranteeStatus,
        actor: String,
        key: String,
        fingerprint: String,
    ): GraphGuaranteeReceipt {
        requireEnabled()
        requireHumanActor(actor)
        require(decision != GraphGuaranteeStatus.PENDING) { "a decision is required" }
        guarantees.findReceipt("DECIDE", key, fingerprint)?.let { return it }
        val proposed = guarantees.find(guaranteeId)
        if (proposed?.proposal?.loanId != loanId) {
            throw com.openbank.lending.application.port.out.GraphGuaranteeNotFound()
        }
        if (proposed.status != GraphGuaranteeStatus.PENDING) {
            // A same-key request may have committed between the first receipt read and this fact read.
            guarantees.findReceipt("DECIDE", key, fingerprint)?.let { return it }
            throw IllegalArgumentException("guarantee is already decided")
        }
        require(actor != proposed.proposedBy) { "maker cannot decide own guarantee" }
        if (decision == GraphGuaranteeStatus.APPROVED) verifyCurrentSources(proposed.proposal)
        return guarantees.decideIdempotent(
            loanId,
            guaranteeId,
            decision,
            actor,
            Instant.now(clock),
            key,
            fingerprint,
        )
    }

    suspend fun decide(guaranteeId: UUID, decision: GraphGuaranteeStatus, actor: String): GraphGuaranteeFact {
        requireEnabled()
        requireHumanActor(actor)
        require(decision != GraphGuaranteeStatus.PENDING) { "a decision is required" }
        val proposed = requireNotNull(guarantees.find(guaranteeId)) { "guarantee does not exist" }
        require(proposed.status == GraphGuaranteeStatus.PENDING) { "guarantee is already decided" }
        require(actor != proposed.proposedBy) { "maker cannot decide own guarantee" }
        if (decision == GraphGuaranteeStatus.APPROVED) verifyCurrentSources(proposed.proposal)
        // The repository locks and repeats the state and maker checks before the atomic outbox write.
        return guarantees.decide(guaranteeId, decision, actor, Instant.now(clock))
    }

    private suspend fun verifyCurrentSources(proposal: GraphGuaranteeProposal) {
        requireNotNull(loans.findById(LoanId(proposal.loanId)).awaitSuspending()) { "loan does not exist" }
        require(proofs.hasVerifiedGuarantorIdentity(proposal.guarantorPartyId)) {
            "guarantor identity is not verified"
        }
        require(
            proofs.matchesSignedGuarantee(
                proposal.sourceDocumentId,
                proposal.loanId,
                proposal.guarantorPartyId,
                bankScope,
                proposal.sourceSha256,
            ),
        ) { "signed guarantee evidence does not match" }
    }

    private fun requireEnabled() {
        check(writerEnabled) { "Lending graph writer is disabled" }
        check(bankScope.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) { "invalid deployment bank scope" }
    }

    private fun requireHumanActor(actor: String) {
        require(actor.isNotBlank() && actor.length <= MAX_ACTOR_LENGTH && !actor.startsWith("service-account-")) {
            "a human acting principal is required"
        }
    }

    private companion object {
        const val MAX_ACTOR_LENGTH = 128
    }
}
