// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import java.util.UUID

/** Live, payload-free authorization against Context for an investigator reading a loan graph. */
interface LendingGraphAccessPort {
    suspend fun check(loanId: UUID, bearer: String): LendingGraphAccessDecision

    suspend fun assignedCandidates(rootLoanId: UUID, bearer: String): LendingAssignedCandidatesResult
}

enum class LendingGraphAccessDecision { ALLOWED, DENIED, UNAVAILABLE }

sealed interface LendingAssignedCandidatesResult {
    data class Available(val ids: List<UUID>, val truncated: Boolean) : LendingAssignedCandidatesResult

    data object Denied : LendingAssignedCandidatesResult

    data object Unavailable : LendingAssignedCandidatesResult
}
