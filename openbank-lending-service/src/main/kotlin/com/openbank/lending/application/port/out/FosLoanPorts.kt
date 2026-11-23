// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.CatalogLoanSnapshot
import com.openbank.libs.audit.decision.InputDigest
import com.openbank.libs.market.PassportMode
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.util.UUID

data class FosRoute(
    val hostCountry: String,
    val bankId: UUID,
    val establishmentId: UUID,
    val channel: String,
    val customerSegment: String,
    val packSetDigest: String,
    val mode: PassportMode = PassportMode.FOS,
)

data class FosRoutePin(val grantId: UUID, val route: FosRoute)

enum class FosAction { SIGN, DISBURSE }

data class FosCash(val accountId: UUID, val date: LocalDate)

data class FosCommand(
    val actor: String,
    val synthetic: Boolean,
    val artefactHash: String? = null,
    val cash: FosCash? = null,
)

data class FosOperation(
    val id: UUID,
    val applicationId: UUID,
    val action: FosAction,
    val command: FosCommand,
    val admitted: Boolean = false,
    val completed: Boolean = false,
    val refused: Boolean = false,
) {
    fun binding(partyId: UUID, revision: CatalogLoanSnapshot): String = InputDigest.sha256(
        mapOf(
            "operation" to id.toString(),
            "action" to action.name,
            "application" to applicationId.toString(),
            "party" to partyId.toString(),
            "revision" to revision.revisionId.toString(),
            "content" to revision.contentHash,
            "actor" to command.actor,
            "synthetic" to command.synthetic.toString(),
            "artefact" to command.artefactHash.orEmpty(),
            "account" to (command.cash?.accountId?.toString() ?: ""),
            "date" to (command.cash?.date?.toString() ?: ""),
        ),
    ).hex
}

interface CatalogFosLoanPort {
    fun resolve(grantId: UUID, partyId: UUID, revision: CatalogLoanSnapshot, mode: PassportMode): Uni<FosRoute>

    /** Retries of the same operation recover its receipt even after revocation. */
    fun admit(operation: FosOperation, pin: FosRoutePin, partyId: UUID, revision: CatalogLoanSnapshot): Uni<Unit>
}

class FosCommitmentRefused : IllegalStateException("Catalogue refused the passport commitment")

interface FosLoanOperations {
    fun pin(applicationId: UUID, route: FosRoutePin): Uni<Unit>
    fun route(applicationId: UUID): Uni<FosRoutePin?>
    fun find(applicationId: UUID, action: FosAction): Uni<FosOperation?>
    fun stage(operation: FosOperation): Uni<Unit>
    fun admit(operationId: UUID): Uni<Unit>
    fun refuse(operationId: UUID): Uni<Unit>
    fun complete(operationId: UUID): Uni<Unit>
    fun claimDue(limit: Int): Uni<List<FosOperation>>
}
