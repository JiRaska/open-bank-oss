// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.openbank.analytics.application.port.out.ProposalDecisionPhase
import com.openbank.analytics.application.port.out.ProposalStore
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.MakerCheckerViolation
import com.openbank.libs.analytics.Proposal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Maker-checker orchestration for sensitive reloads (ADR-0023, finding F3).
 *
 * A backfill / correction / initial-load of the 10-year record cannot be done by one person. The flow:
 *  1. [propose] — operator A submits a [BackfillRequest]; it is stored as a `PROPOSED` [Proposal].
 *  2. [approve] — operator B (must differ from A, enforced by [Proposal.approve]) approves it.
 *  3. [execute] — once `APPROVED`, the underlying [BackfillService] actually loads the data, and the
 *     proposal moves to `EXECUTED` so it cannot be replayed.
 *
 * The four-eyes rule lives in the pure [Proposal] state machine; this service just persists and wires
 * it to the real reload. Every transition is logged for the audit trail.
 */
@ApplicationScoped
class SensitiveReloadService {

    @Inject lateinit var store: ProposalStore

    @Inject lateinit var clock: Clock

    @Inject lateinit var backfill: BackfillService

    private val log = Logger.getLogger(SensitiveReloadService::class.java)

    // CodeQL java/log-injection: source/requestedBy/reason/checker are operator-supplied
    // strings that flow straight into log lines below. Strip CR/LF so an attacker (or a
    // careless operator) can't forge additional audit-trail log lines (log forging, CWE-117).
    private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

    suspend fun propose(request: BackfillRequest): Proposal<BackfillRequest> {
        val proposal = Proposal(
            id = UUID.randomUUID().toString(),
            action = request,
            proposedBy = request.requestedBy,
            proposedAt = Instant.now(clock),
        )
        store.save(proposal)
        log.infof(
            "reload proposed id=%s source=%s by=%s reason=%s",
            proposal.id,
            request.source, // IngestSource enum — fixed values, no sanitization needed
            request.requestedBy.sanitizeForLog(),
            request.reason.sanitizeForLog(),
        )
        return proposal
    }

    suspend fun approve(id: String, checker: String, reason: String?): Proposal<BackfillRequest> {
        val proposal = require(id)
        // Compute the transition first: it is pure (validates PROPOSED + four-eyes, no I/O), so an
        // invalid call (wrong state, self-approval) throws here and never touches the claim below —
        // it must not burn the one claim a later, legitimate decision needs. Only a proposal that
        // WOULD be validly approved right now goes on to contend for the claim.
        val approved = proposal.approve(checker, Instant.now(clock), reason)
        claimOrThrow(id, ProposalDecisionPhase.DECIDE)
        store.save(approved)
        log.infof(
            "reload approved id=%s by=%s (proposer=%s)",
            id.sanitizeForLog(),
            checker.sanitizeForLog(),
            proposal.proposedBy.sanitizeForLog(),
        )
        return approved
    }

    suspend fun reject(id: String, checker: String, reason: String?): Proposal<BackfillRequest> {
        val proposal = require(id)
        val rejected = proposal.reject(checker, Instant.now(clock), reason)
        claimOrThrow(id, ProposalDecisionPhase.DECIDE)
        store.save(rejected)
        log.infof("reload rejected id=%s by=%s", id.sanitizeForLog(), checker.sanitizeForLog())
        return rejected
    }

    /**
     * Executes an APPROVED proposal: runs the actual reload, then marks it EXECUTED so it cannot run
     * twice. The maker-checker invariant is upheld by [Proposal.markExecuted] (only APPROVED → EXECUTED)
     * plus the [ProposalDecisionPhase.EXECUTE] claim, which is won BEFORE [BackfillService.run] so two
     * concurrent callers cannot both trigger the (expensive, side-effecting) backfill itself — only
     * the claim's winner ever reaches [BackfillService.run].
     */
    suspend fun execute(id: String): BackfillReport {
        val proposal = require(id)
        // Pure transition first — fails fast (and claims nothing) if not APPROVED.
        val executing = proposal.markExecuted(Instant.now(clock))
        claimOrThrow(id, ProposalDecisionPhase.EXECUTE)
        val report = backfill.run(proposal.action)
        store.save(executing)
        log.infof(
            "reload executed id=%s batchId=%s ingested=%d",
            id.sanitizeForLog(),
            report.batchId.sanitizeForLog(),
            report.ingested,
        )
        return report
    }

    suspend fun get(id: String): Proposal<BackfillRequest>? = store.get(id)
    suspend fun list(): List<Proposal<BackfillRequest>> = store.list()

    private suspend fun require(id: String): Proposal<BackfillRequest> =
        store.get(id) ?: throw MakerCheckerViolation("no such proposal: $id")

    /**
     * The compare-and-set gate a decision/execution must win before it is persisted (or, for
     * [ProposalDecisionPhase.EXECUTE], before the real backfill runs). See [ProposalStore.claim]'s
     * KDoc: without this, two concurrent calls can both observe the same pre-transition state, both
     * pass [Proposal]'s own state check, and both write — a lost update on this four-eyes control
     * (ADR-0023 F3). Called only AFTER the pure domain transition has already validated the call, so
     * an invalid call never consumes the claim a legitimate later call would need.
     */
    private suspend fun claimOrThrow(id: String, phase: ProposalDecisionPhase) {
        if (!store.claim(id, phase)) {
            throw MakerCheckerViolation("proposal '$id' $phase was already claimed by another decision")
        }
    }
}
