// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.application.port.inbound

import com.openbank.finrep.domain.model.XbrlCsvPreflight
import java.time.LocalDate

data class GetXbrlCsvPreflightQuery(val templateId: String, val asOf: LocalDate)

/** Safeguard-only entrypoint. It does not create an XBRL-CSV artifact or submit anything. */
interface XbrlCsvPreflightUseCase {
    suspend fun getPreflight(query: GetXbrlCsvPreflightQuery): XbrlCsvPreflight
}
