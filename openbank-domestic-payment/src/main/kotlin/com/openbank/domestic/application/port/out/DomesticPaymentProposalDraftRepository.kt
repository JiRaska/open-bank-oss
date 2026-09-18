// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DomesticPaymentProposalDraft
import java.util.UUID

interface DomesticPaymentProposalDraftRepository {
    /** Returns the committed winner if a concurrent writer claimed the same maker/key. */
    suspend fun saveOrGetWinner(draft: DomesticPaymentProposalDraft): DomesticPaymentProposalDraft

    suspend fun findByMakerAndKey(makerPartyId: UUID, idempotencyKey: String): DomesticPaymentProposalDraft?

    suspend fun findById(id: UUID): DomesticPaymentProposalDraft?

    /** Stable newest-first page after [before]; caller must bind [makerPartyId] from identity. */
    suspend fun listByMaker(
        makerPartyId: UUID,
        before: DomesticPaymentProposalDraft?,
        limit: Int,
    ): List<DomesticPaymentProposalDraft>

    /** Stable newest-first page; [ownerPartyId] must come from the effective owner identity. */
    suspend fun listByOwner(
        ownerPartyId: UUID,
        before: DomesticPaymentProposalDraft?,
        limit: Int,
    ): List<DomesticPaymentProposalDraft>
}
