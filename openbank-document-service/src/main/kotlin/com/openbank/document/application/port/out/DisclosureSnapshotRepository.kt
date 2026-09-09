// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.application.port.out

import com.openbank.document.domain.model.DisclosureSnapshot
import java.util.UUID

interface DisclosureSnapshotRepository {
    /** Inserts once, or returns the winner for the same request id after a concurrent retry. */
    suspend fun createOrFind(snapshot: DisclosureSnapshot): DisclosureSnapshot
    suspend fun findById(id: UUID): DisclosureSnapshot?
    suspend fun findByRequestId(requestId: UUID): DisclosureSnapshot?
}
