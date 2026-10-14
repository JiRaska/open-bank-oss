// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.reporting

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.openbank.pension.application.port.out.ParticipantSnapshotSerializer
import com.openbank.pension.domain.reporting.ParticipantPeriodAggregates
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class JacksonParticipantSnapshotSerializer(mapper: ObjectMapper) : ParticipantSnapshotSerializer {
    private val canonical = mapper.copy()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    override fun canonicalJson(aggregates: ParticipantPeriodAggregates): String =
        canonical.writeValueAsString(ParticipantAggregatesResponse.from(aggregates))
}
