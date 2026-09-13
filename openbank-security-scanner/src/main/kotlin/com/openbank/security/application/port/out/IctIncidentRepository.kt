// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.application.port.out

import com.openbank.securityscanner.domain.IctIncident
import com.openbank.securityscanner.domain.IncidentSeverity
import com.openbank.securityscanner.domain.IncidentStatus
import java.util.UUID

/**
 * The record of truth for the DORA ICT incident register (issue #4728).
 *
 * Before this port the register was a per-pod `ConcurrentHashMap`, so a restart erased it and a
 * second replica would have served a different register than the one an incident was reported to.
 * Both failure modes are cured by the same thing — a row — which is why this is a repository and
 * not a cache with a refresher: there was nothing to converge on.
 */
interface IctIncidentRepository {

    /** Upserts [incident]; used for both the initial report and every subsequent transition. */
    suspend fun save(incident: IctIncident): IctIncident

    suspend fun findIncident(id: UUID): IctIncident?

    /** Newest first, filtered server-side so a large register does not cross the wire to be dropped. */
    suspend fun list(status: IncidentStatus?, severity: IncidentSeverity?, limit: Int, offset: Int): List<IctIncident>
}
