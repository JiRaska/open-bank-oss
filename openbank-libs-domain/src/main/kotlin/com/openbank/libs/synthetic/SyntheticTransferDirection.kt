// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.synthetic

import java.time.LocalDate
import java.util.UUID

enum class SyntheticTransferDirection {
    OUTBOUND,
    RETURN,
    ;

    /** One stable key per fixture party, bank date and direction across both HTTP hops. */
    fun idempotencyKey(partyId: UUID, runDate: LocalDate): String = "synthetic-transfer:$partyId:$runDate:$name"
}
