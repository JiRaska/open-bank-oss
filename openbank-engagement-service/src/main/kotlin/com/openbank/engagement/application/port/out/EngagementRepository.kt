// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.port.out

import com.openbank.engagement.domain.model.EngagementProfile
import com.openbank.libs.domain.event.DomainEvent
import java.util.UUID

interface EngagementRepository {
    suspend fun findByParty(partyId: UUID): EngagementProfile?
    suspend fun save(profile: EngagementProfile, event: DomainEvent): EngagementProfile
    suspend fun save(profile: EngagementProfile): EngagementProfile
}
