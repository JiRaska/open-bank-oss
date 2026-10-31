// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.PensionContract
import java.util.UUID

class ContractNotFoundException(id: UUID) : RuntimeException("pension contract $id not found")

interface PensionContractRepository {
    /** Upsert of the whole aggregate, including any strategy elections not yet stored. */
    suspend fun save(contract: PensionContract): PensionContract

    suspend fun findById(id: UUID): PensionContract?

    suspend fun findByIdempotencyKey(participantPartyId: UUID, idempotencyKey: String): PensionContract?

    /** The participant's contracts, newest first (ADR-0334 S8: the edge's contract list). */
    suspend fun findByParticipant(participantPartyId: UUID, limit: Int): List<PensionContract>

    /** Staff view: contracts newest first, optionally in one status. */
    suspend fun findByStatus(status: ContractStatus?, limit: Int): List<PensionContract>

    /**
     * Staff search: contracts newest first matching a free-text [query] — a fragment of the
     * contract or participant party id, the participant birth date (ISO or bare digits, e.g.
     * `1985-04` or `850425`), the product line, or the contract's payment reference (VS).
     * Optionally narrowed to one status.
     */
    suspend fun search(query: String, status: ContractStatus?, limit: Int): List<PensionContract>
}
