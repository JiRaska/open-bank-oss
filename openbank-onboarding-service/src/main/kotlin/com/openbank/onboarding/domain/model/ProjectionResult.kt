// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.onboarding.domain.model

/**
 * What the projection actually did with an event it was handed (#6248).
 *
 * History, because it explains why an out-of-order arrival gets its own value rather than being
 * silently normal. Every branch except `PartyCreated` used to guard with
 * `repo.findByPartyId(...) ?: return`: an event naming a party with no row was discarded, the
 * caller could not tell that apart from work done, and it was counted as a success. Nothing
 * replays this read model — the consumer's own KDoc said it "can be replayed" and no such job
 * exists — so the discarded event was gone for good. #6258 made the drop visible; this change
 * stops it happening. The row is seeded from the event instead.
 *
 * The seeded case still needs its own value. It means the read model is being assembled
 * backwards, which is legal but is also the shape that precedes a real ordering problem, and a
 * quiet path that reports as an ordinary success is exactly what cost the platform 15 enrolments.
 */
enum class ProjectionResult {
    /** The event was applied to a row that already existed. */
    APPLIED,

    /**
     * The event named a party with no row, so a placeholder was created from the event and the
     * event applied to it. Not a loss — but not an ordinary success either: it says an SCA or KYC
     * event overtook `PARTY_CREATED`, and the row will carry no legal name or email until that
     * event arrives.
     */
    APPLIED_TO_SEEDED_RECORD,
}
