// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.CompliancePackActivationRepository
import com.openbank.lending.infrastructure.persistence.entity.CompliancePackActivationEntity
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.lending.compliance.CompiledCompliancePack
import com.openbank.libs.lending.compliance.CompliancePackCompiler
import com.openbank.libs.lending.compliance.CompliancePackParser
import com.openbank.libs.lending.compliance.CompliancePackRegistry
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** API-facing view of one activation workflow row. */
data class PackActivationView(
    val id: UUID,
    val jurisdiction: String,
    val productType: String,
    val packVersion: Int,
    val effectiveFrom: String,
    val contentHash: String,
    val state: ProposalState,
    val proposedBy: String,
    val decidedBy: String?,
    val decidedAt: String?,
)

/**
 * Four-eyes activation of jurisdictional compliance packs (ADR-0212 D4). The maker
 * proposes pack JSON (strictly parsed and compiled — fail-closed errors surface as
 * [IllegalArgumentException] → HTTP 400); a DIFFERENT principal approves it, which
 * compiles the pack into the in-memory [CompliancePackRegistry] (compile-at-
 * activation, ADR-0218 D3) and marks the row EXECUTED. No service release per pack.
 */
@ApplicationScoped
class CompliancePackActivationService(
    private val activations: CompliancePackActivationRepository,
    private val registry: CompliancePackRegistry,
    private val clock: Clock,
) {
    fun propose(packJson: String, maker: String): Uni<PackActivationView> {
        require(maker.isNotBlank()) { "Proposer identity is required" }
        val compiled = CompliancePackCompiler.compile(CompliancePackParser.fromJson(packJson))
        val now = OffsetDateTime.now(clock)
        val entity = CompliancePackActivationEntity().apply {
            id = com.openbank.libs.domain.identifiers.Ids.newId()
            state = ProposalState.PROPOSED
            jurisdiction = compiled.pack.jurisdiction
            productType = compiled.pack.productType.name
            packVersion = compiled.pack.version
            effectiveFrom = compiled.pack.effectiveFrom
            payload = packJson
            contentHash = compiled.contentHash
            proposedBy = maker
            proposedAt = now
            createdAt = now
            updatedAt = now
        }
        return activations.save(entity).map { it.toView() }
    }

    fun decide(proposalId: UUID, approve: Boolean, checker: String, reason: String?): Uni<PackActivationView> =
        activations.findById(proposalId).map { entity ->
            requireNotNull(entity) { "Activation proposal not found: $proposalId" }
            require(entity.state == ProposalState.PROPOSED) {
                "Activation proposal $proposalId is ${entity.state}, not decidable"
            }
            val proposal = entity.toProposal()
            val decided = if (approve) {
                proposal.approve(checker, now(), reason).markExecuted(now())
            } else {
                proposal.reject(checker, now(), reason)
            }
            entity.state = decided.state
            entity.decidedBy = decided.decidedBy
            entity.decidedAt = decided.decidedAt?.let { OffsetDateTime.ofInstant(it, clock.zone) }
            entity.decisionReason = decided.decisionReason
            entity.updatedAt = OffsetDateTime.now(clock)
            entity to decided
        }.flatMap { (entity, decided) ->
            // Persist FIRST, activate only once the row is committed.
            //
            // The previous order activated the in-memory registry inside the map above, before the
            // save. When the save failed, the caller got a 500 and reasonably concluded that nothing
            // had happened — while THIS pod was already enforcing the pack, with no row to show for
            // it. That state is invisible and does not survive: a restart loses it (boot rehydrates
            // from the table), and sibling replicas never had it, so the same application would be
            // judged against different rules depending on which pod answered.
            //
            // Measured, not theorised: with the persist-vs-merge defect still in place, the decide
            // call returned 500 and `GET /compliance-packs/active` nonetheless listed the pack
            // (CompliancePackActivationIT step 3 failing while step 4 passed).
            activations.save(entity).map { saved ->
                if (approve) registry.activate(decided)
                saved.toView()
            }
        }

    fun listPending(): Uni<List<PackActivationView>> =
        activations.findByState(ProposalState.PROPOSED).map { rows -> rows.map { it.toView() } }

    fun listActive(): List<PackActivationView> = registry.allActive(java.time.LocalDate.now(clock)).map { compiled ->
        PackActivationView(
            id = UUID(0, 0),
            jurisdiction = compiled.pack.jurisdiction,
            productType = compiled.pack.productType.name,
            packVersion = compiled.pack.version,
            effectiveFrom = compiled.pack.effectiveFrom.toString(),
            contentHash = compiled.contentHash,
            state = ProposalState.EXECUTED,
            proposedBy = "-",
            decidedBy = null,
            decidedAt = null,
        )
    }

    private fun now() = clock.instant()

    private fun CompliancePackActivationEntity.toProposal(): Proposal<CompiledCompliancePack> = Proposal(
        id = id.toString(),
        action = CompliancePackCompiler.compile(CompliancePackParser.fromJson(payload)),
        proposedBy = proposedBy,
        proposedAt = proposedAt.toInstant(),
        state = state,
        decidedBy = decidedBy,
        decidedAt = decidedAt?.toInstant(),
        decisionReason = decisionReason,
    )

    private fun CompliancePackActivationEntity.toView() = PackActivationView(
        id = id,
        jurisdiction = jurisdiction,
        productType = productType,
        packVersion = packVersion,
        effectiveFrom = effectiveFrom.toString(),
        contentHash = contentHash,
        state = state,
        proposedBy = proposedBy,
        decidedBy = decidedBy,
        decidedAt = decidedAt?.toString(),
    )
}
