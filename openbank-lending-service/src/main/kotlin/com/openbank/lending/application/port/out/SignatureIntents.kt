// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.libs.domain.identifiers.LoanApplicationId
import io.smallrye.mutiny.Uni
import java.util.UUID

data class SignatureIntent(
    val id: UUID,
    val applicationId: LoanApplicationId,
    val revisionId: UUID,
    val revisionContentHash: String,
    val artefactHash: String,
    val actor: String,
    val synthetic: Boolean,
) {
    fun matches(other: SignatureIntent): Boolean = copy(id = other.id) == other
}

interface SignatureIntents {
    /** Called with the application row locked. Returns the original committed intent on replay. */
    fun stage(intent: SignatureIntent): Uni<SignatureIntent>

    fun find(applicationId: LoanApplicationId): Uni<SignatureIntent?>

    /** Commits with the signature and its outbox evidence in the application transaction. */
    fun complete(operationId: UUID): Uni<Unit>

    fun claimDue(limit: Int): Uni<List<SignatureIntent>>
}
