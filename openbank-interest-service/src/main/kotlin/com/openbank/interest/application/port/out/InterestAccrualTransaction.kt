// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.out

import com.openbank.interest.domain.model.InterestAccrual
import io.smallrye.mutiny.Uni

/** One atomic unit for an accrual and the catalog revision pin it establishes. */
interface InterestAccrualTransaction {
    /**
     * Runs natural-key lookup, rate resolution, revision pinning and accrual insertion in one
     * transaction. Nested repository calls share its session; either the pin and accrual commit
     * together, or neither newly written row remains. Recover unique conflicts only after this
     * Uni fails and its transaction has rolled back, not inside the supplied block.
     */
    fun withAccrual(block: () -> Uni<InterestAccrual>): Uni<InterestAccrual>
}
