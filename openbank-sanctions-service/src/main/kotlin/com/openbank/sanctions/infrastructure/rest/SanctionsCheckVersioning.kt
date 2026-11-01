// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.domain.model.SanctionsCheck
import com.openbank.sanctions.domain.model.SanctionsListType

private val V1_LIST_TYPES = listOf(
    SanctionsListType.OFAC_SDN,
    SanctionsListType.EU_CONSOLIDATED,
    SanctionsListType.UN_CONSOLIDATED,
    SanctionsListType.HM_TREASURY,
    SanctionsListType.FATF_HIGH_RISK,
    SanctionsListType.PEP_GLOBAL,
    SanctionsListType.CNB_DOMESTIC,
)

internal fun ScreenEntityCommand.forV1(): ScreenEntityCommand {
    val requested = listTypes
    require(requested.orEmpty().none { it == SanctionsListType.CZ_TERRORISTS.name }) {
        "CZ_TERRORISTS requires /api/v3/sanctions/screen"
    }
    val meansAllLists = requested == null || requested.isEmpty() || requested.all { it?.isBlank() == true }
    return if (meansAllLists) copy(listTypes = V1_LIST_TYPES.map { it.name }) else this
}

internal fun SanctionsCheck.isV1Compatible(): Boolean =
    checkedLists.all { it in V1_LIST_TYPES } && matches.all { it.listType in V1_LIST_TYPES }
