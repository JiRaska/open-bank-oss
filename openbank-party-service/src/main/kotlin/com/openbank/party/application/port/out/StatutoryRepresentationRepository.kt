// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.out

import com.openbank.party.domain.model.StatutoryRepresentationRule
import java.util.UUID

interface StatutoryRepresentationRepository {
    suspend fun insertOrRead(rule: StatutoryRepresentationRule): StatutoryRepresentationRule
    suspend fun findLatest(principalPartyId: UUID): StatutoryRepresentationRule?
}
