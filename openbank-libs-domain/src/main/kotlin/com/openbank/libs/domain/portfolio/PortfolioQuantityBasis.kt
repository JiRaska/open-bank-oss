// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.portfolio

/** The custodian-stated quantity dimension; distinct from the instrument's classification. */
enum class PortfolioQuantityBasis {
    UNIT,
    FACE_AMOUNT,
    AMORTISED_VALUE,
}
