// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.application.port.out

import io.smallrye.mutiny.Uni
import java.util.UUID

/** account-service's ownership projection (ADR-0335 D2): exactly what it answers, nothing more. */
data class DebtorAccountOwnership(val owned: Boolean, val active: Boolean, val accountId: UUID?)

interface DebtorAccountOwnershipPort {
    /** Fails (never answers "owned") when account-service cannot be asked. */
    fun verify(iban: String, partyId: UUID): Uni<DebtorAccountOwnership>
}
