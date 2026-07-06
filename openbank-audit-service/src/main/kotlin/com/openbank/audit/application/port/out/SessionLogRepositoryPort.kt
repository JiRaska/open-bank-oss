// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application.port.out

import com.openbank.audit.domain.model.SessionLogEntry
import java.time.Instant

/**
 * Outbound persistence port for session/access-log records (ADR-0118 §2/§5, issue #268).
 * Implemented by [com.openbank.audit.infrastructure.persistence.SessionLogRepository].
 */
interface SessionLogRepositoryPort {

    suspend fun save(entry: SessionLogEntry)

    /** Deletes session-log rows whose [SessionLogEntry.occurredAt] is strictly before [cutoff]. */
    suspend fun deleteOlderThan(cutoff: Instant): Long

    /** Count of rows strictly older than [cutoff] — used by the dry-run preview. */
    suspend fun countOlderThan(cutoff: Instant): Long
}
