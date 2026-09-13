// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.application.port.inbound

import java.time.LocalDate

data class ReportingPeriods(val latest: LocalDate?, val periods: List<LocalDate>)

interface ReportingPeriodUseCase {
    suspend fun listAvailable(): ReportingPeriods
}
