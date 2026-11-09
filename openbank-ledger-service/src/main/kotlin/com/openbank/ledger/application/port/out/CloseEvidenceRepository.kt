// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.ClosedDayParticipation
import java.time.LocalDate

interface CloseEvidenceRepository {
    suspend fun findMonth(from: LocalDate, to: LocalDate): List<ClosedDayParticipation>
}
