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
interface ProposalStore {
    suspend fun save(proposal: Proposal<BackfillRequest>)
    suspend fun get(id: String): Proposal<BackfillRequest>?
    suspend fun list(): List<Proposal<BackfillRequest>>
}
