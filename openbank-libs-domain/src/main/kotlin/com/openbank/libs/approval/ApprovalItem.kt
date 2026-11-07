// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval

import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Coarse risk classification of an approvable action (ADR-0227 D1, feeding the D3 inbox
 * risk-class filter). The authoritative source of this classification is the action-class
 * metadata in `openbank-libs/governance/rules.yaml` (`four_eyes.verbs` + `money_path_services`
 * → MONEY_PATH, `four_eyes.actions` → DESTRUCTIVE); the admin-UI BFF derives it from a copy of
 * that data. The approval store itself does NOT carry the classification — nothing in the
 * persisted record references rules.yaml — so a projection made here without a classifier
 * lands on [UNKNOWN] and the BFF overwrites it. Do not "classify" by verb heuristics here:
 * the rules.yaml lists exist precisely because action-name-alone guesses were wrong twice
 * (issue #395, the #938 follow-up).
 */
enum class ApprovalRiskClass { MONEY_PATH, DESTRUCTIVE, OPERATIONAL, UNKNOWN }

/** The maker side of an [ApprovalItem] — provenance captured at creation, never inferred later. */
data class ApprovalMaker(val id: String, val channel: MakerActorKind, val at: OffsetDateTime)

/**
 * The canonical inbox item (ADR-0227 D1): one platform shape every service's pending queue
 * projects into, so the federated inbox (D2) has one vocabulary to merge and filter on (D3).
 *
 * This is a PROJECTION of [PendingApproval], not a new store record — the 19 thin
 * ApprovalResources keep delegating to `ApprovalEndpointSupport`, which maps store rows into
 * this shape. Field sourcing, honestly:
 * - [summary] comes from the persisted [ApprovalRequestBinding.summary] — `null` when the
 *   approval was created directly by application code rather than by the intercepting pause.
 * - [amount] / [currency] are `null` for every record today: the maker's original request body
 *   is NOT persisted (only its SHA-256 [PendingApproval.requestFingerprint] and the bounded
 *   summary are). A future phase that persists a structured amount will fill these; until then
 *   the D3 amount-band filter simply has nothing to match and must treat these items as
 *   outside every band, never as "zero amount".
 * - [payloadRef] is `null` today for the same reason: the fingerprint is an integrity digest
 *   for one-time consumption, not a retrievable pointer to the maker's payload. The field is
 *   the slot a future payload-pointer (e.g. an object-storage key) occupies; nothing currently
 *   persisted can serve as one.
 * - [riskClass] is [UNKNOWN] unless the caller supplies a classifier — see the enum's KDoc.
 */
data class ApprovalItem(
    val id: String,
    val domain: String,
    val action: String,
    val summary: String?,
    val amount: BigDecimal?,
    val currency: String?,
    val riskClass: ApprovalRiskClass,
    val maker: ApprovalMaker,
    val state: ApprovalStatus,
    val payloadRef: String?,
)

/**
 * Projects a store row into the canonical D1 item. [domain] is the caller's service domain —
 * the store is per-service and carries no domain of its own; `ApprovalEndpointSupport` derives
 * it from `quarkus.application.name` (the same normalisation rest.rego applies:
 * strip `openbank-` and a trailing `-service`).
 */
fun PendingApproval.toApprovalItem(
    domain: String,
    riskClass: ApprovalRiskClass = ApprovalRiskClass.UNKNOWN,
): ApprovalItem = ApprovalItem(
    id = id,
    domain = domain,
    action = action,
    summary = summary,
    amount = null,
    currency = null,
    riskClass = riskClass,
    maker = ApprovalMaker(id = makerId, channel = makerActorKind, at = createdAt),
    state = status,
    payloadRef = null,
)
