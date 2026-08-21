// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.onboarding.domain.model

/**
 * What the projection actually did with an event it was handed (#6248).
 *
 * Every branch of the projection except `PartyCreated` needs an existing row to update, and
 * guards with `repo.findByPartyId(...) ?: return`. That guard is correct — the three source
 * topics are three independent consumer groups with no ordering between them, so an event can
 * legitimately arrive before the party row exists, and throwing would wedge the group. What was
 * wrong is that the guard returned normally and the caller could not tell it apart from work
 * done: a dropped event was counted as `PROJECTED`.
 *
 * That cost the platform a real defect. All 15 `DEVICE_ENROLLED` events in the sandbox were
 * consumed with zero lag and none reached `onboarding_records`, and the alert built for exactly
 * this class of failure (`UNRECOGNISED > 0 and PROJECTED == 0`, #4353) was structurally unable
 * to fire, because the drop was landing in the success bucket it compares against.
 *
 * So the skip gets its own value, never a flag shared with success — the same rule as
 * `PushSendOutcome.SKIPPED` (ADR-0252 phase 0), and the same rule the sibling
 * `ProjectionOutcomeMetrics.Outcome.UNRECOGNISED` already follows one layer up.
 */
enum class ProjectionResult {
    /** The read model was updated. */
    APPLIED,

    /**
     * The event named a party that has no row yet, so there was nothing to update and the event
     * was discarded. Not an error and not a success — this is the state in which enrolments,
     * KYC transitions and status changes are silently lost.
     */
    SKIPPED_UNKNOWN_PARTY,
}
