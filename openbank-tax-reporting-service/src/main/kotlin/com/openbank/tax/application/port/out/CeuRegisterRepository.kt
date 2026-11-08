// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.port.out

import com.openbank.tax.domain.ceu.RegisterFact
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The append-only CEÚ register projection (ADR-0367 D7). */
interface CeuRegisterRepository {
    /** @return true when newly recorded, false when [fact]'s event was already observed (redelivery). */
    suspend fun append(fact: RegisterFact): Boolean

    /** The first observed start of [relationId], or null if none has been observed. */
    suspend fun startOf(relationId: UUID): RegisterFact.RelationStarted?

    /** Every fact a snapshot for [validOn] known at [knownAt] could need. */
    suspend fun factsUpTo(validOn: LocalDate, knownAt: Instant): List<RegisterFact>
}
