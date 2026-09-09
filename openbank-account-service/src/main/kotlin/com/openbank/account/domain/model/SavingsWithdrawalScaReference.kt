// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import java.util.UUID

/** Stable operation fingerprint signed by the approving device and restated at consume time. */
object SavingsWithdrawalScaReference {
    fun of(proposalId: UUID, approve: Boolean): String =
        "openbank:savings-withdrawal-decision:v1:$proposalId:${if (approve) "approve" else "reject"}"
}
