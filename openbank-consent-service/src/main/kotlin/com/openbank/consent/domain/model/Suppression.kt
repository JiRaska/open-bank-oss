// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.domain.model

import java.time.OffsetDateTime
import java.util.UUID

/** The granularity of a suppression entry (ADR-0219 D3): one topic, one scope, or everything. */
enum class SuppressionScope { ALL, SCOPE, TOPIC }

/** ADR-0219 D3 reason codes — the operational "stop" that is not a consent revocation. */
enum class SuppressionReason { CUSTOMER_OPTOUT, COMPLAINT, RM_MANAGED, LEGAL_HOLD, DECEASED }

/**
 * One platform do-not-contact entry (ADR-0219 D3): the granular stop a customer (or the bank)
 * applies WITHOUT revoking the underlying consent — "not about loans" instead of "no email at
 * all". Evaluated by the contact-policy gate BEFORE consent, on the ADR-0200 D6 ordering
 * principle: a suppression is not a consent question.
 *
 * [value] carries the scope or topic name for SCOPE/TOPIC entries and must be null for ALL —
 * an ALL entry with a value would read as scoped while covering everything. [source] records
 * where the entry came from (preference centre, complaints flow, RM workbench) so a removal can
 * be routed back to its origin.
 */
data class Suppression(
    val id: UUID,
    val partyId: UUID,
    val scope: SuppressionScope,
    val value: String?,
    val reason: SuppressionReason,
    val source: String,
    val createdBy: String,
    val createdAt: OffsetDateTime,
    val revokedAt: OffsetDateTime?,
    val revokedBy: String?,
) {
    init {
        require(source.isNotBlank()) { "suppression source must not be blank" }
        require(createdBy.isNotBlank()) { "suppression createdBy must not be blank" }
        require((scope == SuppressionScope.ALL) == value.isNullOrBlank()) {
            "ALL takes no value; SCOPE/TOPIC require one"
        }
    }

    val active: Boolean get() = revokedAt == null

    fun revoke(revokedBy: String, at: OffsetDateTime): Suppression {
        require(revokedBy.isNotBlank()) { "revokedBy must not be blank" }
        require(active) { "only an active suppression can be revoked" }
        return copy(revokedAt = at, revokedBy = revokedBy)
    }

    /** True when this entry suppresses a contact in [consentScope] about [topic]. */
    fun covers(consentScope: String, topic: String?): Boolean = when (scope) {
        SuppressionScope.ALL -> true
        SuppressionScope.SCOPE -> value == consentScope
        SuppressionScope.TOPIC -> topic != null && value == topic
    }
}
