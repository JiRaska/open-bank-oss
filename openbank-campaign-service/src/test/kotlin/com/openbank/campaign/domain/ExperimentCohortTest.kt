// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.ExperimentCohort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ExperimentCohortTest {
    private val campaignId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `assignment is deterministic and zero holdout never withholds a party`() {
        val parties = (1L..200L).map { UUID(0, it) }

        val first = parties.associateWith { ExperimentCohort.assign(campaignId, it, 20) }
        val repeated = parties.associateWith { ExperimentCohort.assign(campaignId, it, 20) }

        assertThat(repeated).isEqualTo(first)
        assertThat(first.values).contains(ExperimentCohort.TREATMENT, ExperimentCohort.HOLDOUT)
        assertThat(parties.map { ExperimentCohort.assign(campaignId, it, 0) })
            .containsOnly(ExperimentCohort.TREATMENT)
    }
}
