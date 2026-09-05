// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A single entry from a sanctions or PEP list.
 * Populated by [com.openbank.sanctions.application.usecase.SanctionsImportService]
 * from the list's source URL (OFAC XML, OpenSanctions CSV, etc.).
 */
data class SanctionsEntry(
    val id: UUID = UUID.randomUUID(),
    val listType: SanctionsListType,
    val externalId: String?,
    val entityType: EntityType,
    val primaryName: String,
    val aliases: List<String> = emptyList(),
    val dateOfBirth: String? = null,
    val nationalities: List<String> = emptyList(),
    val programs: List<String> = emptyList(),
    /** Normalized (unaccented lowercase) of primaryName + all aliases, joined with ' | '.
     *  Used as the pg_trgm indexed column for similarity search. */
    val searchText: String,
    val active: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SanctionsEntryMatch(val entry: SanctionsEntry, val matchedName: String, val score: Double)
