// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.out

import com.openbank.sanctions.domain.model.SanctionsListType
import java.util.UUID

enum class SanctionsPublicationOutcome { NO_CHANGES, PUBLISHED, WITHHELD, DEFERRED }

/** Capability held only while one list's import and final publication own the database fence. */
class SanctionsPublicationPermit internal constructor(
    private val listType: SanctionsListType,
    val generation: Long = 0,
) {
    @Volatile
    private var active = true

    @Volatile
    internal var retainPendingJournal = false
        private set

    internal fun checkFor(type: SanctionsListType) {
        check(active && type == listType) { "Sanctions publication fence is not held for $type" }
    }

    internal fun invalidate() {
        active = false
    }

    internal fun deferUntilNextRefresh() {
        retainPendingJournal = true
    }
}

/** Publish committed pending changes; retain their evidence on failure or withheld publication. */
interface SanctionsChangePublisher {
    suspend fun publishPending(listId: UUID, listType: SanctionsListType): SanctionsPublicationOutcome

    suspend fun publishFenced(
        listId: UUID,
        listType: SanctionsListType,
        permit: SanctionsPublicationPermit,
    ): SanctionsPublicationOutcome
}
