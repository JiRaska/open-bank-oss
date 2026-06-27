// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.port.`in`

import com.openbank.onboarding.domain.model.FunnelStage
import com.openbank.onboarding.domain.model.OnboardingRecord
import java.util.UUID

/** Inbound port: read-model queries for the onboarding cockpit REST layer (ADR-0068). */
interface OnboardingUseCase {

    /**
     * Return a paginated list of onboarding records.
     * When [stage] is provided the result is scoped to that funnel column.
     */
    suspend fun listRecords(page: Int, size: Int, stage: FunnelStage?): Map<String, Any>

    /** Return the onboarding record for a single party. */
    suspend fun getRecord(partyId: UUID): OnboardingRecord

    /**
     * Return KPI counts per funnel stage for the cockpit tile row.
     * Map key = FunnelStage.name, value = count.
     */
    suspend fun funnelCounts(): Map<String, Long>

}
