// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.proposal

import com.openbank.analytics.application.port.out.ProposalStore
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.Proposal
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [ProposalStore]: an in-memory concurrent map. Keeps the service offline-buildable and lets
 * the maker-checker flow be exercised end-to-end in tests with zero infra. Proposals are lost on
 * restart, so a durable adapter (audit service / object store) is the documented follow-up — the
 * *segregation-of-duties logic* (in the Proposal state machine) is fully real regardless of store.
 */
@ApplicationScoped
class InMemoryProposalStore : ProposalStore {

    private val store = ConcurrentHashMap<String, Proposal<BackfillRequest>>()

    override suspend fun save(proposal: Proposal<BackfillRequest>) {
        store[proposal.id] = proposal
    }

    override suspend fun get(id: String): Proposal<BackfillRequest>? = store[id]

    override suspend fun list(): List<Proposal<BackfillRequest>> = store.values.sortedBy { it.proposedAt }
}
