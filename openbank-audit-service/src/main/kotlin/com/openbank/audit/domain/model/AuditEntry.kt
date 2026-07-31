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
)
