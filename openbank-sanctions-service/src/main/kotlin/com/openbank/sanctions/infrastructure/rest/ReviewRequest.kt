// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import io.quarkus.security.identity.SecurityIdentity
import java.util.UUID

/**
 * Wire body of `POST /api/{v1,v3}/sanctions/review`.
 *
 * It deliberately has NO reviewer field. The reviewer recorded on a sanctions decision is the
 * authenticated principal ([toCommand]); a client-supplied `reviewedBy` is unknown here and
 * ignored (`ignoreUnknown`), so older callers that still send it keep working but cannot choose
 * who is on record. The four-eyes approval binding fingerprints this DTO's fields (checkId, note,
 * newStatus) — exactly what the decision executes on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReviewRequest(val checkId: UUID, val note: String, val newStatus: SanctionsCheckStatus) {
    fun toCommand(identity: SecurityIdentity) = ReviewCommand(
        checkId = checkId,
        reviewedBy = requireNotNull(identity.principal?.name) { "authenticated principal required" },
        note = note,
        newStatus = newStatus,
    )
}
