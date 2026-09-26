// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.application.usecase

import com.openbank.loyalty.application.port.out.BenefitGrantRepository
import com.openbank.loyalty.domain.BenefitGrant
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * The benefits a party has redeemed Lístky for, newest first — the app's "Moje výhody" list.
 *
 * Reports [com.openbank.loyalty.domain.BenefitGrantStatus] exactly as stored. There is no
 * "active" or "applied" projection here on purpose: GRANTED means owed and published, and no
 * delivering engine reports application back to this service yet (see `Benefit.kt`). A derived
 * state that read GRANTED as delivered would be the claim the domain type exists to prevent.
 */
@ApplicationScoped
class ListBenefitGrantsUseCase(private val grants: BenefitGrantRepository) {
    suspend fun list(partyId: UUID): List<BenefitGrant> = grants.listFor(partyId).sortedByDescending { it.reservedAt }
}
