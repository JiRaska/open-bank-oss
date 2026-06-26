// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.notification.domain.ops

import java.time.Instant

/** Desired state of the notification dispatch loop (ADR-0047, Tier A break-glass control). */
enum class DispatchState { ENABLED, HALTED }

/**
 * One immutable, versioned row of the dispatch-control desired state. The control plane is
 * declarative: the dispatcher on *every* replica reads the latest snapshot and converges, so a
 * halt is honoured fleet-wide without a per-pod RPC. The append-only history makes every change
 * point-in-time reconstructible (who halted, when, why) — DORA Art. 17.
 */
data class DispatchControlSnapshot(
    val controlKey: String,
    val state: DispatchState,
    val version: Long,
    val reason: String?,
    val actor: String?,
    val effectiveFrom: Instant,
    /** A break-glass halt sets this until an independent reviewer signs off (deferred dual control). */
    val deferredReviewRequired: Boolean,
)

/** Payload of a four-eyes resume proposal — the action a checker approves. */
data class ResumeAction(val controlKey: String, val reason: String)
