// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.application.port.out

import com.openbank.tppregistry.domain.model.EbaRegisterSyncState
import com.openbank.tppregistry.domain.model.TppEntry
import com.openbank.tppregistry.domain.model.TppEvent
import com.openbank.tppregistry.domain.model.TppRole
import com.openbank.tppregistry.domain.model.TppStatus

/**
 * Outbound persistence port for the TPP registry aggregate and the EBA register sync state.
 *
 * Backs the PSD2 third-party-provider directory: registration, status transitions (e.g.
 * blacklisting), filtered listing, and the bookkeeping of the periodic EBA register synchronisation.
 *
 * [save] and [update] take the [TppEvent] describing the change as a REQUIRED parameter, and there
 * is deliberately no eventless overload (issue #4007): the implementation writes the aggregate row
 * and the `tpp_outbox` row in one transaction, so a caller that could persist without an event
 * would be the exact dual-write this service shipped for its whole life.
 */
interface TppRepository {

    suspend fun findByTppId(tppId: String): TppEntry?

    suspend fun save(entry: TppEntry, event: TppEvent): TppEntry

    suspend fun update(entry: TppEntry, event: TppEvent): TppEntry

    suspend fun list(
        countryCode: String?,
        role: TppRole?,
        status: TppStatus?,
        limit: Int,
        afterCursor: String?,
    ): List<TppEntry>

    suspend fun saveSyncState(state: EbaRegisterSyncState)

    suspend fun getSyncState(): EbaRegisterSyncState?
}
