// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application.port.out

import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.Proposal

/**
 * Persistence for maker-checker [Proposal]s wrapping a [BackfillRequest] (ADR-0023, finding F3).
 *
 * A reload/correction of the 10-year record is a four-eyes action: it is *proposed* by one operator
 * and *approved* by a different one before it can execute. The proposals must be durable so the
 * decision trail survives a restart and can be produced as audit evidence (who proposed, who approved).
 *
 * The default binding [com.openbank.analytics.infrastructure.proposal.InMemoryProposalStore] keeps
 * them in a map so the service is offline-buildable; a ClickHouse/Postgres-free durable adapter (e.g.
 * the audit service or an object store) is the documented follow-up.
 */
/**
 * The two phases a [Proposal] transition must be claimed for before it is computed and persisted.
 * [DECIDE] covers approve/reject (mutually exclusive — only one decision may ever win a proposal);
 * [EXECUTE] covers the one-time backfill run.
 */
enum class ProposalDecisionPhase { DECIDE, EXECUTE }

interface ProposalStore {
    suspend fun save(proposal: Proposal<BackfillRequest>)
    suspend fun get(id: String): Proposal<BackfillRequest>?
    suspend fun list(): List<Proposal<BackfillRequest>>

    /**
     * Atomically claims [phase] for the proposal at [id]. The first caller for a given (id, phase)
     * pair wins (`true`); every other concurrent — or later — caller for the same pair loses
     * (`false`) and must treat that as a refusal with no side effect.
     *
     * This is the compare-and-set primitive maker-checker relies on. A `get`-then-`save` pair is
     * NOT enough: two concurrent decisions can both observe [com.openbank.libs.analytics.ProposalState.PROPOSED],
     * both pass the domain's state check, and both write — a lost update on a segregation-of-duties
     * control. Callers MUST call [claim] before computing the transition, not after.
     */
    suspend fun claim(id: String, phase: ProposalDecisionPhase): Boolean
}
