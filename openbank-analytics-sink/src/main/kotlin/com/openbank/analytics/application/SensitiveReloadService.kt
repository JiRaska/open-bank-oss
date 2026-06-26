// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.analytics.application

import com.openbank.analytics.application.port.out.ProposalStore
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.MakerCheckerViolation
import com.openbank.libs.analytics.Proposal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.jboss.logging.Logger

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
            request.source,
            request.requestedBy,
            request.reason,
        )
        return proposal
    }

    suspend fun approve(id: String, checker: String, reason: String?): Proposal<BackfillRequest> {
        val proposal = require(id)
        val approved = proposal.approve(checker, Instant.now(clock), reason)
        store.save(approved)
        log.infof("reload approved id=%s by=%s (proposer=%s)", id, checker, proposal.proposedBy)
        return approved
    }

    suspend fun reject(id: String, checker: String, reason: String?): Proposal<BackfillRequest> {
        val rejected = require(id).reject(checker, Instant.now(clock), reason)
        store.save(rejected)
        log.infof("reload rejected id=%s by=%s", id, checker)
        return rejected
    }

    /**
     * Executes an APPROVED proposal: runs the actual reload, then marks it EXECUTED so it cannot run
     * twice. The maker-checker invariant is upheld by [Proposal.markExecuted] (only APPROVED → EXECUTED).
     */
    suspend fun execute(id: String): BackfillReport {
        val proposal = require(id)
        // Transition first (fails fast if not APPROVED), persist, then load.
        val executing = proposal.markExecuted(Instant.now(clock))
        val report = backfill.run(proposal.action)
        store.save(executing)
        log.infof("reload executed id=%s batchId=%s ingested=%d", id, report.batchId, report.ingested)
        return report
    }

    suspend fun get(id: String): Proposal<BackfillRequest>? = store.get(id)
    suspend fun list(): List<Proposal<BackfillRequest>> = store.list()

    private suspend fun require(id: String): Proposal<BackfillRequest> =
        store.get(id) ?: throw MakerCheckerViolation("no such proposal: $id")
}
