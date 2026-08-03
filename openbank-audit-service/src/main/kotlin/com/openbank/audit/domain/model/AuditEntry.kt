// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.domain.model

import java.time.Instant
import java.util.UUID

data class AuditEntry(
    val id: UUID,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val actorId: String?,
    val actorType: String?,
    val payload: String,
    val sourceService: String,
    val correlationId: String?,
    val occurredAt: Instant,
    val recordedAt: Instant,
    /** Ingress channel the event arrived through — ui|mcp|api (ADR-0226); null = unknown/legacy. */
    val channel: String? = null,
    /** Ordered on-behalf-of delegation chain from the RFC 8693 `act` claim (ADR-0224); empty = direct. */
    val actChain: List<String> = emptyList(),
    /** Browser or agent session the action belongs to; groups one sitting's events. */
    val sessionId: String? = null,
    /**
     * The party this action was taken ON BEHALF OF — the account owner who issued the delegation
     * grant (ADR-0232 D5). Null for a direct action, which is the overwhelming majority.
     *
     * [actorId] stays the DELEGATE: who did it does not change because they were allowed to.
     * This is the second half of the pair, and it is what makes the grantor transparency query
     * ("what did they do with my account") answerable at all.
     *
     * Like [channel]/[actChain], a query index derived from the chain-hashed [payload], not an
     * independent claim — see V10__delegated_action_index.sql.
     */
    val onBehalfOf: String? = null,
    /** The delegation grant that permitted the action; null for a direct action (ADR-0232 D5). */
    val delegationId: String? = null,
)
