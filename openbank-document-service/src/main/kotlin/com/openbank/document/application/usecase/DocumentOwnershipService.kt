// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.DocumentOwnershipUseCase
import com.openbank.document.application.port.out.DocumentRepositoryPort
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * ADR-0375 D5. `Document.partyRef` is a nullable String, so two different absences collapse into
 * one answer here on purpose: a document that does not exist and a document owned by nobody are
 * both "not this grantor's", and the caller's fail-closed gate treats them identically. A
 * malformed partyRef is the same answer rather than an exception — a stored value that is not a
 * UUID is not evidence of ownership, and throwing would turn it into UNVERIFIABLE, which reads
 * as an outage rather than as the definitive negative it is.
 */
@ApplicationScoped
class DocumentOwnershipService(private val documentRepo: DocumentRepositoryPort) : DocumentOwnershipUseCase {

    override suspend fun ownerOf(id: UUID): UUID? =
        documentRepo.findById(id)?.partyRef?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
