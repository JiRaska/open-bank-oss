// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.port.out

import java.util.UUID

/** One party as the onboarding reconciler needs to see it: ids and statuses, nothing personal. */
data class PartySummary(val partyId: UUID, val partyType: String, val status: String, val kycStatus: String)

/** One page of [PartySummary], plus whether party-service holds more after it. */
data class PartyPage(val items: List<PartySummary>, val hasMore: Boolean)

/**
 * Read-only view of party-service used by the onboarding screening reconciler.
 *
 * [listPendingKyc] returns the parties in status `PENDING_KYC`, one page at a time. It THROWS on a
 * transport or HTTP failure: the reconciler must be able to tell "party-service says nothing is
 * stuck" from "party-service could not be asked", and an empty page cannot carry that difference.
 */
interface PartyDirectoryPort {
    suspend fun listPendingKyc(page: Int, size: Int): PartyPage
}
