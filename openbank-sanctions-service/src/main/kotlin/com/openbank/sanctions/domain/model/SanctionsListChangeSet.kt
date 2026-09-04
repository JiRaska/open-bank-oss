// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

/**
 * The content-level diff produced by one list refresh: which entries were written/changed and
 * which were dropped upstream. This is the firing condition for a `SANCTIONS_LIST_CHANGED`
 * re-screening trigger (ADR-0256 D1) — a refresh whose [changedExternalIds] and
 * [deactivatedExternalIds] are both empty is a no-op import and raises nothing.
 */
data class SanctionsListChangeSet(
    val listType: SanctionsListType,
    val changedExternalIds: Set<String> = emptySet(),
    val deactivatedExternalIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = changedExternalIds.isEmpty() && deactivatedExternalIds.isEmpty()
    val changeCount: Int get() = changedExternalIds.size + deactivatedExternalIds.size
}
