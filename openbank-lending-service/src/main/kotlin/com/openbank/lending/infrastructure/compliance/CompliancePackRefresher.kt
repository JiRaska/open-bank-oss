// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.compliance

import com.openbank.lending.application.port.out.CompliancePackActivationRepository
import com.openbank.lending.infrastructure.persistence.entity.CompliancePackActivationEntity
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.lending.compliance.CompiledCompliancePack
import com.openbank.libs.lending.compliance.CompliancePackCompiler
import com.openbank.libs.lending.compliance.CompliancePackParser
import com.openbank.libs.lending.compliance.CompliancePackRegistry
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Converges this pod's [CompliancePackRegistry] onto the activations that are committed in the
 * database (#3467).
 *
 * WHAT WAS BROKEN
 *
 * The registry had exactly two writers and nothing between them: `CompliancePackActivationService`
 * on the pod that served the approval, and [CompliancePackBootLoader] at boot. A pack approved on
 * pod A was therefore enforced by pod A immediately and by pod B only after pod B next restarted.
 * Nothing bounded that window and nothing reported it — no metric, no readiness signal, no log on
 * the pods that were behind — so the same loan application would be judged against different
 * compliance rules depending on which pod answered. On a money-path four-eyes control that is a
 * divergence, not a stale cache.
 *
 * It is unreachable in today's topology only because `lending-service.yaml` pins `replicas: 1`. That
 * is the whole risk: the constraint lived in a gitops value, the dependency on it lived in Kotlin,
 * and nothing connected the two. A routine "lending is slow, scale it to 3" was all it took.
 *
 * WHY A PERIODIC READ AND NOT AN EVENT
 *
 * The activation row is already the durable, auditable record of the decision — the store the
 * service owns, written under the same transaction as the four-eyes state change. Propagating over
 * the outbox would add a second copy of that fact and a second way for it to be missed (an unread
 * topic, a consumer group that never joined); polling the record of truth cannot drift from it.
 * Convergence is bounded by `lending.compliance.refresh-interval` and self-heals: a refresh that
 * fails simply leaves the pod where it was and the next one catches up.
 *
 * A `suspend fun`, deliberately — a plain `@Scheduled` method carries no Vert.x context, so a
 * reactive Panache call inside `runBlocking` throws `HR000068` and the job silently does nothing
 * (#2148/#2187).
 */
@ApplicationScoped
class CompliancePackRefresher(
    private val activations: CompliancePackActivationRepository,
    private val registry: CompliancePackRegistry,
) {
    private val log = Logger.getLogger(CompliancePackRefresher::class.java)

    @Scheduled(
        every = "{lending.compliance.refresh-interval}",
        delayed = "10s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() {
        val rows = activations.findActivated().awaitSuspending()
        val proposals = rows.mapNotNull { row ->
            // One unparseable row must not stop the others from converging. The boot loader fails
            // LOUDLY on the same input, and that asymmetry is intended: at boot, refusing to start is
            // the safe answer, while here the pod is already serving and dropping every other pack
            // would turn one bad row into a fleet-wide origination outage.
            runCatching { row.toProposal() }
                .onFailure { log.errorf(it, "compliance pack activation %s is unreadable — skipped", row.id) }
                .getOrNull()
        }
        val added = registry.syncFrom(proposals)
        if (added > 0) {
            // The only signal that this replica WAS behind. Silence here is the healthy state.
            log.infof("compliance packs converged from the activation table: %d pack(s) newly enforceable", added)
        }
    }
}

/**
 * The persisted activation, read back as the four-eyes proposal it was approved as. Shared by the
 * boot loader and the refresher so the two cannot disagree about what a row means — the earlier
 * boot-only copy of this mapping is exactly the kind of second copy that drifts.
 */
internal fun CompliancePackActivationEntity.toProposal(): Proposal<CompiledCompliancePack> = Proposal(
    id = "activation-$id",
    action = CompliancePackCompiler.compile(CompliancePackParser.fromJson(payload)),
    proposedBy = proposedBy,
    proposedAt = proposedAt.toInstant(),
    state = ProposalState.EXECUTED,
    decidedBy = decidedBy ?: "boot:unknown-checker",
    decidedAt = decidedAt?.toInstant(),
    decisionReason = decisionReason,
)
