// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.SanctionsJurisdictionRepository
import com.openbank.sanctions.domain.model.SanctionsJurisdiction
import com.openbank.sanctions.domain.model.SanctionsListType

/** Empty jurisdiction reference data, which is also what a fresh deployment holds. */
internal object NoJurisdictions : SanctionsJurisdictionRepository {
    override suspend fun findActive(
        countryCodes: Set<String>,
        listTypes: List<SanctionsListType>,
    ): List<SanctionsJurisdiction> = emptyList()
}
