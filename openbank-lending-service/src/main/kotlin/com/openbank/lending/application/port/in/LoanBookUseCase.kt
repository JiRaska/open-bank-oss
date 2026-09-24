// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.application.port.`in`

import com.openbank.lending.domain.model.LoanBook
import io.smallrye.mutiny.Uni
import java.time.LocalDate

/** READ-ONLY: the loan book as the risk engine's instrument model reads it (ADR-0314 D4). */
interface LoanBookUseCase {
    fun loanBook(asOf: LocalDate): Uni<LoanBook>
}
